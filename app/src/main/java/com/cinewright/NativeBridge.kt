package com.cinewright

import java.nio.ByteBuffer

object NativeBridge {
    init { System.loadLibrary("cinewright") }

    /** Interleaved (in, out) pairs for TonemapCurve; out = Log3G10(0.18 * 2^headroom * in). */
    external fun log3g10TonemapCurve(points: Int, headroom: Float): FloatArray

    /** 33^3 .cube text: Log3G10 (RWG gamut if [rwg], else Rec.709 gamut) -> Rec.709 gamma 2.4. */
    external fun cubeLut(rwg: Boolean): String

    /** Annex-B codec config with every SPS's VUI primaries/transfer rewritten; null if it can't be patched. */
    external fun patchVui(csd: ByteArray, hevc: Boolean, primaries: Int, transfer: Int): ByteArray?

    /**
     * Opens a RAW clip writer. [matrices] = ColorMatrix1/2, ForwardMatrix1/2, CameraCalibration1/2,
     * 9 floats each, row-major, NaN when absent. [noise] = (S, O) or empty. Returns 0 on failure.
     */
    external fun rawOpen(
        dir: String, prefix: String, sensorW: Int, sensorH: Int, lite: Boolean, fps: Int, threads: Int, slots: Int,
        cfa: Int, white: Int, illuminants: IntArray, matrices: FloatArray, noise: DoubleArray,
        make: String, model: String, reel: String, fNumber: Float, focalMm: Float, timecodeStartFrames: Long,
    ): Long

    /** Copies one RAW_SENSOR plane; the Image can be closed as soon as this returns. False = dropped. */
    external fun rawFrame(handle: Long, timestampNs: Long, plane: ByteBuffer, rowStride: Int): Boolean

    /** Per-frame metadata; [shading] = Camera2 lens shading map (rows×cols×[R,Geven,Godd,B]) or null. */
    external fun rawMeta(
        handle: Long, timestampNs: Long, exposureNs: Long, iso: Int, black: FloatArray, white: Int,
        gains: FloatArray, shading: FloatArray?, rows: Int, cols: Int,
    )

    /** [written, dropped, bytes] */
    external fun rawStats(handle: Long): LongArray

    /** Finishes writing queued frames and frees the session. [written, dropped, bytes, droppedNoSlot, droppedNoMeta, missing frame indices…] */
    external fun rawClose(handle: Long): LongArray

    /** [BT.2020→RWG (9), RWG→Rec.709 (9)], row-major. */
    external fun gamutMatrices(): FloatArray

    /** [R, G, B gains, 9 × camera→Rec.709 transform] for [kelvin]; matrices = CM1, CM2, FM1, FM2 row-major. */
    external fun whiteBalance(kelvin: Float, matrices: FloatArray, illuminant1: Int, illuminant2: Int): FloatArray
}
