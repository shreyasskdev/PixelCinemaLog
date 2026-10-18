// File: raw_codec.rs
// Path: app/src/main/rust/src/raw_codec.rs
use std::fs::File;
use std::io::{Read, Write};

pub fn pack_raw16_to_lossless_12bit(raw_bytes: &[u8], w: usize, h: usize, stride_bytes: usize) -> Vec<u8> {
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

pub fn unpack_lossless_12bit_to_raw16(packed: &[u8], w: usize, h: usize) -> Vec<u8> {
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

pub fn write_cinema_dng_frame(
    out_path: &str, w: u32, h: u32, stride: u32,
    white_level: f32, cfa: i32, raw_bytes: &[u8],
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

    let mut push_tag = |tag: u16, typ: u16, count: u32, val_or_offset: u32| {
        hdr.extend_from_slice(&tag.to_le_bytes());
        hdr.extend_from_slice(&typ.to_le_bytes());
        hdr.extend_from_slice(&count.to_le_bytes());
        hdr.extend_from_slice(&val_or_offset.to_le_bytes());
    };

    push_tag(0x00FE, 4, 1, 0);
    push_tag(0x0100, 4, 1, w);
    push_tag(0x0101, 4, 1, h);
    push_tag(0x0102, 3, 1, 16);
    push_tag(0x0103, 3, 1, 1);
    push_tag(0x0106, 3, 1, 32803);
    push_tag(0x0111, 4, 1, pixel_data_offset);
    push_tag(0x0112, 3, 1, 1);
    push_tag(0x0115, 3, 1, 1);
    push_tag(0x0116, 4, 1, h);
    push_tag(0x0117, 4, 1, strip_byte_count);
    push_tag(0x828D, 3, 2, 0x0002_0002);
    push_tag(0x828E, 1, 4, u32::from_le_bytes(cfa_bytes));
    push_tag(0xC612, 1, 4, u32::from_le_bytes([1, 4, 0, 0]));
    push_tag(0xC61A, 4, 1, 64);
    push_tag(0xC61D, 4, 1, (white_level as u32).max(1023));

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
            if end <= raw_bytes.len() { f.write_all(&raw_bytes[start..end])?; }
        }
    }
    Ok(())
}

pub fn read_raw_frame_payload(
    file: &mut File, hdr: &[u8; 24],
    w: usize, h: usize, stride_or_packed_len: usize,
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
