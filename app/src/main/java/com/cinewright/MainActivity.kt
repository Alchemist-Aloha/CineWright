package com.cinewright

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var camera: CameraController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        camera = CameraController(this)
        val lenses = CapabilityProbe.probe(getSystemService(CameraManager::class.java))
        val power = getSystemService(PowerManager::class.java)
        power.addThermalStatusListener(mainExecutor) { if (it >= PowerManager.THERMAL_STATUS_SEVERE) camera.stopIfRecording() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) { App(camera, lenses) }
            }
        }
    }

    override fun onPause() {
        camera.stopIfRecording() // finalize the file before the surface goes away
        super.onPause()
    }
}

private val SHUTTER_ANGLES = listOf(45f, 90f, 172.8f, 180f, 270f, 360f)

@Composable
private fun App(camera: CameraController, lenses: List<Lens>) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[Manifest.permission.CAMERA] == true
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    when {
        lenses.isEmpty() -> Centered("No back camera supports 1080p30 or 4K30 recording.")
        !granted -> Centered("CineWright needs camera access.") { Button({ launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("Grant") } }
        else -> Recorder(camera, lenses)
    }
}

@Composable
private fun Centered(msg: String, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg); Spacer(Modifier.height(12.dp)); extra()
    }
}

/** A mode is selectable if the probe passed and (for Log 8-bit) the runtime curve check didn't fail. */
private fun ModeSupport.usable(camera: CameraController, gl10: Boolean) = when {
    !ok -> false
    mode == Mode.LOG8 -> camera.log8Check.isNullOrEmpty()
    mode == Mode.LOG10 -> gl10
    else -> true
}

private fun ModeSupport.why(camera: CameraController, gl10: Boolean) = when {
    mode == Mode.LOG8 && !camera.log8Check.isNullOrEmpty() -> camera.log8Check!!
    mode == Mode.LOG10 && ok && !gl10 -> "No 10-bit GPU output (RGBA1010102)"
    else -> reason
}

@Composable
private fun Recorder(camera: CameraController, lenses: List<Lens>) {
    val ctx = LocalContext.current
    var lens by remember { mutableStateOf(lenses.first()) }
    var s by remember { mutableStateOf(Settings.load(ctx).let { if (lens.supports(it.mode)) it else it.copy(mode = Mode.STANDARD) }
        .let { it.copy(fps = minOf(it.fps, it.maxFps(lens))) }) }
    var showSettings by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val recording = camera.recordingSince != null
    val gl10 = camera.gl.tenBit
    LaunchedEffect(s) { camera.update(s); Settings.save(ctx, s) }
    // The runtime Log 8-bit check failed: fall back so we never record a mode the ISP ignores.
    LaunchedEffect(camera.log8Check) { if (!camera.log8Check.isNullOrEmpty() && s.mode == Mode.LOG8) s = s.copy(mode = Mode.STANDARD) }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Viewfinder(camera, lens, s) { x, y -> s = s.copy(focusPoint = x to y); camera.triggerAf() }
            StatusBar(camera, s, lens, Modifier.align(Alignment.TopStart))
            if (s.histogram) camera.stats?.let { Histogram(it, Modifier.align(Alignment.BottomStart).padding(8.dp)) }
            (camera.busy ?: notice)?.let {
                Text(it, Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC000000)).padding(8.dp), fontSize = 13.sp)
            }
        }
        Column(Modifier.width(330.dp).fillMaxHeight().background(Color(0xFF111111)).padding(12.dp)) {
            // Record stays pinned; only the settings scroll.
            RecordButton(recording) { camera.toggleRecording() }
            camera.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            camera.lastClip?.let { c ->
                Text("Last: ${c.name} · ${c.seconds}s · ${c.bytes / 1_000_000} MB" + if (c.dropped > 0) " · ${c.dropped} dropped" else "", fontSize = 12.sp)
                if (c.log) TextButton({ camera.exportLuts { notice = it } }) { Text("Copy LUT to Downloads") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Lens")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lenses.forEach { l ->
                        FilterChip(lens.id == l.id, { lens = l; if (!l.supports(s.mode)) s = s.copy(mode = Mode.STANDARD) }, { Text("%.1f mm".format(l.focalMm)) }, enabled = !recording)
                    }
                }
                if (lens.zoomStops.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lens.zoomStops.forEach { z ->
                        FilterChip(s.effectiveZoom == z, { s = s.copy(zoom = z) }, { Text(if (z < 1f) "%.1f×".format(z) else "%.0f×".format(z)) }, enabled = !s.mode.isRaw)
                    }
                }

                Label("Mode")
                lens.modes.forEach { m ->
                    val usable = m.usable(camera, gl10)
                    Row(
                        Modifier.fillMaxWidth().selectable(s.mode == m.mode, enabled = usable && !recording, role = Role.RadioButton) {
                            s = s.copy(mode = m.mode).let { it.copy(fps = minOf(it.fps, it.maxFps(lens))) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(s.mode == m.mode, null, enabled = usable && !recording)
                        Column {
                            Text(m.mode.label, color = if (usable) Color.White else Color.Gray)
                            Text(m.why(camera, gl10), fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }

                Label("Format")
                val codecFixed = s.mode.isRaw || s.mode == Mode.LOG10
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(s.hevc || s.mode == Mode.LOG10, { s = s.copy(hevc = true) }, { Text("H.265") }, enabled = !recording && !codecFixed)
                    FilterChip(!s.hevc && s.mode != Mode.LOG10, { s = s.copy(hevc = false) }, { Text("H.264") }, enabled = !recording && !codecFixed)
                }
                if (s.mode == Mode.LOG10 && lens.videoMaxFps > 30) Text("60p: use Log 8-bit (the camera's 10-bit HDR stream can't sustain 4K60)", fontSize = 11.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(24, 25, 30, 60).forEach { f ->
                        val ok = f <= s.maxFps(lens)
                        FilterChip(s.fps == f, { s = s.copy(fps = f) }, { Text("$f") }, enabled = !recording && ok)
                    }
                }

                Label("Exposure")
                if (s.mode == Mode.STANDARD) Toggle("Auto exposure", s.autoExposure, lens.manual) { s = s.copy(autoExposure = it) }
                if (s.manualExposure && lens.manual) {
                    val iso = lens.isoRange!!
                    Text("ISO ${s.iso}", fontSize = 13.sp)
                    Slider(s.iso.toFloat(), { s = s.copy(iso = it.toInt()) }, valueRange = iso.lower.toFloat()..iso.upper.toFloat(),
                        modifier = Modifier.semantics { contentDescription = "ISO" })
                    Text("Shutter ${s.shutterAngle}°", fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SHUTTER_ANGLES.forEach { a -> FilterChip(s.shutterAngle == a, { s = s.copy(shutterAngle = a) }, { Text("%.0f".format(a), fontSize = 11.sp) }) }
                    }
                }

                Label("Focus")
                Toggle("Continuous AF", s.autoFocus && s.focusPoint == null, lens.minFocusDiopters > 0f) { s = s.copy(autoFocus = it, focusPoint = null) }
                if (s.focusPoint != null) Text("Tap-focused · tap the viewfinder again to refocus", fontSize = 12.sp, color = Color.Gray)
                else if (lens.minFocusDiopters > 0f) Text("Tap the viewfinder to focus there", fontSize = 12.sp, color = Color.Gray)
                if (!s.autoFocus && s.focusPoint == null && lens.minFocusDiopters > 0f) {
                    Text(if (s.focusDiopters == 0f) "∞" else "%.2f m".format(1f / s.focusDiopters), fontSize = 13.sp)
                    Slider(s.focusDiopters, { s = s.copy(focusDiopters = it) }, valueRange = 0f..lens.minFocusDiopters,
                        modifier = Modifier.semantics { contentDescription = "Focus distance" })
                }

                Label("White balance")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WbMode.entries.forEach { m -> FilterChip(s.wb == m, { s = s.copy(wb = m) }, { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                if (s.wb == WbMode.KELVIN) {
                    Text("${s.kelvin} K", fontSize = 13.sp)
                    Slider(s.kelvin.toFloat(), { s = s.copy(kelvin = (it / 50).toInt() * 50) }, valueRange = 2500f..10000f,
                        modifier = Modifier.semantics { contentDescription = "White balance Kelvin" })
                }
                var oneShot by remember { mutableStateOf(false) }
                LaunchedEffect(oneShot) { if (oneShot) { delay(1500); s = s.copy(wb = WbMode.LOCK); oneShot = false } }
                OutlinedButton({ s = s.copy(wb = WbMode.AUTO); oneShot = true }, enabled = !oneShot) { Text(if (oneShot) "Measuring…" else "One-shot auto WB") }

                Label("Monitoring")
                if (s.isLog) Toggle("Log view (recorded signal)", s.logView, true) { s = s.copy(logView = it) }
                Toggle("Zebras", s.zebra, true) { s = s.copy(zebra = it) }
                Toggle("False colour", s.falseColor, true) { s = s.copy(falseColor = it) }
                Toggle("Histogram", s.histogram, true) { s = s.copy(histogram = it) }

                OutlinedButton({ showSettings = true }, Modifier.fillMaxWidth(), enabled = !recording) { Text("Settings") }
                Text("Clips & LUTs: Android/media/com.cinewright/CineWright", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
    if (showSettings) SettingsDialog(camera, s, { s = it }, { notice = it }) { showSettings = false }
}

@Composable
private fun SettingsDialog(camera: CameraController, s: Settings, set: (Settings) -> Unit, notify: (String) -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    val unprocessed = remember { ctx.getSystemService(AudioManager::class.java).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true" }
    // Plain Dialog: AlertDialog squeezes its text slot in landscape so long content can't scroll.
    androidx.compose.ui.window.Dialog(close, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(560.dp).fillMaxHeight(0.95f), shape = MaterialTheme.shapes.large, color = Color(0xFF1E1E1E), contentColor = Color.White) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", fontSize = 22.sp, modifier = Modifier.weight(1f))
                    TextButton(close) { Text("Done") }
                }
                Label("Bitrate (Mbps)")
                BitrateRow("Standard", s.mbpsStd, listOf(50, 80, 120)) { set(s.copy(mbpsStd = it)) }
                BitrateRow("Log 8-bit", s.mbpsLog8, listOf(75, 100, 150)) { set(s.copy(mbpsLog8 = it)) }
                BitrateRow("Log 10-bit", s.mbpsLog10, listOf(100, 150, 200)) { set(s.copy(mbpsLog10 = it)) }

                Label("Log")
                Text("8-bit highlight headroom H: ${"%.1f".format(s.headroomStops)} stops", fontSize = 13.sp)
                Slider(s.headroomStops, { set(s.copy(headroomStops = it)) }, valueRange = 2f..6f, steps = 7,
                    modifier = Modifier.semantics { contentDescription = "Highlight headroom" })
                Toggle("10-bit full range (default limited)", s.fullRange, true) { set(s.copy(fullRange = it)) }
                Text("10-bit exposure scale k = ${"%.2f".format(s.k)}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ close(); camera.calibrateK { k -> set(s.copy(k = k)); notify("k calibrated: %.2f".format(k)) } }, enabled = s.mode == Mode.LOG10) { Text("Calibrate (grey card)") }
                    TextButton({ set(s.copy(k = 3.74f)) }) { Text("Reset") }
                }
                if (s.mode != Mode.LOG10) Text("Switch to Log 10-bit to calibrate k.", fontSize = 11.sp, color = Color.Gray)

                Label("Zebras")
                Text("Log: ${"%.2f".format(s.zebraLog)} code value", fontSize = 13.sp)
                Slider(s.zebraLog, { set(s.copy(zebraLog = it)) }, valueRange = 0.4f..0.9f, modifier = Modifier.semantics { contentDescription = "Log zebra level" })
                Text("Standard: ${(s.zebraStd * 100).toInt()} %", fontSize = 13.sp)
                Slider(s.zebraStd, { set(s.copy(zebraStd = it)) }, valueRange = 0.7f..1f, modifier = Modifier.semantics { contentDescription = "Standard zebra level" })

                Label("Audio input")
                val sources = buildList {
                    add(MediaRecorder.AudioSource.CAMCORDER to "Camcorder"); add(MediaRecorder.AudioSource.MIC to "Mic")
                    if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED to "Unprocessed")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sources.forEach { (v, n) -> FilterChip(s.audioSource == v, { set(s.copy(audioSource = v)) }, { Text(n) }) }
                }

                Label("Output")
                Text(camera.outDir.path, fontSize = 12.sp, color = Color.Gray)
                OutlinedButton({ camera.exportLuts(notify) }) { Text("Export LUT pack to Downloads") }
                OutlinedButton({ close(); camera.storageTest(notify) }) { Text("Test storage speed (writes 2 GB)") }
            }
        }
    }
}

@Composable
private fun BitrateRow(label: String, value: Int, options: List<Int>, set: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        options.forEach { o -> FilterChip(value == o, { set(o) }, { Text("$o") }) }
    }
}

@Composable
private fun Viewfinder(camera: CameraController, lens: Lens, s: Settings, onTap: (Float, Float) -> Unit) {
    val current by rememberUpdatedState(lens)
    Box(Modifier.aspectRatio(16f / 9f)) {
        AndroidView(
            factory = { c ->
                SurfaceView(c).apply {
                    if (camera.gl.tenBit) holder.setFormat(PixelFormat.RGBA_1010102)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {}
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                            camera.gl.setPreview(h.surface, w, hh)
                            camera.open(current)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) { camera.close(); camera.gl.setPreview(null) }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { o -> onTap(o.x / size.width, o.y / size.height) }
        })
        s.focusPoint?.let { (x, y) ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(Modifier.offset(maxWidth * x - 24.dp, maxHeight * y - 24.dp).size(48.dp).border(2.dp, Color(0xFFFFD54F)))
            }
        }
    }
    LaunchedEffect(lens) { camera.open(lens) }
}

@Composable
private fun Histogram(st: Stats, modifier: Modifier) {
    Canvas(modifier.size(192.dp, 64.dp).background(Color(0x99000000))) {
        val bw = size.width / 256f
        st.hist.forEachIndexed { i, v ->
            val hgt = v * size.height
            drawRect(Color(0xCCFFFFFF), Offset(i * bw, size.height - hgt), Size(bw, hgt))
        }
    }
}

@Composable
private fun StatusBar(camera: CameraController, s: Settings, lens: Lens, modifier: Modifier) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    val since = camera.recordingSince
    val elapsed = since?.let { (now - it) / 1000 } ?: 0
    // Measured write rate while recording, nominal rate otherwise.
    val nominal = if (s.mode.isRaw && lens.rawSize != null) {
        // Planning estimate: 16-bit container, ~1.7:1 LJ92 (spec table); measured rate replaces it while recording.
        CapabilityProbe.rawCrop(lens.rawSize, s.mode == Mode.RAW_LITE).let { it.width * it.height * 2.0 * s.fps / 1.7 }
    } else (s.bitrate + 256_000) / 8.0
    val bytesPerSec = if (since != null && elapsed > 2) camera.clipBytes.toDouble() / elapsed else nominal
    // Everything below the 10 % floor is off-limits.
    val usable = camera.outDir.usableSpace - camera.outDir.totalSpace / 10
    val minutesLeft = (usable.coerceAtLeast(0) / bytesPerSec / 60).toInt()
    val thermal = ctx.getSystemService(PowerManager::class.java).currentThermalStatus
    val battery = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    Row(modifier.padding(8.dp).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val tc = "%02d:%02d".format(elapsed / 60, elapsed % 60)
        Text(if (since != null) "● REC $tc" else tc, color = if (since != null) Color.Red else Color.White, fontFamily = FontFamily.Monospace)
        Text("$minutesLeft min left", fontFamily = FontFamily.Monospace)
        Text("Thermal ${THERMAL[thermal] ?: thermal}", color = if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) Color(0xFFFFA000) else Color.White)
        Text("Bat $battery%")
        if (s.mode.isRaw && since != null) Text("Dropped ${camera.rawDropped}", color = if (camera.rawDropped > 0) Color(0xFFFFA000) else Color.White)
    }
}

private val THERMAL = mapOf(0 to "ok", 1 to "light", 2 to "moderate", 3 to "severe", 4 to "critical", 5 to "emergency", 6 to "shutdown")

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    Button(
        onClick, Modifier.fillMaxWidth().height(56.dp), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFFB00020) else Color(0xFF333333), contentColor = Color.White),
    ) { Text(if (recording) "■ Stop" else "● Record", fontSize = 18.sp) }
}

@Composable
private fun Label(t: String) = Text(t.uppercase(), fontSize = 11.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = if (enabled) Color.White else Color.Gray, modifier = Modifier.weight(1f))
        Switch(value, onChange, enabled = enabled)
    }
}
