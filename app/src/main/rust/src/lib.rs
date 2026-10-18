// File: lib.rs
// Path: app/src/main/rust/src/lib.rs
mod zoom;
mod color_science;
mod raw_codec;
mod vulkan;

use jni::{
    objects::{JClass, JFloatArray, JObject, JString},
    sys::{jboolean, jfloat, jfloatArray, jint, jobject},
    JNIEnv,
};
use log::info;
use ndk::hardware_buffer::HardwareBufferUsage;
use ndk::media::image_reader::{AcquireResult, ImageFormat, ImageReader};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::unix::io::FromRawFd;
use std::sync::{
    atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, Ordering},
    Arc, Mutex, OnceLock, RwLock,
};

use bytemuck::Zeroable;

use crate::zoom::{CinemaZoomServo, ZoomEasing};
use crate::color_science::*;
use crate::raw_codec::*;
use crate::vulkan::{FrameParamsPushConstants, SafeNativeWindow};

pub struct SharedCameraState {
    pub transfer_mode: AtomicI32,
    pub preview_assist: AtomicI32,
    pub cfa_pattern: AtomicI32,
    pub raw_frame_counter: AtomicU64,
    pub raw_dump_fd: AtomicI32,
    pub zoom_servo: Mutex<CinemaZoomServo>,
    pub current_zoom: AtomicU32,
    pub zoom_animating: AtomicBool,
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

        let state = Arc::new(SharedCameraState {
            transfer_mode: AtomicI32::new(2),
            preview_assist: AtomicI32::new(0),
            cfa_pattern: AtomicI32::new(0),
            raw_frame_counter: AtomicU64::new(0),
            raw_dump_fd: AtomicI32::new(-1),
            zoom_servo: Mutex::new(CinemaZoomServo::new()),
            current_zoom: AtomicU32::new(1.0f32.to_bits()),
            zoom_animating: AtomicBool::new(false),
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
        });

        // ── 240 Hz servo thread — the ONLY place the servo is stepped ──
        let servo_state = Arc::clone(&state);
        std::thread::Builder::new()
            .name("ZoomServo240Hz".into())
            .spawn(move || loop {
                std::thread::sleep(std::time::Duration::from_micros(4_166));
                if let Ok(mut servo) = servo_state.zoom_servo.lock() {
                    let (z, animating) = servo.step();
                    servo_state.current_zoom.store(z.to_bits(), Ordering::Release);
                    servo_state.zoom_animating.store(animating, Ordering::Release);
                }
            })
            .expect("Failed to spawn ZoomServo240Hz thread");

        state
    })
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
    if window_ptr.is_null() || raw_bytes.is_null() { return; }
    let out_w = (raw_width / 2).min(1920);
    let out_h = (raw_height / 2).min(1080);

    let _ = ndk_sys::ANativeWindow_setBuffersGeometry(window_ptr, out_w, out_h, 1);
    let mut buffer: ndk_sys::ANativeWindow_Buffer = std::mem::zeroed();
    if ndk_sys::ANativeWindow_lock(window_ptr, &mut buffer, std::ptr::null_mut()) != 0 { return; }

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
        if by1 + 1 >= raw_height as usize { continue; }
        let dst_row = dst_ptr.add(y * dst_stride);

        for x in 0..max_x {
            let qx_f = off_qx + ((x as f32) * crop_qw) / (max_x as f32);
            let qx0 = qx_f as usize;
            let fx = qx_f - (qx0 as f32);
            let bx0 = qx0 * 2;
            let bx1 = (qx0 + 1) * 2;
            if bx1 + 1 >= raw_width as usize { continue; }

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
                if ire >= 95 { r = 255; g = 0; b = 0; }
                else if ire >= 85 { r = 255; g = 255; b = 0; }
                else if ire >= 52 && ire <= 58 { r = 255; g = 128; b = 128; }
                else if ire >= 38 && ire <= 44 { r = 0; g = 210; b = 50; }
                else if ire <= 5 { r = 130; g = 0; b = 210; }
                else { r = ire; g = ire; b = ire; }
            } else if apply_assist == 3 {
                let ire = ((r as u32 * 21 + g as u32 * 72 + b as u32 * 7) / 255) as u8;
                if ire >= 95 && ((x + y) / 6) % 2 == 0 { r = 255; g = 0; b = 0; }
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

// ─────────────────────────────────────────────────────────────────────────────
// JNI entry points
// ─────────────────────────────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_initEngine(
    _env: JNIEnv, _class: JClass,
) {
    let _ = get_state();
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_setZoomTarget(
    _env: JNIEnv, _class: JClass, target_zoom: jfloat,
) {
    let state = get_state();
    if let Ok(mut servo) = state.zoom_servo.lock() {
        servo.set_target(target_zoom as f64);
        state.current_zoom.store((servo.current_zoom as f32).to_bits(), Ordering::Release);
        state.zoom_animating.store(servo.is_animating, Ordering::Release);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_setZoomDurationFixed(
    _env: JNIEnv, _class: JClass, seconds: jfloat,
) {
    if let Ok(mut servo) = get_state().zoom_servo.lock() {
        servo.duration_override_secs = if seconds > 0.0 {
            (seconds as f64).clamp(0.10, 5.00)
        } else {
            0.0
        };
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_getZoomDurationFixed(
    _env: JNIEnv, _class: JClass,
) -> jfloat {
    get_state().zoom_servo.lock()
        .map(|s| s.duration_override_secs as f32)
        .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_setZoomDurationScale(
    _env: JNIEnv, _class: JClass, scale: jfloat,
) {
    if let Ok(mut servo) = get_state().zoom_servo.lock() {
        servo.duration_scale = (scale as f64).clamp(0.10, 5.00);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_getZoomDurationScale(
    _env: JNIEnv, _class: JClass,
) -> jfloat {
    get_state().zoom_servo.lock()
        .map(|s| s.duration_scale as f32)
        .unwrap_or(1.0)
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_getCurrentZoom(
    _env: JNIEnv, _class: JClass,
) -> jfloat {
    f32::from_bits(get_state().current_zoom.load(Ordering::Acquire))
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_isZoomAnimating(
    _env: JNIEnv, _class: JClass,
) -> jboolean {
    get_state().zoom_animating.load(Ordering::Acquire) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_resetZoomTo(
    _env: JNIEnv, _class: JClass, zoom: jfloat,
) {
    let state = get_state();
    if let Ok(mut servo) = state.zoom_servo.lock() {
        servo.hard_reset(zoom as f64);
        state.current_zoom.store((servo.current_zoom as f32).to_bits(), Ordering::Release);
        state.zoom_animating.store(false, Ordering::Release);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_snapshotZoom(
    _env: JNIEnv, _class: JClass,
) {
    let state = get_state();
    if let Ok(mut servo) = state.zoom_servo.lock() {
        servo.snapshot();
        state.current_zoom.store((servo.current_zoom as f32).to_bits(), Ordering::Release);
        state.zoom_animating.store(false, Ordering::Release);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_setZoomEasing(
    _env: JNIEnv, _class: JClass, mode: i32,
) {
    if let Ok(mut servo) = get_state().zoom_servo.lock() {
        servo.set_easing(mode);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_setCustomBezierEasing(
    _env: JNIEnv, _class: JClass,
    x1: jfloat, y1: jfloat, x2: jfloat, y2: jfloat,
) {
    if let Ok(mut servo) = get_state().zoom_servo.lock() {
        servo.set_custom_bezier(x1 as f64, y1 as f64, x2 as f64, y2 as f64);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_getCustomBezierEasing(
    env: JNIEnv, _class: JClass,
) -> jfloatArray {
    let (x1, y1, x2, y2) = match get_state().zoom_servo.lock() {
        Ok(servo) => match servo.easing {
            ZoomEasing::CustomBezier { x1, y1, x2, y2 } =>
                (x1 as f32, y1 as f32, x2 as f32, y2 as f32),
            _ => (0.25f32, 0.10f32, 0.25f32, 1.0f32),
        },
        Err(_) => (0.25f32, 0.10f32, 0.25f32, 1.0f32),
    };
    let out = env.new_float_array(4).expect("bezier array");
    let _ = env.set_float_array_region(&out, 0, &[x1, y1, x2, y2]);
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_setCameraMode(
    _env: JNIEnv, _class: JClass, transfer_mode: i32, preview_assist: i32,
) {
    let state = get_state();
    state.transfer_mode.store(transfer_mode, Ordering::Relaxed);
    state.preview_assist.store(preview_assist, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_setRawDumpFd(
    _env: JNIEnv, _class: JClass, fd: i32,
) {
    get_state().raw_dump_fd.store(fd, Ordering::SeqCst);
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_getLiveHistogram(
    env: JNIEnv, _class: JClass,
) -> jfloatArray {
    let state = get_state();
    let hist = state.latest_histogram.lock().map(|h| *h).unwrap_or([0.0f32; 64]);
    let out = env.new_float_array(64).expect("Failed to allocate histogram float array");
    let _ = env.set_float_array_region(&out, 0, &hist);
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_getRaw16FrameCount(
    mut env: JNIEnv, _class: JClass, file_path: JString,
) -> jint {
    let path_str: String = match env.get_string(&file_path) { Ok(s) => s.into(), Err(_) => return 0 };
    let mut file = match File::open(&path_str) { Ok(f) => f, Err(_) => return 0 };
    let file_len = match file.metadata() { Ok(m) => m.len(), Err(_) => return 0 };
    let mut hdr = [0u8; 24];
    if file.read_exact(&mut hdr).is_err() { return 0; }
    let h = u32::from_le_bytes(hdr[8..12].try_into().unwrap()) as u64;
    let stride_or_len = u32::from_le_bytes(hdr[12..16].try_into().unwrap()) as u64;
    let frame_bytes = if &hdr[0..4] == b"RAWZ" { 24 + stride_or_len }
        else if &hdr[0..4] == b"RAWF" { 24 + (h * stride_or_len) } else { return 0 };
    if frame_bytes == 0 { return 0; }
    (file_len / frame_bytes) as jint
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_renderRaw16FrameFromFile(
    mut env: JNIEnv, _class: JClass,
    file_path: JString, frame_index: jint,
    transfer_mode: jint, preview_assist: jint, exposure_ev: f32, surface: JObject,
) -> jint {
    if surface.is_null() { return -1; }
    let path_str: String = match env.get_string(&file_path) { Ok(s) => s.into(), Err(_) => return -1 };
    let mut file = match File::open(&path_str) { Ok(f) => f, Err(_) => return -1 };
    let mut hdr = [0u8; 24];
    if file.read_exact(&mut hdr).is_err() { return -1; }
    let w = u32::from_le_bytes(hdr[4..8].try_into().unwrap()) as usize;
    let h = u32::from_le_bytes(hdr[8..12].try_into().unwrap()) as usize;
    let stride_or_len = u32::from_le_bytes(hdr[12..16].try_into().unwrap()) as usize;
    let white_level = f32::from_le_bytes(hdr[16..20].try_into().unwrap());
    let cfa = u32::from_le_bytes(hdr[20..24].try_into().unwrap()) as i32;
    let frame_total_size = if &hdr[0..4] == b"RAWZ" { 24 + stride_or_len as u64 }
        else if &hdr[0..4] == b"RAWF" { 24 + (h * stride_or_len) as u64 } else { return -1 };
    let target_offset = (frame_index.max(0) as u64) * frame_total_size;
    if file.seek(SeekFrom::Start(target_offset)).is_err() { return -1; }
    if file.read_exact(&mut hdr).is_err() { return -1; }
    let (raw_buf, actual_stride) = match read_raw_frame_payload(&mut file, &hdr, w, h, stride_or_len) {
        Ok(res) => res, Err(_) => return -1,
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
        let win_ptr = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface.as_raw());
        if !win_ptr.is_null() {
            blit_raw16_to_window(win_ptr, raw_buf.as_ptr(), w as i32, h as i32, actual_stride,
                &meta, cfa, &lut, preview_assist, 1.0, Some(&mut hist_local));
            ndk_sys::ANativeWindow_release(win_ptr);
        }
    }
    if let Ok(mut h_lock) = state.latest_histogram.lock() { *h_lock = hist_local; }
    0
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_exportRaw16ToCinemaDng(
    mut env: JNIEnv, _class: JClass, raw16_path: JString, out_dir_path: JString,
) -> jint {
    let in_path: String = match env.get_string(&raw16_path) { Ok(s) => s.into(), Err(_) => return -1 };
    let out_dir: String = match env.get_string(&out_dir_path) { Ok(s) => s.into(), Err(_) => return -1 };
    let _ = std::fs::create_dir_all(&out_dir);
    let mut file = match File::open(&in_path) { Ok(f) => f, Err(_) => return -1 };
    let mut hdr = [0u8; 24];
    let mut exported_count: i32 = 0;
    loop {
        if file.read_exact(&mut hdr).is_err() { break; }
        if &hdr[0..4] != b"RAWZ" && &hdr[0..4] != b"RAWF" { break; }
        let w = u32::from_le_bytes(hdr[4..8].try_into().unwrap()) as usize;
        let h = u32::from_le_bytes(hdr[8..12].try_into().unwrap()) as usize;
        let stride_or_len = u32::from_le_bytes(hdr[12..16].try_into().unwrap()) as usize;
        let white_level = f32::from_le_bytes(hdr[16..20].try_into().unwrap());
        let cfa = u32::from_le_bytes(hdr[20..24].try_into().unwrap()) as i32;
        let (raw_buf, actual_stride) =
            match read_raw_frame_payload(&mut file, &hdr, w, h, stride_or_len) {
                Ok(res) => res, Err(_) => break,
            };
        let dng_filename = format!("{}/frame_{:05}.dng", out_dir, exported_count);
        if write_cinema_dng_frame(&dng_filename, w as u32, h as u32, actual_stride as u32,
            white_level, cfa, &raw_buf).is_ok() { exported_count += 1; } else { break; }
    }
    info!("Exported {} CinemaDNG (.dng) frames to {}", exported_count, out_dir);
    exported_count
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_generateLogCurve(
    env: JNIEnv, _class: JClass, transfer_mode: i32, exposure_ev: f32,
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
    let out = env.new_float_array(curve.len() as i32).expect("Failed to allocate JNI float array");
    env.set_float_array_region(&out, 0, &curve).expect("Failed to copy Log curve to JVM");
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_updateSensorMetadata(
    env: JNIEnv, _class: JClass,
    black_level_arr: JFloatArray, white_level: f32,
    wb_gains_arr: JFloatArray, ccm_arr: JFloatArray,
    cfa_pattern: i32, exposure_ev: f32,
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
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_setRawOutputSurfaces(
    env: JNIEnv, _class: JClass, preview_surface: JObject, record_surface: JObject,
) {
    let state = get_state();
    unsafe {
        if let Ok(mut pw) = state.preview_window.lock() {
            *pw = if !preview_surface.is_null() {
                let ptr = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), preview_surface.as_raw());
                if !ptr.is_null() { Some(SafeNativeWindow(ptr)) } else { None }
            } else { None };
        }
        if let Ok(mut rw) = state.record_window.lock() {
            *rw = if !record_surface.is_null() {
                let ptr = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), record_surface.as_raw());
                if !ptr.is_null() { Some(SafeNativeWindow(ptr)) } else { None }
            } else { None };
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_shreyasskdev_pixelcinemalog_camera_NativeVulkanEngine_createRawSensorSurface(
    env: JNIEnv, _class: JClass, width: i32, height: i32,
) -> jobject {
    let state = get_state().clone();
    info!("Creating RAW_SENSOR (RAW16) Zero-ISP Pipeline at {}x{}", width, height);

    let mut reader = ImageReader::new_with_usage(
        width, height, ImageFormat::from(32i32),
        HardwareBufferUsage::CPU_READ_OFTEN | HardwareBufferUsage::GPU_SAMPLED_IMAGE, 8,
    ).expect("Failed to create RAW16 ImageReader");

    reader.set_image_listener(Box::new(move |reader| {
        let mut latest_img = None;
        while let Ok(AcquireResult::Image(img)) = reader.acquire_next_image() {
            latest_img = Some(img);
        }
        if let Some(image) = latest_img {
            unsafe {
                let raw_img_ptr: *mut ndk_sys::AImage = *(&image as *const _ as *const *mut ndk_sys::AImage);
                let mut w: i32 = width;
                let mut h: i32 = height;
                let mut row_stride: i32 = width * 2;
                let mut data_ptr: *mut u8 = std::ptr::null_mut();
                let mut data_len: i32 = 0;
                let _ = ndk_sys::AImage_getWidth(raw_img_ptr, &mut w);
                let _ = ndk_sys::AImage_getHeight(raw_img_ptr, &mut h);
                let _ = ndk_sys::AImage_getPlaneRowStride(raw_img_ptr, 0, &mut row_stride);
                let _ = ndk_sys::AImage_getPlaneData(raw_img_ptr, 0, &mut data_ptr, &mut data_len);

                if !data_ptr.is_null() && data_len > 0 {
                    let meta = state.latest_metadata.read().map(|m| *m).unwrap_or_else(|_| Zeroable::zeroed());
                    let cfa = state.cfa_pattern.load(Ordering::Relaxed);
                    let assist = state.preview_assist.load(Ordering::Relaxed);
                    let ev_gain = 2.0_f32.powf(meta.exposure_comp_ev);
                    let lut = build_12bit_log_lut(2, ev_gain);

                    // READ-ONLY: servo is stepped by the 240 Hz Rust thread only.
                    let current_z = f32::from_bits(state.current_zoom.load(Ordering::Acquire));

                    let dump_fd = state.raw_dump_fd.load(Ordering::Relaxed);
                    if dump_fd >= 0 {
                        let raw_slice = std::slice::from_raw_parts(data_ptr, data_len as usize);
                        let packed = pack_raw16_to_lossless_12bit(raw_slice, w as usize, h as usize, row_stride as usize);
                        let mut file = std::mem::ManuallyDrop::new(std::fs::File::from_raw_fd(dump_fd));
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
                            blit_raw16_to_window(win.0, data_ptr, w, h, row_stride, &meta, cfa, &lut, assist, current_z, Some(&mut hist_local));
                        }
                    }
                    if let Ok(mut h_lock) = state.latest_histogram.lock() { *h_lock = hist_local; }

                    if let Ok(rw_guard) = state.record_window.lock() {
                        if let Some(ref win) = *rw_guard {
                            blit_raw16_to_window(win.0, data_ptr, w, h, row_stride, &meta, cfa, &lut, 0, current_z, None);
                        }
                    }
                }
            }
        }
    })).expect("Failed to set native RAW16 listener");

    let window = reader.window().expect("Failed to get RAW16 ANativeWindow");
    let surface_obj = unsafe { ndk_sys::ANativeWindow_toSurface(env.get_native_interface(), window.ptr().as_ptr()) };
    std::mem::forget(reader);
    surface_obj
}
