package com.cinewright

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest as CR
import android.hardware.camera2.CaptureResult as RS
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

data class ClipSummary(val name: String, val seconds: Long, val bytes: Long, val dropped: Long = 0, val log: Boolean = false)

/**
 * Owns the CameraDevice, the capture session and the active recorder. All camera work runs on one
 * thread. The camera always feeds [gl] (preview, monitoring, Log 10-bit); the second stream is the
 * encoder's surface (Standard / Log 8-bit) or a RAW_SENSOR reader.
 */
class CameraController(private val ctx: Context) {
    private val thread = HandlerThread("camera").apply { start() }
    private val h = Handler(thread.looper)
    private val exec = Executor { h.post(it) }
    private val rawThread = HandlerThread("raw-in").apply { start() }
    // Capture results on their own thread so RAW metadata isn't delayed behind camera-thread work.
    private val resultHandler = Handler(HandlerThread("results").apply { start() }.looper)
    private val manager = ctx.getSystemService(CameraManager::class.java)
    val gl = GlRenderer(::onStats)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sessionGroup = -1
    private var sessionToken = 0
    private var recSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var lens: Lens? = null
    private var gen = 0
    @Volatile private var settings = Settings()

    // Active clip
    private var encoder: Encoder? = null
    private var glEncoding = false
    private var wav: WavRecorder? = null
    private val rawLock = Object()
    private var rawHandle = 0L
    private var clipDir: File? = null
    private var clipFile: File? = null
    private var clipSettings: Settings? = null
    @Volatile private var lastNoise: DoubleArray = DoubleArray(0)
    private val dropWindow = ArrayDeque<LongArray>()

    // Log 8-bit check (spec risk table: the ISP may ignore TONEMAP_MODE_CONTRAST_CURVE)
    @Volatile private var curveEchoed = false
    private var log8Samples = 0
    // Grey-card calibration of k
    private var calib: ((Float) -> Unit)? = null
    private val calibSamples = ArrayList<Float>()
    @Volatile private var calibAe = false

    var recordingSince by mutableStateOf<Long?>(null); private set
    var error by mutableStateOf<String?>(null)
    var lastClip by mutableStateOf<ClipSummary?>(null); private set
    var rawDropped by mutableStateOf(0L); private set
    var stats by mutableStateOf<Stats?>(null); private set
    /** null = not checked yet, "" = passed, else why Log 8-bit was disabled. */
    var log8Check by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    @Volatile var rawBytes = 0L; private set

    val outDir: File = File(ctx.externalMediaDirs.first(), "CineWright").apply { mkdirs() }
    val clipBytes get() = clipFile?.length() ?: rawBytes

    init {
        // LUT pack for grading log clips; tiny, so (re)written each launch.
        h.post { luts().forEach { (name, text) -> File(File(outDir, "LUTs").apply { mkdirs() }, name).writeText(text) } }
    }

    private fun luts() = listOf(
        "Rec709gamut_Log3G10_to_Rec709_G24.cube" to NativeBridge.cubeLut(false),
        "RWG_Log3G10_to_Rec709_G24.cube" to NativeBridge.cubeLut(true),
    )

    @SuppressLint("MissingPermission") // caller only opens after CAMERA is granted
    fun open(lens: Lens) = h.post {
        // surfaceChanged can fire repeatedly; a second openCamera would evict our own first client.
        if (lens == this.lens) return@post // already open or opening
        closeNow()
        val myGen = ++gen
        this.lens = lens
        log8Check = null
        manager.openCamera(lens.id, exec, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { if (myGen != gen) d.close() else { device = d; createSession() } }
            override fun onDisconnected(d: CameraDevice) { d.close(); if (device == d) { device = null; this@CameraController.lens = null } }
            override fun onError(d: CameraDevice, code: Int) { fail("Camera error $code"); d.close(); if (device == d) { device = null; this@CameraController.lens = null } }
        })
    }

    fun close() = h.post { closeNow(); gen++; lens = null }

    fun update(s: Settings) {
        val old = settings
        settings = s
        gl.monitor = s.monitor()
        h.post {
            if (s.mode == Mode.LOG8 && old.mode != Mode.LOG8 && log8Check == null) { log8Samples = 0; curveEchoed = false }
            if (s.sessionGroup != sessionGroup && device != null && !recording()) createSession() else repeat()
        }
    }

    fun toggleRecording() = h.post { if (!recording()) start() else stop() }

    fun stopIfRecording() = h.post { if (recording()) stop() }

    /** Starts a one-shot AF scan at the current focus point. */
    fun triggerAf() = h.post {
        val s = session ?: return@post
        runCatching {
            s.capture(requestBuilder().apply { set(CR.CONTROL_AF_TRIGGER, CR.CONTROL_AF_TRIGGER_START) }.build(), null, resultHandler)
        }
    }

    /** Grey-card calibration: fill the centre with an 18 % card; AE runs briefly, then k = 0.18 / E. */
    fun calibrateK(done: (Float) -> Unit) = h.post {
        if (settings.mode != Mode.LOG10 || recording()) { fail("Calibrate k in Log3G10 10-bit, not while recording"); return@post }
        calibSamples.clear()
        calibAe = true
        busy = "Calibrating k: hold the grey card in the centre…"
        repeat()
        calib = { k -> done(k); busy = null }
    }

    private fun recording() = encoder != null || rawHandle != 0L

    private fun onStats(st: Stats) {
        stats = st
        val s = settings
        if (s.mode == Mode.LOG8 && log8Check == null && ++log8Samples == 12) {
            // A log-encoded frame can't have code values below Log3G10(0) ≈ 0.09 (minus noise).
            log8Check = when {
                !curveEchoed -> "ISP did not report the contrast curve"
                st.p1 < 0.06f -> "ISP ignored the log curve (blacks at %.2f)".format(st.p1)
                else -> ""
            }
        }
        if (calib != null) {
            calibSamples += st.centreHlg
            if (calibSamples.size >= 16) {
                // First half: AE settling. Second half: measurement.
                val e = GlRenderer.hlgInverse(calibSamples.takeLast(8).average().toFloat())
                val cb = calib!!
                calib = null; calibAe = false
                h.post { repeat() }
                if (e > 0.001f) cb((0.18f / e).coerceIn(1f, 20f)) else { busy = null; fail("Grey card too dark to calibrate") }
            }
        }
    }

    /** Preview/GL input + the encoder's persistent surface, a RAW_SENSOR reader, or (Log 10-bit) nothing else. */
    private fun createSession() {
        val d = device ?: return
        val l = lens ?: return
        session?.close(); session = null
        recSurface?.release(); recSurface = null
        rawReader?.close(); rawReader = null
        val s = settings
        sessionGroup = s.sessionGroup
        val token = ++sessionToken
        val outputs = ArrayList<OutputConfiguration>()
        if (s.mode == Mode.LOG10) {
            gl.setInputSize(l.videoSize.width, l.videoSize.height)
            outputs += OutputConfiguration(gl.inputSurface).apply {
                setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC) // = AudioRecord's clock
            }
        } else {
            gl.setInputSize(l.previewSize.width, l.previewSize.height)
            outputs += OutputConfiguration(gl.inputSurface)
            outputs += if (s.mode.isRaw && l.rawSize != null) {
                OutputConfiguration(ImageReader.newInstance(l.rawSize.width, l.rawSize.height, ImageFormat.RAW_SENSOR, 4).also { r ->
                    r.setOnImageAvailableListener(::onRawImage, Handler(rawThread.looper))
                    rawReader = r
                }.surface)
            } else {
                val surf = MediaCodec.createPersistentInputSurface().also { recSurface = it }
                VideoSpec(l.videoSize.width, l.videoSize.height, 30, s.bitrate, true).prime(surf)
                // Encoder PTS and AudioRecord must share a clock: pin video to System.nanoTime.
                OutputConfiguration(surf).apply { setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC) }
            }
        }
        try {
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cs: CameraCaptureSession) {
                        if (token != sessionToken) { cs.close(); return } // superseded by a newer session
                        session = cs; error = null; repeat()
                    }
                    override fun onConfigureFailed(cs: CameraCaptureSession) { if (token == sessionToken) fail("Capture session configuration failed") }
                })
            // Session parameters (fps range etc.) select the sensor mode; without them 60p ran ~50-58 fps.
            config.sessionParameters = requestBuilder().apply { outputs.forEach { o -> o.surface?.let(::addTarget) } }.build()
            d.createCaptureSession(config)
        } catch (e: Exception) { fail("Session failed: ${e.message}") }
    }

    private fun repeat() {
        val s = session ?: return
        try { s.setRepeatingRequest(requestBuilder().build(), captureCallback, resultHandler) } catch (e: Exception) { fail("Request failed: ${e.message}") }
    }

    private fun requestBuilder(): CR.Builder {
        val s = settings
        val l = lens!!
        val c = l.chars
        return device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(gl.inputSurface)
            if (encoder != null && !glEncoding) addTarget(recSurface!!)
            if (rawHandle != 0L) addTarget(rawReader!!.surface)
            val frameNs = 1_000_000_000L / s.fps
            set(CR.CONTROL_AE_TARGET_FPS_RANGE, Range(s.fps, s.fps))
            set(CR.CONTROL_ZOOM_RATIO, s.effectiveZoom)
            if (s.manualExposure && l.manual && !calibAe) {
                set(CR.CONTROL_AE_MODE, CR.CONTROL_AE_MODE_OFF)
                set(CR.SENSOR_SENSITIVITY, s.iso.coerceIn(l.isoRange!!.lower, l.isoRange.upper))
                set(CR.SENSOR_EXPOSURE_TIME, (frameNs * s.shutterAngle / 360f).toLong().coerceIn(l.exposureRange!!.lower, l.exposureRange.upper))
                set(CR.SENSOR_FRAME_DURATION, frameNs)
            } else {
                set(CR.CONTROL_AE_MODE, CR.CONTROL_AE_MODE_ON)
            }
            val fp = s.focusPoint
            when {
                l.minFocusDiopters == 0f -> set(CR.CONTROL_AF_MODE, CR.CONTROL_AF_MODE_OFF)
                fp != null -> {
                    set(CR.CONTROL_AF_MODE, CR.CONTROL_AF_MODE_AUTO)
                    set(CR.CONTROL_AF_REGIONS, arrayOf(focusRegion(c.get(CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!, fp)))
                }
                s.autoFocus -> set(CR.CONTROL_AF_MODE, CR.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                else -> { set(CR.CONTROL_AF_MODE, CR.CONTROL_AF_MODE_OFF); set(CR.LENS_FOCUS_DISTANCE, s.focusDiopters) }
            }
            when (s.wb) {
                WbMode.AUTO -> set(CR.CONTROL_AWB_MODE, CR.CONTROL_AWB_MODE_AUTO)
                WbMode.LOCK -> { set(CR.CONTROL_AWB_MODE, CR.CONTROL_AWB_MODE_AUTO); set(CR.CONTROL_AWB_LOCK, true) }
                WbMode.KELVIN -> kelvinWb(c, s.kelvin)?.let { (g, t) ->
                    set(CR.CONTROL_AWB_MODE, CR.CONTROL_AWB_MODE_OFF)
                    set(CR.COLOR_CORRECTION_MODE, CR.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    set(CR.COLOR_CORRECTION_GAINS, g)
                    set(CR.COLOR_CORRECTION_TRANSFORM, t)
                }
            }
            if (s.mode == Mode.LOG8) {
                val curve = NativeBridge.log3g10TonemapCurve(l.curvePoints.coerceAtMost(256), s.headroomStops)
                set(CR.TONEMAP_MODE, CR.TONEMAP_MODE_CONTRAST_CURVE)
                set(CR.TONEMAP_CURVE, TonemapCurve(curve, curve, curve))
            }
            if (s.isLog) set(CR.EDGE_MODE, CR.EDGE_MODE_OFF) // no sharpening baked into log footage
            if (s.mode.isRaw) set(CR.STATISTICS_LENS_SHADING_MAP_MODE, CR.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            if (s.fps > 30) for (k in fpsThrottleKeys(c)) set(k, 0.toByte())
        }
    }

    /** Pixel vendor tags that silently halve 60p to 30p; only set when the HAL exposes them to apps. */
    private fun fpsThrottleKeys(c: CC): List<CR.Key<Byte>> {
        val names = setOf("com.google.internal.Video60to30FPSThermalThrottle", "com.google.internal.VideoFpsThrottle")
        return c.availableCaptureRequestKeys.filter { it.name in names }.map { CR.Key(it.name, Byte::class.javaObjectType) }
    }

    /** Tap point (normalised, landscape viewfinder showing a centred 16:9 crop of the active array). */
    private fun focusRegion(active: Rect, p: Pair<Float, Float>): MeteringRectangle {
        val w = active.width()
        val ch = w * 9 / 16
        val y0 = (active.height() - ch) / 2
        val x = (p.first * w).toInt()
        val y = y0 + (p.second * ch).toInt()
        val r = w / 20
        return MeteringRectangle((x - r).coerceIn(0, w - 2 * r), (y - r).coerceIn(0, active.height() - 2 * r), 2 * r, 2 * r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun kelvinWb(c: CC, kelvin: Int): Pair<RggbChannelVector, ColorSpaceTransform>? {
        val keys = listOf(CC.SENSOR_COLOR_TRANSFORM1, CC.SENSOR_COLOR_TRANSFORM2, CC.SENSOR_FORWARD_MATRIX1, CC.SENSOR_FORWARD_MATRIX2)
        val m = keys.map { c.get(it) ?: return null }.flatMap { t -> List(9) { t.getElement(it % 3, it / 3).toFloat() } }.toFloatArray()
        val r = NativeBridge.whiteBalance(kelvin.toFloat(), m, c.get(CC.SENSOR_REFERENCE_ILLUMINANT1) ?: 17, c.get(CC.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 21)
        val gains = RggbChannelVector(r[0], r[1], r[1], r[2])
        val t = ColorSpaceTransform(Array(9) { Rational((r[3 + it] * 10000).toInt(), 10000) })
        return gains to t
    }

    // ---- RAW frame/metadata delivery -------------------------------------------------------------

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(cs: CameraCaptureSession, r: CR, res: TotalCaptureResult) {
            res.get(RS.SENSOR_NOISE_PROFILE)?.firstOrNull()?.let { lastNoise = doubleArrayOf(it.first, it.second) }
            if (res.get(RS.TONEMAP_MODE) == RS.TONEMAP_MODE_CONTRAST_CURVE) curveEchoed = true
            synchronized(rawLock) {
                if (rawHandle == 0L) return
                val ts = res.get(RS.SENSOR_TIMESTAMP) ?: return
                val g = res.get(RS.COLOR_CORRECTION_GAINS)
                val map = res.get(RS.STATISTICS_LENS_SHADING_CORRECTION_MAP)
                val shading = map?.let { FloatArray(it.gainFactorCount).also { a -> it.copyGainFactors(a, 0) } }
                NativeBridge.rawMeta(
                    rawHandle, ts, res.get(RS.SENSOR_EXPOSURE_TIME) ?: 0L, res.get(RS.SENSOR_SENSITIVITY) ?: 0,
                    res.get(RS.SENSOR_DYNAMIC_BLACK_LEVEL) ?: staticBlack(),
                    res.get(RS.SENSOR_DYNAMIC_WHITE_LEVEL) ?: 0,
                    if (g != null) floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue) else floatArrayOf(1f, 1f, 1f, 1f),
                    shading, map?.rowCount ?: 0, map?.columnCount ?: 0,
                )
            }
        }
    }

    private fun onRawImage(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        img.use {
            synchronized(rawLock) {
                if (rawHandle != 0L) NativeBridge.rawFrame(rawHandle, it.timestamp, it.planes[0].buffer, it.planes[0].rowStride)
            }
        }
    }

    private fun staticBlack(): FloatArray {
        val p = lens!!.chars.get(CC.SENSOR_BLACK_LEVEL_PATTERN) ?: return FloatArray(4)
        return FloatArray(4) { p.getOffsetForIndex(it % 2, it / 2).toFloat() }
    }

    // ---- Start / stop ------------------------------------------------------------------------

    private fun start() {
        val l = lens ?: return
        if (session == null) return
        if (ctx.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            fail("Phone too hot to record (thermal status severe). Let it cool down.")
            return
        }
        if (lowOnSpace()) { fail("Storage below the 10% free-space floor"); return }
        val s = settings
        val name = nextClipName()
        val audio = if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) s.audioSource else null
        try {
            if (s.mode.isRaw) startRaw(l, s, name, audio) else startVideo(l, s, name, audio)
            clipSettings = s
            recordingSince = SystemClock.elapsedRealtime()
            writeSidecar(s, l, null)
            dropWindow.clear()
            h.removeCallbacks(watchTask)
            h.postDelayed(watchTask, 1000)
        } catch (e: Exception) {
            if (glEncoding) gl.detachEncoder()
            glEncoding = false
            encoder?.stop(); encoder = null
            synchronized(rawLock) { if (rawHandle != 0L) NativeBridge.rawClose(rawHandle); rawHandle = 0 }
            wav?.stop(); wav = null
            clipFile = null; clipDir = null
            repeat()
            fail("Could not start recording: ${e.message}")
        }
    }

    private fun startVideo(l: Lens, s: Settings, name: String, audio: Int?) {
        val file = File(outDir, "$name.mp4")
        val (w, ht) = l.videoSize.width to l.videoSize.height
        if (s.mode == Mode.LOG10) {
            // GL renders upright landscape frames, so no orientation hint; RWG primaries -> "unspecified".
            val enc = Encoder(file, VideoSpec(w, ht, s.fps, s.bitrate, hevc = true, tenBit = true, fullRange = s.fullRange),
                0, null, audio, vuiPrimaries = 2, clockNs = System::nanoTime)
            encoder = enc
            gl.attachEncoder(enc.inputSurface, w, ht, s.fps)
            glEncoding = true
        } else {
            encoder = Encoder(file, VideoSpec(w, ht, s.fps, s.bitrate, s.hevc), l.orientationHint, recSurface!!, audio,
                vuiPrimaries = if (s.mode == Mode.LOG8) 1 else null, clockNs = System::nanoTime)
        }
        clipFile = file
        repeat()
    }

    private fun startRaw(l: Lens, s: Settings, name: String, audio: Int?) {
        val c = l.chars
        val dir = File(outDir, name).apply { mkdirs() }
        fun m(t: ColorSpaceTransform?) = FloatArray(9) { i -> t?.getElement(i % 3, i / 3)?.toFloat() ?: Float.NaN }
        val matrices = listOf(CC.SENSOR_COLOR_TRANSFORM1, CC.SENSOR_COLOR_TRANSFORM2, CC.SENSOR_FORWARD_MATRIX1,
            CC.SENSOR_FORWARD_MATRIX2, CC.SENSOR_CALIBRATION_TRANSFORM1, CC.SENSOR_CALIBRATION_TRANSFORM2)
            .flatMap { m(c.get(it)).toList() }.toFloatArray()
        val tod = LocalTime.now()
        val handle = NativeBridge.rawOpen(
            dir.path, name, l.rawSize!!.width, l.rawSize.height, s.mode == Mode.RAW_LITE, s.fps,
            // Pixel 11 Pro (warm): ~110 Mpx/s per LJ92 thread; full-res needs 225 (24p) or 281 (30p) Mpx/s.
            threads = when { s.mode == Mode.RAW_LITE -> 3; s.fps > 25 -> 6; else -> 4 }, slots = 12,
            cfa = c.get(CC.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0,
            white = c.get(CC.SENSOR_INFO_WHITE_LEVEL) ?: 1023,
            illuminants = intArrayOf(c.get(CC.SENSOR_REFERENCE_ILLUMINANT1) ?: 21, c.get(CC.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 17),
            matrices = matrices, noise = lastNoise,
            make = Build.MANUFACTURER, model = Build.MODEL, reel = "A001",
            fNumber = c.get(CC.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f, focalMm = l.focalMm,
            timecodeStartFrames = tod.toSecondOfDay().toLong() * s.fps + tod.nano.toLong() * s.fps / 1_000_000_000,
        )
        check(handle != 0L) { "RAW writer could not open ${dir.path}" }
        synchronized(rawLock) { rawHandle = handle }
        clipDir = dir
        rawBytes = 0; rawDropped = 0
        if (audio != null) wav = WavRecorder(File(dir, "$name.wav"), audio)
        repeat()
    }

    private val watchTask = Runnable { watch() }

    private fun lowOnSpace() = outDir.usableSpace < outDir.totalSpace / 10

    /** Once a second while recording: free-space floor, and RAW back-pressure (stop if >1% of frames
     *  in any 5 s window were dropped). */
    private fun watch() {
        if (!recording()) return
        if (lowOnSpace()) { fail("Stopped: storage reached the 10% free-space floor"); stop(); return }
        val handle = rawHandle.takeIf { it != 0L } ?: run { h.postDelayed(watchTask, 1000); return }
        val st = NativeBridge.rawStats(handle)
        rawBytes = st[2]; rawDropped = st[1]
        dropWindow.addLast(st)
        if (dropWindow.size > 6) dropWindow.removeFirst()
        val first = dropWindow.first()
        val d = st[1] - first[1]
        val total = (st[0] - first[0]) + d
        if (dropWindow.size == 6 && total > 0 && d * 100 > total) {
            fail("Stopped: $d of $total frames dropped in 5 s (writer fell behind: try RAW Lite or a lower fps)")
            stop()
            return
        }
        h.postDelayed(watchTask, 1000)
    }

    private fun stop() {
        val l = lens ?: return
        val s = clipSettings ?: settings
        val secs = (SystemClock.elapsedRealtime() - (recordingSince ?: 0)) / 1000
        val enc = encoder
        val handle = rawHandle
        // Stop feeding the recorder first so the final frames are complete.
        encoder = null
        synchronized(rawLock) { rawHandle = 0 }
        if (glEncoding) { gl.detachEncoder(); glEncoding = false }
        repeat()
        recordingSince = null
        if (enc != null) {
            val file = clipFile!!
            if (!enc.stop()) fail(enc.error ?: "Clip had no frames")
            val extra = JSONObject().put("cbr", enc.cbr).put("vuiTransferUnspecified", enc.vuiPatched ?: JSONObject.NULL)
            writeSidecar(s, l, JSONObject().put("durationSec", secs).put("encoder", extra))
            MediaScannerConnection.scanFile(ctx, arrayOf(file.path), null, null)
            lastClip = ClipSummary(file.nameWithoutExtension, secs, file.length(), log = s.isLog)
        } else if (handle != 0L) {
            wav?.stop()
            val r = NativeBridge.rawClose(handle)
            val crop = CapabilityProbe.rawCrop(l.rawSize!!, s.mode == Mode.RAW_LITE)
            writeSidecar(s, l, JSONObject().put("durationSec", secs).put("framesWritten", r[0]).put("framesDropped", r[1])
                .put("bytes", r[2]).put("droppedFrameIndices", JSONArray(r.drop(5)))
                .put("dropReasons", JSONObject().put("noFreeSlot", r[3]).put("metadataLate", r[4]))
                .put("wavBits", wav?.bits ?: JSONObject.NULL).put("output", "${crop.width}x${crop.height}"))
            lastClip = ClipSummary(clipDir!!.name, secs, r[2], r[1])
            rawDropped = r[1]
            wav = null
        }
        clipFile = null
        clipDir = null
        clipSettings = null
    }

    /** Sidecar is written at start (so a killed app still leaves one) and rewritten with results at stop. */
    private fun writeSidecar(s: Settings, l: Lens, result: JSONObject?) {
        val (file, base) = clipFile?.let { File(it.parentFile, "${it.nameWithoutExtension}.json") to it.nameWithoutExtension }
            ?: clipDir!!.let { File(it, "${it.name}.json") to it.name }
        val j = JSONObject()
            .put("clip", base)
            .put("mode", s.mode.name)
            .put("fps", s.fps)
            .put("curve", when { s.isLog -> "Log3G10 v3"; s.mode.isRaw -> "linear (sensor)"; else -> "ISP default" })
            .put("gamut", when (s.mode) { Mode.LOG10 -> "REDWideGamutRGB"; Mode.LOG8 -> "Rec.709"; Mode.RAW, Mode.RAW_LITE -> "camera native (DNG matrices)"; else -> "Rec.709 (ISP)" })
            .put("lut", when (s.mode) { Mode.LOG10 -> "LUTs/RWG_Log3G10_to_Rec709_G24.cube"; Mode.LOG8 -> "LUTs/Rec709gamut_Log3G10_to_Rec709_G24.cube"; else -> JSONObject.NULL })
            .put("exposure", if (s.manualExposure) JSONObject().put("iso", s.iso).put("shutterAngle", s.shutterAngle) else "auto")
            .put("whiteBalance", when (s.wb) { WbMode.AUTO -> "auto"; WbMode.LOCK -> "auto, locked"; WbMode.KELVIN -> "${s.kelvin} K" })
            .put("focus", when { s.focusPoint != null -> "tap AF"; s.autoFocus -> "continuous AF"; else -> "manual ${s.focusDiopters} dpt" })
            .put("lens", JSONObject().put("cameraId", l.id).put("focalMm", l.focalMm).put("zoom", s.effectiveZoom))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("build", Build.FINGERPRINT)
            .put("complete", result != null)
        if (s.mode == Mode.LOG8) j.put("headroomStops", s.headroomStops).put("ispScale_s", 0.18 * Math.pow(2.0, s.headroomStops.toDouble()))
        if (s.mode == Mode.LOG10) j.put("k", s.k).put("source", "HLG10 (BT.2100) → linear × k → RWG → Log3G10")
            .put("matrix", "BT.2020 NCL").put("range", if (s.fullRange) "full" else "limited")
        if (!s.mode.isRaw) j.put("codec", if (s.hevc || s.mode == Mode.LOG10) "HEVC" else "AVC").put("bitrate", s.bitrate)
            .put("bitDepth", if (s.mode == Mode.LOG10) 10 else 8).put("resolution", "${l.videoSize.width}x${l.videoSize.height}")
        if (s.mode.isRaw) j.put("crop", "centre 16:9").put("binning", if (s.mode == Mode.RAW_LITE) "2x2 same-colour average" else "none")
        result?.keys()?.forEach { j.put(it, result.get(it)) }
        file.writeText(j.toString(2))
    }

    private fun nextClipName(): String {
        val prefs = ctx.getSharedPreferences("clips", Context.MODE_PRIVATE)
        val n = prefs.getInt("clip", 0) + 1
        prefs.edit().putInt("clip", n).apply()
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "A001_C%04d_%s".format(n, date)
    }

    // ---- Settings-screen actions ------------------------------------------------------------------

    /** Copies the LUT pack to Downloads/CineWright (visible to other apps and desktop transfers). */
    fun exportLuts(done: (String) -> Unit) = h.post {
        val r = runCatching {
            luts().forEach { (name, text) ->
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CineWright")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            }
        }
        done(r.fold({ "LUTs exported to Downloads/CineWright" }, { "LUT export failed: ${it.message}" }))
    }

    /** Pre-flight write test (spec: 2 GB): sequential write + fsync, reported against the RAW requirement. */
    fun storageTest(done: (String) -> Unit) {
        if (recording()) { done("Stop recording first"); return }
        busy = "Writing 2 GB test file…"
        Thread {
            val f = File(outDir, ".preflight.tmp")
            val r = runCatching {
                val buf = ByteArray(8 shl 20) { it.toByte() }
                val t0 = SystemClock.elapsedRealtimeNanos()
                FileOutputStream(f).use { out -> for (i in 0 until 256) out.write(buf); out.fd.sync() }
                val mbps = 2048.0 / ((SystemClock.elapsedRealtimeNanos() - t0) / 1e9)
                val need = 105 // measured full RAW 24 fps on Pixel 11 Pro, MB/s
                "Storage: %.0f MB/s sustained write (full RAW 24 fps needs ~%d MB/s) — %s".format(mbps, need, if (mbps > need * 1.5) "OK" else "too slow for full RAW")
            }
            f.delete()
            busy = null
            done(r.getOrElse { "Storage test failed: ${it.message}" })
        }.start()
    }

    private fun closeNow() {
        if (recording()) stop()
        session?.close(); session = null
        sessionGroup = -1
        device?.close(); device = null
        recSurface?.release(); recSurface = null
        rawReader?.close(); rawReader = null
    }

    private fun fail(msg: String) {
        Log.e("CineWright", msg)
        error = msg
    }
}
