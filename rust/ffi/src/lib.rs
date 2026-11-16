//! JNI entry points for com.cinewright.NativeBridge.

use jni::objects::JClass;
use jni::sys::{jboolean, jfloat, jfloatArray, jint, jstring};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_cinewright_NativeBridge_log3g10TonemapCurve(
    env: JNIEnv, _: JClass, points: jint, headroom: jfloat,
) -> jfloatArray {
    let curve = logcore::isp_tonemap_curve(points.max(3) as usize, headroom as f64);
    let arr = env.new_float_array(curve.len() as i32).expect("alloc float[]");
    env.set_float_array_region(&arr, 0, &curve).expect("fill float[]");
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_cinewright_NativeBridge_cubeLut(
    env: JNIEnv, _: JClass, rwg: jboolean,
) -> jstring {
    let g = if rwg != 0 { logcore::LogGamut::Rwg } else { logcore::LogGamut::Rec709 };
    env.new_string(logcore::cube_lut(33, g)).expect("alloc string").into_raw()
}

mod raw;
mod vui;

use jni::objects::{JByteArray, JByteBuffer, JDoubleArray, JFloatArray, JIntArray, JString};
use jni::sys::{jbyteArray, jlong, jlongArray};

#[no_mangle]
pub extern "system" fn Java_com_cinewright_NativeBridge_patchVui(
    env: JNIEnv, _: JClass, buf: JByteArray, hevc: jboolean, primaries: jint, transfer: jint,
) -> jbyteArray {
    let Ok(data) = env.convert_byte_array(&buf) else { return std::ptr::null_mut() };
    match vui::set_colour(&data, hevc != 0, primaries as u8, transfer as u8) {
        Some(out) => env.byte_array_from_slice(&out).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

fn floats<const N: usize>(env: &JNIEnv, a: &JFloatArray) -> [f32; N] {
    let mut out = [0f32; N];
    let _ = env.get_float_array_region(a, 0, &mut out);
    out
}

fn mat(m: &[f32], i: usize) -> Option<[f32; 9]> {
    let s: [f32; 9] = m[i * 9..i * 9 + 9].try_into().unwrap();
    s.iter().all(|x| x.is_finite()).then_some(s)
}

/// matrices: ColorMatrix1/2, ForwardMatrix1/2, CameraCalibration1/2 (row-major, NaN = absent).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_cinewright_NativeBridge_rawOpen(
    mut env: JNIEnv, _: JClass, dir: JString, prefix: JString, sensor_w: jint, sensor_h: jint, lite: jboolean,
    fps: jint, threads: jint, slots: jint, cfa: jint, white: jint, illuminants: JIntArray, matrices: JFloatArray,
    noise: JDoubleArray, make: JString, model: JString, reel: JString, f_number: jfloat, focal_mm: jfloat, tc_start: jlong,
) -> jlong {
    let s = |env: &mut JNIEnv, j: &JString| -> String { env.get_string(j).map(|x| x.into()).unwrap_or_default() };
    let (dir, prefix, make, model, reel) = (s(&mut env, &dir), s(&mut env, &prefix), s(&mut env, &make), s(&mut env, &model), s(&mut env, &reel));
    let m: [f32; 54] = floats(&env, &matrices);
    let mut ill = [21i32, 17];
    let _ = env.get_int_array_region(&illuminants, 0, &mut ill);
    let noise = if env.get_array_length(&noise).unwrap_or(0) >= 2 {
        let mut n = [0f64; 2];
        let _ = env.get_double_array_region(&noise, 0, &mut n);
        Some(n)
    } else { None };
    let cfa = match cfa { 1 => [1, 0, 2, 1], 2 => [1, 2, 0, 1], 3 => [2, 1, 1, 0], _ => [0, 1, 1, 2] };
    let cfg = raw::Config {
        dir: dir.into(), prefix, sensor_w: sensor_w as usize, sensor_h: sensor_h as usize, lite: lite != 0,
        fps: fps as u32, threads: threads as usize, slots: slots as usize,
        statics: raw::Statics {
            make, model, reel, cfa, white: white as u32,
            color_matrix: [mat(&m, 0), mat(&m, 1)], forward_matrix: [mat(&m, 2), mat(&m, 3)], calibration: [mat(&m, 4), mat(&m, 5)],
            illuminant: [ill[0] as u16, ill[1] as u16], noise_profile: noise, f_number, focal_mm, tc_start_frames: tc_start as u64,
        },
    };
    match raw::Session::new(cfg) { Ok(s) => Box::into_raw(Box::new(s)) as jlong, Err(_) => 0 }
}

/// Safety: `h` must come from rawOpen and not yet be closed.
unsafe fn session<'a>(h: jlong) -> &'a raw::Session { &*(h as *const raw::Session) }

#[no_mangle]
pub extern "system" fn Java_com_cinewright_NativeBridge_rawFrame(
    env: JNIEnv, _: JClass, h: jlong, ts: jlong, buf: JByteBuffer, row_stride: jint,
) -> jboolean {
    let (Ok(ptr), Ok(len)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) else { return 0 };
    let src = unsafe { std::slice::from_raw_parts(ptr, len) };
    unsafe { session(h) }.submit_frame(ts, src, row_stride as usize) as jboolean
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_cinewright_NativeBridge_rawMeta(
    env: JNIEnv, _: JClass, h: jlong, ts: jlong, exposure_ns: jlong, iso: jint, black: JFloatArray, white: jint,
    gains: JFloatArray, shading: JFloatArray, rows: jint, cols: jint,
) {
    let shading = (rows >= 2 && cols >= 2 && !shading.is_null()).then(|| {
        let mut v = vec![0f32; (rows * cols * 4) as usize];
        let _ = env.get_float_array_region(&shading, 0, &mut v);
        (rows as u32, cols as u32, v)
    });
    let meta = raw::FrameMeta {
        exposure_ns, iso: iso.clamp(0, u16::MAX as i32) as u16, black: floats(&env, &black),
        white: (white > 0).then_some(white as u32), gains: floats(&env, &gains), shading,
    };
    unsafe { session(h) }.submit_meta(ts, meta);
}

/// [written, dropped, bytes]
#[no_mangle]
pub extern "system" fn Java_com_cinewright_NativeBridge_rawStats(env: JNIEnv, _: JClass, h: jlong) -> jlongArray {
    let (w, d, b) = unsafe { session(h) }.stats();
    longs(&env, &[w as i64, d as i64, b as i64])
}

/// Stops the session (blocks until queued frames are written).
/// Returns [written, dropped, bytes, droppedNoSlot, droppedNoMeta, missing indices…].
#[no_mangle]
pub extern "system" fn Java_com_cinewright_NativeBridge_rawClose(env: JNIEnv, _: JClass, h: jlong) -> jlongArray {
    let s = unsafe { Box::from_raw(h as *mut raw::Session) };
    let sum = s.finish();
    let mut v = vec![sum.written as i64, sum.dropped as i64, sum.bytes as i64, sum.no_slot as i64, sum.no_meta as i64];
    v.extend(sum.missing.iter().map(|&i| i as i64));
    longs(&env, &v)
}

fn longs(env: &JNIEnv, v: &[i64]) -> jlongArray {
    let a = env.new_long_array(v.len() as i32).expect("alloc long[]");
    env.set_long_array_region(&a, 0, v).expect("fill long[]");
    a.into_raw()
}

/// matrices = ColorMatrix1, ColorMatrix2, ForwardMatrix1, ForwardMatrix2 (row-major, 36 floats).
/// Returns [gainR, gainG, gainB, 9 × camera→Rec.709 transform].
#[no_mangle]
pub extern "system" fn Java_com_cinewright_NativeBridge_whiteBalance(
    env: JNIEnv, _: JClass, kelvin: jfloat, matrices: JFloatArray, illum1: jint, illum2: jint,
) -> jni::sys::jfloatArray {
    let m: [f32; 36] = floats(&env, &matrices);
    let mat = |i: usize| -> logcore::Mat3 { [0, 1, 2].map(|r| [0, 1, 2].map(|c| m[i * 9 + r * 3 + c] as f64)) };
    let (cm1, cm2, fm1, fm2) = (mat(0), mat(1), mat(2), mat(3));
    let cct = [logcore::illuminant_cct(illum1 as u16), logcore::illuminant_cct(illum2 as u16)];
    let (g, ccm) = logcore::white_balance(kelvin as f64, [&cm1, &cm2], [&fm1, &fm2], cct);
    let out: Vec<f32> = g.iter().chain(ccm.iter().flatten()).map(|&x| x as f32).collect();
    let a = env.new_float_array(12).expect("alloc float[]");
    env.set_float_array_region(&a, 0, &out).expect("fill float[]");
    a.into_raw()
}

/// [BT.2020→RWG (9), RWG→Rec.709 (9)], row-major, built from the primaries.
#[no_mangle]
pub extern "system" fn Java_com_cinewright_NativeBridge_gamutMatrices(env: JNIEnv, _: JClass) -> jni::sys::jfloatArray {
    let a = logcore::rgb_to_rgb(&logcore::BT2020, &logcore::RWG);
    let b = logcore::rgb_to_rgb(&logcore::RWG, &logcore::BT709);
    let v: Vec<f32> = a.iter().chain(b.iter()).flatten().map(|&x| x as f32).collect();
    let arr = env.new_float_array(18).expect("alloc float[]");
    env.set_float_array_region(&arr, 0, &v).expect("fill float[]");
    arr.into_raw()
}
