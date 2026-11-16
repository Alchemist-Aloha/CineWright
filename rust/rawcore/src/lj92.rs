//! Lossless JPEG (ITU T.81 process 14, "LJ92") encoder for Bayer tiles, as DNG compression 7 expects:
//! a W×H CFA tile is coded as two interleaved components of width W/2, so predictor 1 (left)
//! predicts each photosite from the same-colour one two columns away.

const PRECISION: u32 = 16;

struct BitWriter { out: Vec<u8>, acc: u64, n: u32 }

impl BitWriter {
    /// `bits` must fit in `len` (≤ 32) bits.
    #[inline(always)]
    fn put(&mut self, bits: u32, len: u32) {
        self.acc = (self.acc << len) | bits as u64;
        self.n += len;
        if self.n >= 32 {
            self.n -= 32;
            let b = ((self.acc >> self.n) as u32).to_be_bytes();
            if b.contains(&0xFF) {
                for x in b { self.out.push(x); if x == 0xFF { self.out.push(0) } } // byte stuffing
            } else {
                self.out.extend_from_slice(&b);
            }
        }
    }
    fn flush(&mut self) {
        let pad = (8 - self.n % 8) % 8;
        self.acc = (self.acc << pad) | ((1u64 << pad) - 1);
        self.n += pad;
        while self.n > 0 {
            self.n -= 8;
            let x = (self.acc >> self.n) as u8;
            self.out.push(x);
            if x == 0xFF { self.out.push(0) }
        }
    }
}

#[inline(always)]
fn ssss(d: i32) -> u32 { 32 - d.unsigned_abs().leading_zeros() }

/// Prediction residuals in coding order for a w×h block read from `src` with row `stride`.
fn residuals(src: &[u16], stride: usize, w: usize, h: usize, out: &mut Vec<i16>) {
    out.resize(w * h, 0);
    for y in 0..h {
        let row = &src[y * stride..y * stride + w];
        let o = &mut out[y * w..(y + 1) * w];
        let (p0, p1) = if y == 0 { (1u16 << (PRECISION - 1), 1u16 << (PRECISION - 1)) } else { (src[(y - 1) * stride], src[(y - 1) * stride + 1]) };
        o[0] = row[0].wrapping_sub(p0) as i16;
        o[1] = row[1].wrapping_sub(p1) as i16;
        for ((d, &a), &b) in o[2..].iter_mut().zip(&row[2..]).zip(row) { *d = a.wrapping_sub(b) as i16 }
    }
}

/// Code lengths per JPEG Annex K.2/K.3 (≤16 bits, no all-ones code). Index = SSSS 0..=16.
fn code_lengths(hist: &[u64; 17]) -> [u8; 17] {
    let mut freq = [0u64; 18];
    freq[..17].copy_from_slice(hist);
    freq[17] = 1; // reserved symbol guarantees no all-ones codeword
    let mut size = [0usize; 18];
    let mut others = [usize::MAX; 18];
    loop {
        let mut v1 = usize::MAX;
        for i in 0..18 { if freq[i] > 0 && (v1 == usize::MAX || freq[i] <= freq[v1]) { v1 = i } }
        let mut v2 = usize::MAX;
        for i in 0..18 { if freq[i] > 0 && i != v1 && (v2 == usize::MAX || freq[i] <= freq[v2]) { v2 = i } }
        if v2 == usize::MAX { break }
        freq[v1] += freq[v2];
        freq[v2] = 0;
        size[v1] += 1;
        while others[v1] != usize::MAX { v1 = others[v1]; size[v1] += 1 }
        others[v1] = v2;
        size[v2] += 1;
        while others[v2] != usize::MAX { v2 = others[v2]; size[v2] += 1 }
    }
    let mut bits = [0u32; 40];
    for &s in &size { if s > 0 { bits[s] += 1 } }
    let mut i = 39;
    while i > 16 {
        while bits[i] > 0 {
            let mut j = i - 2;
            while bits[j] == 0 { j -= 1 }
            bits[i] -= 2; bits[i - 1] += 1; bits[j + 1] += 2; bits[j] -= 1;
        }
        i -= 1;
    }
    while bits[i] == 0 { i -= 1 }
    bits[i] -= 1; // drop the reserved symbol (always among the longest)
    let mut syms: Vec<usize> = (0..17).filter(|&s| size[s] > 0).collect();
    syms.sort_by_key(|&s| (size[s], s));
    let mut out = [0u8; 17];
    let mut it = syms.into_iter();
    for len in 1..=16 { for _ in 0..bits[len] { out[it.next().unwrap()] = len as u8 } }
    out
}

/// Encodes one W×H Bayer tile (W even) as a complete JPEG stream (SOI … EOI).
pub fn encode(px: &[u16], w: usize, h: usize) -> Vec<u8> {
    assert!(px.len() == w * h);
    encode_strided(px, w, w, h, &mut Vec::new())
}

/// Like [`encode`] but reads the tile in place from a larger image (`stride` pixels per row);
/// `scratch` is reused between calls to avoid allocation.
pub fn encode_strided(src: &[u16], stride: usize, w: usize, h: usize, scratch: &mut Vec<i16>) -> Vec<u8> {
    assert!(w % 2 == 0 && w >= 2 && src.len() >= (h - 1) * stride + w);
    residuals(src, stride, w, h, scratch);
    let mut hist = [0u64; 17];
    for &d in scratch.iter() { hist[ssss(d as i32) as usize] += 1 }
    let lens = code_lengths(&hist);

    // Canonical codes (Annex C) in (length, symbol) order.
    let mut order: Vec<usize> = (0..17).filter(|&s| lens[s] > 0).collect();
    order.sort_by_key(|&s| (lens[s], s));
    let mut codes = [0u32; 17];
    let (mut code, mut len) = (0u32, 1u8);
    for &s in &order {
        while len < lens[s] { code <<= 1; len += 1 }
        codes[s] = code;
        code += 1;
    }

    let mut out = Vec::with_capacity(w * h + 1024);
    out.extend_from_slice(&[0xFF, 0xD8]);
    // DHT
    let mut counts = [0u8; 16];
    for &s in &order { counts[lens[s] as usize - 1] += 1 }
    out.extend_from_slice(&[0xFF, 0xC4]);
    out.extend_from_slice(&((2 + 1 + 16 + order.len()) as u16).to_be_bytes());
    out.push(0x00);
    out.extend_from_slice(&counts);
    out.extend(order.iter().map(|&s| s as u8));
    // SOF3: 2 components, each w/2 wide
    out.extend_from_slice(&[0xFF, 0xC3, 0, 14, PRECISION as u8]);
    out.extend_from_slice(&(h as u16).to_be_bytes());
    out.extend_from_slice(&((w / 2) as u16).to_be_bytes());
    out.extend_from_slice(&[2, 0, 0x11, 0, 1, 0x11, 0]);
    // SOS: predictor 1
    out.extend_from_slice(&[0xFF, 0xDA, 0, 10, 2, 0, 0x00, 1, 0x00, 1, 0, 0]);

    let mut bw = BitWriter { out, acc: 0, n: 0 };
    for &d in scratch.iter() {
        let d = d as i32;
        let s = ssss(d);
        let (c, l) = (codes[s as usize], lens[s as usize] as u32);
        if s == 0 || s == 16 { bw.put(c, l) } else {
            // Huffman code and its s extra bits in one write (≤ 31 bits).
            let v = (if d < 0 { d - 1 } else { d }) as u32 & ((1 << s) - 1);
            bw.put((c << s) | v, l + s);
        }
    }
    bw.flush();
    let mut out = bw.out;
    out.extend_from_slice(&[0xFF, 0xD9]);
    out
}

/// Minimal decoder for streams produced by [`encode`]; used for round-trip tests.
#[cfg(test)]
pub fn decode(data: &[u8]) -> (Vec<u16>, usize, usize) {
    let mut i = 2;
    let (mut counts, mut vals, mut w, mut h) = ([0u8; 16], Vec::new(), 0, 0);
    loop {
        let m = data[i + 1];
        let l = u16::from_be_bytes([data[i + 2], data[i + 3]]) as usize;
        let seg = &data[i + 4..i + 2 + l];
        match m {
            0xC4 => { counts.copy_from_slice(&seg[1..17]); vals = seg[17..].to_vec() }
            0xC3 => { h = u16::from_be_bytes([seg[1], seg[2]]) as usize; w = 2 * u16::from_be_bytes([seg[3], seg[4]]) as usize }
            0xDA => { i += 2 + l; break }
            _ => {}
        }
        i += 2 + l;
    }
    // canonical table: (len, code) -> symbol
    let mut table = std::collections::HashMap::new();
    let (mut code, mut k) = (0u32, 0);
    for len in 1..=16u32 {
        for _ in 0..counts[len as usize - 1] { table.insert((len, code), vals[k]); code += 1; k += 1 }
        code <<= 1;
    }
    let mut bits = data[i..].iter().enumerate().filter(|(j, &b)| !(b == 0 && *j > 0 && data[i + j - 1] == 0xFF)).map(|(_, &b)| b)
        .flat_map(|b| (0..8).rev().map(move |s| (b >> s) & 1));
    let mut read = |n: u32| (0..n).fold(0u32, |a, _| (a << 1) | bits.next().unwrap() as u32);
    let mut px = vec![0u16; w * h];
    for y in 0..h {
        for x in 0..w {
            let (mut c, mut len) = (0u32, 0u32);
            let s = loop { c = (c << 1) | read(1); len += 1; if let Some(&s) = table.get(&(len, c)) { break s as u32 } };
            let d: i32 = match s {
                0 => 0,
                16 => -32768,
                _ => { let v = read(s) as i32; if v < (1 << (s - 1)) { v - (1 << s) + 1 } else { v } }
            };
            let pred = if x >= 2 { px[y * w + x - 2] } else if y > 0 { px[(y - 1) * w + x] } else { 1 << 15 };
            px[y * w + x] = pred.wrapping_add(d as u16);
        }
    }
    (px, w, h)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lcg(seed: &mut u64) -> u16 { *seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); (*seed >> 48) as u16 }

    #[test]
    fn round_trip_noise_gradient_and_flat() {
        let mut seed = 7;
        let (w, h) = (64, 32);
        let cases: Vec<Vec<u16>> = vec![
            (0..w * h).map(|_| lcg(&mut seed) & 0x3FF).collect(),          // 10-bit noise
            (0..w * h).map(|i| ((i % w) * 16 + (i / w) * 3) as u16).collect(), // gradient
            vec![64; w * h],                                                  // flat (single symbol)
            (0..w * h).map(|_| lcg(&mut seed)).collect(),                     // full 16-bit incl. ±32768 diffs
        ];
        for px in cases {
            let (out, dw, dh) = decode(&encode(&px, w, h));
            assert_eq!((dw, dh), (w, h));
            assert_eq!(out, px);
        }
    }

    #[test]
    fn compresses_smooth_10bit() {
        let (w, h) = (256, 256);
        let mut seed = 1;
        let px: Vec<u16> = (0..w * h).map(|i| (200 + (i % w) / 2 + (lcg(&mut seed) & 7) as usize) as u16).collect();
        let n = encode(&px, w, h).len();
        assert!(n < w * h * 10 / 8 / 2, "{n} bytes"); // better than 2:1 vs packed 10-bit
    }

    #[test]
    fn lengths_are_valid_prefix_code() {
        let mut hist = [0u64; 17];
        for (i, h) in hist.iter_mut().enumerate() { *h = 1 << (i.min(16) * 2) } // skewed, forces limiting
        let l = code_lengths(&hist);
        assert!(l.iter().all(|&x| x >= 1 && x <= 16));
        let kraft: f64 = l.iter().map(|&x| 0.5f64.powi(x as i32)).sum();
        assert!(kraft < 1.0); // strictly < 1: all-ones code reserved
    }
}
