//! Bayer crop/bin, LJ92 compression and a CinemaDNG (DNG 1.4) writer. No platform code.

pub mod lj92;

pub const TILE: usize = 256;

/// Centred 16:9 crop of a full sensor frame: (crop width, crop height, y offset). Height and offset
/// are kept multiples of 4 so the CFA phase survives and RAW Lite can bin 2×2 same-colour cells.
pub fn crop_16x9(w: usize, h: usize) -> (usize, usize, usize) {
    let w = w & !3;
    let ch = ((w * 9 / 16 + 3) & !3).min(h & !3);
    (w, ch, ((h - ch) / 2) & !3)
}

/// Copies the crop out of a 16-bit little-endian RAW_SENSOR plane into `out` (cw × ch).
pub fn copy_crop(src: &[u8], row_stride: usize, cw: usize, ch: usize, y0: usize, out: &mut [u16]) {
    for y in 0..ch {
        let row = &src[(y0 + y) * row_stride..][..cw * 2];
        for (d, s) in out[y * cw..(y + 1) * cw].iter_mut().zip(row.chunks_exact(2)) {
            *d = u16::from_le_bytes([s[0], s[1]]);
        }
    }
}

/// Like [`copy_crop`] but averages each 2×2 group of same-colour photosites, producing a half-size
/// mosaic in the original CFA order.
pub fn copy_crop_bin(src: &[u8], row_stride: usize, cw: usize, ch: usize, y0: usize, out: &mut [u16]) {
    let (ow, oh) = (cw / 2, ch / 2);
    let px = |x: usize, y: usize| { let o = (y0 + y) * row_stride + x * 2; u16::from_le_bytes([src[o], src[o + 1]]) as u32 };
    for oy in 0..oh {
        let (by, py) = (oy / 2 * 4, oy % 2);
        for ox in 0..ow {
            let (bx, pxo) = (ox / 2 * 4, ox % 2);
            let (x, y) = (bx + pxo, by + py);
            out[oy * ow + ox] = ((px(x, y) + px(x + 2, y) + px(x, y + 2) + px(x + 2, y + 2) + 2) / 4) as u16;
        }
    }
}

/// Lens-shading gain maps, one per 2×2 CFA position (row-major: (0,0),(0,1),(1,0),(1,1)), with
/// origin/spacing in image-relative coordinates as the DNG GainMap opcode wants.
pub struct GainMaps {
    pub rows: u32,
    pub cols: u32,
    pub origin_v: f64,
    pub spacing_v: f64,
    pub origin_h: f64,
    pub spacing_h: f64,
    pub maps: [Vec<f32>; 4],
}

pub struct DngMeta<'a> {
    pub make: &'a str,
    pub model: &'a str,
    pub software: &'a str,
    pub reel: &'a str,
    /// 0 = red, 1 = green, 2 = blue, row-major 2×2.
    pub cfa: [u8; 4],
    pub black: [f32; 4],
    pub white: u32,
    /// Row-major 3×3 matrices; None when the camera doesn't report them.
    pub color_matrix: [Option<[f32; 9]>; 2],
    pub forward_matrix: [Option<[f32; 9]>; 2],
    pub calibration: [Option<[f32; 9]>; 2],
    pub illuminant: [u16; 2],
    pub as_shot_neutral: [f32; 3],
    pub noise_profile: Option<[f64; 2]>,
    pub exposure_ns: i64,
    pub iso: u16,
    pub f_number: f32,
    pub focal_mm: f32,
    pub fps: u32,
    /// SMPTE BCD time code: frames, seconds, minutes, hours.
    pub timecode: [u8; 4],
    pub gain_maps: Option<&'a GainMaps>,
}

pub fn timecode_bcd(total_frames: u64, fps: u32) -> [u8; 4] {
    let bcd = |v: u64| (((v / 10) << 4) | (v % 10)) as u8;
    let f = total_frames % fps as u64;
    let s = total_frames / fps as u64;
    [bcd(f), bcd(s % 60), bcd(s / 60 % 60), bcd(s / 3600 % 24)]
}

enum V { Byte(Vec<u8>), Ascii(String), Short(Vec<u16>), Long(Vec<u32>), Rational(Vec<(u32, u32)>), SRational(Vec<(i32, i32)>), Undef(Vec<u8>), Double(Vec<f64>) }

impl V {
    fn kind_count_bytes(&self) -> (u16, u32, Vec<u8>) {
        match self {
            V::Byte(v) => (1, v.len() as u32, v.clone()),
            V::Ascii(s) => { let mut b = s.as_bytes().to_vec(); b.push(0); (2, b.len() as u32, b) }
            V::Short(v) => (3, v.len() as u32, v.iter().flat_map(|x| x.to_le_bytes()).collect()),
            V::Long(v) => (4, v.len() as u32, v.iter().flat_map(|x| x.to_le_bytes()).collect()),
            V::Rational(v) => (5, v.len() as u32, v.iter().flat_map(|(a, b)| [a.to_le_bytes(), b.to_le_bytes()].concat()).collect()),
            V::Undef(v) => (7, v.len() as u32, v.clone()),
            V::SRational(v) => (10, v.len() as u32, v.iter().flat_map(|(a, b)| [a.to_le_bytes(), b.to_le_bytes()].concat()).collect()),
            V::Double(v) => (12, v.len() as u32, v.iter().flat_map(|x| x.to_le_bytes()).collect()),
        }
    }
}

fn srat(m: &[f32]) -> V { V::SRational(m.iter().map(|&x| ((x * 10000.0).round() as i32, 10000)).collect()) }
fn urat(x: f64) -> (u32, u32) { ((x * 10000.0).round().max(0.0) as u32, 10000) }

fn gain_map_opcodes(g: &GainMaps, w: usize, h: usize) -> Vec<u8> {
    let mut o = Vec::new();
    o.extend_from_slice(&4u32.to_be_bytes());
    for (i, map) in g.maps.iter().enumerate() {
        let mut p = Vec::new();
        for v in [(i / 2) as u32, (i % 2) as u32, h as u32, w as u32, 0, 1, 2, 2, g.rows, g.cols] { p.extend_from_slice(&v.to_be_bytes()) }
        for v in [g.spacing_v, g.spacing_h, g.origin_v, g.origin_h] { p.extend_from_slice(&v.to_be_bytes()) }
        p.extend_from_slice(&1u32.to_be_bytes());
        for v in map { p.extend_from_slice(&v.to_be_bytes()) }
        o.extend_from_slice(&9u32.to_be_bytes()); // GainMap
        o.extend_from_slice(&0x0103_0000u32.to_be_bytes());
        o.extend_from_slice(&1u32.to_be_bytes()); // optional: readers may skip
        o.extend_from_slice(&(p.len() as u32).to_be_bytes());
        o.extend_from_slice(&p);
    }
    o
}

/// A self-contained IFD (no next IFD) followed by its out-of-line values, placed at file offset `base`.
fn ifd_block(entries: &[(u16, V)], base: usize) -> Vec<u8> {
    let enc: Vec<(u16, u16, u32, Vec<u8>)> = entries.iter().map(|(t, v)| { let (k, c, b) = v.kind_count_bytes(); (*t, k, c, b) }).collect();
    let mut data_off = base + 2 + 12 * enc.len() + 4;
    let mut out = (enc.len() as u16).to_le_bytes().to_vec();
    let mut data = Vec::new();
    for (t, k, c, b) in &enc {
        out.extend_from_slice(&t.to_le_bytes());
        out.extend_from_slice(&k.to_le_bytes());
        out.extend_from_slice(&c.to_le_bytes());
        if b.len() > 4 {
            out.extend_from_slice(&(data_off as u32).to_le_bytes());
            data.extend_from_slice(b);
            if b.len() % 2 == 1 { data.push(0) }
            data_off += (b.len() + 1) & !1;
        } else { let mut v = b.clone(); v.resize(4, 0); out.extend_from_slice(&v) }
    }
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend(data);
    out
}

/// Encodes `img` (w × h CFA, 16-bit) into a tiled LJ92 CinemaDNG file.
pub fn write_dng(img: &[u16], w: usize, h: usize, m: &DngMeta) -> Vec<u8> {
    let (tx, ty) = (w.div_ceil(TILE), h.div_ceil(TILE));
    let mut tile = vec![0u16; TILE * TILE];
    let mut scratch = Vec::with_capacity(TILE * TILE);
    let mut tiles = Vec::with_capacity(tx * ty);
    for j in 0..ty {
        for i in 0..tx {
            let (x0, y0) = (i * TILE, j * TILE);
            if x0 + TILE <= w && y0 + TILE <= h {
                tiles.push(lj92::encode_strided(&img[y0 * w + x0..], w, TILE, TILE, &mut scratch));
                continue;
            }
            // Edge tiles are padded by repeating the last same-colour column/row.
            for y in 0..TILE {
                let gy = y0 + y;
                let sy = if gy < h { gy } else { h - 2 + (gy - h) % 2 };
                for x in 0..TILE {
                    let gx = x0 + x;
                    let sx = if gx < w { gx } else { w - 2 + (gx - w) % 2 };
                    tile[y * TILE + x] = img[sy * w + sx];
                }
            }
            tiles.push(lj92::encode_strided(&tile, TILE, TILE, TILE, &mut scratch));
        }
    }

    let mut e: Vec<(u16, V)> = vec![
        (254, V::Long(vec![0])),
        (256, V::Long(vec![w as u32])),
        (257, V::Long(vec![h as u32])),
        (258, V::Short(vec![16])),
        (259, V::Short(vec![7])),
        (262, V::Short(vec![32803])),
        (271, V::Ascii(m.make.into())),
        (272, V::Ascii(m.model.into())),
        (274, V::Short(vec![1])),
        (277, V::Short(vec![1])),
        (284, V::Short(vec![1])),
        (305, V::Ascii(m.software.into())),
        (322, V::Long(vec![TILE as u32])),
        (323, V::Long(vec![TILE as u32])),
        (324, V::Long(vec![0; tiles.len()])),
        (325, V::Long(tiles.iter().map(|t| t.len() as u32).collect())),
        (33421, V::Short(vec![2, 2])),
        (33422, V::Byte(m.cfa.to_vec())),
        (33434, V::Rational(vec![(m.exposure_ns.clamp(0, u32::MAX as i64) as u32, 1_000_000_000)])),
        (33437, V::Rational(vec![urat(m.f_number as f64)])),
        (34855, V::Short(vec![m.iso])),
        (37386, V::Rational(vec![urat(m.focal_mm as f64)])),
        (50706, V::Byte(vec![1, 4, 0, 0])),
        (50707, V::Byte(vec![1, 3, 0, 0])),
        (50708, V::Ascii(format!("{} {}", m.make, m.model))),
        (50711, V::Short(vec![1])),
        (50713, V::Short(vec![2, 2])),
        (50714, V::Rational(m.black.iter().map(|&b| urat(b as f64)).collect())),
        (50717, V::Long(vec![m.white])),
        (50728, V::Rational(m.as_shot_neutral.iter().map(|&n| urat(n as f64)).collect())),
        (50778, V::Short(vec![m.illuminant[0]])),
        (51043, V::Byte([m.timecode.as_slice(), &[0; 4]].concat())),
        (51044, V::SRational(vec![(m.fps as i32, 1)])),
        (51058, V::SRational(vec![((m.f_number * 10000.0).round() as i32, 10000)])),
        (51081, V::Ascii(m.reel.into())),
    ];
    let pairs = [(50721, 50722, &m.color_matrix), (50964, 50965, &m.forward_matrix), (50723, 50724, &m.calibration)];
    for (t1, t2, mats) in pairs {
        if let Some(a) = mats[0] { e.push((t1, srat(&a))) }
        if let Some(b) = mats[1] { e.push((t2, srat(&b))) }
    }
    if m.color_matrix[1].is_some() { e.push((50779, V::Short(vec![m.illuminant[1]]))) }
    if let Some(n) = m.noise_profile { e.push((51041, V::Double(n.to_vec()))) }
    if let Some(g) = m.gain_maps { e.push((51008, V::Undef(gain_map_opcodes(g, w, h)))) }
    e.push((34665, V::Long(vec![0]))); // Exif IFD pointer, patched below
    e.sort_by_key(|(t, _)| *t);
    // Readers (LibRaw, Resolve) take exposure/ISO from the Exif IFD, not IFD0.
    let exif: Vec<(u16, V)> = vec![
        (33434, V::Rational(vec![(m.exposure_ns.clamp(0, u32::MAX as i64) as u32, 1_000_000_000)])),
        (33437, V::Rational(vec![urat(m.f_number as f64)])),
        (34855, V::Short(vec![m.iso])),
        (34864, V::Short(vec![3])), // SensitivityType: ISO speed
        (37386, V::Rational(vec![urat(m.focal_mm as f64)])),
    ];

    // Layout: header | IFD | out-of-line values | tiles.
    let enc: Vec<(u16, u16, u32, Vec<u8>)> = e.iter().map(|(t, v)| { let (k, c, b) = v.kind_count_bytes(); (*t, k, c, b) }).collect();
    let ifd_len = 2 + 12 * enc.len() + 4;
    let mut data_off = 8 + ifd_len;
    let mut offs = Vec::with_capacity(enc.len());
    for (_, _, _, b) in &enc {
        if b.len() > 4 { offs.push(data_off); data_off += (b.len() + 1) & !1 } else { offs.push(0) }
    }
    let exif_off = data_off;
    let exif_block = ifd_block(&exif, exif_off);
    let mut tile_off = Vec::with_capacity(tiles.len());
    let mut p = exif_off + exif_block.len();
    for t in &tiles { tile_off.push(p as u32); p += t.len() }

    let mut out = Vec::with_capacity(p);
    out.extend_from_slice(b"II*\0");
    out.extend_from_slice(&8u32.to_le_bytes());
    out.extend_from_slice(&(enc.len() as u16).to_le_bytes());
    for ((t, k, c, b), &o) in enc.iter().zip(&offs) {
        out.extend_from_slice(&t.to_le_bytes());
        out.extend_from_slice(&k.to_le_bytes());
        out.extend_from_slice(&c.to_le_bytes());
        if *t == 34665 { out.extend_from_slice(&(exif_off as u32).to_le_bytes()) }
        else if b.len() > 4 { out.extend_from_slice(&(o as u32).to_le_bytes()) } else { let mut v = b.clone(); v.resize(4, 0); out.extend_from_slice(&v) }
    }
    out.extend_from_slice(&0u32.to_le_bytes());
    for ((t, _, _, b), _) in enc.iter().zip(&offs).filter(|(x, _)| x.3.len() > 4) {
        if *t == 324 { out.extend(tile_off.iter().flat_map(|x| x.to_le_bytes())) } else { out.extend_from_slice(b) }
        if b.len() % 2 == 1 { out.push(0) }
    }
    debug_assert_eq!(out.len(), data_off);
    out.extend_from_slice(&exif_block);
    for t in &tiles { out.extend_from_slice(t) }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crop_keeps_cfa_phase() {
        assert_eq!(crop_16x9(4080, 3064), (4080, 2296, 384));
        let (_, ch, y0) = crop_16x9(4032, 3024);
        assert!(ch % 4 == 0 && y0 % 4 == 0 && ch + y0 <= 3024);
    }

    #[test]
    fn bin_averages_same_colour() {
        // 8×4 frame where each pixel = colour index (x%2 + 2*(y%2)) * 100 + block offset
        let (w, h) = (8usize, 4usize);
        let mut src = vec![0u8; w * h * 2];
        for y in 0..h { for x in 0..w {
            let v = ((x % 2 + 2 * (y % 2)) * 100 + x / 4 * 10) as u16;
            src[(y * w + x) * 2..][..2].copy_from_slice(&v.to_le_bytes());
        }}
        let mut out = vec![0u16; 4 * 2];
        copy_crop_bin(&src, w * 2, w, h, 0, &mut out);
        assert_eq!(out, vec![0, 100, 10, 110, 200, 300, 210, 310]);
    }

    #[test]
    fn timecode() {
        assert_eq!(timecode_bcd(24 * 3661 + 5, 24), [0x05, 0x01, 0x01, 0x01]);
    }

    #[test]
    fn dng_structure() {
        let (w, h) = (300, 200);
        let img: Vec<u16> = (0..w * h).map(|i| (i % 1024) as u16).collect();
        let meta = test_meta();
        let d = write_dng(&img, w, h, &meta);
        assert_eq!(&d[..4], b"II*\0");
        let n = u16::from_le_bytes([d[8], d[9]]) as usize;
        let tags: Vec<u16> = (0..n).map(|i| u16::from_le_bytes([d[10 + i * 12], d[11 + i * 12]])).collect();
        assert!(tags.windows(2).all(|p| p[0] < p[1]), "tags sorted");
        // first tile decodes back to the top-left 256×200 (+ padding) of the image
        let to = |tag: u16| { let i = tags.iter().position(|&t| t == tag).unwrap(); u32::from_le_bytes(d[18 + i * 12..22 + i * 12].try_into().unwrap()) as usize };
        let (offs, cnts) = (to(324), to(325));
        let o0 = u32::from_le_bytes(d[offs..offs + 4].try_into().unwrap()) as usize;
        let c0 = u32::from_le_bytes(d[cnts..cnts + 4].try_into().unwrap()) as usize;
        let (px, tw, _) = lj92::decode(&d[o0..o0 + c0]);
        assert_eq!(tw, TILE);
        for y in 0..h { for x in 0..TILE { assert_eq!(px[y * TILE + x], img[y * w + x]) } }
    }

    pub fn test_meta() -> DngMeta<'static> {
        DngMeta {
            make: "Google", model: "Test", software: "CineWright test", reel: "A001",
            cfa: [0, 1, 1, 2], black: [64.0; 4], white: 1023,
            color_matrix: [Some([1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]), None],
            forward_matrix: [None, None], calibration: [None, None], illuminant: [21, 17],
            as_shot_neutral: [0.5, 1.0, 0.6], noise_profile: Some([1e-5, 1e-7]),
            exposure_ns: 20_833_333, iso: 100, f_number: 1.68, focal_mm: 6.9, fps: 24,
            timecode: [0, 0, 0, 0x10], gain_maps: None,
        }
    }
}

#[cfg(test)]
mod libraw_check {
    use super::*;

    /// `CW_DNG_OUT=/path/x.dng cargo test -p rawcore write_sample` writes a DNG for external validation
    /// (LibRaw `dcraw_emu -D -4` must reproduce the raw values exactly).
    #[test]
    fn write_sample() {
        let Ok(path) = std::env::var("CW_DNG_OUT") else { return };
        let (w, h) = (4080, 2296);
        let img: Vec<u16> = (0..w * h).map(|i| (64 + (i % w) / 5 + (i / w) / 4 + (i % 7) * 3) as u16 % 1024).collect();
        let g = GainMaps { rows: 13, cols: 17, origin_v: 0.0, spacing_v: 1.0 / 12.0, origin_h: 0.0, spacing_h: 1.0 / 16.0,
            maps: std::array::from_fn(|_| vec![1.5f32; 13 * 17]) };
        let mut m = tests::test_meta();
        m.gain_maps = Some(&g);
        std::fs::write(&path, write_dng(&img, w, h, &m)).unwrap();
        let raw: Vec<u8> = img.iter().flat_map(|v| v.to_be_bytes()).collect();
        std::fs::write(format!("{path}.raw16be"), raw).unwrap();
    }
}
