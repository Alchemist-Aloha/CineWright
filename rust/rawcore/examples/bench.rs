//! On-device throughput check: `bench [threads]` compresses synthetic 4080×2296 12-bit frames.
use std::time::Instant;
fn main() {
    let threads: usize = std::env::args().nth(1).and_then(|a| a.parse().ok()).unwrap_or(4);
    let (w, h) = (4080, 2296);
    let mut seed = 1u64;
    let img: Vec<u16> = (0..w * h).map(|i| {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        (256 + (i % w) / 4 + (i / w) / 3 + ((seed >> 60) as usize)) as u16 & 4095
    }).collect();
    let frames = 8 * threads;
    let t = Instant::now();
    let bytes: usize = std::thread::scope(|s| {
        (0..threads).map(|_| s.spawn(|| (0..frames / threads).map(|_| {
            let m = rawcore::DngMeta { make: "x", model: "x", software: "x", reel: "x", cfa: [1, 2, 0, 1], black: [256.0; 4], white: 4095,
                color_matrix: [None, None], forward_matrix: [None, None], calibration: [None, None], illuminant: [17, 21],
                as_shot_neutral: [0.5, 1.0, 0.6], noise_profile: None, exposure_ns: 1, iso: 100, f_number: 1.7, focal_mm: 6.9,
                fps: 30, timecode: [0; 4], gain_maps: None };
            rawcore::write_dng(&img, w, h, &m).len()
        }).sum::<usize>())).collect::<Vec<_>>().into_iter().map(|j| j.join().unwrap()).sum()
    });
    let secs = t.elapsed().as_secs_f64();
    println!("{threads} threads: {:.1} fps, {:.0} Mpx/s, {:.2} MB/frame", frames as f64 / secs, (frames * w * h) as f64 / secs / 1e6, bytes as f64 / frames as f64 / 1e6);
}
