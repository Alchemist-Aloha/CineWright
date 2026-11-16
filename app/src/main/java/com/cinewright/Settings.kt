package com.cinewright

import android.content.Context
import android.media.MediaRecorder
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.pow

enum class WbMode { AUTO, LOCK, KELVIN }

data class Settings(
    val mode: Mode = Mode.STANDARD,
    val hevc: Boolean = true,
    val fps: Int = 30,
    val zoom: Float = 1f,
    val autoExposure: Boolean = true,
    val iso: Int = 100,
    val shutterAngle: Float = 180f,
    val autoFocus: Boolean = true,
    val focusDiopters: Float = 0f,
    /** Tap-to-focus point in normalised viewfinder coordinates; null = no tap focus. */
    val focusPoint: Pair<Float, Float>? = null,
    val wb: WbMode = WbMode.AUTO,
    val kelvin: Int = 5600,
    // Settings screen
    val headroomStops: Float = 4f,
    val mbpsStd: Int = 80,
    val mbpsLog8: Int = 100,
    val mbpsLog10: Int = 150,
    val fullRange: Boolean = false,
    val audioSource: Int = MediaRecorder.AudioSource.CAMCORDER,
    val zebraLog: Float = 0.62f,
    val zebraStd: Float = 0.95f,
    val k: Float = 3.74f,
    // Monitoring
    val logView: Boolean = false,
    val zebra: Boolean = false,
    val falseColor: Boolean = false,
    val histogram: Boolean = true,
) {
    /** Highest frame rate this mode can really record. Log 10-bit: the 4K HLG10 camera stream measured
     *  ~55 fps when asked for 60 on Pixel 11 Pro, so it is held to 30 rather than dropping frames. */
    fun maxFps(lens: Lens) = when {
        mode.isRaw -> minOf(30, lens.rawMaxFps)
        mode == Mode.LOG10 -> minOf(30, lens.videoMaxFps)
        else -> lens.videoMaxFps
    }

    /** Log and RAW modes are always manual exposure (spec: UI › Controls). */
    val manualExposure get() = !autoExposure || mode != Mode.STANDARD
    /** Presets are for ≤ 30 fps; higher rates scale up, capped at the Pixel HEVC encoder's 240 Mbps. */
    val bitrate get() = (when (mode) { Mode.LOG8 -> mbpsLog8; Mode.LOG10 -> mbpsLog10; else -> mbpsStd } * maxOf(1.0, fps / 30.0))
        .coerceAtMost(240.0).toInt() * 1_000_000
    /** RAW uses the 1× lens only: zoom would hand RAW_SENSOR to a different physical sensor mid-session. */
    val effectiveZoom get() = if (mode.isRaw) 1f else zoom
    /** Changes that need a new capture session: stream layout, and the frame rate (aeTargetFpsRange
     *  is a session key; the HAL picks the sensor mode from it at session creation). */
    val sessionGroup get() = when { mode.isRaw -> 1; mode == Mode.LOG10 -> 2; else -> 0 } * 1000 + fps
    val isLog get() = mode == Mode.LOG8 || mode == Mode.LOG10

    /** Code value where the ISP clips in the 8-bit log path (in = 1.0 → Log3G10(0.18·2^H)). */
    val log8Clip get() = log3g10(0.18 * 2.0.pow(headroomStops.toDouble())).toFloat()

    fun monitor() = Monitor(
        input = when (mode) { Mode.LOG8 -> InputKind.LOG709; Mode.LOG10 -> InputKind.HLG; else -> InputKind.SDR },
        logView = logView && isLog,
        zebra = zebra,
        zebraLevel = when (mode) { Mode.LOG10 -> zebraLog; Mode.LOG8 -> minOf(zebraLog, log8Clip - 0.005f); else -> zebraStd },
        falseColor = falseColor,
        grey = if (isLog) 1f / 3f else 0.43f,
        k = k,
    )

    fun toJson(): String = JSONObject().apply {
        put("mode", mode.name); put("hevc", hevc); put("fps", fps); put("zoom", zoom.toDouble())
        put("ae", autoExposure); put("iso", iso); put("shutter", shutterAngle.toDouble())
        put("af", autoFocus); put("focus", focusDiopters.toDouble()); put("wb", wb.name); put("kelvin", kelvin)
        put("headroom", headroomStops.toDouble()); put("mbpsStd", mbpsStd); put("mbpsLog8", mbpsLog8); put("mbpsLog10", mbpsLog10)
        put("fullRange", fullRange); put("audio", audioSource); put("zebraLog", zebraLog.toDouble()); put("zebraStd", zebraStd.toDouble())
        put("k", k.toDouble()); put("logView", logView); put("zebra", zebra); put("falseColor", falseColor); put("histogram", histogram)
    }.toString()

    companion object {
        fun log3g10(x: Double): Double { val xc = x + 0.01; return if (xc >= 0) 0.224282 * log10(155.975327 * xc + 1) else 15.1927 * xc }

        fun load(ctx: Context): Settings {
            val d = Settings()
            val j = runCatching { JSONObject(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("s", "{}")!!) }.getOrNull() ?: return d
            fun f(k: String, v: Float) = j.optDouble(k, v.toDouble()).toFloat()
            return Settings(
                mode = runCatching { Mode.valueOf(j.optString("mode")) }.getOrDefault(d.mode),
                hevc = j.optBoolean("hevc", d.hevc), fps = j.optInt("fps", d.fps), zoom = f("zoom", d.zoom),
                autoExposure = j.optBoolean("ae", d.autoExposure), iso = j.optInt("iso", d.iso), shutterAngle = f("shutter", d.shutterAngle),
                autoFocus = j.optBoolean("af", d.autoFocus), focusDiopters = f("focus", d.focusDiopters),
                wb = runCatching { WbMode.valueOf(j.optString("wb")) }.getOrDefault(d.wb), kelvin = j.optInt("kelvin", d.kelvin),
                headroomStops = f("headroom", d.headroomStops), mbpsStd = j.optInt("mbpsStd", d.mbpsStd),
                mbpsLog8 = j.optInt("mbpsLog8", d.mbpsLog8), mbpsLog10 = j.optInt("mbpsLog10", d.mbpsLog10),
                fullRange = j.optBoolean("fullRange", d.fullRange), audioSource = j.optInt("audio", d.audioSource),
                zebraLog = f("zebraLog", d.zebraLog), zebraStd = f("zebraStd", d.zebraStd), k = f("k", d.k),
                logView = j.optBoolean("logView"), zebra = j.optBoolean("zebra"), falseColor = j.optBoolean("falseColor"),
                histogram = j.optBoolean("histogram", d.histogram),
            )
        }

        fun save(ctx: Context, s: Settings) =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("s", s.toJson()).apply()
    }
}
