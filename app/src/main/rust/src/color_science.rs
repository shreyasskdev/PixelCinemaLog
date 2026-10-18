// File: color_science.rs
// Path: app/src/main/rust/src/color_science.rs
#[inline(always)]
pub fn linear_to_slog3(x: f32) -> f32 {
    if x >= 0.01125000 {
        (420.0 + ((x + 0.01).log10() - 0.19_f32.log10()) * 261.5) / 1023.0
    } else {
        (x * (171.2102946929 - 95.0) / 0.01125000 + 95.0) / 1023.0
    }
}

#[inline(always)]
pub fn linear_to_arri_logc4(x: f32) -> f32 {
    let a = 2231.8263_f32;
    let b = 0.90713587_f32;
    let c = 0.092864125_f32;
    let s = -0.000441625_f32;
    let t = -0.018056996_f32;
    if x >= t { ((a * x + 64.0).log2() - 6.0) / 14.0 * b + c } else { (x - t) / s }
}

#[inline(always)]
pub fn linear_to_aces_cct(x: f32) -> f32 {
    if x <= 0.0078125 { 10.5402377 * x + 0.072905534 } else { (x.log2() + 9.72) / 17.52 }
}

#[inline(always)]
pub fn linear_to_canon_log3(x: f32) -> f32 {
    if x < -0.014 {
        -0.36726845 * (1.0 - 14.98325 * x).log10() + 0.12783901
    } else if x <= 0.014 {
        1.9754798 * x + 0.12512219
    } else {
        0.36726845 * (14.98325 * x + 1.0).log10() + 0.12240537
    }
}

#[inline(always)]
pub fn linear_to_apple_log(r: f32) -> f32 {
    let r0 = -0.05641088_f32;
    let rt = 0.01_f32;
    let c = 47.28711236_f32;
    let beta = 0.00964052_f32;
    let gamma = 0.08550479_f32;
    let delta = 0.69336945_f32;
    if r < r0 { 0.0 }
    else if r < rt { c * (r - r0).powi(2) }
    else { gamma * (r + beta).log2() + delta }
}

#[inline(always)]
pub fn linear_to_rec709(x: f32) -> f32 {
    let m = (x / (x + 0.18)).clamp(0.0, 1.0);
    m.powf(1.0 / 2.2)
}

pub fn build_12bit_log_lut(transfer_mode: i32, ev_gain: f32) -> [u8; 4096] {
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
