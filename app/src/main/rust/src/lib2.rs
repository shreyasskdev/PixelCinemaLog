use ndk::media::image_reader::ImageFormat;
pub fn test() -> ImageFormat { unsafe { std::mem::transmute::<i32, ImageFormat>(54) } }
