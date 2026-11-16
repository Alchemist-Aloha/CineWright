package com.cinewright

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Logs (tag "Probe60") whether 4K60 is possible: camera stream rates and HEVC encoder limits. */
@RunWith(AndroidJUnit4::class)
class Probe60 {
    @Test
    fun report() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cm = ctx.getSystemService(CameraManager::class.java)
        val k4 = Size(3840, 2160)
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CC.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val map = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP)!!
            fun fps(cls: Class<*>) = if (k4 in map.getOutputSizes(cls).orEmpty()) 1e9 / map.getOutputMinFrameDuration(cls, k4) else 0.0
            Log.i("Probe60", "cam $id AE fps ranges=${c.get(CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()}")
            Log.i("Probe60", "cam $id 4K max fps: SurfaceTexture=%.1f MediaRecorder=%.1f".format(fps(SurfaceTexture::class.java), fps(MediaRecorder::class.java)))
            Log.i("Probe60", "cam $id highSpeed sizes=${map.highSpeedVideoSizes.joinToString()} ranges=${map.highSpeedVideoFpsRanges.joinToString()}")
            Log.i("Probe60", "cam $id request keys with Throttle/Fps: ${c.availableCaptureRequestKeys.map { it.name }.filter { "hrottle" in it || "Fps" in it }}")
            Log.i("Probe60", "cam $id session keys: ${c.availableSessionKeys.map { it.name }}")
            val dr = c.get(CC.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            Log.i("Probe60", "cam $id HLG10 constraints=${dr?.getProfileCaptureRequestConstraints(android.hardware.camera2.params.DynamicRangeProfiles.HLG10)}")
        }
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || MediaFormat.MIMETYPE_VIDEO_HEVC !in info.supportedTypes) continue
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            val v = caps.videoCapabilities
            val main10 = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            Log.i("Probe60", "enc ${info.name} hw=${info.isHardwareAccelerated} main10=$main10 4K60=${v.areSizeAndRateSupported(3840, 2160, 60.0)} " +
                "4Kfps=${v.getSupportedFrameRatesFor(3840, 2160)} achievable=${v.getAchievableFrameRatesFor(3840, 2160)} bitrate=${v.bitrateRange}")
            Log.i("Probe60", "enc ${info.name} perfPoints=${v.supportedPerformancePoints?.joinToString()}")
        }
    }
}
