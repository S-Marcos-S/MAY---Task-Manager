// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.ui.theme.glass

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages wallpaper textures and renders refractive liquid/frosted glass backdrops
 * mapped directly to the screen coordinate system for MAY Task Manager.
 */
object WallpaperGlassProvider {

    private const val SHARP_WIDTH = 720
    private const val FROSTED_SAMPLE_WIDTH = 180

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _wallpaperTick = MutableStateFlow(0)
    val wallpaperTick: StateFlow<Int> = _wallpaperTick.asStateFlow()

    private val _currentWallpaperFlow = MutableStateFlow<Bitmap?>(null)
    val currentWallpaperFlow: StateFlow<Bitmap?> = _currentWallpaperFlow.asStateFlow()

    @Volatile
    private var cachedSharp: Bitmap? = null

    @Volatile
    private var cachedBlurred: Bitmap? = null

    val currentWallpaper: Bitmap?
        get() = cachedSharp

    @Volatile
    private var fallbackBitmap: Bitmap? = null

    private var liquidShaderInstance: Any? = null
    @Volatile
    private var appContextRef: Context? = null

    private val emptyMaskShader: BitmapShader by lazy {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val reusablePath = Path()
    private val reusableRadii = FloatArray(8)
    private val reusableMatrix = Matrix()
    private val reusablePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reusableTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reusableBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    @Volatile
    private var cachedActiveBitmapShader: BitmapShader? = null
    @Volatile
    private var lastBitmapForShader: Bitmap? = null

    private fun getOrCreateBitmapShader(bitmap: Bitmap): BitmapShader {
        var bs = cachedActiveBitmapShader
        if (bs == null || lastBitmapForShader !== bitmap) {
            bs = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            cachedActiveBitmapShader = bs
            lastBitmapForShader = bitmap
        }
        return bs
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        if (!initialized.compareAndSet(false, true)) return
        refreshWallpaper(appContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val wm = WallpaperManager.getInstance(appContext)
            runCatching {
                wm.addOnColorsChangedListener(
                    { _, which ->
                        if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                            refreshWallpaper(appContext)
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
        }
    }

    fun refreshWallpaper(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        scope.launch {
            val bitmap = loadWallpaperBitmap(appContext)
            if (bitmap != null) {
                processWallpaper(bitmap)
            } else {
                _currentWallpaperFlow.value = null
                val dm = appContext.resources.displayMetrics
                val w = max(1, dm.widthPixels)
                val h = max(1, dm.heightPixels)
                fallbackBitmap = createFallbackWallpaper(appContext, w, h)
            }
            withContext(Dispatchers.Main) {
                _wallpaperTick.value += 1
            }
        }
    }

    private fun processWallpaper(source: Bitmap) {
        val aspect = source.width.toFloat() / source.height.coerceAtLeast(1)
        val sharpW = min(SHARP_WIDTH, max(1, source.width))
        val sharpH = max(1, (sharpW / aspect).roundToInt())
        val sharp = if (source.width == sharpW && source.height == sharpH) {
            source
        } else {
            Bitmap.createScaledBitmap(source, sharpW, sharpH, true)
        }
        cachedSharp = sharp

        val blurW = min(FROSTED_SAMPLE_WIDTH, sharpW)
        val blurred = GlassShader.sampleBlur(sharp, blurW, 4)
        cachedBlurred = blurred
        _currentWallpaperFlow.value = sharp
    }

    private fun loadWallpaperBitmap(context: Context): Bitmap? {
        val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull()

        // 1. Try WallpaperManager.getWallpaperFile (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && wm != null) {
            runCatching {
                wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use { pfd ->
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                }
            }.getOrNull()?.let { return it }
        }

        // 2. Try WallpaperManager drawable / peekDrawable
        if (wm != null) {
            runCatching {
                val d = wm.drawable ?: wm.peekDrawable()
                if (d is BitmapDrawable && d.bitmap != null) {
                    return d.bitmap
                }
                if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                    val b = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)
                    val c = Canvas(b)
                    d.setBounds(0, 0, b.width, b.height)
                    d.draw(c)
                    return b
                }
            }
        }

        // 3. Try direct read from standard Android wallpaper storage locations
        val standardPaths = listOf(
            "/data/system/users/0/wallpaper",
            "/data/system/users/0/wallpaper_orig",
            "/data/system/users/0/wallpaper_lock",
        )
        for (path in standardPaths) {
            runCatching {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val b = BitmapFactory.decodeFile(file.absolutePath)
                    if (b != null) return b
                }
            }
        }

        // 4. Try root read if accessible (MAY is a root task manager)
        runCatching {
            val cacheFile = File(context.cacheDir, "sys_wallpaper.raw")
            val script = "if [ -f /data/system/users/0/wallpaper ]; then cat /data/system/users/0/wallpaper > ${cacheFile.absolutePath}; elif [ -f /data/system/users/0/wallpaper_orig ]; then cat /data/system/users/0/wallpaper_orig > ${cacheFile.absolutePath}; fi && chmod 644 ${cacheFile.absolutePath}"
            val process = ProcessBuilder("su", "-c", script).start()
            val finished = process.waitFor(2500, TimeUnit.MILLISECONDS)
            if (finished && cacheFile.exists() && cacheFile.length() > 0) {
                val b = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (b != null) return b
            }
        }

        // 5. Try built-in drawable
        if (wm != null) {
            runCatching {
                val d = wm.getBuiltInDrawable(WallpaperManager.FLAG_SYSTEM)
                if (d is BitmapDrawable && d.bitmap != null) {
                    return d.bitmap
                }
            }
        }

        return null
    }

    private fun createFallbackWallpaper(context: Context, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val baseColor1 = if (isDark) 0xFF14171F.toInt() else 0xFFE6E9F0.toInt()
        val baseColor2 = if (isDark) 0xFF1F2430.toInt() else 0xFFD8DCE6.toInt()
        val spotColor1 = if (isDark) 0x332B3A4A.toInt() else 0x33B0C4DE.toInt()
        val spotColor2 = if (isDark) 0x33352B44.toInt() else 0x33D2BFE3.toInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), baseColor1, baseColor2, Shader.TileMode.CLAMP)
        }
        canvas.drawPaint(paint)

        val spotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.35f, height * 0.3f, width * 0.65f,
                spotColor1, 0, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(width * 0.35f, height * 0.3f, width * 0.65f, spotPaint)

        spotPaint.shader = RadialGradient(
            width * 0.75f, height * 0.7f, width * 0.55f,
            spotColor2, 0, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(width * 0.75f, height * 0.7f, width * 0.55f, spotPaint)

        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateLiquidShader(): RuntimeShader {
        var s = liquidShaderInstance as? RuntimeShader
        if (s == null) {
            s = RuntimeShader(GlassShader.LIQUID)
            liquidShaderInstance = s
        }
        return s
    }

    fun getRealWindowSize(context: Context, view: View? = null): Pair<Float, Float> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = wm.currentWindowMetrics
                val bounds = metrics.bounds
                return bounds.width().toFloat() to bounds.height().toFloat()
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
            }
        }
        val dm = context.resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    fun drawGlassElement(
        canvas: Canvas,
        bounds: RectF,
        positionInWindow: Offset,
        screenWidth: Float,
        screenHeight: Float,
        topCorner: Float,
        bottomCorner: Float,
        style: GlassStyle = GlassStyle.DEFAULT,
        surfaceColor: Int = Color.WHITE,
        tintOpacity: Int = 20,
        density: Float = 1f,
        night: Boolean = true,
    ) {
        if (bounds.isEmpty) return

        val path = reusablePath.apply {
            reset()
            if (topCorner == bottomCorner) {
                addRoundRect(bounds, topCorner, topCorner, Path.Direction.CW)
            } else {
                reusableRadii[0] = topCorner
                reusableRadii[1] = topCorner
                reusableRadii[2] = topCorner
                reusableRadii[3] = topCorner
                reusableRadii[4] = bottomCorner
                reusableRadii[5] = bottomCorner
                reusableRadii[6] = bottomCorner
                reusableRadii[7] = bottomCorner
                addRoundRect(bounds, reusableRadii, Path.Direction.CW)
            }
        }

        GlassShader.drawShadow(canvas, path, bounds, style.shadow, density)

        val base = cachedSharp ?: fallbackBitmap
        var targetWidth = screenWidth
        var targetHeight = screenHeight
        if (targetWidth <= 0f || targetHeight <= 0f) {
            appContextRef?.let { ctx ->
                val (w, h) = getRealWindowSize(ctx)
                targetWidth = w
                targetHeight = h
            }
        }

        if (base != null && targetWidth > 0f && targetHeight > 0f) {
            val isTiramisu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val activeBitmap = cachedBlurred ?: base
            val scale = targetWidth / activeBitmap.width.toFloat().coerceAtLeast(1f)
            val scaledWidth = targetWidth
            val scaledHeight = activeBitmap.height.toFloat() * scale
            val dx = 0f
            val dy = (targetHeight - scaledHeight) / 2f

            val shaderMatrix = reusableMatrix.apply {
                reset()
                setScale(scale, scale)
                postTranslate(dx, dy)
                postTranslate(-positionInWindow.x, -positionInWindow.y)
            }

            val imageShader = getOrCreateBitmapShader(activeBitmap).apply {
                setLocalMatrix(shaderMatrix)
            }

            val dimColor = if (style.dimFactor > 0f) {
                if (night) {
                    val alpha = (85 * style.dimFactor).roundToInt().coerceIn(0, 255)
                    Color.argb(alpha, 14, 18, 26)
                } else {
                    val alpha = (95 * style.dimFactor).roundToInt().coerceIn(0, 255)
                    Color.argb(alpha, 245, 247, 252)
                }
            } else {
                Color.TRANSPARENT
            }

            if (isTiramisu) {
                val shader = getOrCreateLiquidShader()
                shader.setInputShader("image", imageShader)
                shader.setFloatUniform("origin", bounds.left, bounds.top)
                shader.setFloatUniform("size", bounds.width(), bounds.height())
                GlassShader.setRadii(shader, topCorner, bottomCorner)
                GlassShader.applyStyle(shader, bounds.width(), bounds.height(), style)
                GlassShader.setDim(shader, dimColor)
                shader.setInputShader("mask", emptyMaskShader)
                shader.setFloatUniform("useMask", 0f)
                shader.setFloatUniform("maskOrigin", 0f, 0f)
                shader.setFloatUniform("maskScale", 1f)
                shader.setFloatUniform("maskRange", 1f)

                val paint = reusablePaint.apply {
                    reset()
                    isAntiAlias = true
                    this.shader = shader
                }
                canvas.drawPath(path, paint)
            } else {
                val paint = reusablePaint.apply {
                    reset()
                    isAntiAlias = true
                    this.shader = imageShader
                    colorFilter = GlassShader.backdropFilter(dimColor, style.saturation)
                }
                canvas.drawPath(path, paint)
            }
        } else {
            val basePaint = reusableBasePaint.apply {
                reset()
                isAntiAlias = true
                color = if (night) 0x33FFFFFF else 0x55FFFFFF
            }
            canvas.drawPath(path, basePaint)
        }

        if (tintOpacity > 0) {
            val tintAlpha = (tintOpacity.coerceIn(0, 100) * 255 / 100)
            val tintPaint = reusableTintPaint.apply {
                reset()
                isAntiAlias = true
                color = ColorUtils.setAlphaComponent(surfaceColor, tintAlpha)
            }
            canvas.drawPath(path, tintPaint)
        }

        GlassShader.drawOutline(canvas, bounds, topCorner, bottomCorner, style, 1f, density)
    }
}
