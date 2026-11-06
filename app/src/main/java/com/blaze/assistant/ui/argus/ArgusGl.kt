package com.blaze.assistant.ui.argus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.blaze.assistant.ui.ArgusGeom
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 0.52.2 the ARGUS Home's living 3D centrepiece in OpenGL ES 3, no engine and no new dependency: every mesh is made here
 * (a UV sphere, three metallic bands, the pedestal disc, a few camera-facing quads) and lit in small shaders.
 *
 *  - The orb: glossy black with a blue fresnel rim, two speculars and the pedestal's light bouncing up from below.
 *  - The rings ([ArgusScene.RINGS]): translucent brushed-metal bands that turn slowly, with blue light streaks running round.
 *  - The pedestal: concentric glowing circles and ripples moving outward, a soft beam up to the orb.
 *  - Glow: an additive halo behind the orb (a cheap stand-in for bloom: no extra framebuffer, no blur pass).
 *  - The ARGUS "A": the brand mark's vector bars (ArgusGeom), drawn once into a small texture and set on the orb's face.
 *
 * Frames are drawn on request only (RENDERMODE_WHEN_DIRTY); [ArgusGlScene] asks for them at the capped rate and stops
 * asking when the Home isn't seen. Any shader or GL failure calls [onFail] on the main thread and the Home switches to the
 * Canvas orb, so a GPU quirk never leaves a blank or crashing Home.
 */
class ArgusRenderer(context: Context, private val onFail: (String) -> Unit) : GLSurfaceView.Renderer {
    private val main = Handler(Looper.getMainLooper())

    /** The look the scene eases toward (written by the UI thread). */
    @Volatile var target: ArgusScene.Look = ArgusScene.look(ArgusScene.State.IDLE)
    /** Parallax, -1..1 each way (0 when off). */
    @Volatile var parallaxX = 0f
    @Volatile var parallaxY = 0f
    /** Where the orb sits, as a share of the view's height from the top. */
    @Volatile var orbAt = 0.40f

    private var failed = false
    private var cur = target
    private var lastNs = 0L
    private var time = 0f
    private var phase = 0f
    private var aspect = 0.5f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val eye = FloatArray(3)

    private var bgProg = 0; private var orbProg = 0; private var ringProg = 0; private var padProg = 0; private var quadProg = 0; private var markProg = 0
    private var bgVao = 0; private var orbVao = 0; private var orbCount = 0; private val ringVao = IntArray(3); private var ringCount = 0
    private var padVao = 0; private var quadVao = 0; private var markTex = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        failed = false; lastNs = 0L; uniforms.clear()
        try {
            bgProg = program(BG_VS, BG_FS); orbProg = program(ORB_VS, ORB_FS); ringProg = program(RING_VS, RING_FS)
            padProg = program(PAD_VS, PAD_FS); quadProg = program(QUAD_VS, QUAD_FS); markProg = program(QUAD_VS, MARK_FS)
            bgVao = vao(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f), null, intArrayOf(2))
            val s = ArgusMesh.sphere(28, 40); orbCount = s.second.size; orbVao = vao(s.first, s.second, intArrayOf(3))
            for (i in ArgusScene.RINGS.indices) {
                val r = ArgusScene.RINGS[i]
                val m = ArgusMesh.band(128, r.radius, r.width); ringCount = m.second.size
                ringVao[i] = vao(m.first, m.second, intArrayOf(3, 3, 2))
            }
            padVao = vao(floatArrayOf(-1f, 0f, -1f, -1f, -1f, 1f, 0f, -1f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, -1f, 0f, 1f, -1f, 1f),
                shortArrayOf(0, 1, 2, 0, 2, 3), intArrayOf(3, 2))
            quadVao = vao(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f), shortArrayOf(0, 1, 2, 0, 2, 3), intArrayOf(2))
            markTex = texture(markBitmap(256))
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            check("setup")
        } catch (t: Throwable) { fail(t) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 0.5f
    }

    override fun onDrawFrame(gl: GL10?) {
        if (failed) { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); return }
        try { frame() } catch (t: Throwable) { fail(t) }
    }

    private fun frame() {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        cur = ArgusScene.approach(cur, target, dt)
        time += dt
        phase += cur.ringSpeed * dt
        val breath = ArgusScene.breath(time, cur)

        // Camera: far enough that the widest ring fits a portrait screen; the orb sits [orbAt] from the top.
        val fov = 30f
        val half = tan(Math.toRadians(fov / 2.0)).toFloat()
        val dist = (ArgusScene.REACH / (half * aspect.coerceAtLeast(0.3f))).coerceIn(6.5f, 18f)
        eye[0] = parallaxX * 0.35f; eye[1] = 0.55f + parallaxY * 0.2f; eye[2] = dist
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 60f)
        // Shift the picture so the orb lands at [orbAt] (NDC y = 1 - 2 * orbAt); a translate after the projection.
        Matrix.setIdentityM(tmp, 0); Matrix.translateM(tmp, 0, 0f, 1f - 2f * orbAt, 0f)
        Matrix.multiplyMM(model, 0, tmp, 0, proj, 0); System.arraycopy(model, 0, proj, 0, 16)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        // 1. background: obsidian with a deep-blue atmosphere around the orb
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(bgProg)
        GLES20.glUniform1f(u(bgProg, "uAspect"), aspect)
        GLES20.glUniform1f(u(bgProg, "uTime"), time)
        GLES20.glUniform2f(u(bgProg, "uCenter"), 0.5f, 1f - orbAt)
        GLES20.glUniform1f(u(bgProg, "uGlow"), cur.glow)
        GLES30.glBindVertexArray(bgVao); GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)   // additive light
        // 2. halo behind the orb, and the beam from the pedestal
        billboard(quadProg, 0f, 0f, 0f, 2.6f * breath, 2.6f * breath, 0.55f * cur.glow, 0)
        billboard(quadProg, 0f, -1.0f, 0f, 0.62f, 1.05f, 0.22f * cur.glow, 1)
        // 3. pedestal
        GLES20.glUseProgram(padProg)
        Matrix.setIdentityM(model, 0); Matrix.translateM(model, 0, 0f, -2.0f, 0f); Matrix.scaleM(model, 0, 2.5f, 1f, 2.5f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(u(padProg, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(u(padProg, "uTime"), time)
        GLES20.glUniform1f(u(padProg, "uRipple"), cur.ripple)
        GLES20.glUniform1f(u(padProg, "uGlow"), cur.glow)
        GLES30.glBindVertexArray(padVao); GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)

        // 4. the orb (opaque, writes depth)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true); GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glUseProgram(orbProg)
        Matrix.setIdentityM(model, 0); Matrix.scaleM(model, 0, breath, breath, breath)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(u(orbProg, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(u(orbProg, "uModel"), 1, false, model, 0)
        GLES20.glUniform3f(u(orbProg, "uEye"), eye[0], eye[1], eye[2])
        GLES20.glUniform1f(u(orbProg, "uGlow"), cur.glow)
        GLES30.glBindVertexArray(orbVao); GLES20.glDrawElements(GLES20.GL_TRIANGLES, orbCount, GLES20.GL_UNSIGNED_SHORT, 0)

        // 5. the A on the orb's face (premultiplied texture), over the orb, under the rings
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(markProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, markTex)
        GLES20.glUniform1i(u(markProg, "uTex"), 0)
        billboard(markProg, 0f, 0.02f, 1.0f * breath, 0.42f * breath, 0.42f * breath, 0.75f + 0.25f * cur.glow, 2)

        // 6. the rings: translucent, tested against the orb's depth, not writing their own
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ringProg)
        GLES20.glUniform3f(u(ringProg, "uEye"), eye[0], eye[1], eye[2])
        GLES20.glUniform1f(u(ringProg, "uStreak"), cur.streak)
        GLES20.glUniform1f(u(ringProg, "uGlow"), cur.glow)
        for (i in ArgusScene.RINGS.indices) {
            val r = ArgusScene.RINGS[i]
            Matrix.setIdentityM(model, 0)
            Matrix.scaleM(model, 0, breath, breath, breath)
            Matrix.rotateM(model, 0, r.tiltZ, 0f, 0f, 1f)
            Matrix.rotateM(model, 0, r.tiltX, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, Math.toDegrees(ArgusScene.ringAngle(r, phase).toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUniformMatrix4fv(u(ringProg, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(u(ringProg, "uModel"), 1, false, model, 0)
            GLES20.glUniform1f(u(ringProg, "uPhase"), phase * r.speed)
            GLES20.glUniform1f(u(ringProg, "uSeed"), i * 0.37f)
            GLES30.glBindVertexArray(ringVao[i]); GLES20.glDrawElements(GLES20.GL_TRIANGLES, ringCount, GLES20.GL_UNSIGNED_SHORT, 0)
        }
        GLES30.glBindVertexArray(0)
        GLES20.glDepthMask(true)
    }

    /** A camera-facing quad at world (x, y, z), half-size (w, h) world units; [kind] picks the quad shader's pattern. */
    private fun billboard(prog: Int, x: Float, y: Float, z: Float, w: Float, h: Float, intensity: Float, kind: Int) {
        val c = floatArrayOf(x, y, z, 1f); val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vp, 0, c, 0)
        GLES20.glUseProgram(prog)
        GLES20.glUniform4f(u(prog, "uCenter"), clip[0], clip[1], clip[2], clip[3])
        GLES20.glUniform2f(u(prog, "uScale"), proj[0] * w, proj[5] * h)
        GLES20.glUniform1f(u(prog, "uIntensity"), intensity)
        GLES20.glUniform1i(u(prog, "uKind"), kind)
        GLES30.glBindVertexArray(quadVao); GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    private val uniforms = HashMap<String, Int>()
    private fun u(prog: Int, name: String): Int = uniforms.getOrPut("$prog/$name") { GLES20.glGetUniformLocation(prog, name) }

    private fun fail(t: Throwable) {
        if (failed) return
        failed = true
        val msg = t.message ?: t.javaClass.simpleName
        main.post { onFail(msg) }
    }

    private fun check(where: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) throw IllegalStateException("GL error 0x${Integer.toHexString(e)} in $where")
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw IllegalStateException("shader: $log") }
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    /** A vertex array: interleaved floats with attribute [sizes] at locations 0, 1, 2…, optional short indices. */
    private fun vao(data: FloatArray, idx: ShortArray?, sizes: IntArray): Int {
        val a = IntArray(1); GLES30.glGenVertexArrays(1, a, 0); GLES30.glBindVertexArray(a[0])
        val b = IntArray(2); GLES20.glGenBuffers(2, b, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, floats(data), GLES20.GL_STATIC_DRAW)
        val stride = sizes.sum() * 4
        var off = 0
        for (i in sizes.indices) {
            GLES20.glEnableVertexAttribArray(i)
            GLES20.glVertexAttribPointer(i, sizes[i], GLES20.GL_FLOAT, false, stride, off)
            off += sizes[i] * 4
        }
        if (idx != null) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, b[1])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, shorts(idx), GLES20.GL_STATIC_DRAW)
        }
        GLES30.glBindVertexArray(0)
        return a[0]
    }

    private fun texture(bmp: Bitmap): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return t[0]
    }

    /** The ARGUS A in glowing electric blue on transparent, [size] px square. */
    private fun markBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val k = size * 0.62f; val cx = size / 2f; val cy = size / 2f
        val path = Path()
        for (b in ArgusGeom.BARS) {
            path.moveTo(cx + b[0] * k, cy + b[1] * k)
            var i = 2
            while (i < b.size) { path.lineTo(cx + b[i] * k, cy + b[i + 1] * k); i += 2 }
            path.close()
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1286FF.toInt(); maskFilter = BlurMaskFilter(size * 0.06f, BlurMaskFilter.Blur.NORMAL) }
        cv.drawPath(path, glow)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cx - k / 2, cy - k / 2, cx + k / 2, cy + k / 2, intArrayOf(0xFFE8F1FF.toInt(), 0xFF6FA8FF.toInt(), 0xFF1286FF.toInt()), null, Shader.TileMode.CLAMP)
        }
        cv.drawPath(path, fill)
        return bmp
    }

    companion object {
        private fun floats(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        private fun shorts(a: ShortArray): ShortBuffer = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }

        // ---------------------------------------------------------------- shaders (GLSL ES 3.00)
        const val BG_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.999, 1.0); }
"""
        const val BG_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform float uAspect;
uniform float uTime;
uniform vec2 uCenter;
uniform float uGlow;
out vec4 o;
void main() {
  vec2 d = vUv - uCenter; d.x *= uAspect;
  float r = length(d);
  vec3 deep = vec3(0.0, 0.07, 0.24);
  vec3 col = mix(deep, vec3(0.0), smoothstep(0.0, 0.85, r));
  float floorLine = exp(-pow((vUv.y - (uCenter.y - 0.30)) / 0.05, 2.0)) * 0.22;
  col += vec3(0.0, 0.18, 0.65) * floorLine * (0.6 + 0.4 * uGlow);
  float cols = 0.0;
  for (int i = 0; i < 5; i++) {
    float x = fract(0.11 + float(i) * 0.213);
    float w = 0.0025 + 0.0015 * float(i % 2);
    cols += exp(-pow((vUv.x - x) / w, 2.0)) * smoothstep(0.30, 0.95, vUv.y) * (0.55 + 0.45 * sin(uTime * 0.3 + float(i) * 1.7));
  }
  col += vec3(0.05, 0.25, 0.9) * cols * 0.16;
  vec2 v = (vUv - 0.5) * vec2(uAspect, 1.0) * 1.4;
  col *= 1.0 - 0.35 * smoothstep(0.5, 1.2, length(v));
  o = vec4(col, 1.0);
}
"""
        const val ORB_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vN;
out vec3 vW;
void main() { vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vN = mat3(uModel) * aPos; gl_Position = uMVP * vec4(aPos, 1.0); }
"""
        const val ORB_FS = """#version 300 es
precision mediump float;
in vec3 vN;
in vec3 vW;
uniform vec3 uEye;
uniform float uGlow;
out vec4 o;
void main() {
  vec3 n = normalize(vN);
  vec3 v = normalize(uEye - vW);
  float ndv = clamp(dot(n, v), 0.0, 1.0);
  float fres = pow(1.0 - ndv, 3.0);
  vec3 l1 = normalize(vec3(-0.5, 0.8, 0.6));
  float s1 = pow(max(dot(n, normalize(l1 + v)), 0.0), 90.0);
  vec3 l2 = normalize(vec3(0.7, -0.2, 0.5));
  float s2 = pow(max(dot(n, normalize(l2 + v)), 0.0), 36.0);
  vec3 base = vec3(0.010, 0.013, 0.026) + vec3(0.004, 0.010, 0.030) * max(dot(n, l1), 0.0);
  vec3 rim = vec3(0.07, 0.52, 1.0) * fres * (0.55 + 0.8 * uGlow);
  float under = clamp(-n.y, 0.0, 1.0);
  vec3 bounce = vec3(0.0, 0.22, 0.85) * under * under * 0.35 * uGlow;
  o = vec4(base + rim + bounce + vec3(0.9, 0.95, 1.0) * s1 * 0.9 + vec3(0.3, 0.5, 1.0) * s2 * 0.25, 1.0);
}
"""
        const val RING_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
layout(location = 2) in vec2 aUv;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vN;
out vec3 vW;
out vec2 vUv;
void main() { vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vN = mat3(uModel) * aNor; vUv = aUv; gl_Position = uMVP * vec4(aPos, 1.0); }
"""
        const val RING_FS = """#version 300 es
precision mediump float;
in vec3 vN;
in vec3 vW;
in vec2 vUv;
uniform vec3 uEye;
uniform float uPhase;
uniform float uStreak;
uniform float uGlow;
uniform float uSeed;
out vec4 o;
void main() {
  vec3 n = normalize(vN);
  if (!gl_FrontFacing) n = -n;
  vec3 v = normalize(uEye - vW);
  float ndv = abs(dot(n, v));
  vec3 l = normalize(vec3(-0.4, 0.9, 0.5));
  float spec = pow(max(dot(n, normalize(l + v)), 0.0), 60.0);
  float edge = smoothstep(0.0, 0.12, vUv.y) * (1.0 - smoothstep(0.88, 1.0, vUv.y));
  vec3 metal = mix(vec3(0.09, 0.10, 0.12), vec3(0.55, 0.58, 0.64), pow(1.0 - ndv, 2.0));
  metal *= 0.85 + 0.15 * sin(vUv.y * 40.0);
  float a1 = fract(vUv.x - uPhase * 0.16 + uSeed);
  float st1 = smoothstep(0.0, 0.02, a1) * (1.0 - smoothstep(0.02, 0.16, a1));
  float a2 = fract(vUv.x * 2.0 - uPhase * 0.11 + uSeed * 1.7);
  float st2 = smoothstep(0.0, 0.01, a2) * (1.0 - smoothstep(0.01, 0.06, a2));
  float line = smoothstep(0.30, 0.5, vUv.y) * (1.0 - smoothstep(0.5, 0.70, vUv.y));
  vec3 blue = vec3(0.07, 0.52, 1.0);
  vec3 col = metal + vec3(0.85, 0.9, 1.0) * spec * 0.8 + blue * (st1 * 0.9 + st2 * 0.6) * uStreak * (0.4 + 0.6 * line) + blue * 0.12 * uGlow * line;
  float alpha = (0.50 + 0.35 * pow(1.0 - ndv, 1.5)) * edge;
  alpha = max(alpha, (st1 + st2) * uStreak * 0.8 * edge);
  o = vec4(col, clamp(alpha, 0.0, 1.0));
}
"""
        const val PAD_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uMVP;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = uMVP * vec4(aPos, 1.0); }
"""
        const val PAD_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform float uTime;
uniform float uRipple;
uniform float uGlow;
out vec4 o;
void main() {
  float r = length(vUv);
  if (r > 1.0) discard;
  vec3 blue = vec3(0.07, 0.45, 1.0);
  float ra = exp(-pow((r - 0.36) / 0.014, 2.0));
  float rb = exp(-pow((r - 0.52) / 0.009, 2.0)) * 0.8;
  float rc = exp(-pow((r - 0.70) / 0.007, 2.0)) * 0.6;
  float rd = exp(-pow((r - 0.86) / 0.005, 2.0)) * 0.35;
  float rip = 0.0;
  for (int i = 0; i < 3; i++) {
    float p = fract(uTime * uRipple * 0.25 + float(i) / 3.0);
    float rr = 0.2 + p * 0.8;
    rip += exp(-pow((r - rr) / 0.012, 2.0)) * (1.0 - p);
  }
  float disk = (1.0 - smoothstep(0.0, 0.38, r)) * 0.30;
  float k = (ra + rb + rc + rd) * (0.6 + 0.6 * uGlow) + rip * 0.5 * uGlow + disk * uGlow;
  k *= (1.0 - smoothstep(0.85, 1.0, r));
  o = vec4(blue * k + vec3(0.6, 0.8, 1.0) * ra * 0.3, 1.0);
}
"""
        const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec4 uCenter;
uniform vec2 uScale;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = uCenter + vec4(aPos * uScale, 0.0, 0.0); }
"""
        const val QUAD_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform float uIntensity;
uniform int uKind;
out vec4 o;
void main() {
  vec2 p = vUv * 2.0 - 1.0;
  float k;
  if (uKind == 1) {
    float x = abs(p.x);
    k = pow(max(1.0 - x, 0.0), 3.0) * (1.0 - smoothstep(-0.6, 1.0, p.y)) * smoothstep(-1.0, -0.7, p.y);
  } else {
    float r2 = dot(p, p);
    k = exp(-r2 * 3.2) * step(r2, 1.0);
  }
  o = vec4(vec3(0.07, 0.42, 1.0) * k * uIntensity, 1.0);
}
"""
        const val MARK_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uIntensity;
out vec4 o;
void main() { vec4 c = texture(uTex, vec2(vUv.x, 1.0 - vUv.y)); o = c * uIntensity; }
"""
    }
}

/** Procedural meshes (pure maths, ArgusHomeLogicTest checks their sizes and that every index is in range). */
object ArgusMesh {
    /** Unit UV sphere: positions only (the normal is the position). */
    fun sphere(lat: Int, lon: Int): Pair<FloatArray, ShortArray> {
        val v = FloatArray((lat + 1) * (lon + 1) * 3)
        var k = 0
        for (i in 0..lat) {
            val th = PI * i / lat
            for (j in 0..lon) {
                val ph = 2 * PI * j / lon
                v[k++] = (sin(th) * cos(ph)).toFloat(); v[k++] = cos(th).toFloat(); v[k++] = (sin(th) * sin(ph)).toFloat()
            }
        }
        val idx = ShortArray(lat * lon * 6)
        k = 0
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = i * (lon + 1) + j; val b = a + lon + 1
            idx[k++] = a.toShort(); idx[k++] = b.toShort(); idx[k++] = (a + 1).toShort()
            idx[k++] = (a + 1).toShort(); idx[k++] = b.toShort(); idx[k++] = (b + 1).toShort()
        }
        return v to idx
    }

    /**
     * A metal band around the Y axis ([radius], height [width]): position, radial normal and uv (u round the ring, v across)
     * interleaved, 8 floats a vertex.
     */
    fun band(segments: Int, radius: Float, width: Float): Pair<FloatArray, ShortArray> {
        val v = FloatArray((segments + 1) * 2 * 8)
        var k = 0
        for (j in 0..segments) {
            val a = 2 * PI * j / segments
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            for (side in 0..1) {
                v[k++] = radius * c; v[k++] = if (side == 0) -width / 2 else width / 2; v[k++] = radius * s
                v[k++] = c; v[k++] = 0f; v[k++] = s
                v[k++] = j.toFloat() / segments; v[k++] = side.toFloat()
            }
        }
        val idx = ShortArray(segments * 6)
        k = 0
        for (j in 0 until segments) {
            val a = j * 2; val b = a + 2
            idx[k++] = a.toShort(); idx[k++] = (a + 1).toShort(); idx[k++] = b.toShort()
            idx[k++] = (a + 1).toShort(); idx[k++] = (b + 1).toShort(); idx[k++] = b.toShort()
        }
        return v to idx
    }
}

/** An EGL config for GLES 3 with 4x MSAA when allowed, else without; never null on a GLES 3 device. */
class ArgusConfigChooser(private val msaa: Boolean) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        fun pick(samples: Int, es3: Boolean): EGLConfig? {
            val attrs = ArrayList<Int>()
            attrs += listOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16)
            if (es3) attrs += listOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT)
            if (samples > 0) attrs += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
            attrs += EGL10.EGL_NONE
            val a = attrs.toIntArray()
            val num = IntArray(1)
            if (!egl.eglChooseConfig(display, a, null, 0, num) || num[0] <= 0) return null
            val configs = arrayOfNulls<EGLConfig>(num[0])
            if (!egl.eglChooseConfig(display, a, configs, num[0], num)) return null
            val v = IntArray(1)
            fun attr(c: EGLConfig, key: Int): Int = if (egl.eglGetConfigAttrib(display, c, key, v)) v[0] else -1
            return configs.filterNotNull().firstOrNull { attr(it, EGL10.EGL_RED_SIZE) == 8 && attr(it, EGL10.EGL_GREEN_SIZE) == 8 && attr(it, EGL10.EGL_BLUE_SIZE) == 8 }
                ?: configs.filterNotNull().firstOrNull()
        }
        return (if (msaa) pick(4, true) else null) ?: pick(0, true) ?: pick(0, false)
            ?: throw IllegalStateException("no EGL config")
    }

    companion object { const val EGL_OPENGL_ES3_BIT = 0x40 }
}
