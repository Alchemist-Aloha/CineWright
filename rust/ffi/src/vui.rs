//! Rewrites colour_primaries / transfer_characteristics in the VUI of H.264/H.265 SPS NAL units.
//! The Android encoder tags camera-surface input as sRGB, which is wrong for log footage.
//! Instead of a full SPS parse we locate the unique bit pattern
//! `video_format=5 | full_range | colour_description_present=1 | P | T | M` and patch P and T in place.

fn unescape(b: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(b.len());
    let mut zeros = 0;
    for &x in b {
        if zeros >= 2 && x == 3 { zeros = 0; continue }
        zeros = if x == 0 { zeros + 1 } else { 0 };
        out.push(x);
    }
    out
}

fn escape(b: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(b.len() + 4);
    let mut zeros = 0;
    for &x in b {
        if zeros >= 2 && x <= 3 { out.push(3); zeros = 0 }
        zeros = if x == 0 { zeros + 1 } else { 0 };
        out.push(x);
    }
    out
}

fn bit(b: &[u8], i: usize) -> u32 { ((b[i / 8] >> (7 - i % 8)) & 1) as u32 }
fn bits(b: &[u8], i: usize, n: usize) -> u32 { (0..n).fold(0, |a, k| (a << 1) | bit(b, i + k)) }
fn set_bits(b: &mut [u8], i: usize, n: usize, v: u32) {
    for k in 0..n {
        let (byte, sh) = ((i + k) / 8, 7 - (i + k) % 8);
        let on = (v >> (n - 1 - k)) & 1 == 1;
        if on { b[byte] |= 1 << sh } else { b[byte] &= !(1 << sh) }
    }
}

fn patch_sps(rbsp: &mut [u8], primaries: u8, transfer: u8) -> bool {
    const P: [u32; 6] = [1, 2, 5, 6, 9, 12];
    const M: [u32; 6] = [0, 1, 2, 5, 6, 9];
    let total = rbsp.len() * 8;
    let hits: Vec<usize> = (0..total.saturating_sub(29)).filter(|&i| {
        bits(rbsp, i, 3) == 5 && bit(rbsp, i + 4) == 1
            && P.contains(&bits(rbsp, i + 5, 8)) && (1..=18).contains(&bits(rbsp, i + 13, 8)) && M.contains(&bits(rbsp, i + 21, 8))
    }).collect();
    if hits.len() != 1 { return false }
    set_bits(rbsp, hits[0] + 5, 8, primaries as u32);
    set_bits(rbsp, hits[0] + 13, 8, transfer as u32);
    true
}

/// Returns a copy of the Annex-B buffer with every SPS patched, or None if any SPS couldn't be.
pub fn set_colour(annexb: &[u8], hevc: bool, primaries: u8, transfer: u8) -> Option<Vec<u8>> {
    // Split on 00 00 01, keeping each NAL's leading zeros with the start code.
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 3 <= annexb.len() {
        if annexb[i] == 0 && annexb[i + 1] == 0 && annexb[i + 2] == 1 { starts.push(i + 3); i += 3 } else { i += 1 }
    }
    let mut out = Vec::with_capacity(annexb.len() + 8);
    let mut prev_end = 0;
    let mut patched = 0;
    for (k, &s) in starts.iter().enumerate() {
        let end = starts.get(k + 1).map(|&n| { let mut e = n - 3; while e > s && annexb[e - 1] == 0 { e -= 1 } e }).unwrap_or(annexb.len());
        out.extend_from_slice(&annexb[prev_end..s]);
        let nal = &annexb[s..end];
        let is_sps = !nal.is_empty() && if hevc { (nal[0] >> 1) & 0x3F == 33 } else { nal[0] & 0x1F == 7 };
        if is_sps {
            let hl = if hevc { 2 } else { 1 };
            let mut rbsp = unescape(&nal[hl..]);
            if !patch_sps(&mut rbsp, primaries, transfer) { return None }
            out.extend_from_slice(&nal[..hl]);
            out.extend_from_slice(&escape(&rbsp));
            patched += 1;
        } else {
            out.extend_from_slice(nal);
        }
        prev_end = end;
    }
    out.extend_from_slice(&annexb[prev_end..]);
    (patched > 0).then_some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> { (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect() }

    // SPS captured from the Pixel 11 Pro encoders (VUI: 5, limited, 1/13/1).
    const HEVC_SPS: &str = "420101016000000300000300000300000300b4a001e020021c596b924c9ae59cd404340402";
    const AVC_SPS: &str = "67640033acd200f0010fa6a021a020f08042a0";

    fn colour(annexb: &[u8], hevc: bool) -> (u32, u32, u32) {
        let nal = &annexb[4..];
        let rbsp = unescape(&nal[if hevc { 2 } else { 1 }..]);
        let i = (0..rbsp.len() * 8 - 29).find(|&i| bits(&rbsp, i, 3) == 5 && bit(&rbsp, i + 4) == 1 && bits(&rbsp, i + 13, 8) <= 18 && bits(&rbsp, i + 5, 8) <= 12).unwrap();
        (bits(&rbsp, i + 5, 8), bits(&rbsp, i + 13, 8), bits(&rbsp, i + 21, 8))
    }

    #[test]
    fn patches_pixel_sps() {
        for (sps, hevc) in [(HEVC_SPS, true), (AVC_SPS, false)] {
            let mut buf = vec![0, 0, 0, 1];
            buf.extend(hex(sps));
            assert_eq!(colour(&buf, hevc), (1, 13, 1));
            let out = set_colour(&buf, hevc, 1, 2).expect("patched");
            assert_eq!(colour(&out, hevc), (1, 2, 1));
            assert_eq!(out.len(), buf.len());
        }
    }

    #[test]
    fn escape_round_trip() {
        let raw = [0u8, 0, 1, 0, 0, 0, 0, 3, 5, 0, 0];
        assert_eq!(unescape(&escape(&raw)), raw);
    }
}
