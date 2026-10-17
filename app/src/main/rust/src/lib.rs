use ash::vk;
use bytemuck::{Pod, Zeroable};
use jni::{
    objects::{JClass, JFloatArray, JObject, JString},
    sys::{jfloat, jfloatArray, jint, jobject},
    JNIEnv,
};
use log::info;
use ndk::hardware_buffer::HardwareBufferUsage;
use ndk::media::image_reader::{AcquireResult, ImageFormat, ImageReader};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::unix::io::FromRawFd;
use std::sync::{
    atomic::{AtomicI32, AtomicU64, Ordering},
    Arc, Mutex, OnceLock, RwLock,
};
use std::time::Instant;

pub static CINEMA_SHADER_SPV: &[u8] = include_bytes!("../shaders/cinema_pipeline.spv");

pub unsafe fn create_cinema_shader_module(
    device: &ash::Device,
) -> Result<vk::ShaderModule, vk::Result> {
    let spv_words: &[u32] = bytemuck::cast_slice(CINEMA_SHADER_SPV);
    let create_info = vk::ShaderModuleCreateInfo::default().code(spv_words);
    device.create_shader_module(&create_info, None)
}

pub struct SafeNativeWindow(*mut ndk_sys::ANativeWindow);
unsafe impl Send for SafeNativeWindow {}
unsafe impl Sync for SafeNativeWindow {}

impl Drop for SafeNativeWindow {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                ndk_sys::ANativeWindow_release(self.0);
            }
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
pub struct FrameParamsPushConstants {
    pub black_level: [f32; 4],
    pub white_level: f32,
    pub transfer_mode: i32,
    pub preview_assist: i32,
    pub exposure_comp_ev: f32,
    pub wb_gains: [f32; 4],
    pub sensor_to_rec2020_col0: [f32; 4],
    pub sensor_to_rec2020_col1: [f32; 4],
    pub sensor_to_rec2020_col2: [f32; 4],
}

/// High-Precision Native Rust Cinema Power-Zoom Servo
/// Uses a 7th-Order C3-Continuous Minimum-Snap Spline (Velocity, Acceleration, AND Jerk = 0 at endpoints!)
pub struct CinemaZoomServo {
    pub start_zoom: f64,
    pub target_zoom: f64,
    pub current_zoom: f64,
    pub last_emitted_zoom: f64,
    pub start_instant: Instant,
    pub duration_secs: f64,
    pub is_animating: bool,
}

impl CinemaZoomServo {
    pub fn new() -> Self {
        Self {
            start_zoom: 1.0,
            target_zoom: 1.0,
            current_zoom: 1.0,
            last_emitted_zoom: 1.0,
            start_instant: Instant::now(),
            duration_secs: 0.0,
            is_animating: false,
        }
    }

    pub fn set_target(&mut self, target: f64) {
        let clamped_target = target.clamp(0.5, 10.0);
        if (clamped_target - self.current_zoom).abs() < 0.001 {
            self.current_zoom = clamped_target;
            self.target_zoom = clamped_target;
            self.is_animating = false;
            return;
        }
        self.start_zoom = self.current_zoom.clamp(0.5, 10.0);
        self.target_zoom = clamped_target;

        let octaves = (self.target_zoom / self.start_zoom).log2().abs();
        // Smooth iOS timing: ~0.55s for 1x->2x, ~1.35s for 0.5x->5x
        self.duration_secs = (0.32 + octaves * 0.30).clamp(0.48, 1.55);
        self.start_instant = Instant::now();
        self.is_animating = true;
    }

    /// Pure Cosine Ease-In-Out with Velvet Landing:
    /// Avoids the high midpoint velocity spike of high-order polynomials so crossing
    /// optical lens boundaries (1x / 5x) is calm, linear-feeling, and seamless!
    pub fn step(&mut self) -> (f32, bool) {
        if !self.is_animating {
            return (self.current_zoom as f32, false);
        }
        let elapsed = self.start_instant.elapsed().as_secs_f64();
        let t = (elapsed / self.duration_secs).clamp(0.0, 1.0);

        if t >= 1.0 {
            self.current_zoom = self.target_zoom;
            self.last_emitted_zoom = self.target_zoom;
            self.is_animating = false;
            return (self.current_zoom as f32, false);
        }

        // Gentle time warp (t^0.88) + Cosine S-Curve:
        // Starts smoothly, cruises at a controlled speed through lens switches, and feathers to a stop
        let warped_t = t.powf(0.88);
        let eased = 0.5 - 0.5 * (std::f64::consts::PI * warped_t).cos();

        self.current_zoom = self.start_zoom * (self.target_zoom / self.start_zoom).powf(eased);
        self.last_emitted_zoom = self.current_zoom;

        (self.current_zoom as f32, true)
    }
}

pub struct SharedCameraState {
    pub transfer_mode: AtomicI32,
    pub preview_assist: AtomicI32,
    pub cfa_pattern: AtomicI32,
    pub raw_frame_counter: AtomicU64,
    pub raw_dump_fd: AtomicI32,
    pub zoom_servo: Mutex<CinemaZoomServo>,
    pub latest_metadata: RwLock<FrameParamsPushConstants>,
    pub latest_histogram: Mutex<[f32; 64]>,
    pub preview_window: Mutex<Option<SafeNativeWindow>>,
    pub record_window: Mutex<Option<SafeNativeWindow>>,
}

static ENGINE_STATE: OnceLock<Arc<SharedCameraState>> = OnceLock::new();

fn get_state() -> &'static Arc<SharedCameraState> {
    ENGINE_STATE.get_or_init(|| {
        android_logger::init_once(
            android_logger::Config::default().with_max_level(log::LevelFilter::Info),
        );
        info!("Rust PixelCinemaLog Engine Initialized!");
        info!(
            "Loaded compiled Vulkan Compute Shader (cinema_pipeline.spv): {} bytes",
            CINEMA_SHADER_SPV.len()
        );

        Arc::new(SharedCameraState {
            transfer_mode: AtomicI32::new(2),
            preview_assist: AtomicI32::new(0),
            cfa_pattern: AtomicI32::new(0),
            raw_frame_counter: AtomicU64::new(0),
            raw_dump_fd: AtomicI32::new(-1),
            zoom_servo: Mutex::new(CinemaZoomServo::new()),
            latest_metadata: RwLock::new(FrameParamsPushConstants {
                black_level: [64.0, 64.0, 64.0, 64.0],
                white_level: 1023.0,
                transfer_mode: 2,
                preview_assist: 0,
                exposure_comp_ev: 0.0,
                wb_gains: [1.95, 1.0, 1.0, 1.65],
                sensor_to_rec2020_col0: [1.2, -0.1, -0.1, 0.0],
                sensor_to_rec2020_col1: [-0.2, 1.3, -0.1, 0.0],
                sensor_to_rec2020_col2: [-0.05, -0.15, 1.2, 0.0],
            }),
            latest_histogram: Mutex::new([0.0; 64]),
            preview_window: Mutex::new(None),
            record_window: Mutex::new(None),
        })
    })
}

fn pack_raw16_to_lossless_12bit(raw_bytes: &[u8], w: usize, h: usize, stride_bytes: usize) -> Vec<u8> {
    let total_pixels = w * h;
    let mut packed = Vec::with_capacity((total_pixels * 3) / 2);
    let src_u16 = unsafe { std::slice::from_raw_parts(raw_bytes.as_ptr() as *const u16, raw_bytes.len() / 2) };
    let stride_u16 = stride_bytes / 2;

    for y in 0..h {
        let row = &src_u16[y * stride_u16 .. y * stride_u16 + w];
        let mut x = 0;
        while x + 1 < w {
            let p0 = row[x] & 0x0FFF;
            let p1 = row[x + 1] & 0x0FFF;
            packed.push((p0 & 0xFF) as u8);
            packed.push((((p0 >> 8) & 0x0F) | ((p1 & 0x0F) << 4)) as u8);
            packed.push(((p1 >> 4) & 0xFF) as u8);
            x += 2;
        }
    }
    packed
}

fn unpack_lossless_12bit_to_raw16(packed: &[u8], w: usize, h: usize) -> Vec<u8> {
    let mut out_u8 = Vec::with_capacity(w * h * 2);
    let mut i = 0;
    while i + 2 < packed.len() {
        let b0 = packed[i] as u16;
        let b1 = packed[i + 1] as u16;
        let b2 = packed[i + 2] as u16;

        let p0: u16 = b0 | ((b1 & 0x0F) << 8);
        let p1: u16 = (b1 >> 4) | (b2 << 4);

        out_u8.extend_from_slice(&p0.to_le_bytes());
        out_u8.extend_from_slice(&p1.to_le_bytes());
        i += 3;
    }
    out_u8
}

#[inline(always)]
fn linear_to_slog3(x: f32) -> f32 {
    if x >= 0.01125000 {
        (420.0 + ((x + 0.01).log10() - 0.19_f32.log10()) * 261.5) / 1023.0
    } else {
        (x * (171.2102946929 - 95.0) / 0.01125000 + 95.0) / 1023.0
    }
}

#[inline(always)]
fn linear_to_arri_logc4(x: f32) -> f32 {
    let a = 2231.8263_f32;
    let b = 0.90713587_f32;
    let c = 0.092864125_f32;
    let s = -0.000441625_f32;
    let t = -0.018056996_f32;
    if x >= t {
        ((a * x + 64.0).log2() - 6.0) / 14.0 * b + c
    } else {
        (x - t) / s
    }
}

#[inline(always)]
fn linear_to_aces_cct(x: f32) -> f32 {
    if x <= 0.0078125 {
        10.5402377 * x + 0.072905534
    } else {
        (x.log2() + 9.72) / 17.52
    }
}

#[inline(always)]
fn linear_to_canon_log3(x: f32) -> f32 {
    if x < -0.014 {
        -0.36726845 * (1.0 - 14.98325 * x).log10() + 0.12783901
    } else if x <= 0.014 {
        1.9754798 * x + 0.12512219
    } else {
        0.36726845 * (14.98325 * x + 1.0).log10() + 0.12240537
    }
}

#[inline(always)]
fn linear_to_apple_log(r: f32) -> f32 {
    let r0 = -0.05641088_f32;
    let rt = 0.01_f32;
    let c = 47.28711236_f32;
    let beta = 0.00964052_f32;
    let gamma = 0.08550479_f32;
    let delta = 0.69336945_f32;
    if r < r0 {
        0.0
    } else if r < rt {
        c * (r - r0).powi(2)
    } else {
        gamma * (r + beta).log2() + delta
    }
}

#[inline(always)]
fn linear_to_rec709(x: f32) -> f32 {
    let m = (x / (x + 0.18)).clamp(0.0, 1.0);
    m.powf(1.0 / 2.2)
}

fn build_12bit_log_lut(transfer_mode: i32, ev_gain: f32) -> [u8; 4096] {
    let mut lut = [0u8; 4096];
    for i in 0..4096 {
        let x = (i as f32 / 4095.0) * ev_gain;
        let y = match transfer_mode {
            0 => linear_to_rec709(x),
            1 => x.clamp(0.0, 1.0).powf(1.0 / 2.2),
            3 => linear_to_arri_logc4(x),
            4 => linear_to_aces_cct(x),
            5 => linear_to_canon_log3(x),
            6 => linear_to_apple_log(x),
            _ => linear_to_slog3(x),
        };
        lut[i] = (y.clamp(0.0, 1.0) * 255.0) as u8;
    }
    lut
}

#[inline(always)]
unsafe fn sample_bayer_quad(
    src_u16: *const u16,
    stride_u16: usize,
    bx: usize,
    by: usize,
    bl: i32,
    cfa: i32,
) -> (f32, f32, f32) {
    let row0 = src_u16.add(by * stride_u16);
    let row1 = src_u16.add((by + 1) * stride_u16);
    let p00 = (*row0.add(bx) as i32 - bl).max(0) as f32;
    let p01 = (*row0.add(bx + 1) as i32 - bl).max(0) as f32;
    let p10 = (*row1.add(bx) as i32 - bl).max(0) as f32;
    let p11 = (*row1.add(bx + 1) as i32 - bl).max(0) as f32;

    match cfa {
        1 => (p01, 0.5 * (p00 + p11), p10),
        2 => (p10, 0.5 * (p00 + p11), p01),
        3 => (p11, 0.5 * (p01 + p10), p00),
        _ => (p00, 0.5 * (p01 + p10), p11),
    }
}

/// Renders an unprocessed 16-bit Bayer `RAW_SENSOR` frame with TRUE 2D Bilinear Sub-Pixel
/// Interpolation so zooming in RAW16 mode has zero integer pixel stepping!
unsafe fn blit_raw16_to_window(
    window_ptr: *mut ndk_sys::ANativeWindow,
    raw_bytes: *const u8,
    raw_width: i32,
    raw_height: i32,
    row_stride_bytes: i32,
    meta: &FrameParamsPushConstants,
    cfa: i32,
    lut: &[u8; 4096],
    apply_assist: i32,
    zoom_factor: f32,
    mut hist_out: Option<&mut [f32; 64]>,
) {
    if window_ptr.is_null() || raw_bytes.is_null() {
        return;
    }

    let out_w = (raw_width / 2).min(1920);
    let out_h = (raw_height / 2).min(1080);

    let _ = ndk_sys::ANativeWindow_setBuffersGeometry(window_ptr, out_w, out_h, 1);

    let mut buffer: ndk_sys::ANativeWindow_Buffer = std::mem::zeroed();
    if ndk_sys::ANativeWindow_lock(window_ptr, &mut buffer, std::ptr::null_mut()) != 0 {
        return;
    }

    let dst_ptr = buffer.bits as *mut u32;
    let dst_stride = buffer.stride as usize;
    let src_u16 = raw_bytes as *const u16;
    let src_stride_u16 = (row_stride_bytes as usize) / 2;

    let bl = meta.black_level[0].max(0.0) as i32;
    let range = (meta.white_level - meta.black_level[0]).max(1.0);
    let scale_r = (4095.0 / range) * meta.wb_gains[0].clamp(0.8, 3.5);
    let scale_g = (4095.0 / range) * meta.wb_gains[1].clamp(0.8, 3.5);
    let scale_b = (4095.0 / range) * meta.wb_gains[3].clamp(0.8, 3.5);

    let max_y = (buffer.height as usize).min(out_h as usize);
    let max_x = (buffer.width as usize).min(out_w as usize);

    // Work in 2x2 Bayer super-pixel coordinates (0 .. quad_w, 0 .. quad_h)
    let quad_w = (raw_width as usize / 2).saturating_sub(2) as f32;
    let quad_h = (raw_height as usize / 2).saturating_sub(2) as f32;
    let z = zoom_factor.max(1.0);
    let crop_qw = quad_w / z;
    let crop_qh = quad_h / z;
    let off_qx = (quad_w - crop_qw) * 0.5;
    let off_qy = (quad_h - crop_qh) * 0.5;

    let mut bins = [0u32; 64];

    for y in 0..max_y {
        let qy_f = off_qy + ((y as f32) * crop_qh) / (max_y as f32);
        let qy0 = qy_f as usize;
        let fy = qy_f - (qy0 as f32);
        let by0 = qy0 * 2;
        let by1 = (qy0 + 1) * 2;
        if by1 + 1 >= raw_height as usize {
            continue;
        }
        let dst_row = dst_ptr.add(y * dst_stride);

        for x in 0..max_x {
            let qx_f = off_qx + ((x as f32) * crop_qw) / (max_x as f32);
            let qx0 = qx_f as usize;
            let fx = qx_f - (qx0 as f32);
            let bx0 = qx0 * 2;
            let bx1 = (qx0 + 1) * 2;
            if bx1 + 1 >= raw_width as usize {
                continue;
            }

            // Bilinear sub-pixel interpolation across 4 neighboring Bayer quads
            let c00 = sample_bayer_quad(src_u16, src_stride_u16, bx0, by0, bl, cfa);
            let c10 = sample_bayer_quad(src_u16, src_stride_u16, bx1, by0, bl, cfa);
            let c01 = sample_bayer_quad(src_u16, src_stride_u16, bx0, by1, bl, cfa);
            let c11 = sample_bayer_quad(src_u16, src_stride_u16, bx1, by1, bl, cfa);

            let w00 = (1.0 - fx) * (1.0 - fy);
            let w10 = fx * (1.0 - fy);
            let w01 = (1.0 - fx) * fy;
            let w11 = fx * fy;

            let raw_r = c00.0 * w00 + c10.0 * w10 + c01.0 * w01 + c11.0 * w11;
            let raw_g = c00.1 * w00 + c10.1 * w10 + c01.1 * w01 + c11.1 * w11;
            let raw_b = c00.2 * w00 + c10.2 * w10 + c01.2 * w01 + c11.2 * w11;

            let idx_r = ((raw_r * scale_r) as usize).min(4095);
            let idx_g = ((raw_g * scale_g) as usize).min(4095);
            let idx_b = ((raw_b * scale_b) as usize).min(4095);

            let mut r = lut[idx_r];
            let mut g = lut[idx_g];
            let mut b = lut[idx_b];

            if (x & 3) == 0 && (y & 3) == 0 {
                let luma_bin = (((r as usize) * 2 + (g as usize) * 5 + (b as usize)) >> 5).min(63);
                bins[luma_bin] += 1;
            }

            if apply_assist == 1 {
                r = (linear_to_rec709(idx_r as f32 / 4095.0) * 255.0) as u8;
                g = (linear_to_rec709(idx_g as f32 / 4095.0) * 255.0) as u8;
                b = (linear_to_rec709(idx_b as f32 / 4095.0) * 255.0) as u8;
            } else if apply_assist == 2 {
                let ire = ((r as u32 * 21 + g as u32 * 72 + b as u32 * 7) / 255) as u8;
                if ire >= 95 {
                    r = 255; g = 0; b = 0;
                } else if ire >= 85 {
                    r = 255; g = 255; b = 0;
                } else if ire >= 52 && ire <= 58 {
                    r = 255; g = 128; b = 128;
                } else if ire >= 38 && ire <= 44 {
                    r = 0; g = 210; b = 50;
                } else if ire <= 5 {
                    r = 130; g = 0; b = 210;
                } else {
                    r = ire; g = ire; b = ire;
                }
            } else if apply_assist == 3 {
                let ire = ((r as u32 * 21 + g as u32 * 72 + b as u32 * 7) / 255) as u8;
                if ire >= 95 && ((x + y) / 6) % 2 == 0 {
                    r = 255; g = 0; b = 0;
                }
            }

            *dst_row.add(x) = 0xFF00_0000 | ((b as u32) << 16) | ((g as u32) << 8) | (r as u32);
        }
    }

    let _ = ndk_sys::ANativeWindow_unlockAndPost(window_ptr);

    if let Some(ref mut hist) = hist_out {
        let max_bin = (*bins.iter().max().unwrap_or(&1)).max(1) as f32;
        for i in 0..64 {
            hist[i] = (bins[i] as f32 / max_bin).clamp(0.0, 1.0);
        }
    }
}

fn write_cinema_dng_frame(
    out_path: &str,
    w: u32,
    h: u32,
    stride: u32,
    white_level: f32,
    cfa: i32,
    raw_bytes: &[u8],
) -> std::io::Result<()> {
    let mut f = File::create(out_path)?;

    let cfa_bytes: [u8; 4] = match cfa {
        1 => [1, 0, 2, 1],
        2 => [1, 2, 0, 1],
        3 => [2, 1, 1, 0],
        _ => [0, 1, 1, 2],
    };

    let num_entries: u16 = 16;
    let ifd_offset: u32 = 8;
    let pixel_data_offset: u32 = 256;
    let strip_byte_count: u32 = w * h * 2;

    let mut hdr = Vec::with_capacity(pixel_data_offset as usize);

    hdr.extend_from_slice(b"II");
    hdr.extend_from_slice(&42u16.to_le_bytes());
    hdr.extend_from_slice(&ifd_offset.to_le_bytes());
    hdr.extend_from_slice(&num_entries.to_le_bytes());

    let push_tag = |buf: &mut Vec<u8>, tag: u16, typ: u16, count: u32, val_or_offset: u32| {
        buf.extend_from_slice(&tag.to_le_bytes());
        buf.extend_from_slice(&typ.to_le_bytes());
        buf.extend_from_slice(&count.to_le_bytes());
        buf.extend_from_slice(&val_or_offset.to_le_bytes());
    };

    push_tag(&mut hdr, 0x00FE, 4, 1, 0);
    push_tag(&mut hdr, 0x0100, 4, 1, w);
    push_tag(&mut hdr, 0x0101, 4, 1, h);
    push_tag(&mut hdr, 0x0102, 3, 1, 16);
    push_tag(&mut hdr, 0x0103, 3, 1, 1);
    push_tag(&mut hdr, 0x0106, 3, 1, 32803);
    push_tag(&mut hdr, 0x0111, 4, 1, pixel_data_offset);
    push_tag(&mut hdr, 0x0112, 3, 1, 1);
    push_tag(&mut hdr, 0x0115, 3, 1, 1);
    push_tag(&mut hdr, 0x0116, 4, 1, h);
    push_tag(&mut hdr, 0x0117, 4, 1, strip_byte_count);
    push_tag(&mut hdr, 0x828D, 3, 2, 0x0002_0002);
    push_tag(&mut hdr, 0x828E, 1, 4, u32::from_le_bytes(cfa_bytes));
    push_tag(&mut hdr, 0xC612, 1, 4, u32::from_le_bytes([1, 4, 0, 0]));
    push_tag(&mut hdr, 0xC61A, 4, 1, 64);
    push_tag(&mut hdr, 0xC61D, 4, 1, (white_level as u32).max(1023));

    hdr.extend_from_slice(&0u32.to_le_bytes());
    hdr.resize(pixel_data_offset as usize, 0u8);
    f.write_all(&hdr)?;

    let row_bytes = (w * 2) as usize;
    let stride_usize = stride as usize;
    if stride_usize == row_bytes && raw_bytes.len() >= (h as usize) * row_bytes {
        f.write_all(&raw_bytes[0..(h as usize) * row_bytes])?;
    } else {
        for y in 0..(h as usize) {
            let start = y * stride_usize;
            let end = start + row_bytes;
            if end <= raw_bytes.len() {
                f.write_all(&raw_bytes[start..end])?;
            }
        }
    }

    Ok(())
}

fn read_raw_frame_payload(
    file: &mut File,
    hdr: &[u8; 24],
    w: usize,
    h: usize,
    stride_or_packed_len: usize,
) -> std::io::Result<(Vec<u8>, i32)> {
    if &hdr[0..4] == b"RAWZ" {
        let mut packed = vec![0u8; stride_or_packed_len];
        file.read_exact(&mut packed)?;
        let unpacked = unpack_lossless_12bit_to_raw16(&packed, w, h);
        Ok((unpacked, (w * 2) as i32))
    } else {
        let payload_size = h * stride_or_packed_len;
        let mut raw_buf = vec![0u8; payload_size];
        file.read_exact(&mut raw_buf)?;
        Ok((raw_buf, stride_or_packed_len as i32))
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_initEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = get_state();
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_setZoomTarget(
    _env: JNIEnv,
    _class: JClass,
    target_zoom: jfloat,
) {
    if let Ok(mut servo) = get_state().zoom_servo.lock() {
        servo.set_target(target_zoom as f64);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_stepZoomServo(
    _env: JNIEnv,
    _class: JClass,
) -> jfloat {
    if let Ok(mut servo) = get_state().zoom_servo.lock() {
        let (z, active) = servo.step();
        if active { z } else { -z }
    } else {
        -1.0
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_setCameraMode(
    _env: JNIEnv,
    _class: JClass,
    transfer_mode: i32,
    preview_assist: i32,
) {
    let state = get_state();
    state.transfer_mode.store(transfer_mode, Ordering::Relaxed);
    state.preview_assist.store(preview_assist, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_setRawDumpFd(
    _env: JNIEnv,
    _class: JClass,
    fd: i32,
) {
    let state = get_state();
    state.raw_dump_fd.store(fd, Ordering::SeqCst);
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_getLiveHistogram(
    env: JNIEnv,
    _class: JClass,
) -> jfloatArray {
    let state = get_state();
    let hist = state
        .latest_histogram
        .lock()
        .map(|h| *h)
        .unwrap_or([0.0f32; 64]);
    let out = env
        .new_float_array(64)
        .expect("Failed to allocate histogram float array");
    let _ = env.set_float_array_region(&out, 0, &hist);
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_getRaw16FrameCount(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jint {
    let path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let mut file = match File::open(&path_str) {
        Ok(f) => f,
        Err(_) => return 0,
    };
    let file_len = match file.metadata() {
        Ok(m) => m.len(),
        Err(_) => return 0,
    };
    let mut hdr = [0u8; 24];
    if file.read_exact(&mut hdr).is_err() {
        return 0;
    }
    let h = u32::from_le_bytes(hdr[8..12].try_into().unwrap()) as u64;
    let stride_or_len = u32::from_le_bytes(hdr[12..16].try_into().unwrap()) as u64;

    let frame_bytes = if &hdr[0..4] == b"RAWZ" {
        24 + stride_or_len
    } else if &hdr[0..4] == b"RAWF" {
        24 + (h * stride_or_len)
    } else {
        return 0;
    };

    if frame_bytes == 0 {
        return 0;
    }
    (file_len / frame_bytes) as jint
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_renderRaw16FrameFromFile(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
    frame_index: jint,
    transfer_mode: jint,
    preview_assist: jint,
    exposure_ev: f32,
    surface: JObject,
) -> jint {
    if surface.is_null() {
        return -1;
    }
    let path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let mut file = match File::open(&path_str) {
        Ok(f) => f,
        Err(_) => return -1,
    };

    let mut hdr = [0u8; 24];
    if file.read_exact(&mut hdr).is_err() {
        return -1;
    }

    let w = u32::from_le_bytes(hdr[4..8].try_into().unwrap()) as usize;
    let h = u32::from_le_bytes(hdr[8..12].try_into().unwrap()) as usize;
    let stride_or_len = u32::from_le_bytes(hdr[12..16].try_into().unwrap()) as usize;
    let white_level = f32::from_le_bytes(hdr[16..20].try_into().unwrap());
    let cfa = u32::from_le_bytes(hdr[20..24].try_into().unwrap()) as i32;

    let frame_total_size = if &hdr[0..4] == b"RAWZ" {
        24 + stride_or_len as u64
    } else if &hdr[0..4] == b"RAWF" {
        24 + (h * stride_or_len) as u64
    } else {
        return -1;
    };

    let target_offset = (frame_index.max(0) as u64) * frame_total_size;
    if file.seek(SeekFrom::Start(target_offset)).is_err() {
        return -1;
    }
    if file.read_exact(&mut hdr).is_err() {
        return -1;
    }

    let (raw_buf, actual_stride) = match read_raw_frame_payload(&mut file, &hdr, w, h, stride_or_len) {
        Ok(res) => res,
        Err(_) => return -1,
    };

    let ev_gain = 2.0_f32.powf(exposure_ev);
    let lut = build_12bit_log_lut(transfer_mode, ev_gain);

    let mut meta: FrameParamsPushConstants = Zeroable::zeroed();
    meta.black_level = [64.0, 64.0, 64.0, 64.0];
    meta.white_level = white_level.max(1023.0);
    meta.wb_gains = [1.95, 1.0, 1.0, 1.65];

    let state = get_state();
    let mut hist_local = [0.0f32; 64];

    unsafe {
        let win_ptr =
            ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface.as_raw());
        if !win_ptr.is_null() {
            blit_raw16_to_window(
                win_ptr,
                raw_buf.as_ptr(),
                w as i32,
                h as i32,
                actual_stride,
                &meta,
                cfa,
                &lut,
                preview_assist,
                1.0,
                Some(&mut hist_local),
            );
            ndk_sys::ANativeWindow_release(win_ptr);
        }
    }

    if let Ok(mut h_lock) = state.latest_histogram.lock() {
        *h_lock = hist_local;
    }

    0
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_exportRaw16ToCinemaDng(
    mut env: JNIEnv,
    _class: JClass,
    raw16_path: JString,
    out_dir_path: JString,
) -> jint {
    let in_path: String = match env.get_string(&raw16_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let out_dir: String = match env.get_string(&out_dir_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    let _ = std::fs::create_dir_all(&out_dir);

    let mut file = match File::open(&in_path) {
        Ok(f) => f,
        Err(_) => return -1,
    };

    let mut hdr = [0u8; 24];
    let mut exported_count: i32 = 0;

    loop {
        if file.read_exact(&mut hdr).is_err() {
            break;
        }
        if &hdr[0..4] != b"RAWZ" && &hdr[0..4] != b"RAWF" {
            break;
        }

        let w = u32::from_le_bytes(hdr[4..8].try_into().unwrap()) as usize;
        let h = u32::from_le_bytes(hdr[8..12].try_into().unwrap()) as usize;
        let stride_or_len = u32::from_le_bytes(hdr[12..16].try_into().unwrap()) as usize;
        let white_level = f32::from_le_bytes(hdr[16..20].try_into().unwrap());
        let cfa = u32::from_le_bytes(hdr[20..24].try_into().unwrap()) as i32;

        let (raw_buf, actual_stride) =
            match read_raw_frame_payload(&mut file, &hdr, w, h, stride_or_len) {
                Ok(res) => res,
                Err(_) => break,
            };

        let dng_filename = format!("{}/frame_{:05}.dng", out_dir, exported_count);
        if write_cinema_dng_frame(
            &dng_filename,
            w as u32,
            h as u32,
            actual_stride as u32,
            white_level,
            cfa,
            &raw_buf,
        )
        .is_ok()
        {
            exported_count += 1;
        } else {
            break;
        }
    }

    info!(
        "Exported {} CinemaDNG (.dng) frames to {}",
        exported_count, out_dir
    );
    exported_count
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_generateLogCurve(
    env: JNIEnv,
    _class: JClass,
    transfer_mode: i32,
    exposure_ev: f32,
) -> jfloatArray {
    let points = 64usize;
    let mut curve = Vec::with_capacity(points * 2);
    let ev_gain = 2.0_f32.powf(exposure_ev);

    for i in 0..points {
        let x = i as f32 / (points - 1) as f32;
        let scaled_x = (x * ev_gain).max(0.0);

        let y = match transfer_mode {
            1 => scaled_x.powf(1.0 / 2.2),
            2 => linear_to_slog3(scaled_x),
            3 => linear_to_arri_logc4(scaled_x),
            4 => linear_to_aces_cct(scaled_x),
            5 => linear_to_canon_log3(scaled_x),
            6 => linear_to_apple_log(scaled_x),
            _ => linear_to_slog3(scaled_x),
        };

        curve.push(x);
        curve.push(y.clamp(0.0, 1.0));
    }

    let out = env
        .new_float_array(curve.len() as i32)
        .expect("Failed to allocate JNI float array");
    env.set_float_array_region(&out, 0, &curve)
        .expect("Failed to copy Log curve to JVM");
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_updateSensorMetadata(
    env: JNIEnv,
    _class: JClass,
    black_level_arr: JFloatArray,
    white_level: f32,
    wb_gains_arr: JFloatArray,
    ccm_arr: JFloatArray,
    cfa_pattern: i32,
    exposure_ev: f32,
) {
    let mut bl = [64.0f32; 4];
    let mut wb = [1.0f32; 4];
    let mut ccm = [0.0f32; 9];

    let _ = env.get_float_array_region(&black_level_arr, 0, &mut bl);
    let _ = env.get_float_array_region(&wb_gains_arr, 0, &mut wb);
    let _ = env.get_float_array_region(&ccm_arr, 0, &mut ccm);

    let state = get_state();
    state.cfa_pattern.store(cfa_pattern, Ordering::Relaxed);

    if let Ok(mut meta) = state.latest_metadata.write() {
        meta.black_level = bl;
        meta.white_level = white_level;
        meta.exposure_comp_ev = exposure_ev;
        meta.wb_gains = wb;
        meta.sensor_to_rec2020_col0 = [ccm[0], ccm[3], ccm[6], 0.0];
        meta.sensor_to_rec2020_col1 = [ccm[1], ccm[4], ccm[7], 0.0];
        meta.sensor_to_rec2020_col2 = [ccm[2], ccm[5], ccm[8], 0.0];
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_setRawOutputSurfaces(
    env: JNIEnv,
    _class: JClass,
    preview_surface: JObject,
    record_surface: JObject,
) {
    let state = get_state();
    unsafe {
        if let Ok(mut pw) = state.preview_window.lock() {
            *pw = if !preview_surface.is_null() {
                let ptr = ndk_sys::ANativeWindow_fromSurface(
                    env.get_native_interface(),
                    preview_surface.as_raw(),
                );
                if !ptr.is_null() {
                    Some(SafeNativeWindow(ptr))
                } else {
                    None
                }
            } else {
                None
            };
        }
        if let Ok(mut rw) = state.record_window.lock() {
            *rw = if !record_surface.is_null() {
                let ptr = ndk_sys::ANativeWindow_fromSurface(
                    env.get_native_interface(),
                    record_surface.as_raw(),
                );
                if !ptr.is_null() {
                    Some(SafeNativeWindow(ptr))
                } else {
                    None
                }
            } else {
                None
            };
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_NativeVulkanEngine_createRawSensorSurface(
    env: JNIEnv,
    _class: JClass,
    width: i32,
    height: i32,
) -> jobject {
    let state = get_state().clone();
    info!(
        "Creating Full RAW_SENSOR (RAW16) Zero-ISP Pipeline at {}x{}",
        width, height
    );

    let mut reader = ImageReader::new_with_usage(
        width,
        height,
        ImageFormat::from(32i32),
        HardwareBufferUsage::CPU_READ_OFTEN | HardwareBufferUsage::GPU_SAMPLED_IMAGE,
        8,
    )
    .expect("Failed to create RAW16 ImageReader");

    reader
        .set_image_listener(Box::new(move |reader| {
            let mut latest_img = None;
            while let Ok(AcquireResult::Image(img)) = reader.acquire_next_image() {
                latest_img = Some(img);
            }

            if let Some(image) = latest_img {
                unsafe {
                    let raw_img_ptr: *mut ndk_sys::AImage =
                        *(&image as *const _ as *const *mut ndk_sys::AImage);
                    let mut w: i32 = width;
                    let mut h: i32 = height;
                    let mut row_stride: i32 = width * 2;
                    let mut data_ptr: *mut u8 = std::ptr::null_mut();
                    let mut data_len: i32 = 0;

                    let _ = ndk_sys::AImage_getWidth(raw_img_ptr, &mut w);
                    let _ = ndk_sys::AImage_getHeight(raw_img_ptr, &mut h);
                    let _ = ndk_sys::AImage_getPlaneRowStride(raw_img_ptr, 0, &mut row_stride);
                    let _ = ndk_sys::AImage_getPlaneData(
                        raw_img_ptr,
                        0,
                        &mut data_ptr,
                        &mut data_len,
                    );

                    if !data_ptr.is_null() && data_len > 0 {
                        let meta = state
                            .latest_metadata
                            .read()
                            .map(|m| *m)
                            .unwrap_or_else(|_| Zeroable::zeroed());
                        let cfa = state.cfa_pattern.load(Ordering::Relaxed);
                        let assist = state.preview_assist.load(Ordering::Relaxed);
                        let ev_gain = 2.0_f32.powf(meta.exposure_comp_ev);
                        let lut = build_12bit_log_lut(2, ev_gain);

                        let current_z = state
                            .zoom_servo
                            .lock()
                            .map(|mut s| s.step().0)
                            .unwrap_or(1.0);

                        let dump_fd = state.raw_dump_fd.load(Ordering::Relaxed);
                        if dump_fd >= 0 {
                            let raw_slice =
                                std::slice::from_raw_parts(data_ptr, data_len as usize);
                            let packed = pack_raw16_to_lossless_12bit(
                                raw_slice,
                                w as usize,
                                h as usize,
                                row_stride as usize,
                            );

                            let mut file = std::mem::ManuallyDrop::new(
                                std::fs::File::from_raw_fd(dump_fd),
                            );
                            let mut hdr = [0u8; 24];
                            hdr[0..4].copy_from_slice(b"RAWZ");
                            hdr[4..8].copy_from_slice(&(w as u32).to_le_bytes());
                            hdr[8..12].copy_from_slice(&(h as u32).to_le_bytes());
                            hdr[12..16].copy_from_slice(&(packed.len() as u32).to_le_bytes());
                            hdr[16..20].copy_from_slice(&meta.white_level.to_le_bytes());
                            hdr[20..24].copy_from_slice(&(cfa as u32).to_le_bytes());
                            let _ = file.write_all(&hdr);
                            let _ = file.write_all(&packed);
                        }

                        let mut hist_local = [0.0f32; 64];
                        if let Ok(pw_guard) = state.preview_window.lock() {
                            if let Some(ref win) = *pw_guard {
                                blit_raw16_to_window(
                                    win.0,
                                    data_ptr,
                                    w,
                                    h,
                                    row_stride,
                                    &meta,
                                    cfa,
                                    &lut,
                                    assist,
                                    current_z,
                                    Some(&mut hist_local),
                                );
                            }
                        }
                        if let Ok(mut h_lock) = state.latest_histogram.lock() {
                            *h_lock = hist_local;
                        }

                        if let Ok(rw_guard) = state.record_window.lock() {
                            if let Some(ref win) = *rw_guard {
                                blit_raw16_to_window(
                                    win.0,
                                    data_ptr,
                                    w,
                                    h,
                                    row_stride,
                                    &meta,
                                    cfa,
                                    &lut,
                                    0,
                                    current_z,
                                    None,
                                );
                            }
                        }
                    }
                }
            }
        }))
        .expect("Failed to set native RAW16 listener");

    let window = reader.window().expect("Failed to get RAW16 ANativeWindow");
    let surface_obj = unsafe {
        ndk_sys::ANativeWindow_toSurface(env.get_native_interface(), window.ptr().as_ptr())
    };

    std::mem::forget(reader);
    surface_obj
}