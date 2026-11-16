# CineWright — MVP Spec

## Overview

The CineWright MVP is a Pixel-only Camera2 app that records three things: standard H.265/H.264 video, RED Log3G10 video in 10-bit H.265, and CinemaDNG RAW with lossless compression. It uses Kotlin for the UI, Camera2 session control and MediaCodec. A Rust core library, called through JNI, does all per-pixel processing: curve conversion, RAW packing and compression, and file writing.

**Goals**

- Record gradeable Log3G10 footage that decodes with RED's published inverse curve and a matching LUT (the 3D lookup table editors use to convert the footage), at 4K30 on a Pixel 8 or newer.
- Record RAW video that opens directly in DaVinci Resolve without converting it first.
- Detect what each phone supports when the app runs, and only offer the modes that phone can do well. Never claim a mode the hardware ignores.

**Non-goals for the MVP**

- Audio beyond a single stereo AAC track.
- Multi-camera recording, external monitors (USB-C/UVC), timecode sync and LUT preview creation.
- Phones other than Pixels. The capability probe makes other phones possible later, but they are not tested.
- Any real-time grading or effects in the app.

**Target devices**

- Pixel 7 and newer (Tensor G2+), on Android 14 (API 34) or later. Set minSdk to 33, because 10-bit `DynamicRangeProfiles` arrived in Android 13.
- Main (wide) camera first. The ultrawide and telephoto are offered only if the capability probe passes for them.

## Recording modes and formats

The MVP has three modes. For RAW, it uses **CinemaDNG compressed with lossless JPEG (LJ92)**, with an optional half-resolution "RAW Lite" mode. This keeps every sensor value, opens natively in Resolve, and roughly halves the storage of uncompressed DNG.

| Mode | Pipeline | Codec / container | Bit depth | Default resolution | Est. storage |
| --- | --- | --- | --- | --- | --- |
| Standard | ISP default tone mapping | H.265 Main or H.264 High, MP4 | 8-bit (H.265 can also be 10-bit HLG) | 3840×2160 @ 24/30 | \~600 MB/min at 80 Mbps |
| Log3G10 (10-bit) | HLG10 ISP output, converted to Log3G10 in Rust | H.265 Main10, MP4 | 10-bit | 3840×2160 @ 24/30 | \~1.1 GB/min at 150 Mbps |
| Log3G10 (8-bit fallback) | Log3G10 curve loaded into the ISP tonemap | H.264 High or H.265 Main, MP4 | 8-bit | 3840×2160 @ 24/30 | \~750 MB/min at 100 Mbps |
| RAW | `RAW_SENSOR` frames, compressed in Rust | CinemaDNG + LJ92, plus WAV audio | Native (10-bit on recent Pixels; read from `WHITE_LEVEL`) | Full width, cropped to 16:9 (e.g. 4080×2296) | \~10 GB/min at 24 fps |
| RAW Lite | `RAW_SENSOR` frames, 2×2 same-color binning, LJ92 | CinemaDNG + LJ92, plus WAV audio | Native | Half (e.g. 2040×1148) | \~2.6 GB/min at 24 fps |

The RAW storage figures are planning estimates: the native bit depth, the crop and an assumed \~1.7:1 LJ92 ratio. Measure them on real Pixel footage in milestone M3. H.264 log is always 8-bit, because Android encoders don't generally offer H.264 High10.

### Choosing the RAW format

| Format | Openness | Compression | Editor support | Verdict |
| --- | --- | --- | --- | --- |
| CinemaDNG + LJ92 | Open Adobe spec, royalty-free | Lossless, \~1.5–2:1 | Native in Resolve. Most raw tools read DNG | **Chosen** |
| Uncompressed CinemaDNG | Open | None | Same as above | Too large (\~27 GB/min at 24 fps). Kept as a debug option |
| MLV (Magic Lantern) | Open, community-run | Lossless LJ92 | Needs MLV App or conversion before Resolve | Single file, but makes the post workflow harder |
| MotionCam MCRAW | Open-source container | Lossless | Needs conversion tools or a virtual file system to open in editors | Couples the app to another project's format |
| ProRes RAW / BRAW | Proprietary | Lossy | Wide | Not open, and needs a license or SDK |

CinemaDNG writes one `.dng` per frame into a clip folder, with a matching `.wav`. Rust packs every frame into a DNG 1.4 file with the camera's color matrices, black and white levels, lens-shading map and timecode. That metadata lets editors decode the footage correctly without any app-specific profile.

## Architecture

Kotlin runs the camera and the encoders. Rust touches pixels and writes RAW files. Frames reach Rust as `HardwareBuffer`s (for GPU work) or direct `ByteBuffer`s (for CPU work), so no pixel data passes through the Java heap. Standard mode bypasses Rust entirely: the camera writes straight into the encoder's input `Surface`.

### Kotlin app (`:app`, `:camera`, `:encode`)

| Module | Responsibility |
| --- | --- |
| `ui` (Jetpack Compose) | Viewfinder (`SurfaceView`), mode picker, the shutter/ISO/WB/focus controls, record button, storage and thermal readouts |
| `CapabilityProbe` | Reads `CameraCharacteristics` and works out which modes this phone supports. Checks the hardware level, `TONEMAP_MAX_CURVE_POINTS`, the `DynamicRangeProfiles` it supports, the `RAW_SENSOR` sizes and their minimum frame durations, and 10-bit HEVC encoder support |
| `SessionController` | Opens the `CameraDevice`, builds the `OutputConfiguration`s for each mode, and runs the repeating request with manual 3A (exposure, focus and white balance). Delivers each `TotalCaptureResult` to Rust, matched by sensor timestamp |
| `EncoderController` | Sets up `MediaCodec` video (HEVC Main10, HEVC Main or AVC High) and AAC audio from `AudioRecord`, and muxes them with `MediaMuxer`. Adds the color metadata (VUI) for each mode |
| `StorageManager` | Picks the output location through MediaStore, or a folder chosen with SAF for RAW clips. Watches free space, and estimates the minutes left from the measured write rate |
| `NativeBridge` | `external fun` declarations, plus the lifecycle of the native handles the Rust side owns |

### Rust core (`libcinewright.so`, Cargo workspace)

| Crate | Responsibility |
| --- | --- |
| `logcore` | Pure math with no platform code: the Log3G10 encode and decode, the HLG inverse OETF, the BT.2020 → REDWideGamutRGB matrix, the ISP tonemap curve generator and `.cube` LUT export. Every other crate depends on it, and it carries all the golden-value tests |
| `gpu` | Vulkan compute (`ash`). Imports `AHardwareBuffer`s through `VK_ANDROID_external_memory_android_hardware_buffer` and runs the P010 HLG → Log3G10 shader, written ahead of time as SPIR-V |
| `rawcore` | Bayer cropping and 2×2 binning, the LJ92 lossless JPEG encoder, a CinemaDNG/DNG 1.4 writer, and per-frame metadata assembly |
| `io` | A pre-allocated frame ring buffer, a pool of writer threads, back-pressure to the camera, and a policy for when to sync to disk |
| `ffi` | JNI entry points (`jni` crate), plus `ndk` for `AHardwareBuffer` and `ANativeWindow`. Each recording session is one opaque handle |

The build uses `cargo-ndk` to produce `arm64-v8a` only. Gradle calls it from a `preBuild` task, and the `.so` is packaged in `jniLibs`.

### Data flow per mode

&#91;embedded content: data flow per recording mode · 4 paths\]

Standard and 8-bit log stay zero-copy on the ISP; only Log 10-bit and RAW pay for a trip through Rust.

1. **Standard:** the camera writes into the encoder's input `Surface`, the encoder output goes to `MediaMuxer`, and the result is an MP4. No Rust.
2. **Log 8-bit fallback:** the same path as Standard. Each request also carries `TONEMAP_MODE_CONTRAST_CURVE`, with a curve `logcore` generates.
3. **Log 10-bit:** the camera's HLG10 output goes to an `ImageReader` (`YCBCR_P010`, GPU-sampled usage). Rust's `gpu` crate converts each frame to Log3G10 P010 and writes it into a `HardwareBuffer` taken from an `ImageWriter` on the encoder's input `Surface`. That buffer is queued to `MediaCodec`, encoded as HEVC Main10, muxed by `MediaMuxer`, and saved as an MP4.
4. **RAW / RAW Lite:** the camera's `RAW_SENSOR` output goes to an `ImageReader`. Each frame is copied into Rust's ring buffer, and the `Image` is closed right away. Writer threads crop or bin the frame, compress it with LJ92, and write a DNG that includes that frame's `TotalCaptureResult` metadata. The clip ends up as a folder of DNGs plus a WAV.

Every mode also runs a separate preview stream to a `SurfaceView`. If the camera can't combine the HLG10 profile with a STANDARD preview (check `getProfileCaptureRequestConstraints`), the preview shows HLG instead. Rust then applies a simple tone map for display only.

## Log3G10 implementation

Use RED's current Log3G10 curve (v3, from RED's 2017 white paper), so footage decodes with RED's own transforms in Resolve and in OCIO/ACES color pipelines. Mid grey (0.18 linear) lands at code value 1/3, and 0 linear lands at about 0.0916.

```latex
y = \begin{cases} a \log_{10}\!\big(b\,(x + c) + 1\big) & x + c \ge 0 \\ g\,(x + c) & x + c < 0 \end{cases} \qquad a = 0.224282,\; b = 155.975327,\; c = 0.01,\; g = 15.1927
```

- `logcore` implements the encode and its exact inverse in `f64` for reference, and in `f32` for the shader. Unit tests check y(0) = 0.0915514 and y(0.18) = 0.33333 to within 1e-6, plus a round-trip error below 1e-5 across −0.05 to 184.
- The gamut is REDWideGamutRGB, with these primaries (CIE xy): R (0.780308, 0.304253), G (0.121595, 1.493994), B (0.095612, −0.084589), and a D65 white point. `logcore` builds the BT.2020 → RWG and BT.709 → RWG matrices from the primaries rather than hard-coding them.

### 10-bit path (preferred)

1. **Decode P010.** Read Y'CbCr → R'G'B' using the buffer's dataspace (`Image.getDataSpace()`), handling both full and limited range.
2. **Undo HLG.** Apply the HLG inverse OETF (BT.2100) to get relative scene-linear E between 0 and 1.
3. **Normalize exposure.** Compute L = k · E, with k ≈ 3.74 by default. That maps HLG's 38% reference grey (BT.2408) to 0.18. Each device gets a calibrated k from a grey-card capture, stored in that device's profile.
4. **Convert gamut.** Multiply by the 3×3 matrix from BT.2020 linear to RWG linear. Negative values are allowed, because Log3G10's linear segment encodes them.
5. **Apply Log3G10.** Encode each channel, then convert R'G'B' → Y'CbCr with the BT.2020 non-constant-luminance matrix, in limited (video) range by default.
6. **Tag the file.** Write VUI tags (the color metadata inside the video stream): primaries and transfer "unspecified", matrix BT.2020 NCL, limited range. Add an MP4 `udta` metadata box plus a sidecar `.json` recording `Log3G10 / REDWideGamutRGB`, the k used and the device profile. In the editor, the clip's input color space is set to RWG / Log3G10.

The shader does steps 1–5 in a single compute pass at 16×16 workgroups. One pixel's chroma is shared by four luma samples, so each invocation handles a 2×2 luma block.

**Dynamic-range ceiling.** HLG tops out at E = 1, which is about 4.4 stops above mid grey after scaling, or a Log3G10 code value of about 0.62. Code values above that go unused in this path. That is expected: the sensor and ISP set the dynamic range, and Log3G10 carries it without clipping. RAW keeps more of the highlights.

### 8-bit fallback path

- The ISP applies `TONEMAP_MODE_CONTRAST_CURVE` to its linear output, which is in BT.709 primaries. `logcore` generates a curve with `TONEMAP_MAX_CURVE_POINTS` points, spaced evenly in log, so the shadows get most of them. Each point is out = Log3G10(s · in), where s puts ISP clipping (in = 1.0) at 0.18 · 2^H, with H = 4 stops of headroom by default.
- The gamut stays BT.709 here, because the ISP cannot output negative values. Files are tagged **Log3G10 / Rec.709 gamut**, and the matching LUT is exported for that combination.
- A sensor-to-RWG color matrix (`COLOR_CORRECTION_TRANSFORM`) is a later experiment. Saturated colors would clip.

### LUTs and metadata

- Export 33³ `.cube` LUTs from `logcore`: RWG/Log3G10 → Rec.709 Gamma 2.4, and Rec.709-gamut/Log3G10 → Rec.709. Use a simple filmic shoulder so they stay tied to the published math and aren't a "look".
- Give each clip a sidecar with: the mode, the curve version, the gamut, k or s, the ISO, shutter, white balance and lens, and the device model and build.

## RAW recording pipeline

RAW captures the largest `RAW_SENSOR` size whose minimum frame duration fits the chosen frame rate. Rust crops or bins each frame as it is copied in, compresses it on 3–4 writer threads, and writes tiled LJ92 DNGs. Dropped frames are counted and leave gaps in the frame numbers, so they are never hidden.

### Capture

- **Streams:** a `RAW_SENSOR` `ImageReader` (`maxImages` 4) plus a PRIV preview. FULL and LEVEL\_3 devices guarantee this combination. Offer 24, 25 or 30 fps only when `getOutputMinFrameDuration(RAW_SENSOR, size)` allows it.
- **Request:** manual exposure and ISO, with AE off. Focus is manual or locked, and white balance is locked after a one-shot measurement. Turn lens shading map statistics on (`STATISTICS_LENS_SHADING_MAP_MODE_ON`), so each DNG gets a gain map. Lock exposure to the mains frequency (1/50 or 1/60 s) so lights don't flicker.
- **Metadata:** match each `TotalCaptureResult` to its frame by `SENSOR_TIMESTAMP`. Rust keeps a small map of pending timestamps, and a frame is written only once its metadata has arrived.

### Processing in Rust

1. **Copy in.** `GetDirectBufferAddress` on the plane buffer gives Rust a pointer. Rust copies only the crop rows into a ring buffer slot, then the `Image` is closed. That holds 12 slots, about 225 MB for RAW or about 60 MB for RAW Lite.
2. **Crop.** Center a 16:9 crop, with even x and y offsets so the CFA phase is preserved. The black and white levels don't change.
3. **Bin (RAW Lite only).** Average each 2×2 group of same-color photosites, and output a Bayer mosaic at half size in the original CFA order. Noise drops by about √4, and it compresses better.
4. **Compress.** Encode LJ92 with predictor 1, using 256×256 tiles that are each encoded independently. That splits the work across threads, and decoders can parallelize too. The Huffman table is built per tile from a histogram pass. The target is at least 250 Mpixel/s across the writer pool, while RAW at 24 fps needs about 225.
5. **Write.** Write the DNG with one large buffered `write` per file into the app's media folder. That folder is visible to file managers, and it avoids MediaStore and SAF, which are slow at creating thousands of small files. Sync to disk every 1 s, and when the clip ends.

### DNG / CinemaDNG tags

| Tag group | Source in Camera2 |
| --- | --- |
| `CFAPattern`, `CFARepeatPatternDim` | `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` |
| `BlackLevel`, `WhiteLevel` | `SENSOR_DYNAMIC_BLACK_LEVEL` / `SENSOR_DYNAMIC_WHITE_LEVEL` per frame, falling back to the static values |
| `ColorMatrix1/2`, `ForwardMatrix1/2`, `CameraCalibration1/2`, `CalibrationIlluminant1/2` | `SENSOR_COLOR_TRANSFORM1/2`, `SENSOR_FORWARD_MATRIX1/2`, `SENSOR_CALIBRATION_TRANSFORM1/2`, `SENSOR_REFERENCE_ILLUMINANT1/2` |
| `AsShotNeutral` | Inverse of the `COLOR_CORRECTION_GAINS` per frame |
| `NoiseProfile` | `SENSOR_NOISE_PROFILE` |
| `OpcodeList2` → `GainMap` | `STATISTICS_LENS_SHADING_CORRECTION_MAP`, one map per Bayer channel |
| `ExposureTime`, `ISOSpeedRatings`, `FNumber`, `FocalLength` | Per-frame capture result |
| `FrameRate`, `TimeCodes`, `TStop`, `ReelName` (CinemaDNG) | Session settings, plus a timecode taken from the first frame's `SENSOR_TIMESTAMP` |

Android's built-in `DngCreator` isn't used: it can't compress, and it is too slow at video frame rates.

### Clip layout

```
Android/media/<pkg>/CineWright/
  A001_C0001_20261116/
    A001_C0001_20261116_000000.dng … _001439.dng
    A001_C0001_20261116.wav        (48 kHz, 24-bit if the mic path allows it, else 16-bit)
    A001_C0001_20261116.json       (mode, crop or bin, fps, dropped frame indices, device profile)
```

**Back-pressure:** if the ring buffer is full, the newest frame is dropped and logged, and the UI shows a warning. If more than 1% of frames in any 5 s window are dropped, recording stops.

## UI and UX

The MVP UI is one landscape screen: a viewfinder, a strip of manual controls and a status bar. Only modes the capability probe passed can be selected. Each mode the phone can't run is shown greyed out, with a one-line reason.

**Viewfinder**

- In log modes the preview is always Rec.709, either the ISP's STANDARD stream or a display-only tone map, so it is easy to frame. A "Log view" toggle shows the signal actually being recorded.
- Show a luma histogram of the recorded signal, and zebras at a configurable code value. The default is 0.62 Log3G10 in the 10-bit path, or about 95% in Standard.
- Add a false-color overlay that marks mid grey (Log3G10 1/3 ± 0.02) and clipping. It's computed in the preview shader, and never written to the file.

**Controls** (always manual in log and RAW modes)

- Shutter (shown as a shutter angle, default 180°), ISO, white balance in Kelvin plus a one-shot auto, manual focus with tap-to-focus, and an AE lock for Standard mode.
- Pick format and frame rate: the codec, the bitrate preset, 24/25/30 fps, and the RAW crop or RAW Lite.
- Lens picker. It lists only lenses whose probe passed.

**Status bar**

- The record timer and the estimated minutes remaining at the measured write rate.
- The thermal state (from `PowerManager.getThermalHeadroom`), the dropped-frame count in RAW, and battery level.
- A post-clip summary: duration, size, dropped frames, and a "Copy LUT" shortcut for log clips.

**Settings:** the default bitrates, log headroom H, the range (full or limited), the audio input, the output folder, and an "Export LUT pack" action.

## Performance budgets and risks

The tightest budgets are the Log 10-bit GPU pass at 4K30 and the RAW write rate, which is close to 170 MB/s sustained. Both need early spikes on real Pixels before the UI work starts.

| Budget | Target | Notes |
| --- | --- | --- |
| Log 10-bit GPU pass | ≤ 6 ms per 3840×2160 frame | That leaves headroom inside the 33 ms frame time at 30 fps |
| Added latency camera → encoder (Log 10-bit) | ≤ 2 frames | Up to 3 `ImageWriter` buffers in flight |
| RAW compression throughput | ≥ 250 Mpixel/s | 3–4 writer threads, pinned off the camera HAL threads |
| Sustained RAW write | ≥ 170 MB/s for 10 min | Measure on each phone model with a 2 GB pre-flight write test |
| Native memory | ≤ 400 MB for RAW, ≤ 150 MB for log | The ring buffer is the main cost |
| Continuous recording | 10 min of Log 10-bit 4K30 and 5 min of RAW at 24 fps, at 25 °C room temperature | Stops cleanly at `THERMAL_STATUS_SEVERE` and finalizes the file |

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Pixel ISP adds local tone mapping (HDR+-style) to its HLG output, so inverting it isn't exact | Log 10-bit footage won't match the published curve | Turn off every processing option the camera exposes. Measure the transfer with an exposure-bracketed grey card or step chart, and store a per-device correction LUT in place of a fixed k |
| `TONEMAP_MODE_CONTRAST_CURVE` is ignored, or there are too few curve points | No 8-bit log | The probe checks the curve's effect on a test frame at startup, and hides the mode if it fails |
| HLG10 can't be combined with a STANDARD preview | Harder preview | Use an HLG preview plus Rust's display-only tone map |
| The RAW stream can't run at 24 fps at full size | No full-resolution RAW | Offer RAW Lite only, or a lower frame rate. Report what the probe found |
| Storage slows down as it fills, or under thermal throttling | Dropped RAW frames | Pre-flight write test, a 10% free-space floor, and the drop-rate auto-stop |
| `ImageWriter` / `HardwareBuffer` format quirks with P010 on the encoder `Surface` | Log 10-bit pipeline fails | Fall back to `MediaCodec` byte-buffer input (an extra copy), with the shader writing into a mapped buffer |
| Resolve doesn't read VUI "unspecified" tags or the sidecar | Users must set the color space manually | Document the steps. Later, explore OCIO or ACES IDT metadata |

## Testing, acceptance and milestones

The MVP is done when all three modes pass the acceptance checks below on a Pixel 8 and a Pixel 9. The plan is five milestones over about 16 weeks, and the riskiest hardware questions are answered in the first two.

### Testing

| Layer | Test | Pass threshold |
| --- | --- | --- |
| `logcore` | Golden values against `colour-science` (Python), generated in CI | ≤ 1e-6 absolute error |
| `gpu` shader | Compared with the CPU reference on synthetic P010 ramps and real captured frames | ≤ 1 code value (10-bit) maximum difference |
| `rawcore` | LJ92 round trip through an independent decoder. `dng_validate` from the Adobe DNG SDK | Bit-exact, with zero validator errors |
| Integration | Instrumented tests that record 30 s in each mode and parse the outputs | The file plays, the frame count is within 1 of expected, and the metadata is present |
| Color | X-Rite chart and a grey card under 5600 K light. Bracket ±3 stops in 1-stop steps | Mid grey within 1/3 ± 0.01 code value, and each stop within ±0.03 of the theoretical value |
| Endurance | 10 min of Log 10-bit and 5 min of RAW, at room temperature | No crash, a valid file, and < 0.1% dropped frames |

### MVP acceptance checklist

- [ ] Standard H.265 and H.264 4K30 clips play in the stock Android player and import into Resolve.
- [ ] Log3G10 10-bit H.265 clips, with the input set to RWG / Log3G10 in Resolve, match the chart reference to within the color thresholds.
- [ ] The 8-bit fallback log is tagged Rec.709 gamut, and its LUT produces a correct-looking image.
- [ ] RAW and RAW Lite CinemaDNG folders open natively in Resolve with the correct white balance and lens shading, and without a magenta cast in the highlights.
- [ ] Measured RAW storage per minute is within ±25% of the planning estimates, and the doc is updated with the real numbers.
- [ ] Unsupported modes are hidden or explained on every test phone, and nothing fails silently.

### Milestones

&#91;embedded content: MVP roadmap · 5 phases, 5 gates, weeks not to scale\]

If M0 shows the Pixel's HLG output can't be inverted cleanly, drop Log 10-bit from the MVP. Ship the 8-bit log and RAW, and move M2's weeks to RAW.

## Sources

- [colour-science: RED log encodings (Log3G10 v1–v3 constants)](https://colour.readthedocs.io/en/v0.4.4/_modules/colour/models/rgb/transfer_functions/red.html)
- [colour-science: REDWideGamutRGB primaries](https://colour.readthedocs.io/en/v0.4.1/generated/colour.models.RGB_COLOURSPACE_RED_WIDE_GAMUT_RGB.html)
- RED Digital Cinema (2017), *White Paper on REDWideGamutRGB and Log3G10*, the primary reference that colour-science cites.

The Pixel capability limits, LJ92 ratios and bitrates above are planning estimates, not measurements. M0 replaces them with probe data.
