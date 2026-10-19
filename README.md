# Pixel Cinema Log

A professional cinema camera application for Android, built with Jetpack Compose, Camera2 API, and a custom Rust/Vulkan engine. It brings professional filmmaking tools to Android devices (especially tuned for Google Pixel), including 10-bit log profiles, RAW recording, and custom cinematic zoom easings.

## Features

* **Professional Log Profiles:** Shoot in Sony S-Log3, ARRI LogC4, Canon C-Log3, Apple Log, or ACEScct directly on device.
* **RAW Sensor Capture:** Capture 16-bit uncompressed RAW data or hardware-accelerated 10-bit All-Intra HLG.
* **Custom Zoom Easing:** A built-in bezier curve editor to fine-tune digital zoom interpolations for cinematic push-ins and pull-outs.
* **Exposure Tools:** Live Histogram, False Color, and Zebra stripes to nail exposure.
* **Aspect Ratios:** Real-time matte overlays for 4:3 Open Gate, 3:2, 16:9, 2.39:1 Scope, and more.
* **RAW to CinemaDNG:** Includes an on-device RAW16 player and exporter to CinemaDNG for DaVinci Resolve workflows.
* **Manual Controls:** ISO, Shutter Speed, Exposure Compensation, White Balance, and Manual Focus.

## Architecture

* **UI:** Jetpack Compose with a custom Leica-inspired design system.
* **Camera Interface:** Android Camera2 API for low-level sensor access.
* **Processing Engine:** Rust + Ash (Vulkan) for high-performance LUT processing, color space transforms, and RAW decoding.
* **Video Encoding:** Hardware-accelerated `MediaCodec` (HEVC Main10, All-Intra).

## Build Instructions

### Prerequisites
* Android Studio
* Rust toolchain with `aarch64-linux-android` target installed.
* `cargo-ndk` installed.

### Setup
1. Clone the repository.
2. Open the project in Android Studio.
3. Build the Rust JNI library:
   ```bash
   ./gradlew :app:buildRustVulkanEngine
   ```
4. Build and run the Android app:
   ```bash
   ./gradlew assembleDebug
   ```

## License
MIT License
