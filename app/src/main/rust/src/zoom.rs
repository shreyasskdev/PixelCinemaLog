// File: zoom.rs
// Path: app/src/main/rust/src/zoom.rs
use std::time::Instant;

// ─────────────────────────────────────────────────────────────────────────────
// Zoom easing modes — includes a fully custom cubic-bezier variant
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Clone, Copy)]
pub enum ZoomEasing {
    Cosine,
    EaseInOutCubic,
    Smootherstep,
    EaseInOutQuint,
    CustomBezier { x1: f64, y1: f64, x2: f64, y2: f64 },
}

impl ZoomEasing {
    pub fn from_i32(v: i32) -> Self {
        match v {
            0 => Self::Cosine,
            2 => Self::Smootherstep,
            3 => Self::EaseInOutQuint,
            4 => Self::CustomBezier { x1: 0.25, y1: 0.10, x2: 0.25, y2: 1.0 },
            _ => Self::EaseInOutCubic,
        }
    }

    #[inline(always)]
    pub fn apply(&self, t: f64) -> f64 {
        let t = t.clamp(0.0, 1.0);
        match self {
            Self::Cosine => 0.5 - 0.5 * (std::f64::consts::PI * t).cos(),
            Self::EaseInOutCubic => {
                if t < 0.5 {
                    4.0 * t * t * t
                } else {
                    let f = -2.0 * t + 2.0;
                    1.0 - (f * f * f) / 2.0
                }
            }
            Self::Smootherstep => t * t * t * (t * (t * 6.0 - 15.0) + 10.0),
            Self::EaseInOutQuint => {
                if t < 0.5 {
                    16.0 * t.powi(5)
                } else {
                    let f = -2.0 * t + 2.0;
                    1.0 - f.powi(5) / 2.0
                }
            }
            Self::CustomBezier { x1, y1, x2, y2 } => bezier_y_for_x(t, *x1, *y1, *x2, *y2),
        }
    }
}

// ── Cubic-bezier math (CSS / iOS compatible) ────────────────────────────────
// P(t) = 3(1-t)^2 t * C1 + 3(1-t) t^2 * C2 + t^3    with P0=(0,0), P3=(1,1)

#[inline(always)]
fn bezier_coord(t: f64, c1: f64, c2: f64) -> f64 {
    let mt = 1.0 - t;
    3.0 * mt * mt * t * c1 + 3.0 * mt * t * t * c2 + t * t * t
}

#[inline(always)]
fn bezier_derivative(t: f64, c1: f64, c2: f64) -> f64 {
    let mt = 1.0 - t;
    3.0 * mt * mt * c1 + 6.0 * mt * t * (c2 - c1) + 3.0 * t * t * (1.0 - c2)
}

/// Solve y for a given x (time) using Newton-Raphson. Fast + accurate.
fn bezier_y_for_x(x: f64, x1: f64, y1: f64, x2: f64, y2: f64) -> f64 {
    if x <= 0.0 { return 0.0; }
    if x >= 1.0 { return 1.0; }
    if (x1 - y1).abs() < 1e-9 && (x2 - y2).abs() < 1e-9 { return x; }

    let mut t = x;
    for _ in 0..12 {
        let err = bezier_coord(t, x1, x2) - x;
        if err.abs() < 1e-7 { break; }
        let d = bezier_derivative(t, x1, x2);
        if d.abs() < 1e-7 { break; }
        t = (t - err / d).clamp(0.0, 1.0);
    }
    bezier_coord(t, y1, y2)
}

/// High-Precision Native Rust Cinema Power-Zoom Servo.
/// Stepped exclusively by the dedicated `ZoomServo240Hz` thread.
pub struct CinemaZoomServo {
    pub start_zoom: f64,
    pub target_zoom: f64,
    pub current_zoom: f64,
    pub start_instant: Instant,
    pub duration_secs: f64,
    pub is_animating: bool,
    pub easing: ZoomEasing,

    // ── NEW ── Duration control
    /// If > 0, overrides the auto-computed duration entirely (seconds).
    pub duration_override_secs: f64,
    /// Multiplier on the auto-computed duration (only used when override == 0).
    pub duration_scale: f64,
}

impl CinemaZoomServo {
    pub fn new() -> Self {
        Self {
            start_zoom: 1.0,
            target_zoom: 1.0,
            current_zoom: 1.0,
            start_instant: Instant::now(),
            duration_secs: 0.0,
            is_animating: false,
            easing: ZoomEasing::EaseInOutCubic,
            duration_override_secs: 0.0,   // 0 = auto
            duration_scale: 1.0,           // 1.0 = default speed
        }
    }

    pub fn set_easing(&mut self, mode: i32) {
        self.easing = ZoomEasing::from_i32(mode);
    }

    pub fn set_custom_bezier(&mut self, x1: f64, y1: f64, x2: f64, y2: f64) {
        self.easing = ZoomEasing::CustomBezier {
            x1: x1.clamp(0.0, 1.0),
            y1: y1.clamp(0.0, 1.0),
            x2: x2.clamp(0.0, 1.0),
            y2: y2.clamp(0.0, 1.0),
        };
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
        let auto_duration = (0.30 + octaves * 0.28).clamp(0.42, 1.45);

        // ── CHANGED ── Honour user override, else scale the auto value.
        self.duration_secs = if self.duration_override_secs > 0.0 {
            self.duration_override_secs.clamp(0.10, 5.00)
        } else {
            (auto_duration * self.duration_scale.clamp(0.10, 5.00)).clamp(0.10, 5.00)
        };

        self.start_instant = Instant::now();
        self.is_animating = true;
    }

    pub fn hard_reset(&mut self, zoom: f64) {
        let z = zoom.clamp(0.5, 10.0);
        self.start_zoom = z;
        self.target_zoom = z;
        self.current_zoom = z;
        self.duration_secs = 0.0;
        self.is_animating = false;
    }

    pub fn snapshot(&mut self) {
        self.start_zoom = self.current_zoom;
        self.target_zoom = self.current_zoom;
        self.duration_secs = 0.0;
        self.is_animating = false;
    }

    #[inline(always)]
    pub fn step(&mut self) -> (f32, bool) {
        if !self.is_animating {
            return (self.current_zoom as f32, false);
        }
        let elapsed = self.start_instant.elapsed().as_secs_f64();
        let t = (elapsed / self.duration_secs).clamp(0.0, 1.0);

        if t >= 1.0 {
            self.current_zoom = self.target_zoom;
            self.is_animating = false;
            return (self.current_zoom as f32, false);
        }

        let eased = self.easing.apply(t);
        // Log-space interpolation: equal octaves take equal time.
        self.current_zoom = self.start_zoom * (self.target_zoom / self.start_zoom).powf(eased);
        (self.current_zoom as f32, true)
    }
}
