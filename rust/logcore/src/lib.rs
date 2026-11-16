//! Pure colour math: Log3G10 (v3), HLG inverse OETF, gamut matrices, ISP tonemap curve, .cube LUTs.

use std::fmt::Write;

// RED Log3G10 v3 constants (RED white paper 2017, as published in colour-science).
const A: f64 = 0.224282;
const B: f64 = 155.975327;
const C: f64 = 0.01;
const G: f64 = 15.1927;

pub fn log3g10_encode(x: f64) -> f64 {
    let xc = x + C;
    if xc >= 0.0 { A * (B * xc + 1.0).log10() } else { G * xc }
}

pub fn log3g10_decode(y: f64) -> f64 {
    if y >= 0.0 { (10f64.powf(y / A) - 1.0) / B - C } else { y / G - C }
}

/// f32 version for the GPU shader path; must stay in sync with the f64 reference.
pub fn log3g10_encode_f32(x: f32) -> f32 {
    let xc = x + C as f32;
    if xc >= 0.0 { A as f32 * (B as f32 * xc + 1.0).log10() } else { G as f32 * xc }
}

/// BT.2100 HLG inverse OETF: non-linear E' in [0,1] -> relative scene-linear E in [0,1].
pub fn hlg_inverse_oetf(e: f64) -> f64 {
    const HA: f64 = 0.17883277;
    const HB: f64 = 1.0 - 4.0 * HA;
    let hc = 0.5 - HA * (4.0 * HA).ln();
    if e <= 0.5 { e * e / 3.0 } else { (((e - hc) / HA).exp() + HB) / 12.0 }
}

pub type Mat3 = [[f64; 3]; 3];
pub type Primaries = [(f64, f64); 3];

pub const D65: (f64, f64) = (0.3127, 0.3290);
pub const BT709: Primaries = [(0.64, 0.33), (0.30, 0.60), (0.15, 0.06)];
pub const BT2020: Primaries = [(0.708, 0.292), (0.170, 0.797), (0.131, 0.046)];
pub const RWG: Primaries = [(0.780308, 0.304253), (0.121595, 1.493994), (0.095612, -0.084589)];

fn xyz(p: (f64, f64)) -> [f64; 3] {
    [p.0 / p.1, 1.0, (1.0 - p.0 - p.1) / p.1]
}

pub fn mul(a: &Mat3, b: &Mat3) -> Mat3 {
    let mut m = [[0.0; 3]; 3];
    for i in 0..3 { for j in 0..3 { m[i][j] = (0..3).map(|k| a[i][k] * b[k][j]).sum(); } }
    m
}

pub fn apply(m: &Mat3, v: [f64; 3]) -> [f64; 3] {
    [0, 1, 2].map(|i| m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2])
}

pub fn invert(m: &Mat3) -> Mat3 {
    let det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
        - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
        + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    let mut r = [[0.0; 3]; 3];
    for i in 0..3 {
        for j in 0..3 {
            // cofactor of (j,i) for the adjugate
            let (r0, r1) = ((j + 1) % 3, (j + 2) % 3);
            let (c0, c1) = ((i + 1) % 3, (i + 2) % 3);
            r[i][j] = (m[r0][c0] * m[r1][c1] - m[r0][c1] * m[r1][c0]) / det;
        }
    }
    r
}

/// RGB -> XYZ normalised primary matrix.
pub fn npm(p: &Primaries, white: (f64, f64)) -> Mat3 {
    let cols = p.map(xyz);
    let pm: Mat3 = [0, 1, 2].map(|i| [cols[0][i], cols[1][i], cols[2][i]]);
    let s = apply(&invert(&pm), xyz(white));
    [0, 1, 2].map(|i| [0, 1, 2].map(|j| pm[i][j] * s[j]))
}

/// Linear RGB `src` -> linear RGB `dst`, both D65.
pub fn rgb_to_rgb(src: &Primaries, dst: &Primaries) -> Mat3 {
    mul(&invert(&npm(dst, D65)), &npm(src, D65))
}

/// Camera2 TONEMAP_CURVE for the 8-bit fallback: interleaved (in, out) pairs, `points` long,
/// spaced evenly in log2 of the input so shadows get most of them. out = Log3G10(s * in) with
/// s = 0.18 * 2^headroom, i.e. ISP clip lands `headroom` stops above mid grey.
pub fn isp_tonemap_curve(points: usize, headroom: f64) -> Vec<f32> {
    assert!(points >= 3);
    const SPAN_STOPS: f64 = 14.0;
    let s = 0.18 * 2f64.powf(headroom);
    let mut v = Vec::with_capacity(points * 2);
    for i in 0..points {
        let x = if i == 0 { 0.0 } else { 2f64.powf(-SPAN_STOPS * (points - 1 - i) as f64 / (points - 2) as f64) };
        v.push(x as f32);
        v.push(log3g10_encode(s * x).clamp(0.0, 1.0) as f32);
    }
    v
}

#[derive(Clone, Copy)]
pub enum LogGamut { Rwg, Rec709 }

/// 3D .cube LUT: Log3G10 in `gamut` -> Rec.709 / gamma 2.4, with a plain extended-Reinhard
/// shoulder (white at 4.0 linear) so it stays tied to the math rather than being a "look".
pub fn cube_lut(size: usize, gamut: LogGamut) -> String {
    const WHITE: f64 = 4.0;
    let m = match gamut { LogGamut::Rwg => rgb_to_rgb(&RWG, &BT709), LogGamut::Rec709 => rgb_to_rgb(&BT709, &BT709) };
    let title = match gamut { LogGamut::Rwg => "RWG_Log3G10_to_Rec709_G24", LogGamut::Rec709 => "Rec709gamut_Log3G10_to_Rec709_G24" };
    let mut out = format!("TITLE \"CineWright {title}\"\nLUT_3D_SIZE {size}\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n");
    let n = (size - 1) as f64;
    for b in 0..size { for g in 0..size { for r in 0..size {
        let lin = [r, g, b].map(|c| log3g10_decode(c as f64 / n));
        let d = apply(&m, lin).map(|x| {
            let x = x.max(0.0);
            (x * (1.0 + x / (WHITE * WHITE)) / (1.0 + x)).min(1.0).powf(1.0 / 2.4)
        });
        let _ = writeln!(out, "{:.6} {:.6} {:.6}", d[0], d[1], d[2]);
    }}}
    out
}

/// CIE xy of a Planckian radiator (Kim et al. cubic approximation, 1667–25000 K).
pub fn planck_xy(t: f64) -> (f64, f64) {
    let t = t.clamp(1667.0, 25000.0);
    let (t2, t3) = (t * t, t * t * t);
    let x = if t <= 4000.0 { -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910 }
            else { -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390 };
    let (x2, x3) = (x * x, x * x * x);
    let y = if t <= 2222.0 { -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683 }
            else if t <= 4000.0 { -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867 }
            else { 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483 };
    (x, y)
}

/// Correlated colour temperature of an EXIF LightSource code (the ones Camera2 reports).
pub fn illuminant_cct(code: u16) -> f64 {
    match code { 17 => 2856.0, 24 => 3200.0, 18 => 4874.0, 2 | 14 => 4200.0, 23 => 5003.0, 20 => 5503.0, 21 => 6504.0, 19 => 6774.0, 22 => 7504.0, 3 => 3200.0, _ => 5000.0 }
}

/// Manual white balance for Camera2 from a Kelvin value and the sensor calibration:
/// returns (R, G, B gains for COLOR_CORRECTION_GAINS, row-major camera→linear Rec.709 transform).
/// `cm` = ColorMatrix1/2 (XYZ→camera), `fm` = ForwardMatrix1/2 (WB camera→XYZ D50), `cct` = their illuminants.
pub fn white_balance(kelvin: f64, cm: [&Mat3; 2], fm: [&Mat3; 2], cct: [f64; 2]) -> ([f64; 3], Mat3) {
    // DNG-style interpolation weight on inverse CCT, clamped to the two calibration points.
    let (lo, hi) = if cct[0] <= cct[1] { (0, 1) } else { (1, 0) };
    let g = ((1.0 / kelvin - 1.0 / cct[hi]) / (1.0 / cct[lo] - 1.0 / cct[hi])).clamp(0.0, 1.0);
    let lerp = |a: &Mat3, b: &Mat3, w: f64| -> Mat3 { [0, 1, 2].map(|i| [0, 1, 2].map(|j| a[i][j] * w + b[i][j] * (1.0 - w))) };
    let (cm_t, fm_t) = (lerp(cm[lo], cm[hi], g), lerp(fm[lo], fm[hi], g));
    let (x, y) = planck_xy(kelvin);
    let n = apply(&cm_t, [x / y, 1.0, (1.0 - x - y) / y]);
    let gains = [n[1] / n[0], 1.0, n[1] / n[2]];
    const BRADFORD_D50_D65: Mat3 = [[0.9555766, -0.0230393, 0.0631636], [-0.0282895, 1.0099416, 0.0210077], [0.0122982, -0.0204830, 1.3299098]];
    let xyz_to_709 = invert(&npm(&BT709, D65));
    (gains, mul(&xyz_to_709, &mul(&BRADFORD_D50_D65, &fm_t)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn log3g10_golden() {
        assert!((log3g10_encode(0.0) - 0.0915514).abs() < 1e-6);
        assert!((log3g10_encode(0.18) - 1.0 / 3.0).abs() < 1e-5);
        assert!((log3g10_encode_f32(0.18) as f64 - 1.0 / 3.0).abs() < 1e-5);
    }

    #[test]
    fn log3g10_round_trip() {
        let mut x = -0.05;
        while x <= 184.0 {
            assert!((log3g10_decode(log3g10_encode(x)) - x).abs() < 1e-5 * x.abs().max(1.0), "x={x}");
            x += 0.013;
        }
    }

    #[test]
    fn hlg_points() {
        assert_eq!(hlg_inverse_oetf(0.0), 0.0);
        assert!((hlg_inverse_oetf(0.5) - 1.0 / 12.0).abs() < 1e-12);
        assert!((hlg_inverse_oetf(1.0) - 1.0).abs() < 1e-6);
        // BT.2408 reference grey: HLG 38% -> E ~ 0.0481, which k~3.74 maps to 0.18.
        assert!((hlg_inverse_oetf(0.38) * 3.74 - 0.18).abs() < 0.001);
    }

    #[test]
    fn gamut_matrices_preserve_white_and_invert() {
        for src in [BT709, BT2020] {
            let m = rgb_to_rgb(&src, &RWG);
            let w = apply(&m, [1.0, 1.0, 1.0]);
            assert!(w.iter().all(|c| (c - 1.0).abs() < 1e-9), "{w:?}");
            let id = mul(&rgb_to_rgb(&RWG, &src), &m);
            for i in 0..3 { for j in 0..3 { assert!((id[i][j] - (i == j) as u8 as f64).abs() < 1e-9); } }
        }
        // Known BT.709 luma coefficients fall out of the NPM's Y row.
        let y = npm(&BT709, D65)[1];
        assert!((y[0] - 0.2126).abs() < 1e-4 && (y[1] - 0.7152).abs() < 1e-4 && (y[2] - 0.0722).abs() < 1e-4);
    }

    #[test]
    fn tonemap_curve_shape() {
        let c = isp_tonemap_curve(64, 4.0);
        assert_eq!(c.len(), 128);
        assert_eq!(c[0], 0.0);
        assert_eq!(c[126], 1.0);
        assert!(c.chunks(2).zip(c.chunks(2).skip(1)).all(|(a, b)| b[0] > a[0] && b[1] > a[1]));
        // ISP clip (in = 1) sits 4 stops over mid grey.
        assert!((c[127] as f64 - log3g10_encode(0.18 * 16.0)).abs() < 1e-6);
    }

    #[test]
    fn planck_and_white_balance() {
        let (x, y) = planck_xy(6504.0);
        assert!((x - 0.3135).abs() < 0.002 && (y - 0.3237).abs() < 0.003, "{x} {y}"); // Planckian 6500 K, just off D65
        let (x, y) = planck_xy(2856.0);
        assert!((x - 0.4476).abs() < 0.002 && (y - 0.4074).abs() < 0.002);
        // A camera whose ColorMatrix is XYZ->"camera = XYZ" and ForwardMatrix maps neutral to D50 white:
        let id: Mat3 = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
        let d50 = [0.9642, 1.0, 0.8251];
        let fm: Mat3 = [0, 1, 2].map(|i| [0, 1, 2].map(|j| if i == j { d50[i] } else { 0.0 }));
        let (gains, ccm) = white_balance(5000.0, [&id, &id], [&fm, &fm], [2856.0, 6504.0]);
        assert_eq!(gains[1], 1.0);
        let (x, y) = planck_xy(5000.0);
        assert!((gains[0] - 1.0 / (x / y)).abs() < 1e-9);
        // WB'd neutral (1,1,1) must come out white in Rec.709.
        let w = apply(&ccm, [1.0, 1.0, 1.0]);
        assert!(w.iter().all(|c| (c - 1.0).abs() < 2e-3), "{w:?}");
    }

    #[test]
    fn cube_lut_header_and_size() {
        let l = cube_lut(33, LogGamut::Rwg);
        assert!(l.contains("LUT_3D_SIZE 33"));
        assert_eq!(l.lines().count(), 4 + 33 * 33 * 33);
    }
}
