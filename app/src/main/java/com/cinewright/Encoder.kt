package com.cinewright

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class VideoSpec(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val hevc: Boolean, val tenBit: Boolean = false, val fullRange: Boolean = false) {
    val mime get() = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** Returns the format and whether CBR was granted. */
    fun format(codec: MediaCodec): Pair<MediaFormat, Boolean> {
        val vc = codec.codecInfo.getCapabilitiesForType(mime).videoCapabilities
        require(vc.areSizeAndRateSupported(width, height, fps.toDouble())) { "${codec.name} can't encode ${width}x$height@$fps" }
        val caps = codec.codecInfo.getCapabilitiesForType(mime).encoderCapabilities
        val cbr = caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Realtime priority + operating rate make the codec clock for the real frame rate;
            // without them the Pixel encoder ran 4K60 at ~50-57 fps with a fraction of the bitrate.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            // CBR keeps storage predictable and spends bits on flat log areas (less banding).
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (hevc) {
                // Without an explicit level the encoder picks a Main-tier level for 4K60 and caps the
                // bitrate (~32 Mbps measured); High tier 5.1/5.2 allows 160/240 Mbps.
                setInteger(MediaFormat.KEY_PROFILE, if (tenBit) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, if (fps > 30) MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52 else MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51)
            }
            if (tenBit) {
                // GL renders Log3G10 R'G'B'; the encoder converts with the BT.2020 NCL matrix (spec: 10-bit path step 5).
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_RANGE, if (fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
            if (!hevc) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel52)
            }
        } to cbr
    }

    /** A persistent input surface needs a configured consumer once before the camera can use it. */
    fun prime(surface: Surface) {
        val c = MediaCodec.createEncoderByType(mime)
        try {
            c.configure(format(c).first, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.setInputSurface(surface)
            c.start(); c.stop()
        } finally { c.release() }
    }
}

/**
 * Surface-fed video encoder + optional AAC from AudioRecord, muxed to MP4.
 * [input]: camera-fed persistent surface, or null to create one for GL ([inputSurface]).
 * [vuiPrimaries]: for log, strip the encoder's colour tags and write these primaries with transfer
 * "unspecified" into the SPS (1 = BT.709 gamut, 2 = unspecified/RWG); null leaves tags alone.
 */
class Encoder(
    out: File, private val spec: VideoSpec, orientation: Int, input: Surface?,
    audioSource: Int?, private val vuiPrimaries: Int?, private val clockNs: () -> Long,
) {
    private val thread = HandlerThread("encoder").apply { start() }
    private val video = MediaCodec.createEncoderByType(spec.mime)
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(orientation) }
    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private val pending = ArrayList<Triple<Boolean, ByteBuffer, MediaCodec.BufferInfo>>()
    private val needAudio = audioSource != null
    private val audioSource = audioSource ?: MediaRecorder.AudioSource.CAMCORDER
    lateinit var inputSurface: Surface; private set
    private val videoEos = CountDownLatch(1)
    @Volatile private var stopping = false
    private var audioThread: Thread? = null

    val cbr: Boolean
    @Volatile var vuiPatched: Boolean? = null; private set
    @Volatile var error: String? = null; private set

    init {
        video.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, i: Int) {}
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) = addTrack(true, f)
            override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0) write(true, c.getOutputBuffer(i)!!, info)
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) videoEos.countDown()
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { error = "Video encoder: ${e.diagnosticInfo}"; videoEos.countDown() }
        }, Handler(thread.looper))
        val (fmt, cbr) = spec.format(video)
        this.cbr = cbr
        video.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (input != null) { video.setInputSurface(input); inputSurface = input } else inputSurface = video.createInputSurface()
        video.start()
        if (needAudio) audioThread = Thread(::audioLoop, "aac").apply { start() }
    }

    private fun addTrack(isVideo: Boolean, f: MediaFormat) = synchronized(lock) {
        if (isVideo) {
            if (vuiPrimaries != null) {
                listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE).forEach(f::removeKey)
                val csd = f.getByteBuffer("csd-0")
                val bytes = csd?.let { ByteArray(it.remaining()).also { b -> it.duplicate().get(b) } }
                // Transfer 2 = unspecified so editors don't assume sRGB/HLG; the sidecar names Log3G10.
                val patched = bytes?.let { NativeBridge.patchVui(it, spec.hevc, vuiPrimaries, 2) }
                if (patched != null) f.setByteBuffer("csd-0", ByteBuffer.wrap(patched))
                vuiPatched = patched != null
            }
            videoTrack = muxer.addTrack(f)
        } else audioTrack = muxer.addTrack(f)
        if (videoTrack >= 0 && (!needAudio || audioTrack >= 0)) {
            muxer.start()
            muxing = true
            pending.forEach { (v, b, i) -> muxer.writeSampleData(if (v) videoTrack else audioTrack, b, i) }
            pending.clear()
        }
    }

    private fun write(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (muxing) muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
        else {
            val copy = ByteBuffer.allocateDirect(info.size).put(buf.duplicate().position(info.offset).limit(info.offset + info.size) as ByteBuffer).flip() as ByteBuffer
            pending += Triple(isVideo, copy, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
        }
    }

    @SuppressLint("MissingPermission") // only started when RECORD_AUDIO is granted
    private fun audioLoop() {
        val rate = 48_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(audioSource, rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        val aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            aac.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aac.start()
            rec.startRecording()
            val startNs = clockNs()
            var frames = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = aac.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = aac.getInputBuffer(i)!!
                        val end = stopping
                        val n = if (end) 0 else rec.read(b, minOf(b.capacity(), 8192)).coerceAtLeast(0)
                        aac.queueInputBuffer(i, 0, n, (startNs / 1000) + frames * 1_000_000 / rate, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frames += n / 4
                        inputDone = end
                    }
                }
                val o = aac.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(false, aac.outputFormat)
                    o >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) write(false, aac.getOutputBuffer(o)!!, info)
                        aac.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            error = "Audio: ${e.message}"
        } finally {
            runCatching { rec.stop() }; rec.release()
            runCatching { aac.stop() }; aac.release()
        }
    }

    /** Call after the camera has stopped feeding the surface. Returns true if the file is valid. */
    fun stop(): Boolean {
        runCatching { video.signalEndOfInputStream() }
        stopping = true
        videoEos.await(3, TimeUnit.SECONDS)
        audioThread?.join(3000)
        runCatching { video.stop() }
        video.release()
        val ok = synchronized(lock) {
            val started = muxing
            if (started) runCatching { muxer.stop() }.onFailure { error = "Muxer: ${it.message}" }
            muxer.release()
            started
        }
        thread.quitSafely()
        error?.let { Log.e("CineWright", it) }
        return ok && error == null
    }
}
