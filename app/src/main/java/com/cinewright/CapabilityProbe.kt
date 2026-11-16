package com.cinewright

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder

enum class Mode(val label: String) {
    STANDARD("Standard"), LOG8("Log3G10 8-bit"), LOG10("Log3G10 10-bit"), RAW("RAW (CinemaDNG)"), RAW_LITE("RAW Lite (½ res)");
    val isRaw get() = this == RAW || this == RAW_LITE
}

data class ModeSupport(val mode: Mode, val ok: Boolean, val reason: String)

data class Lens(
    val id: String,
    val focalMm: Float,
    val videoSize: Size,
    val previewSize: Size,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val minFocusDiopters: Float,
    val curvePoints: Int,
    val orientationHint: Int,
    val modes: List<ModeSupport>,
    val chars: CameraCharacteristics,
    val rawSize: Size?,
    val rawMaxFps: Int,
    /** Zoom stops the logical camera can reach (it switches physical lenses itself). */
    val zoomStops: List<Float>,
    /** Highest frame rate (≤ 60) the camera delivers at [videoSize] to both the encoder and GPU streams. */
    val videoMaxFps: Int,
) {
    val manual get() = isoRange != null && exposureRange != null
    fun supports(m: Mode) = modes.first { it.mode == m }.ok
}

object CapabilityProbe {
    private const val FRAME_30_NS = 1_000_000_000L / 30

    fun probe(manager: CameraManager): List<Lens> {
        val main10 = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && MediaFormat.MIMETYPE_VIDEO_HEVC in info.supportedTypes &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any { it.profile == CodecProfileLevel.HEVCProfileMain10 }
        }
        return manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CC.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return@mapNotNull null
            val map = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP) ?: return@mapNotNull null
            val caps = c.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps

            val recSizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
            val videoSize = listOf(Size(3840, 2160), Size(1920, 1080)).firstOrNull { s ->
                s in recSizes && map.getOutputMinFrameDuration(MediaRecorder::class.java, s) <= FRAME_30_NS
            } ?: return@mapNotNull null
            val prevSizes = map.getOutputSizes(SurfaceHolder::class.java).orEmpty()
            val previewSize = prevSizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }.maxByOrNull { it.width } ?: videoSize

            val curvePoints = c.get(CC.TONEMAP_MAX_CURVE_POINTS) ?: 0
            val curveMode = c.get(CC.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true
            val hlg = c.get(CC.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
            val rawSize = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps)
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } else null
            val rawMaxFps = rawSize?.let { (1_000_000_000L / map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, it)).toInt() } ?: 0
            val zoom = c.get(CC.CONTROL_ZOOM_RATIO_RANGE)

            val modes = listOf(
                ModeSupport(Mode.STANDARD, true, "${videoSize.width}×${videoSize.height}"),
                when {
                    !manual -> ModeSupport(Mode.LOG8, false, "No MANUAL_SENSOR capability")
                    !curveMode -> ModeSupport(Mode.LOG8, false, "ISP has no contrast-curve tonemap")
                    curvePoints < 32 -> ModeSupport(Mode.LOG8, false, "Only $curvePoints tonemap curve points")
                    else -> ModeSupport(Mode.LOG8, true, "$curvePoints-point ISP curve, Rec.709 gamut")
                },
                when {
                    !hlg -> ModeSupport(Mode.LOG10, false, "No HLG10 camera profile")
                    !main10 -> ModeSupport(Mode.LOG10, false, "No HEVC Main10 encoder")
                    !manual -> ModeSupport(Mode.LOG10, false, "No MANUAL_SENSOR capability")
                    videoSize !in map.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty() ->
                        ModeSupport(Mode.LOG10, false, "No ${videoSize.width}×${videoSize.height} GPU stream")
                    else -> ModeSupport(Mode.LOG10, true, "HLG10 → GPU → HEVC Main10, RWG gamut")
                },
                rawMode(Mode.RAW, manual, rawSize, rawMaxFps),
                rawMode(Mode.RAW_LITE, manual, rawSize, rawMaxFps),
            )
            Lens(
                id = id,
                focalMm = c.get(CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
                videoSize = videoSize,
                previewSize = previewSize,
                isoRange = if (manual) c.get(CC.SENSOR_INFO_SENSITIVITY_RANGE) else null,
                exposureRange = if (manual) c.get(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
                minFocusDiopters = c.get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                curvePoints = curvePoints,
                // Activity is locked to landscape (display rotation 90).
                orientationHint = ((c.get(CC.SENSOR_ORIENTATION) ?: 90) - 90 + 360) % 360,
                modes = modes,
                chars = c,
                rawSize = rawSize,
                rawMaxFps = rawMaxFps,
                videoMaxFps = listOf(MediaRecorder::class.java, android.graphics.SurfaceTexture::class.java)
                    .minOf { (1_000_000_000L / map.getOutputMinFrameDuration(it, videoSize)).toInt() }.coerceAtMost(60),
                zoomStops = listOf(0.5f, 1f, 2f, 5f).filter { zoom?.contains(it) ?: (it == 1f) },
            )
        }
    }

    private fun rawMode(m: Mode, manual: Boolean, size: Size?, fps: Int) = when {
        size == null -> ModeSupport(m, false, "No RAW_SENSOR output")
        !manual -> ModeSupport(m, false, "No MANUAL_SENSOR capability")
        fps < 24 -> ModeSupport(m, false, "RAW ${size.width}×${size.height} only reaches $fps fps")
        else -> {
            val c = rawCrop(size, m == Mode.RAW_LITE)
            ModeSupport(m, true, "${c.width}×${c.height} LJ92 DNG, ≤ ${minOf(fps, 30)} fps")
        }
    }

    /** Output size after the centred 16:9 crop (and 2×2 bin for Lite); mirrors rawcore::crop_16x9. */
    fun rawCrop(sensor: Size, lite: Boolean): Size {
        val w = sensor.width and 3.inv()
        val h = minOf((w * 9 / 16 + 3) and 3.inv(), sensor.height and 3.inv())
        return if (lite) Size(w / 2, h / 2) else Size(w, h)
    }
}
