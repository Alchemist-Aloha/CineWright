package com.cinewright

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30 as GL
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlin.math.exp
import kotlin.math.max

/** What the camera feeds the SurfaceTexture. */
enum class InputKind { SDR, LOG709, HLG }

data class Monitor(
    val input: InputKind = InputKind.SDR,
    val logView: Boolean = false,
    val zebra: Boolean = false,
    /** Zebra / clip threshold in recorded-signal code value. */
    val zebraLevel: Float = 0.95f,
    val falseColor: Boolean = false,
    /** Mid-grey code value of the recorded signal, for false colour. */
    val grey: Float = 0.43f,
    /** HLG scene-linear scale (spec: k ≈ 3.74, maps BT.2408 38 % to 0.18). */
    val k: Float = 3.74f,
)

/** 256-bin luma histogram of the recorded signal, its 1st percentile, and centre HLG signal (for k calibration). */
class Stats(val hist: FloatArray, val p1: Float, val centreHlg: Float)

/**
 * GL stage between the camera and the screen/encoder. Owns an EGL context on its own thread,
 * a SurfaceTexture the camera writes to, and renders: the preview (Rec.709 or log view + overlays),
 * the Log3G10 10-bit signal into the encoder surface, and a small stats target read back for the UI.
 */
class GlRenderer(private val onStats: (Stats) -> Unit) {
    private val thread = HandlerThread("gl").apply { start() }
    private val h = Handler(thread.looper)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private lateinit var config: EGLConfig
    private lateinit var context: android.opengl.EGLContext
    private lateinit var pbuffer: EGLSurface
    private var program = 0
    private var tex = 0
    private lateinit var st: SurfaceTexture
    lateinit var inputSurface: Surface; private set
    var tenBit = false; private set

    private var preview: EGLSurface? = null
    private var previewSize = 0 to 0
    private var encoder: EGLSurface? = null
    private var encoderSize = 0 to 0
    private var encoderFps = 30
    private var statsFbo = 0
    private val statsBuf = ByteBuffer.allocateDirect(STATS_W * STATS_H * 4).order(ByteOrder.nativeOrder())
    private var frame = 0L
    private val texM = FloatArray(16)

    @Volatile var monitor = Monitor()

    init { sync { setup() } }

    private fun sync(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        h.post { try { block() } catch (t: Throwable) { err = t } finally { latch.countDown() } }
        latch.await()
        err?.let { throw it }
    }

    private fun chooseConfig(bits: Int): EGLConfig? {
        val alpha = if (bits == 10) 2 else 8
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits, EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val out = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        return if (EGL14.eglChooseConfig(display, attrs, 0, out, 0, 1, n, 0) && n[0] > 0) out[0] else null
    }

    private fun setup() {
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val c10 = chooseConfig(10)
        tenBit = c10 != null
        config = c10 ?: chooseConfig(8) ?: error("No EGL config")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = link(VERTEX, FRAGMENT)
        val t = IntArray(1)
        GL.glGenTextures(1, t, 0)
        tex = t[0]
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        st = SurfaceTexture(tex)
        st.setOnFrameAvailableListener({ draw() }, h)
        inputSurface = Surface(st)

        // Stats target: small RGBA8 FBO read back every few frames.
        val fb = IntArray(1); val rb = IntArray(1)
        GL.glGenFramebuffers(1, fb, 0); GL.glGenRenderbuffers(1, rb, 0)
        GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, rb[0])
        GL.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_RGBA8, STATS_W, STATS_H)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fb[0])
        GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_RENDERBUFFER, rb[0])
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        statsFbo = fb[0]
    }

    /** Camera buffer size for the next session. */
    fun setInputSize(w: Int, hgt: Int) = sync { st.setDefaultBufferSize(w, hgt) }

    fun setPreview(surface: Surface?, w: Int = 0, hgt: Int = 0) = sync {
        preview?.let { EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context); EGL14.eglDestroySurface(display, it) }
        preview = surface?.let { EGL14.eglCreateWindowSurface(display, config, it, intArrayOf(EGL14.EGL_NONE), 0) }
        preview?.let { p ->
            // Don't block the render thread on display vsync; the encoder must get every camera frame.
            EGL14.eglMakeCurrent(display, p, p, context); EGL14.eglSwapInterval(display, 0)
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        }
        previewSize = w to hgt
    }

    fun attachEncoder(surface: Surface, w: Int, hgt: Int, fps: Int) = sync {
        encoderFps = fps
        encoder = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        encoderSize = w to hgt
    }

    fun detachEncoder() = sync {
        encoder?.let { EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context); EGL14.eglDestroySurface(display, it) }
        encoder = null
    }

    // Timing (ns) for the GL stage, logged every 120 frames while encoding.
    private var tEnc = 0L; private var tPrev = 0L; private var tAll = 0L; private var nT = 0; private var lastTs = 0L; private var gaps = 0

    private fun draw() {
        val t0 = System.nanoTime()
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        st.updateTexImage()
        if (encoder != null && lastTs != 0L && st.timestamp - lastTs > 1_500_000_000L / encoderFps) gaps++
        lastTs = st.timestamp
        st.getTransformMatrix(texM)
        val m = monitor
        GL.glUseProgram(program)
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(program, "uTexM"), 1, false, texM, 0)
        GL.glUniform1i(GL.glGetUniformLocation(program, "uInput"), m.input.ordinal)
        GL.glUniform1i(GL.glGetUniformLocation(program, "uLogView"), if (m.logView) 1 else 0)
        GL.glUniform1i(GL.glGetUniformLocation(program, "uZebra"), if (m.zebra) 1 else 0)
        GL.glUniform1i(GL.glGetUniformLocation(program, "uFalse"), if (m.falseColor) 1 else 0)
        GL.glUniform1f(GL.glGetUniformLocation(program, "uZebraLevel"), m.zebraLevel)
        GL.glUniform1f(GL.glGetUniformLocation(program, "uGrey"), m.grey)
        GL.glUniform1f(GL.glGetUniformLocation(program, "uK"), m.k)
        GL.glUniformMatrix3fv(GL.glGetUniformLocation(program, "uToRwg"), 1, true, BT2020_TO_RWG, 0)
        GL.glUniformMatrix3fv(GL.glGetUniformLocation(program, "uRwgTo709"), 1, true, RWG_TO_709, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        val out = GL.glGetUniformLocation(program, "uOut")

        val t1 = System.nanoTime()
        encoder?.let { e ->
            EGL14.eglMakeCurrent(display, e, e, context)
            GL.glViewport(0, 0, encoderSize.first, encoderSize.second)
            GL.glUniform1i(out, 1)
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, e, st.timestamp)
            EGL14.eglSwapBuffers(display, e)
        }
        val t2 = System.nanoTime()
        // At 60p the encoder must get every frame; the preview can run at half rate.
        val skipPreview = encoder != null && encoderFps > 30 && frame % 2 == 1L
        if (!skipPreview) preview?.let { p ->
            EGL14.eglMakeCurrent(display, p, p, context)
            GL.glViewport(0, 0, previewSize.first, previewSize.second)
            GL.glUniform1i(out, 0)
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, p)
        }
        val t3 = System.nanoTime()
        if (encoder != null) {
            tEnc += t2 - t1; tPrev += t3 - t2; tAll += t3 - t0
            if (++nT == 120) {
                Log.i("CineWrightGL", "avg ms: encode %.2f preview %.2f frame %.2f; input gaps >1.5 frames: %d".format(tEnc / 120e6, tPrev / 120e6, tAll / 120e6, gaps))
                tEnc = 0; tPrev = 0; tAll = 0; nT = 0; gaps = 0
            }
        }
        // glReadPixels drains the GPU pipeline: at high rates while encoding, read stats far less often.
        if (frame++ % (if (encoder != null && encoderFps > 30) 15 else 4) == 0L) stats(out)
    }

    private fun stats(out: Int) {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, statsFbo)
        GL.glViewport(0, 0, STATS_W, STATS_H)
        GL.glUniform1i(out, 2)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        statsBuf.clear()
        GL.glReadPixels(0, 0, STATS_W, STATS_H, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, statsBuf)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        val hist = FloatArray(256)
        var centre = 0.0; var cn = 0
        for (y in 0 until STATS_H) for (x in 0 until STATS_W) {
            val i = (y * STATS_W + x) * 4
            hist[statsBuf.get(i).toInt() and 0xFF]++
            if (x in STATS_W * 3 / 8 until STATS_W * 5 / 8 && y in STATS_H * 3 / 8 until STATS_H * 5 / 8) { centre += (statsBuf.get(i + 1).toInt() and 0xFF) / 255.0; cn++ }
        }
        var acc = 0f; var p1 = 0
        val n = (STATS_W * STATS_H).toFloat()
        while (p1 < 255 && acc + hist[p1] < n * 0.01f) { acc += hist[p1]; p1++ }
        val peak = max(1f, hist.max())
        onStats(Stats(FloatArray(256) { hist[it] / peak }, p1 / 255f, (centre / max(1, cn)).toFloat()))
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GL.glCreateShader(type).also {
            GL.glShaderSource(it, src); GL.glCompileShader(it)
            val ok = IntArray(1); GL.glGetShaderiv(it, GL.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: ${GL.glGetShaderInfoLog(it)}" }
        }
        return GL.glCreateProgram().also {
            GL.glAttachShader(it, shader(GL.GL_VERTEX_SHADER, vs))
            GL.glAttachShader(it, shader(GL.GL_FRAGMENT_SHADER, fs))
            GL.glLinkProgram(it)
            val ok = IntArray(1); GL.glGetProgramiv(it, GL.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Link: ${GL.glGetProgramInfoLog(it)}" }
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        const val STATS_W = 160
        const val STATS_H = 90

        /** Row-major BT.2020→RWG and RWG→Rec.709, built by logcore from the published primaries. */
        private val GAMUT = NativeBridge.gamutMatrices()
        val BT2020_TO_RWG = GAMUT.copyOfRange(0, 9)
        val RWG_TO_709 = GAMUT.copyOfRange(9, 18)

        /** BT.2100 HLG inverse OETF (same as logcore::hlg_inverse_oetf). */
        fun hlgInverse(e: Float): Float = if (e <= 0.5f) e * e / 3f else ((exp(((e - 0.55991073f) / 0.17883277f).toDouble()) + 0.28466892) / 12.0).toFloat()

        private const val VERTEX = """#version 300 es
uniform mat4 uTexM;
out vec2 vUv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    // The SurfaceTexture transform rotates to the device's natural (portrait) orientation; the
    // activity is locked to landscape (display rotation 90), so rotate back by 90 degrees.
    vUv = (uTexM * vec4(p.y, 1.0 - p.x, 0.0, 1.0)).xy;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

        // Math mirrors rust/logcore (Log3G10 v3, BT.2100 HLG); keep the constants in sync.
        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform int uInput;      // 0 SDR video, 1 Log3G10 in Rec.709 gamut (ISP curve), 2 HLG BT.2020
uniform int uOut;        // 0 preview, 1 recorded signal (encoder), 2 stats
uniform bool uLogView, uZebra, uFalse;
uniform float uZebraLevel, uGrey, uK;
uniform mat3 uToRwg, uRwgTo709;
in vec2 vUv;
out vec4 o;

const float A = 0.224282, B = 155.975327, C = 0.01, G = 15.1927;
float enc(float x) { float xc = x + C; return xc >= 0.0 ? A * log2(B * xc + 1.0) / log2(10.0) : G * xc; }
float dec(float y) { return y >= 0.0 ? (pow(10.0, y / A) - 1.0) / B - C : y / G - C; }
float hlg(float e) { return e <= 0.5 ? e * e / 3.0 : (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0; }
vec3 display709(vec3 x) { x = max(x, 0.0); x = x * (1.0 + x / 16.0) / (1.0 + x); return pow(min(x, 1.0), vec3(1.0 / 2.4)); }

void main() {
    vec3 s = texture(uTex, vUv).rgb;
    vec3 rec = s;           // recorded signal (code values)
    vec3 lin = vec3(0.0);   // scene-linear Rec.709, for the Rec.709 preview of log modes
    vec3 lw = vec3(0.2126, 0.7152, 0.0722);
    if (uInput == 2) {
        vec3 rwg = uToRwg * (vec3(hlg(s.r), hlg(s.g), hlg(s.b)) * uK);
        rec = vec3(enc(rwg.r), enc(rwg.g), enc(rwg.b));
        lin = uRwgTo709 * rwg;
        lw = vec3(0.2627, 0.6780, 0.0593);
    } else if (uInput == 1) {
        lin = vec3(dec(s.r), dec(s.g), dec(s.b));
    }
    if (uOut == 1) { o = vec4(rec, 1.0); return; }
    float y = dot(rec, lw);
    if (uOut == 2) { o = vec4(y, dot(s, vec3(0.2627, 0.6780, 0.0593)), 0.0, 1.0); return; }
    vec3 c = (uInput == 0 || uLogView) ? rec : display709(lin);
    if (uFalse) {
        if (y >= uZebraLevel) c = vec3(1.0, 0.15, 0.1);
        else if (abs(y - uGrey) < 0.02) c = vec3(0.2, 0.9, 0.2);
        else c = vec3(dot(c, vec3(0.2126, 0.7152, 0.0722)));
    }
    if (uZebra && y >= uZebraLevel && mod(floor((gl_FragCoord.x + gl_FragCoord.y) / 6.0), 2.0) < 1.0) c = vec3(0.0);
    o = vec4(c, 1.0);
}"""
    }
}
