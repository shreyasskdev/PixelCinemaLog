use std::env;
use std::path::PathBuf;
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=shaders/cinema_pipeline.comp");

    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let comp_path = manifest_dir.join("shaders/cinema_pipeline.comp");
    let spv_path = manifest_dir.join("shaders/cinema_pipeline.spv");

    // 1. Try system `glslc`, or locate it inside the Android NDK toolchain on Linux
    let glslc_bin = env::var("ANDROID_NDK_HOME")
        .or_else(|_| env::var("ANDROID_NDK_ROOT"))
        .map(|ndk| {
            PathBuf::from(ndk)
                .join("shader-tools")
                .join("linux-x86_64")
                .join("glslc")
        })
        .ok()
        .filter(|p| p.exists())
        .unwrap_or_else(|| PathBuf::from("glslc"));

    let status = Command::new(&glslc_bin)
        .args([
            "-fshader-stage=compute",
            "-O",
            comp_path.to_str().unwrap(),
            "-o",
            spv_path.to_str().unwrap(),
        ])
        .status()
        .expect("Failed to execute glslc shader compiler from Android NDK");

    if !status.success() {
        panic!("glslc failed to compile shaders/cinema_pipeline.comp!");
    }
}