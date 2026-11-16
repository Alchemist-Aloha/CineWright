//! RAW recording session: a fixed pool of frame slots (back-pressure), timestamp matching of frames
//! with their capture metadata, and writer threads that compress and write one DNG per frame.

use rawcore::{DngMeta, GainMaps};
use std::collections::VecDeque;
use std::fs::File;
use std::io::Write;
use std::os::fd::AsRawFd;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

pub struct Statics {
    pub make: String,
    pub model: String,
    pub reel: String,
    pub cfa: [u8; 4],
    pub white: u32,
    pub color_matrix: [Option<[f32; 9]>; 2],
    pub forward_matrix: [Option<[f32; 9]>; 2],
    pub calibration: [Option<[f32; 9]>; 2],
    pub illuminant: [u16; 2],
    pub noise_profile: Option<[f64; 2]>,
    pub f_number: f32,
    pub focal_mm: f32,
    pub tc_start_frames: u64,
}

pub struct Config {
    pub dir: PathBuf,
    pub prefix: String,
    pub sensor_w: usize,
    pub sensor_h: usize,
    pub lite: bool,
    pub fps: u32,
    pub threads: usize,
    pub slots: usize,
    pub statics: Statics,
}

pub struct FrameMeta {
    pub exposure_ns: i64,
    pub iso: u16,
    pub black: [f32; 4],
    pub white: Option<u32>,
    /// Camera2 COLOR_CORRECTION_GAINS: R, G_even, G_odd, B.
    pub gains: [f32; 4],
    /// Camera2 lens shading map: rows, cols, interleaved [R, G_even, G_odd, B] per point.
    pub shading: Option<(u32, u32, Vec<f32>)>,
}

struct Job { index: u64, img: Vec<u16>, meta: FrameMeta }

struct Pending {
    frames: VecDeque<(i64, Vec<u16>)>,
    metas: VecDeque<(i64, FrameMeta)>,
    ts0: Option<i64>,
    tx: Option<SyncSender<Job>>,
}

struct Shared {
    cfg: Config,
    crop: (usize, usize, usize),
    out: (usize, usize),
    free: Mutex<Vec<Vec<u16>>>,
    pending: Mutex<Pending>,
    written_idx: Mutex<Vec<u64>>,
    written: AtomicU64,
    dropped: AtomicU64,
    /// Drop reasons, for diagnosing: no free slot / metadata never matched / write failed.
    no_slot: AtomicU64,
    no_meta: AtomicU64,
    bytes: AtomicU64,
    last_sync: Mutex<Instant>,
}

pub struct Session { s: Arc<Shared>, workers: Vec<JoinHandle<()>> }

pub struct Summary { pub written: u64, pub dropped: u64, pub bytes: u64, pub no_slot: u64, pub no_meta: u64, pub missing: Vec<u64> }

impl Session {
    pub fn new(cfg: Config) -> std::io::Result<Self> {
        std::fs::create_dir_all(&cfg.dir)?;
        let crop = rawcore::crop_16x9(cfg.sensor_w, cfg.sensor_h);
        let out = if cfg.lite { (crop.0 / 2, crop.1 / 2) } else { (crop.0, crop.1) };
        let (tx, rx) = sync_channel(cfg.slots);
        let rx = Arc::new(Mutex::new(rx));
        let s = Arc::new(Shared {
            free: Mutex::new((0..cfg.slots).map(|_| vec![0u16; out.0 * out.1]).collect()),
            pending: Mutex::new(Pending { frames: VecDeque::new(), metas: VecDeque::new(), ts0: None, tx: Some(tx) }),
            written_idx: Mutex::new(Vec::new()),
            written: AtomicU64::new(0),
            dropped: AtomicU64::new(0),
            no_slot: AtomicU64::new(0),
            no_meta: AtomicU64::new(0),
            bytes: AtomicU64::new(0),
            last_sync: Mutex::new(Instant::now()),
            crop,
            out,
            cfg,
        });
        let workers = (0..s.cfg.threads).map(|i| {
            let (s, rx) = (s.clone(), rx.clone());
            std::thread::Builder::new().name(format!("dng-writer-{i}")).spawn(move || worker(&s, &rx)).unwrap()
        }).collect();
        Ok(Session { s, workers })
    }


    /// Copies (and crops/bins) one RAW_SENSOR plane. Returns false if the frame was dropped.
    pub fn submit_frame(&self, ts: i64, src: &[u8], row_stride: usize) -> bool {
        let Some(mut slot) = self.s.free.lock().unwrap().pop() else {
            self.s.dropped.fetch_add(1, Relaxed);
            self.s.no_slot.fetch_add(1, Relaxed);
            return false;
        };
        let (cw, ch, y0) = self.s.crop;
        if (y0 + ch) * row_stride > src.len() || cw * 2 > row_stride {
            self.s.free.lock().unwrap().push(slot);
            self.s.dropped.fetch_add(1, Relaxed);
            return false;
        }
        if self.s.cfg.lite { rawcore::copy_crop_bin(src, row_stride, cw, ch, y0, &mut slot) } else { rawcore::copy_crop(src, row_stride, cw, ch, y0, &mut slot) }
        let mut p = self.s.pending.lock().unwrap();
        p.ts0.get_or_insert(ts);
        if let Some(i) = p.metas.iter().position(|(t, _)| *t == ts) {
            let (_, meta) = p.metas.remove(i).unwrap();
            self.dispatch(&mut p, ts, slot, meta);
        } else {
            p.frames.push_back((ts, slot));
            if p.frames.len() > 8 {
                // Metadata never arrived for the oldest frame: drop it rather than stall the pool.
                let (_, old) = p.frames.pop_front().unwrap();
                self.s.free.lock().unwrap().push(old);
                self.s.dropped.fetch_add(1, Relaxed);
                self.s.no_meta.fetch_add(1, Relaxed);
            }
        }
        true
    }

    pub fn submit_meta(&self, ts: i64, meta: FrameMeta) {
        let mut p = self.s.pending.lock().unwrap();
        if let Some(i) = p.frames.iter().position(|(t, _)| *t == ts) {
            let (_, img) = p.frames.remove(i).unwrap();
            self.dispatch(&mut p, ts, img, meta);
        } else {
            p.metas.push_back((ts, meta));
            if p.metas.len() > 32 { p.metas.pop_front(); }
        }
    }

    fn dispatch(&self, p: &mut Pending, ts: i64, img: Vec<u16>, meta: FrameMeta) {
        let ts0 = *p.ts0.get_or_insert(ts);
        if ts < ts0 { self.s.free.lock().unwrap().push(img); return } // metadata from before the first frame
        let index = ((ts - ts0) as f64 * self.s.cfg.fps as f64 / 1e9).round() as u64;
        if let Some(tx) = &p.tx { let _ = tx.send(Job { index, img, meta }); }
    }

    pub fn stats(&self) -> (u64, u64, u64) {
        (self.s.written.load(Relaxed), self.s.dropped.load(Relaxed), self.s.bytes.load(Relaxed))
    }

    pub fn finish(self) -> Summary {
        {
            let mut p = self.s.pending.lock().unwrap();
            p.tx = None;
            // Frames still waiting for metadata are the clip's tail after Stop, not losses.
            p.frames.clear();
        }
        for w in self.workers { let _ = w.join(); }
        unsafe { libc::sync() };
        let mut idx = self.s.written_idx.lock().unwrap().clone();
        idx.sort_unstable();
        let missing: Vec<u64> = match idx.last() {
            Some(&last) => { let have: std::collections::HashSet<u64> = idx.iter().copied().collect(); (0..last).filter(|i| !have.contains(i)).collect() }
            None => vec![],
        };
        let written = self.s.written.load(Relaxed);
        Summary {
            written, dropped: self.s.dropped.load(Relaxed).max(missing.len() as u64), bytes: self.s.bytes.load(Relaxed),
            no_slot: self.s.no_slot.load(Relaxed), no_meta: self.s.no_meta.load(Relaxed), missing,
        }
    }
}

fn worker(s: &Shared, rx: &Mutex<Receiver<Job>>) {
    loop {
        let job = match rx.lock().unwrap().recv() { Ok(j) => j, Err(_) => return };
        let st = &s.cfg.statics;
        let (w, h) = s.out;
        let g = job.meta.shading.as_ref().map(|(r, c, m)| gain_maps(*r, *c, m, &st.cfa, s.crop, s.cfg.sensor_h));
        let gains = job.meta.gains;
        let gm = (gains[1] + gains[2]) / 2.0;
        let meta = DngMeta {
            make: &st.make, model: &st.model, software: "CineWright", reel: &st.reel,
            cfa: st.cfa, black: job.meta.black, white: job.meta.white.unwrap_or(st.white),
            color_matrix: st.color_matrix, forward_matrix: st.forward_matrix, calibration: st.calibration,
            illuminant: st.illuminant,
            as_shot_neutral: [gm / gains[0], 1.0, gm / gains[3]],
            noise_profile: st.noise_profile,
            exposure_ns: job.meta.exposure_ns, iso: job.meta.iso, f_number: st.f_number, focal_mm: st.focal_mm,
            fps: s.cfg.fps,
            timecode: rawcore::timecode_bcd(st.tc_start_frames + job.index, s.cfg.fps),
            gain_maps: g.as_ref(),
        };
        let dng = rawcore::write_dng(&job.img, w, h, &meta);
        s.free.lock().unwrap().push(job.img);
        let path = s.cfg.dir.join(format!("{}_{:06}.dng", s.cfg.prefix, job.index));
        match File::create(&path).and_then(|mut f| { f.write_all(&dng)?; Ok(f) }) {
            Ok(f) => {
                s.written.fetch_add(1, Relaxed);
                s.bytes.fetch_add(dng.len() as u64, Relaxed);
                s.written_idx.lock().unwrap().push(job.index);
                let mut last = s.last_sync.lock().unwrap();
                if last.elapsed() >= Duration::from_secs(1) { *last = Instant::now(); drop(last); unsafe { libc::syncfs(f.as_raw_fd()) }; }
            }
            Err(_) => { s.dropped.fetch_add(1, Relaxed); }
        }
    }
}

/// Converts Camera2's full-sensor shading map into four per-CFA-position gain maps resampled to
/// span exactly the cropped rows.
fn gain_maps(rows: u32, cols: u32, m: &[f32], cfa: &[u8; 4], crop: (usize, usize, usize), sensor_h: usize) -> GainMaps {
    let (_, ch, y0) = crop;
    let (r, c) = (rows as usize, cols as usize);
    let channel = |pos: usize| match cfa[pos] { 0 => 0, 2 => 3, _ => if pos < 2 { 1 } else { 2 } };
    let maps = std::array::from_fn(|pos| {
        let k = channel(pos);
        let mut out = Vec::with_capacity(r * c);
        for row in 0..r {
            let v = (y0 as f64 + row as f64 / (r - 1) as f64 * ch as f64) / sensor_h as f64 * (r - 1) as f64;
            let (i0, f) = (v.floor().min((r - 2) as f64) as usize, v - v.floor().min((r - 2) as f64));
            for col in 0..c {
                let a = m[(i0 * c + col) * 4 + k] as f64;
                let b = m[((i0 + 1) * c + col) * 4 + k] as f64;
                out.push((a + (b - a) * f) as f32);
            }
        }
        out
    });
    GainMaps { rows, cols, origin_v: 0.0, spacing_v: 1.0 / (r - 1) as f64, origin_h: 0.0, spacing_h: 1.0 / (c - 1) as f64, maps }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn statics() -> Statics {
        Statics { make: "Google".into(), model: "Test".into(), reel: "A001".into(), cfa: [0, 1, 1, 2], white: 1023,
            color_matrix: [None, None], forward_matrix: [None, None], calibration: [None, None], illuminant: [21, 17],
            noise_profile: None, f_number: 1.7, focal_mm: 6.9, tc_start_frames: 0 }
    }

    fn meta() -> FrameMeta { FrameMeta { exposure_ns: 1_000_000, iso: 100, black: [64.0; 4], white: None, gains: [2.0, 1.0, 1.0, 1.5], shading: Some((3, 4, vec![1.0; 48])) } }

    #[test]
    fn matches_out_of_order_and_numbers_gaps() {
        let dir = std::env::temp_dir().join(format!("cw_raw_test_{}", std::process::id()));
        let (w, h) = (64, 48);
        let s = Session::new(Config { dir: dir.clone(), prefix: "T".into(), sensor_w: w, sensor_h: h, lite: true, fps: 24, threads: 2, slots: 4, statics: statics() }).unwrap();
        let frame = vec![0u8; w * h * 2];
        let dt = 1_000_000_000 / 24;
        s.submit_meta(1000, meta());              // metadata first
        s.submit_frame(1000, &frame, w * 2);
        s.submit_frame(1000 + dt, &frame, w * 2); // frame first
        s.submit_meta(1000 + dt, meta());
        s.submit_frame(1000 + 3 * dt, &frame, w * 2); // frame 2 never arrives
        s.submit_meta(1000 + 3 * dt, meta());
        let sum = s.finish();
        assert_eq!(sum.written, 3);
        assert_eq!(sum.missing, vec![2]);
        assert!(dir.join("T_000003.dng").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn pool_exhaustion_drops() {
        let dir = std::env::temp_dir().join(format!("cw_raw_drop_{}", std::process::id()));
        let s = Session::new(Config { dir: dir.clone(), prefix: "T".into(), sensor_w: 64, sensor_h: 48, lite: false, fps: 24, threads: 1, slots: 2, statics: statics() }).unwrap();
        let frame = vec![0u8; 64 * 48 * 2];
        for i in 0..3 { s.submit_frame(i, &frame, 128); } // no metadata: slots stay pending
        assert_eq!(s.stats().1, 1);
        s.finish();
        std::fs::remove_dir_all(dir).unwrap();
    }
}
