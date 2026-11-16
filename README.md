# CineWright

Pixel Camera2 video app — see `docs/MVP Spec.md`.

## Build

Needs: Android SDK + NDK 28.2.13676358, Rust with `aarch64-linux-android` target, `cargo-ndk`.

```sh
rustup target add aarch64-linux-android && cargo install cargo-ndk
./gradlew installDebug        # builds rust/ via cargo-ndk, then the APK
cd rust && cargo test         # logcore, rawcore (LJ92/DNG), ffi (RAW session, VUI patch)
```

On-device LJ92 throughput: `cargo ndk -t arm64-v8a build --release -p rawcore --example bench`, push
`target/aarch64-linux-android/release/examples/bench` to `/data/local/tmp` and run it with a thread count.

DNG check against LibRaw: `CW_DNG_OUT=/tmp/x.dng cargo test -p rawcore write_sample`, then
`unprocessed_raw /tmp/x.dng` must reproduce `/tmp/x.dng.raw16be` exactly.

Output: `Android/media/com.cinewright/CineWright/` — `A001_C0001_<date>.mp4` + `.json` for video,
`A001_C0001_<date>/` folders of DNGs + `.wav` + `.json` for RAW, and `LUTs/*.cube`.

On-device clip check (decodes the newest MP4s with the phone's decoder; report + luma thumbnail in
`CineWright/checks/`):

```sh
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.cinewright.ClipCheck com.cinewright.test/androidx.test.runner.AndroidJUnitRunner
```

**Do not use `./gradlew connectedAndroidTest`**: it uninstalls the app afterwards, and Android deletes
`Android/media/com.cinewright/` (all clips) with it.

## Status

| Mode | State |
| --- | --- |
| Standard H.265/H.264 4K24/25/30 + AAC | Built (MediaCodec + MediaMuxer, CBR) |
| Log3G10 8-bit (ISP contrast curve, Rec.709 gamut) | Built; VUI transfer unspecified; runtime check disables it if the ISP ignores the curve |
| Log3G10 10-bit (HLG10 → GLES shader → HEVC Main10, RWG) | Built; VUI primaries/transfer unspecified, BT.2020 matrix |
| RAW / RAW Lite CinemaDNG (LJ92, tiled, gain maps, WAV) | Built |

Also built: GPU preview with Rec.709 view / Log view, zebras, false colour, histogram; white balance
Auto / Lock / Kelvin + one-shot auto; tap-to-focus; settings (bitrates, headroom H, range, audio input,
zebra levels, k calibration, LUT export to Downloads, 2 GB storage test); 10 % free-space floor;
refusal to record at THERMAL_STATUS_SEVERE.

Deviation from the spec: the 10-bit log pass runs as a GLES fragment shader (Kotlin) instead of
Vulkan compute in Rust. Same maths (constants mirrored from `logcore`, gamut matrices taken from it via
JNI), far less code, and it reuses the GL stage the monitoring overlays need anyway.

Not done: per-device correction LUT for HLG local tone mapping (spec risk table), output to a SAF /
MediaStore folder (everything goes to the app media folder), Kelvin WB checked under a known light,
k calibration with a real grey card, the 5 min RAW endurance at 25 °C.

## Measured on Pixel 11 Pro (Android 17, 2026-11-16)

| What | Result |
| --- | --- |
| Probe | 4K30 HEVC/AVC, 81-point tonemap curve, HLG10 + Main10, RAW_SENSOR 4080×3064 ≤ 30 fps, GBRG, 12-bit (white 4095, black 256) |
| RAW 4080×2296 | ~4.3 MB/frame → **~6.2 GB/min at 24 fps** (spec estimate ~10 GB/min) |
| RAW Lite 2040×1148 | ~0.9 MB/frame → **~1.3 GB/min at 24 fps** (spec estimate ~2.6 GB/min) |
| LJ92 + DNG encode | ~110–145 Mpx/s per thread; 4 threads 421 Mpx/s warm (spec target ≥ 250) |
| Storage write | 817 MB/s sequential (RAW needs ~105 MB/s at 24 fps) |
| RAW 24 fps endurance | 161 s, 3877 frames, 0 dropped, then clean stop at THERMAL_STATUS_SEVERE (phone already warm from earlier runs; the 5 min at 25 °C target is not yet proven) |
| RAW 30 fps | Fine for ~20 s; drops once thermally throttled — offer 24/25 fps for long takes |
| 4K60 Log 8-bit HEVC (20 s) | **59.5 fps, 149 Mbps** of 150 — offered |
| 4K60 Standard HEVC (20 s) | 55.0 fps with AE, 57.7 fps manual — offered, but the ISP's own tone mapping drops frames |
| 4K60 Log 10-bit | Camera's HLG10 4K stream delivers ~55 fps → held to 30p |
| 4K60 encoder hints that mattered | session parameters (aeTargetFpsRange is a session key), HEVC High tier level 5.2; operating rate + realtime priority set |
