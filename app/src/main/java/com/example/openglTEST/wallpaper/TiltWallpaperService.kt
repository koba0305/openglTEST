package com.example.openglTEST.wallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.google.gson.Gson
import com.example.openglTEST.wallpaper.model.*   // Pack / LayerCfg / PaletteItem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class TiltWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = TiltEngine()

    private inner class TiltEngine : Engine(), SensorEventListener {

        // --------- JSON pack ----------
        private var pack: Pack? = null

        // --------- Sensor ----------
        private val ctx: Context = this@TiltWallpaperService
        private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)

        @Volatile private var tiltX = 0f
        @Volatile private var tiltY = 0f
        @Volatile private var tiltMotion = 0f
        private var lastTiltX = 0f
        private var lastTiltY = 0f
        private var lastSensorNs = 0L

        // --------- EGL / GL ----------
        private var eglDisplay: EGLDisplay? = null
        private var eglContext: EGLContext? = null
        private var eglSurface: EGLSurface? = null
        private var eglConfig: EGLConfig? = null
        private var holderRef: SurfaceHolder? = null

        private var width = 0
        private var height = 0

        // --------- Thread ----------
        @Volatile private var running = false
        private var glThread: Thread? = null

        // --------- Renderer ----------
        private var renderer: GridRenderer? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(true)
            Log.d(TAG, "Engine onCreate")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startSensors()
            Log.d(TAG, "onSurfaceCreated")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            holderRef = holder
            Log.d(TAG, "onSurfaceChanged: ${width}x${height}")

            stopGLThread()
            destroyEgl()
            initEgl(holder)

            // assets/pack.json を一度だけ読む
            if (pack == null) {
                try {
                    val assetName = try {
                        applicationContext.assets.open("pack_vivid_test.json").close()
                        "pack_vivid_test.json"
                    } catch (_: Exception) {
                        "pack.json"
                    }
                    val txt = applicationContext.assets.open(assetName).bufferedReader().use { it.readText() }
                    pack = Gson().fromJson(txt, Pack::class.java)
                    Log.d(TAG, "pack loaded: layers=${pack?.layers?.size}")
                } catch (e: Exception) {
                    Log.w(TAG, "pack load failed: ${e.message}")
                    pack = null
                }
            }

            renderer = GridRenderer(applicationContext, pack)
            startGLThread()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "onSurfaceDestroyed")
            stopSensors()
            stopGLThread()
            destroyEgl()
            super.onSurfaceDestroyed(holder)
        }

        // ----------------- Sensors -----------------
        private fun startSensors() {
            rotVec?.let {
                sensorManager.registerListener(this, it, SENSOR_PERIOD_US)
            }
        }

        private fun stopSensors() {
            sensorManager.unregisterListener(this)
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val pitch = orientation[1]
            val roll = orientation[2]

            fun norm(v: Float): Float {
                val n = v / (Math.PI.toFloat() / 3f) // ±60度 → ±1 くらい
                return max(-1f, min(1f, n))
            }

            tiltX = norm(roll)
            tiltY = norm(-pitch)

            if (lastSensorNs != 0L) {
                val dtSec = ((event.timestamp - lastSensorNs).coerceAtLeast(1L)) / 1_000_000_000f
                val dx = tiltX - lastTiltX
                val dy = tiltY - lastTiltY
                val speed = (sqrt(dx * dx + dy * dy) / dtSec).coerceAtMost(12f)
                val rawMotion = (speed / 3.2f).coerceIn(0f, 1f)
                val gatedMotion = if (rawMotion < 0.08f) 0f else rawMotion
                tiltMotion = max(gatedMotion, tiltMotion * 0.25f)
            }
            lastTiltX = tiltX
            lastTiltY = tiltY
            lastSensorNs = event.timestamp
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        // ----------------- EGL -----------------
        private fun initEgl(holder: SurfaceHolder) {
            fun eglError(msg: String): Nothing {
                val err = EGL14.eglGetError()
                throw RuntimeException("$msg, eglError=0x${Integer.toHexString(err)}")
            }

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) eglError("eglGetDisplay failed")

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                eglError("eglInitialize failed")
            }

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                eglError("eglChooseConfig failed")
            }
            eglConfig = configs[0]

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
                eglError("eglCreateContext failed")
            }

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, holder.surface, surfAttribs, 0)
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                eglError("eglCreateWindowSurface failed")
            }

            Log.d(TAG, "EGL initialized")
        }

        private fun destroyEgl() {
            try {
                if (eglDisplay != null && eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglDisplay != null && eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                if (eglDisplay != null) {
                    EGL14.eglTerminate(eglDisplay)
                }
            } finally {
                eglDisplay = null
                eglSurface = null
                eglContext = null
                eglConfig = null
            }
            Log.d(TAG, "EGL destroyed")
        }

        // ----------------- GL Thread -----------------
        private fun startGLThread() {
            if (running) return
            running = true

            glThread = Thread {
                var glInited = false
                var frame = 0L
                val startNs = System.nanoTime()

                while (running) {
                    val frameStartNs = System.nanoTime()
                    val disp = eglDisplay
                    val surf = eglSurface
                    val ctx = eglContext

                    if (disp == null || surf == null || surf == EGL14.EGL_NO_SURFACE || ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
                        try { Thread.sleep(30) } catch (_: InterruptedException) {}
                        continue
                    }

                    if (!EGL14.eglMakeCurrent(disp, surf, surf, ctx)) {
                        Log.w(TAG, "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                        try { Thread.sleep(30) } catch (_: InterruptedException) {}
                        continue
                    }

                    if (!glInited) {
                        renderer?.onSurfaceCreated()
                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                        glInited = true
                    }

                    GLES20.glViewport(0, 0, width, height)
                    GLES20.glClearColor(0f, 0f, 0f, 0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    val now = System.nanoTime()
                    val timeSec = ((now - startNs) / 1_000_000_000.0).toFloat()
                    renderer?.draw(width, height, tiltX, tiltY, tiltMotion, timeSec)

                    val ok = EGL14.eglSwapBuffers(disp, surf)
                    if (!ok) {
                        val err = EGL14.eglGetError()
                        Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(err)}")
                        if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_CONTEXT_LOST) {
                            holderRef?.let {
                                destroyEgl()
                                initEgl(it)
                                glInited = false
                            }
                        }
                    }

                    if ((++frame % 240L) == 0L) {
                        Log.d(TAG, "rendering... f=$frame size=${width}x$height tilt=($tiltX,$tiltY)")
                    }

                    val elapsedNs = System.nanoTime() - frameStartNs
                    val sleepMs = ((TARGET_FRAME_NS - elapsedNs) / 1_000_000L).coerceAtLeast(1L)
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
                }
            }.apply {
                name = "GLThread(LiveWallpaper)"
                start()
            }

            Log.d(TAG, "GL thread started")
        }

        private fun stopGLThread() {
            running = false
            glThread?.interrupt()
            glThread?.join(300)
            glThread = null
            Log.d(TAG, "GL thread stopped")
        }
    }

    companion object {
        private const val TAG = "TiltWP"
        private const val TARGET_FRAME_NS = 33_333_333L
        private const val SENSOR_PERIOD_US = 33_333
    }
}

/**
 * 5層を「重ねて」描く全画面クアッド renderer
 *
 * L1-L3: 壊れ液晶縦ライン
 * L4   : ドットノイズ
 * L5   : 色調補正(仮) ＝薄いベタ + 色寄せ
 */
private class GridRenderer(
    private val context: Context,
    private val pack: Pack?
) {
    // ---- GL program ----
    private var program = 0

    // ---- attribute ----
    private var aPos = 0

    // ---- uniforms (共通) ----
    private var uColorLoc = 0
    private var uAltColorLoc = 0
    private var uWeirdColorLoc = 0
    private var uAlphaLoc = 0
    private var uOffsetLoc = 0
    private var uTimeLoc = 0
    private var uTiltLenLoc = 0
    private var uTiltVecLoc = 0
    private var uModeLoc = 0
    private var uColorVarianceLoc = 0
    private var uAccentChanceLoc = 0

    // ---- uniforms (dot noise) ----
    private var uDensityLoc = 0
    private var uSeedLoc = 0
    private var uCellSizeLoc = 0

    // ---- uniforms (stripe lcd) ----
    private var uStripeMinLoc = 0
    private var uStripeMaxLoc = 0
    private var uStripeFillLoc = 0
    private var uJumpPxLoc = 0
    private var uYShiftPxLoc = 0
    private var uScanPeriodLoc = 0
    private var uRgbChaosLoc = 0
    private var uRgbSplitPxLoc = 0
    private var uDotBreakupLoc = 0
    private var uHoldStrengthLoc = 0
    private var uBandStrengthLoc = 0
    private var uFrontDotSizeLoc = 0
    private var uBandHeightLoc = 0

    // 全画面クアッド（2三角形）
    private val quad = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f,  1f,
        1f, -1f,
        1f,  1f,
        -1f,  1f
    )
    private val vbo = IntArray(1)
    private var tuning = WallpaperTuning.load(context)

    private data class NoiseProfile(
        val parallaxBoost: Float = 1f,
        val fineDensity: Float = 1f,
        val fineCellScale: Float = 1f,
        val coarseDensity: Float = 1f,
        val coarseSizeScale: Float = 1f,
        val coarseAlpha: Float = 1f,
        val l0Density: Float = 1f,
        val l0CellScale: Float = 1f,
        val flickerBoost: Float = 1f,
        val stripeFillScale: Float = 1f,
        val stripeWidthScale: Float = 1f,
        val jumpBoost: Float = 1f,
        val splitBoost: Float = 1f,
        val bandBoost: Float = 1f
    )

    private fun noiseProfile(presetId: String): NoiseProfile = when (presetId) {
        "P1" -> NoiseProfile(
            parallaxBoost = 0.45f,
            fineDensity = 2.80f,
            fineCellScale = 0.32f,
            coarseDensity = 2.80f,
            coarseSizeScale = 0.62f,
            coarseAlpha = 1.18f,
            l0Density = 3.20f,
            l0CellScale = 0.34f,
            flickerBoost = 3.80f,
            stripeFillScale = 2.90f,
            stripeWidthScale = 0.42f,
            jumpBoost = 3.10f,
            splitBoost = 2.70f,
            bandBoost = 3.00f
        )
        "P2" -> NoiseProfile(
            parallaxBoost = 0.50f,
            fineDensity = 3.10f,
            fineCellScale = 0.28f,
            coarseDensity = 3.10f,
            coarseSizeScale = 0.58f,
            coarseAlpha = 1.25f,
            l0Density = 3.40f,
            l0CellScale = 0.30f,
            flickerBoost = 4.20f,
            stripeFillScale = 3.20f,
            stripeWidthScale = 0.38f,
            jumpBoost = 3.40f,
            splitBoost = 3.40f,
            bandBoost = 3.30f
        )
        "P3" -> NoiseProfile(
            parallaxBoost = 1.70f,
            fineDensity = 0.42f,
            fineCellScale = 1.80f,
            coarseDensity = 0.58f,
            coarseSizeScale = 1.65f,
            coarseAlpha = 0.62f,
            l0Density = 0.45f,
            l0CellScale = 1.85f,
            flickerBoost = 0.24f,
            stripeFillScale = 0.42f,
            stripeWidthScale = 1.30f,
            jumpBoost = 0.42f,
            splitBoost = 0.25f,
            bandBoost = 0.40f
        )
        "P4" -> NoiseProfile(
            parallaxBoost = 1.85f,
            fineDensity = 0.52f,
            fineCellScale = 1.55f,
            coarseDensity = 0.70f,
            coarseSizeScale = 1.55f,
            coarseAlpha = 0.70f,
            l0Density = 0.55f,
            l0CellScale = 1.65f,
            flickerBoost = 0.30f,
            stripeFillScale = 0.52f,
            stripeWidthScale = 1.15f,
            jumpBoost = 0.50f,
            splitBoost = 0.55f,
            bandBoost = 0.48f
        )
        else -> NoiseProfile()
    }

    // ---------- layers / palette ----------
    private val layers: List<LayerCfg>
    private var paletteFloats: List<FloatArray>
    private var currentPaletteAsset: String? = null
    private var lastDrawTimeSec = 0f
    private var zabiGlitchTimeSec = 0f

    init {
        val baseLayers = pack?.layers ?: listOf(
            LayerCfg("L1", 0.0, 1.0, 1.0, 60, listOf(0, 1)),
            LayerCfg("L2", 0.25, 0.7, 1.0, 45, listOf(0, 1)),
            LayerCfg("L3", 0.5, 0.45, 1.0, 30, listOf(0, 1)),
            LayerCfg("L4", 0.75, 0.25, 0.9, 24, listOf(0, 1)),
            LayerCfg("L5", 1.0, 0.1, 1.0, 15, listOf(0, 1))
        )
        layers = baseLayers.sortedBy { it.depth }

        val rawPalette = pack?.palette ?: listOf(
            PaletteItem("#00FF66", 1.0),
            PaletteItem("#00CC55", 1.0),
            PaletteItem("#009944", 1.0),
            PaletteItem("#006633", 1.0),
            PaletteItem("#00331A", 1.0)
        )
        paletteFloats = rawPalette.map { parseHexColor(it.hex) }
        refreshPaletteAsset(tuning.paletteAsset)
    }

    private fun parseHexColor(hex: String): FloatArray {
        val clean = hex.removePrefix("#")
        val r = clean.substring(0, 2).toInt(16) / 255f
        val g = clean.substring(2, 4).toInt(16) / 255f
        val b = clean.substring(4, 6).toInt(16) / 255f
        return floatArrayOf(r, g, b)
    }

    private fun refreshPaletteAsset(assetName: String) {
        if (assetName == currentPaletteAsset) return
        try {
            val txt = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val loadedPack = Gson().fromJson(txt, Pack::class.java)
            val loadedPalette = loadedPack?.palette.orEmpty()
            if (loadedPalette.isNotEmpty()) {
                paletteFloats = loadedPalette.map { parseHexColor(it.hex) }
                currentPaletteAsset = assetName
            }
        } catch (e: Exception) {
            Log.w("TiltWP", "palette asset load failed: $assetName ${e.message}")
        }
    }

    private fun colorAt(index: Int): FloatArray {
        if (paletteFloats.isEmpty()) return floatArrayOf(0.5f, 0.8f, 0.3f)
        val color = paletteFloats[index.mod(paletteFloats.size)]
        return if (isMonochromePreset(tuning.presetId)) grayscaleColor(color) else color
    }

    private fun isMonochromePreset(presetId: String): Boolean = presetId == "P1" || presetId == "P3"

    private fun isZabiPreset(presetId: String): Boolean = presetId == "P1" || presetId == "P2"

    private fun grayscaleColor(c: FloatArray): FloatArray {
        val y = (c[0] * 0.299f + c[1] * 0.587f + c[2] * 0.114f).coerceIn(0f, 1f)
        return floatArrayOf(y, y, y)
    }

    private fun lerpColor(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        val tt = t.coerceIn(0f, 1f)
        return floatArrayOf(
            a[0] + (b[0] - a[0]) * tt,
            a[1] + (b[1] - a[1]) * tt,
            a[2] + (b[2] - a[2]) * tt
        )
    }

    private fun scaleColor(c: FloatArray, scale: Float): FloatArray =
        floatArrayOf(
            (c[0] * scale).coerceIn(0f, 1f),
            (c[1] * scale).coerceIn(0f, 1f),
            (c[2] * scale).coerceIn(0f, 1f)
        )

    private fun mixedLayerColors(layer: LayerCfg, tFront: Float): Triple<FloatArray, FloatArray, FloatArray> {
        val bias = if (layer.palette_bias.isEmpty()) listOf(0) else layer.palette_bias
        val baseA = colorAt(bias.first())
        val baseB = colorAt(bias.last())
        val base = lerpColor(baseA, baseB, (0.22f + 0.56f * tFront).coerceIn(0f, 1f))

        val neighbor = colorAt((bias.first() + 1 + (tFront * 2f).toInt()).mod(paletteFloats.size.coerceAtLeast(1)))
        val alt = lerpColor(base, neighbor, (0.28f + 0.44f * tuning.colorVariance).coerceIn(0f, 1f))

        val farIndex = (bias.first() + paletteFloats.size / 2 + 1).mod(paletteFloats.size.coerceAtLeast(1))
        val far = colorAt(farIndex)
        val weird = lerpColor(
            far,
            scaleColor(base, 0.18f + 0.20f * (1f - tuning.accentChance)),
            0.24f
        )
        return Triple(base, alt, weird)
    }

    fun onSurfaceCreated() {
        val vs = """
            attribute vec2 aPos;
            varying vec2 vUV;
            void main() {
              vUV = (aPos * 0.5) + 0.5;
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        // 0=dot / 1=lcd stripes / 2=grade
        val fs = """
            precision highp float;
            varying vec2 vUV;

            uniform vec3  uColor;
            uniform vec3  uAltColor;
            uniform vec3  uWeirdColor;
            uniform float uAlpha;
            uniform vec2  uOffset;
            uniform float uTime;
            uniform float uTiltLen;
            uniform vec2  uTiltVec;
            uniform float uMode;
            uniform float uColorVariance;
            uniform float uAccentChance;

            // ---- dot noise ----
            uniform float uDensity;    // 0..1
            uniform float uSeed;       // layer seed
            uniform float uCellSize;   // pixel cell

            // ---- lcd stripes ----
            uniform float uStripeMin;  // px
            uniform float uStripeMax;  // px
            uniform float uStripeFill; // 0..1 (線が残る割合)
            uniform float uJumpPx;     // X jump max
            uniform float uYShiftPx;   // Y shift max
            uniform float uScanPeriod; // px
            uniform float uRgbChaos;   // 0..1
            uniform float uRgbSplitPx; // px
            uniform float uDotBreakup; // 0..1
            uniform float uHoldStrength; // 0..1
            uniform float uBandStrength; // 0..1
            uniform float uFrontDotSize; // px
            uniform float uBandHeight; // px

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            // グリッド用
            float randCell(vec2 cell, float seed) {
                return hash(cell + vec2(seed, seed * 0.37));
            }

            vec3 chooseColor(float idHash, float mixHash) {
                vec3 nearCol = mix(uColor, uAltColor, clamp(uColorVariance * mixHash, 0.0, 1.0));
                float weirdMask = step(1.0 - clamp(uAccentChance, 0.0, 0.95), idHash);
                return mix(nearCol, uWeirdColor, weirdMask);
            }

            vec3 chooseDotColor(float idHash, float mixHash) {
                vec3 nearCol = mix(uColor, uAltColor, clamp(0.22 + uColorVariance * (0.55 + 0.45 * mixHash), 0.0, 1.0));
                float accentMix = smoothstep(
                    1.0 - clamp(0.18 + uAccentChance * 1.8, 0.0, 0.92),
                    1.0,
                    idHash
                );
                vec3 accentCol = mix(uAltColor, uWeirdColor, clamp(0.35 + mixHash * 0.65, 0.0, 1.0));
                return mix(nearCol, accentCol, accentMix);
            }


            void main() {
                // 共通座標（ピクセル）
                vec2 p = gl_FragCoord.xy + uOffset;

                // ============================
                // Mode 0 : Dot noise
                // ============================
                if (uMode < 0.5) {
                    float cs = max(uCellSize, 1.0);
                    float frontFlag = step(300.0, uSeed);
                    float specialFlag = step(120.0, uSeed);
                    float flickerRate = mix(0.0, mix(3.5, 28.0, frontFlag), uHoldStrength);
                    float flickerT = floor(uTime * flickerRate);

                    if (specialFlag > 0.5) {
                        vec2 baseSize = vec2(max(uFrontDotSize, cs), max(uFrontDotSize * 0.72, cs));
                        vec2 jump = vec2(
                            floor(hash(vec2(flickerT, uSeed + 1.7)) * mix(4.0, 9.0, frontFlag)) - mix(2.0, 4.0, frontFlag),
                            floor(hash(vec2(flickerT, uSeed + 7.3)) * mix(3.0, 7.0, frontFlag)) - mix(1.0, 3.0, frontFlag)
                        );
                        vec2 movedP = p + jump * baseSize * mix(0.45, 1.0, frontFlag);
                        vec2 coarse = floor(movedP / max(baseSize, vec2(1.0)));
                        float active = step(hash(coarse + vec2(uSeed, flickerT)), uDensity);
                        if (active < 0.5) discard;

                        float shapePick = hash(coarse + vec2(2.3, uSeed));
                        vec2 shapeScale = vec2(
                            mix(0.18, 2.65, hash(coarse + vec2(5.1, uSeed))),
                            mix(0.16, 2.15, hash(coarse + vec2(8.3, uSeed)))
                        );
                        if (shapePick < 0.34) {
                            shapeScale = vec2(
                                mix(1.65, 4.40, hash(coarse + vec2(31.1, uSeed))),
                                mix(0.055, 0.30, hash(coarse + vec2(31.9, uSeed)))
                            );
                        } else if (shapePick > 0.72) {
                            shapeScale = vec2(
                                mix(0.055, 0.32, hash(coarse + vec2(41.1, uSeed))),
                                mix(1.35, 3.40, hash(coarse + vec2(41.9, uSeed)))
                            );
                        }
                        vec2 size = vec2(
                            max(baseSize.x * shapeScale.x, 1.0),
                            max(baseSize.y * shapeScale.y, 1.0)
                        );
                        vec2 localJitter = vec2(
                            (hash(coarse + vec2(11.7, uSeed)) - 0.5) * baseSize.x * 0.85,
                            (hash(coarse + vec2(13.9, uSeed)) - 0.5) * baseSize.y * 0.85
                        );
                        vec2 localP = mod(movedP + localJitter, baseSize);
                        float insideRect = step(localP.x, size.x) * step(localP.y, size.y);
                        if (insideRect < 0.5) discard;

                        vec2 edgeDist = min(localP, size - localP);
                        float edgeBand = 1.0 - smoothstep(0.0, max(1.5, min(size.x, size.y) * 0.18), min(edgeDist.x, edgeDist.y));
                        vec2 biteCell = floor((localP + vec2(flickerT * 3.0, uSeed * 0.13)) / max(vec2(2.0), size * vec2(0.10, 0.18)));
                        float raggedCut = step(
                            0.70 - 0.22 * uDotBreakup,
                            hash(biteCell + coarse * 0.07)
                        ) * edgeBand;
                        if (raggedCut > 0.5) discard;

                        vec2 sliceCell = floor(localP / max(vec2(1.0), size * vec2(0.18, 0.11)));
                        float sliceGap = step(
                            0.82 - 0.26 * uDotBreakup,
                            hash(sliceCell + coarse + vec2(19.1, flickerT))
                        );
                        float holeSeed = hash(coarse + vec2(61.7, uSeed));
                        vec2 holeCenter = size * vec2(
                            mix(0.18, 0.82, hash(coarse + vec2(63.1, uSeed))),
                            mix(0.18, 0.82, hash(coarse + vec2(67.1, uSeed)))
                        );
                        vec2 holeRadius = size * vec2(
                            mix(0.08, 0.32, hash(coarse + vec2(71.1, uSeed))),
                            mix(0.06, 0.26, hash(coarse + vec2(73.1, uSeed)))
                        );
                        vec2 holeP = abs(localP - holeCenter) / max(holeRadius, vec2(1.0));
                        float hole = step(dot(holeP, holeP), 1.0) * step(0.42, holeSeed);
                        if (max(sliceGap * step(0.36, shapePick), hole) > 0.5) discard;

                        float collapse = step(
                            hash(coarse + vec2(flickerT, uSeed * 0.11)),
                            1.0 - (uDotBreakup * mix(0.42, 0.85, frontFlag))
                        );
                        if (collapse < 0.5) discard;

                        float pick = hash(coarse + vec2(17.3, uSeed * 0.03));
                        vec3 chosen = chooseDotColor(pick, hash(coarse + vec2(27.9, uSeed)));
                        float flicker = mix(mix(0.72, 0.42, frontFlag), 1.0, hash(coarse + vec2(flickerT, 91.7)));
                        flicker *= mix(1.0, 0.56 + 0.62 * hash(biteCell + vec2(81.2, flickerT)), edgeBand);
                        vec3 col = chosen * flicker;
                        gl_FragColor = vec4(col, uAlpha);
                        return;
                    }

                    vec2 coarse = floor((p + vec2(flickerT * 5.0, flickerT * 2.0)) / max(max(uFrontDotSize, cs), 1.0));
                    vec2 rectScale = vec2(
                        max(mix(cs, max(uFrontDotSize, cs) * mix(0.35, 2.8, hash(coarse + vec2(uSeed, 5.1))), frontFlag), 1.0),
                        max(mix(cs, max(uFrontDotSize, cs) * mix(0.28, 2.2, hash(coarse + vec2(uSeed, 8.3))), frontFlag), 1.0)
                    );
                    vec2 cell = floor((p + vec2(flickerT * 5.0, flickerT * 2.0)) / rectScale);
                    float r = randCell(cell, uSeed);
                    float collapse = step(
                        hash(cell + vec2(flickerT, uSeed * 0.11)),
                        1.0 - (uDotBreakup * 0.85)
                    );

                    if (r > uDensity || collapse < 0.5) discard;

                    vec3 col = chooseDotColor(hash(cell + vec2(17.3, uSeed * 0.03)), hash(cell + vec2(uSeed, 8.3)));
                    if (frontFlag > 0.5) {
                        float flicker = mix(0.55, 1.0, hash(cell + vec2(flickerT, 91.7)));
                        gl_FragColor = vec4(col * flicker, uAlpha);
                        return;
                    }

                    gl_FragColor = vec4(col, uAlpha);
                    return;
                }

                // ============================
                // Mode 1 : Broken LCD stripes
                // ============================
                if (uMode < 1.5) {
                    float burst = clamp(uHoldStrength, 0.0, 1.0);
                    float tiltDrive = clamp(length(uTiltVec), 0.0, 1.0);
                    float glitchDrive = max(burst, tiltDrive);
                    float stepT = floor((uTime + uTiltVec.x * 0.19 + uTiltVec.y * 0.11) * mix(14.0, 54.0, glitchDrive));
                    float xChunkW = mix(38.0, 142.0, hash(vec2(floor(p.y / 91.0), stepT + uSeed * 0.03)));
                    float xChunk = floor((p.x + stepT * mix(2.0, 18.0, glitchDrive)) / max(xChunkW, 1.0));
                    float yBlock = floor((p.y + uTiltVec.y * 160.0) / 72.0);
                    float chunkWarp = (hash(vec2(xChunk, yBlock + stepT * 0.41)) - 0.5) * uBandHeight * mix(0.35, 3.40, uBandStrength);
                    float chunkStep = (floor(hash(vec2(xChunk + 9.7, stepT + yBlock)) * 5.0) - 2.0) * uBandHeight * 0.42 * uBandStrength;
                    float overlapWarp = step(0.72, hash(vec2(xChunk, stepT + 31.1))) * uBandHeight * mix(-1.15, 1.15, hash(vec2(xChunk, 45.3)));
                    float warpedY = p.y + chunkWarp + chunkStep + overlapWarp + uTiltVec.y * 220.0;
                    float rowH = max(uBandHeight * mix(0.18, 1.45, hash(vec2(floor(warpedY / 28.0), stepT + 2.7))), 2.0);
                    float row = floor((warpedY + stepT * mix(4.0, 24.0, glitchDrive)) / rowH);
                    float rowHash = hash(vec2(row, stepT + uSeed * 0.07));
                    float rowFill = clamp(uStripeFill + 0.42 * burst + 0.28 * tiltDrive + 0.22 * uBandStrength, 0.0, 0.98);
                    float rowAlive = step(1.0 - rowFill, rowHash);
                    float neighborRow = floor((warpedY + rowH * mix(-0.85, 0.85, hash(vec2(xChunk, row)))) / rowH);
                    float bandBlock = step(1.0 - clamp(0.20 + uBandStrength * 0.48 + tiltDrive * 0.24, 0.0, 0.92), hash(vec2(row * 0.23 + xChunk * 0.17, stepT + 19.3)));
                    bandBlock = max(bandBlock, step(0.76, hash(vec2(neighborRow, xChunk + stepT))) * uBandStrength);
                    rowAlive = max(rowAlive, bandBlock);
                    float tiltTear = uTiltVec.x * mix(0.45, 1.85, rowHash);
                    float chunkTear = (hash(vec2(xChunk, row + stepT * 0.63)) - 0.5) * 2.0 * mix(0.25, 1.50, uBandStrength);
                    float rowTear = ((floor(hash(vec2(row, stepT + 5.9)) * 7.0) - 3.0) * mix(0.25, 1.35, glitchDrive) + tiltTear + chunkTear) * uJumpPx;
                    p.x += rowTear * rowAlive;

                    float region = floor((p.x + row * 13.0 + xChunk * 29.0) / 64.0);
                    float w01 = hash(vec2(region, row + 1.3));
                    float stripeW = mix(uStripeMin, uStripeMax, w01) * mix(0.72, 1.18, hash(vec2(row, 3.1)));
                    stripeW = max(stripeW, 2.0);

                    float id = floor(p.x / stripeW);
                    float columnAlive = step(1.0 - clamp(uStripeFill + 0.28 * burst + 0.22 * tiltDrive, 0.0, 0.97), hash(vec2(id, stepT + row * 0.37)));

                    float j = floor(hash(vec2(id, stepT + 3.7)) * 3.0) - 1.0;
                    p.x += (j * mix(0.35, 1.1, glitchDrive) + uTiltVec.x * 0.72) * uJumpPx * columnAlive;

                    float splitLean = clamp(uTiltVec.x, -1.0, 1.0);
                    float stripeR = step(fract((p.x + uRgbSplitPx * (1.0 + splitLean * 0.65)) / stripeW), 0.54);
                    float stripeG = step(fract(p.x / stripeW), 0.54);
                    float stripeB = step(fract((p.x - uRgbSplitPx * (1.0 - splitLean * 0.65)) / stripeW), 0.54);
                    float verticalMask = max(max(stripeR, stripeG), stripeB) * columnAlive;
                    float verticalScratch = step(1.0 - clamp(0.06 + uBandStrength * 0.20 + uRgbSplitPx / 90.0, 0.0, 0.46), hash(vec2(id * 0.19, row + stepT * 0.31)));
                    verticalMask = max(verticalMask, verticalScratch * rowAlive);

                    float ys = floor(hash(vec2(id, stepT + 7.7)) * 2.0) - 1.0;
                    p.y += (ys + uTiltVec.y * 0.85) * uYShiftPx * max(rowAlive, columnAlive);

                    vec2 dotCell = floor((p + vec2(stepT * 17.0 + uTiltVec.x * 130.0, row * 9.0 + uTiltVec.y * 130.0)) / max(uFrontDotSize, 1.0));
                    float snow = step(1.0 - clamp(uDotBreakup * (0.48 + 0.55 * burst + 0.35 * tiltDrive), 0.0, 0.97), hash(dotCell + vec2(uSeed * 0.17, stepT)));
                    float speck = step(0.46, hash(dotCell + vec2(31.7, stepT * 1.7))) * snow;
                    float edgeDust = step(0.82, hash(dotCell + vec2(rowHash * 37.0, stepT + 4.4))) * smoothstep(0.16, 0.96, uBandStrength) * rowAlive;
                    float mask = max(max(rowAlive, verticalMask), max(speck, edgeDust));
                    if (mask < 0.5) discard;

                    float sp = max(uScanPeriod, 1.0);
                    float scan = mix(0.48, 1.18, step(0.5, fract((p.y + stepT) / sp)));

                    vec3 rowCol = chooseColor(hash(vec2(row, 41.7 + stepT)), hash(vec2(id, 71.3)));
                    vec3 dotTint = mix(uAltColor, uWeirdColor, hash(dotCell + vec2(93.4, stepT)));
                    vec3 rgbStripe = vec3(stripeR, stripeG, stripeB);
                    vec3 col = rowCol * mix(vec3(1.0), rgbStripe, clamp(0.45 + uRgbSplitPx / 18.0, 0.0, 1.0));
                    col = mix(col, dotTint, max(speck * 0.82, edgeDust * 0.58));
                    col += vec3(
                        hash(dotCell + vec2(3.1, stepT)),
                        hash(dotCell + vec2(7.7, stepT)),
                        hash(dotCell + vec2(11.9, stepT))
                    ) * max(speck, edgeDust) * mix(0.10, 0.65, glitchDrive);

                    float luma = dot(col, vec3(0.299, 0.587, 0.114));
                    float rowSaturation = mix(0.28, 1.85, hash(vec2(row, stepT + 55.1)));
                    float rowContrast = mix(0.62, 2.20, hash(vec2(row, stepT + 67.7)));
                    col = mix(vec3(luma), col, rowSaturation);
                    col = (col - 0.5) * rowContrast + 0.5;
                    col = mix(col, 1.0 - col, bandBlock * step(0.82, rowHash) * 0.32);
                    col *= mix(vec3(1.0), vec3(1.22, 0.82, 1.34), verticalScratch * 0.55);

                    float holdFlicker = mix(0.72, 1.28, hash(vec2(row + id, stepT + 21.7)));
                    float alpha = uAlpha * mix(0.58, 1.0, mask) * mix(0.82, 1.18, burst);
                    gl_FragColor = vec4(col * scan * holdFlicker, alpha);
                    return;
                }

                // ============================
                // Mode 2 : Grade (simple)
                // ============================
                // ここは後で本物の調整に差し替える前提：軽い色寄せだけ
                vec3 col = mix(uColor, uAltColor, 0.55 * uColorVariance);
                col = mix(col, vec3(col.g, col.g, col.g), 0.18);
                gl_FragColor = vec4(col, uAlpha);
            }
        """.trimIndent()

        program = createProgram(vs, fs)
        if (program == 0) return

        // locations
        aPos = GLES20.glGetAttribLocation(program, "aPos")

        uColorLoc = GLES20.glGetUniformLocation(program, "uColor")
        uAltColorLoc = GLES20.glGetUniformLocation(program, "uAltColor")
        uWeirdColorLoc = GLES20.glGetUniformLocation(program, "uWeirdColor")
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        uOffsetLoc = GLES20.glGetUniformLocation(program, "uOffset")
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
        uTiltLenLoc = GLES20.glGetUniformLocation(program, "uTiltLen")
        uTiltVecLoc = GLES20.glGetUniformLocation(program, "uTiltVec")
        uModeLoc = GLES20.glGetUniformLocation(program, "uMode")
        uColorVarianceLoc = GLES20.glGetUniformLocation(program, "uColorVariance")
        uAccentChanceLoc = GLES20.glGetUniformLocation(program, "uAccentChance")

        uDensityLoc = GLES20.glGetUniformLocation(program, "uDensity")
        uSeedLoc = GLES20.glGetUniformLocation(program, "uSeed")
        uCellSizeLoc = GLES20.glGetUniformLocation(program, "uCellSize")

        uStripeMinLoc = GLES20.glGetUniformLocation(program, "uStripeMin")
        uStripeMaxLoc = GLES20.glGetUniformLocation(program, "uStripeMax")
        uStripeFillLoc = GLES20.glGetUniformLocation(program, "uStripeFill")
        uJumpPxLoc = GLES20.glGetUniformLocation(program, "uJumpPx")
        uYShiftPxLoc = GLES20.glGetUniformLocation(program, "uYShiftPx")
        uScanPeriodLoc = GLES20.glGetUniformLocation(program, "uScanPeriod")
        uRgbChaosLoc = GLES20.glGetUniformLocation(program, "uRgbChaos")
        uRgbSplitPxLoc = GLES20.glGetUniformLocation(program, "uRgbSplitPx")
        uDotBreakupLoc = GLES20.glGetUniformLocation(program, "uDotBreakup")
        uHoldStrengthLoc = GLES20.glGetUniformLocation(program, "uHoldStrength")
        uBandStrengthLoc = GLES20.glGetUniformLocation(program, "uBandStrength")
        uFrontDotSizeLoc = GLES20.glGetUniformLocation(program, "uFrontDotSize")
        uBandHeightLoc = GLES20.glGetUniformLocation(program, "uBandHeight")

        // VBO
        GLES20.glGenBuffers(1, vbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])

        val bb = java.nio.ByteBuffer.allocateDirect(quad.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        bb.put(quad).position(0)

        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, bb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        Log.d("TiltWP", "GridRenderer init OK")
    }

    fun draw(w: Int, h: Int, tiltX: Float, tiltY: Float, tiltMotion: Float, timeSec: Float) {
        if (program == 0) return
        tuning = WallpaperTuning.load(context)
        refreshPaletteAsset(tuning.paletteAsset)

        GLES20.glUseProgram(program)

        // quad
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, 0)

        val tiltLen = sqrt(tiltX * tiltX + tiltY * tiltY).coerceIn(0f, 1f)
        val zabiPreset = isZabiPreset(tuning.presetId)
        val tiltKick = ((tiltMotion - 0.015f) / 0.18f).coerceIn(0f, 1f)
        val drawDelta = if (lastDrawTimeSec > 0f) {
            (timeSec - lastDrawTimeSec).coerceIn(0f, 0.08f)
        } else {
            0f
        }
        lastDrawTimeSec = timeSec
        if (zabiPreset) {
            zabiGlitchTimeSec += drawDelta * (3.20f * tiltKick)
        }
        val shaderTimeSec = if (zabiPreset) zabiGlitchTimeSec else timeSec
        val flickerGain = when (tuning.flickerMode) {
            WallpaperTuning.FLICKER_MODE_NONE -> 0f
            WallpaperTuning.FLICKER_MODE_TILT -> if (zabiPreset) {
                tiltKick
            } else {
                tiltMotion.coerceIn(0f, 1f)
            }
            else -> if (zabiPreset) {
                tiltKick
            } else {
                1f
            }
        }
        val parallaxSensitivity = if (zabiPreset) {
            tuning.parallaxSensitivity.coerceAtMost(0.90f)
        } else {
            tuning.parallaxSensitivity
        }
        val profile = noiseProfile(tuning.presetId)
        val zabiMotionPulse = if (zabiPreset) {
            (0.05f + 1.70f * flickerGain).coerceIn(0.05f, 1.70f)
        } else {
            1f
        }
        val glitchPulse = if (zabiPreset) {
            (zabiMotionPulse + 0.55f * tiltLen * tiltKick).coerceIn(0.05f, 2.20f)
        } else {
            zabiMotionPulse
        }

        // time + tilt
        GLES20.glUniform1f(uTimeLoc, shaderTimeSec)
        GLES20.glUniform1f(uTiltLenLoc, tiltLen)
        GLES20.glUniform2f(uTiltVecLoc, tiltX, tiltY)

        // 描画順：奥→手前（重ねる前提）
        val sorted = layers.sortedByDescending { it.depth }
        val lastIdx = sorted.lastIndex.coerceAtLeast(1)

        sorted.forEachIndexed { idx, layer ->
            val tFront = idx.toFloat() / lastIdx.toFloat() // 0=奥,1=手前
            val (baseColor, altColor, weirdColor) = mixedLayerColors(layer, tFront)
            GLES20.glUniform3f(uColorLoc, baseColor[0], baseColor[1], baseColor[2])
            GLES20.glUniform3f(uAltColorLoc, altColor[0], altColor[1], altColor[2])
            GLES20.glUniform3f(uWeirdColorLoc, weirdColor[0], weirdColor[1], weirdColor[2])
            GLES20.glUniform1f(uColorVarianceLoc, tuning.colorVariance.coerceIn(0f, 1f))
            GLES20.glUniform1f(uAccentChanceLoc, tuning.accentChance.coerceIn(0f, 0.5f))

            // ---- alpha：packのopacityを尊重 ----
            GLES20.glUniform1f(uAlphaLoc, layer.opacity.toFloat().coerceIn(0f, 1f))

            // ザビ系は奥行き差よりも、傾きでグリッチの位相が変わるほうを優先する。
            val depthFactor = if (zabiPreset) {
                0.55f + 0.20f * tFront
            } else {
                0.18f + 2.70f * tFront
            }
            val parallaxBase = if (zabiPreset) 44f else 150f * profile.parallaxBoost
            val parallaxPx = if (zabiPreset) {
                parallaxBase * (0.75f + 0.35f * tiltLen) * depthFactor
            } else {
                parallaxBase * parallaxSensitivity * depthFactor * (0.65f + 0.55f * tiltLen)
            }
            val offX = tiltX * parallaxPx * (w / 1080f)
            val offY = tiltY * parallaxPx * (h / 1920f)
            GLES20.glUniform2f(uOffsetLoc, offX, offY)

            val isGrade = (idx == 0)
            val isNoise = (idx == 1 || idx == 2 || idx == 4)
            val isStripe = (idx == 3 || idx >= 5)

            if (isStripe) {
                // ========== L1-L3 : stripes ==========
                GLES20.glUniform1f(uModeLoc, 1f)

                // 線を増やす → 幅を「全体的に細く」するのが効く
                // 手前ほど “太さレンジ” は大きくしてもよい
                val stripeMaxWidth = (
                    w * tuning.stripeMaxWidthRatio.coerceIn(0.02f, 0.5f) * profile.stripeWidthScale
                ).coerceAtLeast(12f)
                val stripeMin = (
                    4f + stripeMaxWidth * (0.12f + 0.14f * (1f - tFront))
                ).coerceAtLeast(2f)
                val stripeMax = (
                    stripeMin + stripeMaxWidth * (0.42f + 0.55f * tFront) + 18f * tiltLen
                ).coerceAtLeast(stripeMin + 1f)
                GLES20.glUniform1f(uStripeMinLoc, stripeMin)
                GLES20.glUniform1f(uStripeMaxLoc, stripeMax)

                // スカスカ度：手前ほど小さく（＝線が減る）
                // ここを下げると「奥が見える」ようになる
                val fill = (
                    (0.18f + 0.56f * tuning.stripeMaxWidthRatio - 0.12f * tFront) *
                        profile.stripeFillScale * glitchPulse
                    ).coerceIn(0.04f, 0.92f)
                GLES20.glUniform1f(uStripeFillLoc, fill)

                // 破壊ジャンプ量（tiltで増える）
                val motionJump = if (zabiPreset) glitchPulse else 1f
                val jumpPx = (10f + 55f * tiltLen + 25f * tFront) * profile.jumpBoost * motionJump
                val yShiftPx = (6f + 40f * tiltLen + 16f * tFront) * profile.jumpBoost * motionJump
                GLES20.glUniform1f(uJumpPxLoc, jumpPx)
                GLES20.glUniform1f(uYShiftPxLoc, yShiftPx)

                // スキャン周期：2～4px
                val scanP = 2f + 2f * (1f - tFront)
                GLES20.glUniform1f(uScanPeriodLoc, scanP)

                // RGB崩れ：tilt強いほど強く、手前ほどちょい強く
                val rgbChaos = 0f
                GLES20.glUniform1f(uRgbChaosLoc, rgbChaos)
                val splitMotion = if (zabiPreset) glitchPulse else 1f
                val rgbSplitPx = if (idx == 3 || idx == 5) {
                    ((2.0f + tuning.rgbSplitPx * 0.18f + 6.0f * tiltLen) * profile.splitBoost * splitMotion).coerceIn(0f, 18f)
                } else {
                    0f
                }
                GLES20.glUniform1f(uRgbSplitPxLoc, rgbSplitPx)

                val dotBreakup = when {
                    idx >= 6 -> (0.32f * profile.flickerBoost * glitchPulse).coerceIn(0f, 0.82f)
                    idx == 5 -> ((0.46f + 0.12f * tiltLen) * profile.flickerBoost * glitchPulse).coerceIn(0f, 0.88f)
                    idx == 3 -> ((0.38f + 0.10f * tiltLen) * profile.flickerBoost * glitchPulse).coerceIn(0f, 0.82f)
                    else -> 0f
                }
                GLES20.glUniform1f(uDotBreakupLoc, dotBreakup)
                val frontDotSize = when {
                    idx >= 6 -> (tuning.frontDotSizePx * 2.2f).coerceIn(1f, 64f)
                    idx == 5 -> (tuning.frontDotSizePx * 1.45f).coerceIn(1f, 52f)
                    idx == 3 -> tuning.frontDotSizePx.coerceIn(1f, 44f)
                    else -> 1f
                }
                GLES20.glUniform1f(uFrontDotSizeLoc, frontDotSize)

                val holdStrength = if (idx == 3 || idx == 5) {
                    ((0.34f + 0.46f * tiltLen) * profile.flickerBoost * glitchPulse).coerceIn(0f, 0.98f)
                } else {
                    ((0.18f + 0.08f * tiltLen) * profile.flickerBoost * glitchPulse).coerceIn(0f, 0.78f)
                }
                GLES20.glUniform1f(uHoldStrengthLoc, holdStrength)

                val bandStrength = if (idx == 3 || idx == 5) {
                    ((0.58f + 0.18f * tiltLen) * profile.bandBoost * glitchPulse).coerceIn(0f, 1f)
                } else {
                    (0.22f * profile.bandBoost).coerceIn(0.16f, 0.72f)
                }
                GLES20.glUniform1f(uBandStrengthLoc, bandStrength)
                GLES20.glUniform1f(
                    uBandHeightLoc,
                    if (idx == 3 || idx == 5) {
                        if (zabiPreset) 72f else 46f
                    } else {
                        if (zabiPreset) 28f else 22f
                    }
                )

                // dot値は未使用だが安全に入れる
                GLES20.glUniform1f(uDensityLoc, 1f)
                GLES20.glUniform1f(uCellSizeLoc, 8f)
                GLES20.glUniform1f(uSeedLoc, (idx * 31).toFloat())

            } else if (isNoise) {
                // ========== L4 : dot noise ==========
                GLES20.glUniform1f(uModeLoc, 0f)
                GLES20.glUniform1f(
                    uColorVarianceLoc,
                    (0.42f + tuning.colorVariance * 0.95f + 0.08f * (1f - tFront)).coerceIn(0f, 1f)
                )
                GLES20.glUniform1f(
                    uAccentChanceLoc,
                    (0.18f + tuning.accentChance * 1.9f + 0.05f * (1f - tFront)).coerceIn(0f, 0.5f)
                )

                val cell = when (idx) {
                    1 -> (tuning.frontDotSizePx * 0.54f).coerceIn(3f, 14f)
                    2 -> (tuning.frontDotSizePx * 0.60f).coerceIn(3f, 16f)
                    4 -> (tuning.frontDotSizePx * 0.82f).coerceIn(4f, 20f)
                    else -> 2.2f
                } * profile.fineCellScale
                GLES20.glUniform1f(uCellSizeLoc, cell)

                val density = when (idx) {
                    1 -> (0.40f - 0.07f * tiltLen).coerceIn(0.22f, 0.46f)
                    2 -> (0.34f - 0.06f * tiltLen).coerceIn(0.18f, 0.40f)
                    4 -> (0.28f - 0.05f * tiltLen).coerceIn(0.14f, 0.34f)
                    else -> 0.24f
                } * profile.fineDensity
                GLES20.glUniform1f(uDensityLoc, density)

                GLES20.glUniform1f(
                    uSeedLoc,
                    when (idx) {
                        2 -> 211.3f
                        4 -> 251.7f
                        else -> 123.4f
                    }
                )
                GLES20.glUniform1f(uRgbSplitPxLoc, 0f)
                GLES20.glUniform1f(
                    uDotBreakupLoc,
                    when (idx) {
                        2 -> 0.34f
                        4 -> 0.48f
                        else -> 0.42f
                    }
                )
                GLES20.glUniform1f(
                    uHoldStrengthLoc,
                    when (idx) {
                        2 -> ((0.18f + 0.55f * tuning.frontFlicker) * flickerGain * profile.flickerBoost).coerceIn(0f, 1f)
                        4 -> ((0.28f + 0.60f * tuning.frontFlicker) * flickerGain * profile.flickerBoost).coerceIn(0f, 1f)
                        else -> ((0.12f + 0.18f * tiltLen) * flickerGain * profile.flickerBoost).coerceIn(0f, 1f)
                    }
                )
                GLES20.glUniform1f(uBandStrengthLoc, 0.20f)
                GLES20.glUniform1f(
                    uFrontDotSizeLoc,
                    when (idx) {
                        2 -> cell * 1.8f * profile.coarseSizeScale
                        4 -> cell * 2.2f * profile.coarseSizeScale
                        else -> 1f
                    }
                )
                GLES20.glUniform1f(uBandHeightLoc, 32f)

                // stripe値は適当
                GLES20.glUniform1f(uStripeMinLoc, 0f)
                GLES20.glUniform1f(uStripeMaxLoc, 0f)
                GLES20.glUniform1f(uStripeFillLoc, 0f)
                GLES20.glUniform1f(uJumpPxLoc, 0f)
                GLES20.glUniform1f(uYShiftPxLoc, 0f)
                GLES20.glUniform1f(uScanPeriodLoc, 0f)
                GLES20.glUniform1f(uRgbChaosLoc, 0f)

            } else if (isGrade) {
                // ========== L5 : grade (薄い色面) ==========
                GLES20.glUniform1f(uModeLoc, 2f)

                // 奥のパレットが見えるようにごく薄く残す
                GLES20.glUniform1f(uAlphaLoc, 0.18f)

                GLES20.glUniform1f(uDensityLoc, 1f)
                GLES20.glUniform1f(uCellSizeLoc, 9999f)
                GLES20.glUniform1f(uSeedLoc, 0f)

                GLES20.glUniform1f(uStripeMinLoc, 0f)
                GLES20.glUniform1f(uStripeMaxLoc, 0f)
                GLES20.glUniform1f(uStripeFillLoc, 0f)
                GLES20.glUniform1f(uJumpPxLoc, 0f)
                GLES20.glUniform1f(uYShiftPxLoc, 0f)
                GLES20.glUniform1f(uScanPeriodLoc, 0f)
                GLES20.glUniform1f(uRgbChaosLoc, 0f)
                GLES20.glUniform1f(uRgbSplitPxLoc, 0f)
                GLES20.glUniform1f(uDotBreakupLoc, 0f)
                GLES20.glUniform1f(uHoldStrengthLoc, 0f)
                GLES20.glUniform1f(uBandStrengthLoc, 0f)
                GLES20.glUniform1f(uFrontDotSizeLoc, 1f)
                GLES20.glUniform1f(uBandHeightLoc, 32f)
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

            // L4.5: L5 の直後に入る巨大矩形ノイズ
            if (idx == 0) {
                val l45ParallaxPx = if (zabiPreset) {
                    28f * (0.75f + 0.30f * tiltLen)
                } else {
                    150f * parallaxSensitivity * profile.parallaxBoost * 0.40f * (0.55f + 0.20f * tiltLen)
                }
                val l45Base = colorAt(0)
                val l45Alt = colorAt(1)
                val l45Weird = colorAt(paletteFloats.lastIndex.coerceAtLeast(0))
                GLES20.glUniform3f(uColorLoc, l45Base[0], l45Base[1], l45Base[2])
                GLES20.glUniform3f(uAltColorLoc, l45Alt[0], l45Alt[1], l45Alt[2])
                GLES20.glUniform3f(uWeirdColorLoc, l45Weird[0], l45Weird[1], l45Weird[2])
                GLES20.glUniform1f(uColorVarianceLoc, (tuning.colorVariance * 0.85f).coerceIn(0f, 1f))
                GLES20.glUniform1f(uAccentChanceLoc, (tuning.accentChance * 0.65f).coerceIn(0f, 0.5f))
                GLES20.glUniform1f(uAlphaLoc, (0.72f * profile.coarseAlpha).coerceIn(0.12f, 0.92f))
                GLES20.glUniform2f(
                    uOffsetLoc,
                    tiltX * l45ParallaxPx * (w / 1080f),
                    tiltY * l45ParallaxPx * (h / 1920f)
                )
                GLES20.glUniform1f(uModeLoc, 0f)
                GLES20.glUniform1f(uDensityLoc, (0.20f * profile.coarseDensity).coerceIn(0.04f, 0.62f))
                GLES20.glUniform1f(uCellSizeLoc, (18f * profile.coarseSizeScale).coerceIn(6f, 72f))
                GLES20.glUniform1f(uSeedLoc, 611.3f)
                GLES20.glUniform1f(uRgbSplitPxLoc, 0f)
                GLES20.glUniform1f(uDotBreakupLoc, (0.38f * profile.flickerBoost * glitchPulse).coerceIn(0.08f, 0.86f))
                GLES20.glUniform1f(uHoldStrengthLoc, (0.10f * flickerGain * profile.flickerBoost * glitchPulse).coerceIn(0f, 1f))
                GLES20.glUniform1f(uBandStrengthLoc, 0f)
                GLES20.glUniform1f(uFrontDotSizeLoc, (220f * profile.coarseSizeScale).coerceIn(48f, 520f))
                GLES20.glUniform1f(uBandHeightLoc, 32f)
                GLES20.glUniform1f(uStripeMinLoc, 0f)
                GLES20.glUniform1f(uStripeMaxLoc, 0f)
                GLES20.glUniform1f(uStripeFillLoc, 0f)
                GLES20.glUniform1f(uJumpPxLoc, 0f)
                GLES20.glUniform1f(uYShiftPxLoc, 0f)
                GLES20.glUniform1f(uScanPeriodLoc, 0f)
                GLES20.glUniform1f(uRgbChaosLoc, 0f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            }
        }

        // 最前面のドット崩壊専用レイヤー L0
        val l0ParallaxPx = if (zabiPreset) {
            58f * (0.80f + 0.40f * tiltLen)
        } else {
            150f * parallaxSensitivity * profile.parallaxBoost * 3.45f * (0.75f + 0.55f * tiltLen)
        }
        val l0Base = colorAt(0)
        val l0Alt = colorAt(2.coerceAtMost(paletteFloats.lastIndex.coerceAtLeast(0)))
        val l0Weird = floatArrayOf(0.03f, 0.03f, 0.03f)
        GLES20.glUniform3f(uColorLoc, l0Base[0], l0Base[1], l0Base[2])
        GLES20.glUniform3f(uAltColorLoc, l0Alt[0], l0Alt[1], l0Alt[2])
        GLES20.glUniform3f(uWeirdColorLoc, l0Weird[0], l0Weird[1], l0Weird[2])
        GLES20.glUniform1f(uColorVarianceLoc, (tuning.colorVariance * 1.1f).coerceIn(0f, 1f))
        GLES20.glUniform1f(uAccentChanceLoc, (0.08f + tuning.accentChance * 1.35f).coerceIn(0f, 0.45f))
        GLES20.glUniform1f(uAlphaLoc, 0.86f)
        GLES20.glUniform2f(
            uOffsetLoc,
            tiltX * l0ParallaxPx * (w / 1080f),
            tiltY * l0ParallaxPx * (h / 1920f)
        )
        GLES20.glUniform1f(uModeLoc, 0f)
        GLES20.glUniform1f(uDensityLoc, ((0.05f + 0.07f * (1f - tiltLen)) * profile.l0Density).coerceIn(0.01f, 0.36f))
        GLES20.glUniform1f(uCellSizeLoc, (3.2f * profile.l0CellScale).coerceIn(1.6f, 12f))
        GLES20.glUniform1f(uSeedLoc, 321.7f)
        GLES20.glUniform1f(uRgbSplitPxLoc, 0f)
        GLES20.glUniform1f(uDotBreakupLoc, (0.64f * profile.flickerBoost * glitchPulse).coerceIn(0.06f, 0.94f))
        GLES20.glUniform1f(uHoldStrengthLoc, ((0.36f + 0.62f * tuning.frontFlicker) * flickerGain * profile.flickerBoost * glitchPulse).coerceIn(0f, 1f))
        GLES20.glUniform1f(uBandStrengthLoc, 0f)
        GLES20.glUniform1f(uFrontDotSizeLoc, (tuning.l0MaxSizePx * profile.coarseSizeScale).coerceIn(8f, 520f))
        GLES20.glUniform1f(uBandHeightLoc, 32f)
        GLES20.glUniform1f(uStripeMinLoc, 0f)
        GLES20.glUniform1f(uStripeMaxLoc, 0f)
        GLES20.glUniform1f(uStripeFillLoc, 0f)
        GLES20.glUniform1f(uJumpPxLoc, 0f)
        GLES20.glUniform1f(uYShiftPxLoc, 0f)
        GLES20.glUniform1f(uScanPeriodLoc, 0f)
        GLES20.glUniform1f(uRgbChaosLoc, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        if (zabiPreset) {
            // 最前面に細長い破片を追加して、正方形グリッド感を崩す
            val shardBase = colorAt(1.coerceAtMost(paletteFloats.lastIndex.coerceAtLeast(0)))
            val shardAlt = colorAt(3.coerceAtMost(paletteFloats.lastIndex.coerceAtLeast(0)))
            val shardWeird = colorAt(paletteFloats.lastIndex.coerceAtLeast(0))
            GLES20.glUniform3f(uColorLoc, shardBase[0], shardBase[1], shardBase[2])
            GLES20.glUniform3f(uAltColorLoc, shardAlt[0], shardAlt[1], shardAlt[2])
            GLES20.glUniform3f(uWeirdColorLoc, shardWeird[0], shardWeird[1], shardWeird[2])
            GLES20.glUniform1f(uColorVarianceLoc, (0.72f + tuning.colorVariance * 0.55f).coerceIn(0f, 1f))
            GLES20.glUniform1f(uAccentChanceLoc, (0.14f + tuning.accentChance * 1.15f).coerceIn(0f, 0.50f))
            GLES20.glUniform1f(uAlphaLoc, 0.54f)
            GLES20.glUniform2f(
                uOffsetLoc,
                -tiltX * l0ParallaxPx * 0.34f * (w / 1080f),
                tiltY * l0ParallaxPx * 0.20f * (h / 1920f)
            )
            GLES20.glUniform1f(uModeLoc, 0f)
            GLES20.glUniform1f(uDensityLoc, ((0.04f + 0.05f * (1f - tiltLen)) * profile.l0Density).coerceIn(0.01f, 0.24f))
            GLES20.glUniform1f(uCellSizeLoc, (2.4f * profile.l0CellScale).coerceIn(1.4f, 8f))
            GLES20.glUniform1f(uSeedLoc, 421.9f)
            GLES20.glUniform1f(uRgbSplitPxLoc, 0f)
            GLES20.glUniform1f(uDotBreakupLoc, (0.82f * profile.flickerBoost * glitchPulse).coerceIn(0.10f, 0.96f))
            GLES20.glUniform1f(uHoldStrengthLoc, ((0.24f + 0.52f * tuning.frontFlicker) * flickerGain * profile.flickerBoost * glitchPulse).coerceIn(0f, 1f))
            GLES20.glUniform1f(uBandStrengthLoc, 0f)
            GLES20.glUniform1f(uFrontDotSizeLoc, (tuning.l0MaxSizePx * 0.52f * profile.coarseSizeScale).coerceIn(6f, 220f))
            GLES20.glUniform1f(uBandHeightLoc, 32f)
            GLES20.glUniform1f(uStripeMinLoc, 0f)
            GLES20.glUniform1f(uStripeMaxLoc, 0f)
            GLES20.glUniform1f(uStripeFillLoc, 0f)
            GLES20.glUniform1f(uJumpPxLoc, 0f)
            GLES20.glUniform1f(uYShiftPxLoc, 0f)
            GLES20.glUniform1f(uScanPeriodLoc, 0f)
            GLES20.glUniform1f(uRgbChaosLoc, 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        }

        // 最前面の軽い画面破損オーバーレイ
        val screenParallaxPx = if (zabiPreset) {
            22f * (0.70f + 0.25f * tiltLen)
        } else {
            150f * parallaxSensitivity * profile.parallaxBoost * 0.24f * (0.45f + 0.18f * tiltLen)
        }
        val screenBase = floatArrayOf(0.98f, 0.98f, 0.98f)
        val screenAlt = colorAt(0)
        val screenWeird = colorAt(1.coerceAtMost(paletteFloats.lastIndex.coerceAtLeast(0)))
        GLES20.glUniform3f(uColorLoc, screenBase[0], screenBase[1], screenBase[2])
        GLES20.glUniform3f(uAltColorLoc, screenAlt[0], screenAlt[1], screenAlt[2])
        GLES20.glUniform3f(uWeirdColorLoc, screenWeird[0], screenWeird[1], screenWeird[2])
        GLES20.glUniform1f(uColorVarianceLoc, 0.72f)
        GLES20.glUniform1f(uAccentChanceLoc, 0.24f)
        GLES20.glUniform1f(uAlphaLoc, 0.22f)
        GLES20.glUniform2f(
            uOffsetLoc,
            tiltX * screenParallaxPx * (w / 1080f),
            tiltY * screenParallaxPx * (h / 1920f)
        )
        GLES20.glUniform1f(uModeLoc, 1f)
        GLES20.glUniform1f(uDensityLoc, 1f)
        GLES20.glUniform1f(uCellSizeLoc, 8f)
        GLES20.glUniform1f(uSeedLoc, 901.7f)
        GLES20.glUniform1f(uStripeMinLoc, 1f)
        GLES20.glUniform1f(uStripeMaxLoc, if (zabiPreset) 9.5f else 3.2f)
        GLES20.glUniform1f(
            uStripeFillLoc,
            if (zabiPreset) {
                (0.34f * profile.stripeFillScale * glitchPulse).coerceIn(0.18f, 0.88f)
            } else {
                (0.08f * profile.stripeFillScale * glitchPulse).coerceIn(0.02f, 0.36f)
            }
        )
        GLES20.glUniform1f(
            uJumpPxLoc,
            (if (zabiPreset) 36f + 62f * tiltLen else 24f + 46f * tiltLen) * profile.jumpBoost * glitchPulse
        )
        GLES20.glUniform1f(
            uYShiftPxLoc,
            (if (zabiPreset) 20f + 36f * tiltLen else 12f + 28f * tiltLen) * profile.jumpBoost * glitchPulse
        )
        GLES20.glUniform1f(uScanPeriodLoc, if (zabiPreset) 1.0f else 1.5f)
        GLES20.glUniform1f(uRgbChaosLoc, 0f)
        val screenRgbSplitPx = if (zabiPreset) {
            ((4.2f + tuning.rgbSplitPx * 0.24f + 7.5f * tiltLen) * profile.splitBoost * glitchPulse).coerceIn(0f, 38f)
        } else {
            ((2.8f + tuning.rgbSplitPx * 0.18f + 4.8f * tiltLen) * profile.splitBoost * glitchPulse).coerceIn(0f, 28f)
        }
        GLES20.glUniform1f(uRgbSplitPxLoc, screenRgbSplitPx)
        GLES20.glUniform1f(
            uDotBreakupLoc,
            if (zabiPreset) {
                (0.36f * profile.flickerBoost * glitchPulse).coerceIn(0.08f, 0.92f)
            } else {
                (0.22f * profile.flickerBoost * glitchPulse).coerceIn(0.02f, 0.72f)
            }
        )
        GLES20.glUniform1f(uHoldStrengthLoc, ((0.24f + 0.52f * tuning.frontFlicker) * flickerGain * profile.flickerBoost * glitchPulse).coerceIn(0f, 1f))
        GLES20.glUniform1f(uBandStrengthLoc, (0.68f * profile.bandBoost * glitchPulse).coerceIn(0f, 1f))
        GLES20.glUniform1f(uFrontDotSizeLoc, if (zabiPreset) 4f else 18f)
        GLES20.glUniform1f(uBandHeightLoc, if (zabiPreset) 9f else 14f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(0)
    }
    // ---------- shader compile/link ----------
    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)

            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e("TiltWP", "Shader compile error:\n${GLES20.glGetShaderInfoLog(s)}")
                GLES20.glDeleteShader(s)
                return 0
            }
            return s
        }

        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        if (vs == 0 || fs == 0) return 0

        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)

        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e("TiltWP", "Program link error:\n${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }

        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }
}
