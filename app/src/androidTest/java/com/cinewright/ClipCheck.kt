package com.cinewright

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Decodes the newest clips with the phone's own decoder and writes, per clip, `checks/<clip>.json`
 * (format, colour tags, plane statistics at ~1 s) and `checks/<clip>_y.pgm` (luma thumbnail).
 * Run after recording: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ClipCheck {
    @Test
    fun decodeNewestClips() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.externalMediaDirs.first(), "CineWright")
        val out = File(dir, "checks").apply { mkdirs() }
        val clips = dir.listFiles { f -> f.extension == "mp4" }!!.sortedByDescending { it.lastModified() }.take(4)
        for (clip in clips) File(out, "${clip.nameWithoutExtension}.json").writeText(check(clip, out).toString(2))
        assertTrue(clips.isNotEmpty())
    }

    private fun check(clip: File, out: File): JSONObject {
        val ex = MediaExtractor().apply { setDataSource(clip.path) }
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val r = JSONObject().put("mime", fmt.getString(MediaFormat.KEY_MIME))
        // Frame count and measured rate from sample timestamps; then rewind for decoding.
        var n = 0; var first = -1L; var last = 0L
        while (ex.sampleTime >= 0) { if (first < 0) first = ex.sampleTime; last = maxOf(last, ex.sampleTime); n++; ex.advance() }
        r.put("frames", n).put("measuredFps", if (n > 1) (n - 1) * 1e6 / (last - first) else 0.0)
            .put("bitrate", clip.length() * 8.0 / ((last - first) / 1e6) / 1e6)
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        listOf(MediaFormat.KEY_PROFILE, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE)
            .forEach { k -> if (fmt.containsKey(k)) r.put(k, fmt.getInteger(k)) }
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var decoded = 0
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o < 0) { if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break; continue }
            if (++decoded == 30) {
                codec.getOutputImage(o)!!.use { img -> stats(img, r, File(out, "${clip.nameWithoutExtension}_y.pgm")) }
                codec.releaseOutputBuffer(o, false)
                break
            }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        codec.stop(); codec.release(); ex.release()
        return r.put("framesDecoded", decoded)
    }

    /** Plane means as fractions of full code range, plus a 480×270 8-bit luma thumbnail. */
    private fun stats(img: android.media.Image, r: JSONObject, pgm: File) {
        val tenBit = img.format == ImageFormat.YCBCR_P010
        val maxV = if (tenBit) 1023.0 else 255.0
        fun sample(p: android.media.Image.Plane, x: Int, y: Int): Double {
            val b = p.buffer
            val o = y * p.rowStride + x * p.pixelStride
            return if (tenBit) (((b.get(o).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8)) shr 6).toDouble()
            else (b.get(o).toInt() and 0xFF).toDouble()
        }
        val (w, h) = img.width to img.height
        fun mean(pi: Int, sub: Int, centre: Boolean): Double {
            val p = img.planes[pi]
            val (pw, ph) = w / sub to h / sub
            val xs = if (centre) (pw * 3 / 8 until pw * 5 / 8) else (0 until pw)
            val ys = if (centre) (ph * 3 / 8 until ph * 5 / 8) else (0 until ph)
            var s = 0.0; var n = 0
            for (y in ys step 4) for (x in xs step 4) { s += sample(p, x, y); n++ }
            return s / n / maxV
        }
        r.put("width", w).put("height", h).put("imageFormat", img.format).put("tenBit", tenBit)
            .put("meanY", mean(0, 1, false)).put("meanCb", mean(1, 2, false)).put("meanCr", mean(2, 2, false))
            .put("centreY", mean(0, 1, true)).put("centreCb", mean(1, 2, true)).put("centreCr", mean(2, 2, true))
        val tw = 480; val th = 270
        val bytes = ByteArray(tw * th) { i -> (sample(img.planes[0], (i % tw) * w / tw, (i / tw) * h / th) / maxV * 255).toInt().toByte() }
        pgm.outputStream().use { it.write("P5\n$tw $th\n255\n".toByteArray()); it.write(bytes) }
    }
}
