// File: vulkan.rs
// Path: app/src/main/rust/src/vulkan.rs
use ash::vk;
use bytemuck::{Pod, Zeroable};

pub static CINEMA_SHADER_SPV: &[u8] = include_bytes!("../shaders/cinema_pipeline.spv");

pub unsafe fn create_cinema_shader_module(
    device: &ash::Device,
) -> Result<vk::ShaderModule, vk::Result> {
    let spv_words: &[u32] = bytemuck::cast_slice(CINEMA_SHADER_SPV);
    let create_info = vk::ShaderModuleCreateInfo::default().code(spv_words);
    device.create_shader_module(&create_info, None)
}

pub struct SafeNativeWindow(pub *mut ndk_sys::ANativeWindow);
unsafe impl Send for SafeNativeWindow {}
unsafe impl Sync for SafeNativeWindow {}

impl Drop for SafeNativeWindow {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { ndk_sys::ANativeWindow_release(self.0); }
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
