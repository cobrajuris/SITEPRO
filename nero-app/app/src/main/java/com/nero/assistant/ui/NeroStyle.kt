package com.nero.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.nero.assistant.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Linguagem visual do Nero: fundos orgânicos em verde-oliva, vidro fosco, degradês suaves
 * (água → pêssego → lilás) e tipografia Urbanist com títulos em matriz de pontos (Doto).
 */
object N {
    // Tinta e superfícies
    val INK = Color.parseColor("#15130F")
    val CREAM = Color.parseColor("#F3F1E8")
    val TEXT = Color.parseColor("#F7F5EE")
    val MUTED = Color.parseColor("#C9C6BB")
    val FAINT = Color.parseColor("#8F8C82")

    // Natureza
    val OLIVE_DEEP = Color.parseColor("#1F2412")
    val OLIVE = Color.parseColor("#3C4524")
    val MOSS = Color.parseColor("#7E8A55")
    val SAGE = Color.parseColor("#B4BD8E")

    // Acentos do degradê
    val LILAC = Color.parseColor("#E4CDF7")
    val LILAC_DEEP = Color.parseColor("#C5A2EA")
    val PEACH = Color.parseColor("#F4BE95")
    val AQUA = Color.parseColor("#93D9D4")

    // Vidro
    val GLASS = Color.argb(0x26, 0xFF, 0xFF, 0xFF)
    val GLASS_STROKE = Color.argb(0x33, 0xFF, 0xFF, 0xFF)
    val GLASS_DARK = Color.argb(0xC4, 0x1E, 0x1B, 0x17)
    val GLASS_DARKER = Color.argb(0xE6, 0x17, 0x15, 0x12)
    val ERROR = Color.parseColor("#FFB4A8")
}

/** Fontes empacotadas no app (licença OFL). */
class NeroFonts(context: Context) {
    val light: Typeface = context.resources.getFont(R.font.urbanist_light)
    val regular: Typeface = context.resources.getFont(R.font.urbanist_regular)
    val semibold: Typeface = context.resources.getFont(R.font.urbanist_semibold)
    val dots: Typeface = context.resources.getFont(R.font.doto_bold)
}

object Shapes {
    fun glass(context: Context, radiusDp: Int, fill: Int = N.GLASS, stroke: Int? = N.GLASS_STROKE) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = context.dp(radiusDp).toFloat()
            if (stroke != null) setStroke(context.dp(1), stroke)
        }

    fun solid(context: Context, radiusDp: Int, color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }

    fun circle(color: Int, stroke: Int? = null, context: Context? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke != null && context != null) setStroke(context.dp(1), stroke)
    }

    /** O degradê do assistente: água → pêssego → lilás. */
    fun aurora(context: Context, radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(N.AQUA, N.PEACH, N.LILAC_DEEP),
    ).apply { cornerRadius = context.dp(radiusDp).toFloat() }

    fun auroraCircle() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(N.AQUA, N.PEACH, N.LILAC_DEEP),
    ).apply { shape = GradientDrawable.OVAL }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/**
 * Fundo vivo: manchas suaves de luz que derivam devagar, como as fotos desfocadas de grama
 * das referências, mas desenhadas pelo app (sem imagens de terceiros).
 */
class AuroraBackground(context: Context) : View(context) {

    private data class Blob(val color: Int, val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)

    private val blobs = listOf(
        Blob(N.SAGE, 0.80f, 0.12f, 0.75f, 0.9f, 0.0f),
        Blob(Color.parseColor("#DCDAC4"), 0.35f, 0.02f, 0.55f, 0.7f, 1.7f),
        Blob(N.MOSS, 0.10f, 0.55f, 0.70f, 0.6f, 3.1f),
        Blob(N.LILAC_DEEP, 0.95f, 0.85f, 0.55f, 0.8f, 4.4f),
        Blob(Color.parseColor("#5C6A3A"), 0.55f, 0.75f, 0.65f, 0.5f, 2.2f),
    )
    private val alphas = intArrayOf(0x8C, 0x55, 0x80, 0x45, 0x90)
    private val basePaint = Paint()
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaders: List<RadialGradient> = emptyList()
    private var running = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        basePaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), N.OLIVE, N.OLIVE_DEEP, Shader.TileMode.CLAMP)
        val size = max(w, h).toFloat()
        shaders = blobs.mapIndexed { i, b ->
            val color = (b.color and 0x00FFFFFF) or (alphas[i] shl 24)
            RadialGradient(0f, 0f, b.r * size, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
        val t = SystemClock.uptimeMillis() / 9000f
        blobs.forEachIndexed { i, b ->
            val shader = shaders.getOrNull(i) ?: return@forEachIndexed
            val cx = (b.x + 0.08f * sin(t * b.speed + b.phase)) * width
            val cy = (b.y + 0.06f * cos(t * b.speed * 0.8f + b.phase)) * height
            blobPaint.shader = shader
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawCircle(0f, 0f, b.r * max(width, height), blobPaint)
            canvas.restore()
        }
        if (running && isShown) postInvalidateDelayed(50)
    }
}

/** Organiza pílulas lado a lado, quebrando linha quando não cabem (como os chips das referências). */
class FlowLayout(context: Context, private val gapPx: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            x += child.measuredWidth + gapPx
            rowHeight = max(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            y + rowHeight + paddingTop + paddingBottom,
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            child.layout(
                paddingLeft + x, paddingTop + y,
                paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight,
            )
            x += child.measuredWidth + gapPx
            rowHeight = max(rowHeight, child.measuredHeight)
        }
    }
}
