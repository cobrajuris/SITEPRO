package com.enzo.ilhadinamica

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * A bolha em volta da câmera, desenhada nativamente (fluida como um componente do sistema):
 * bolinha preta + anel fino de carregamento na cor da aba. Não pulsa.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val onTap: () -> Unit) : View(context) {

    private val density = resources.displayMetrics.density
    var bubbleScale = 1f
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(15, 255, 255, 255)
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1E1F24")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E8814B")
    }
    private val oval = RectF()

    private var progress = 0f
    private var progressAnim: ValueAnimator? = null
    private var colorAnim: ValueAnimator? = null
    private var targetColor = arc.color
    private var lastW = -1

    /** Atualiza cor e progresso com transição suave (1 s linear, como um carregamento). */
    fun setState(color: Int, pct: Float) {
        val p = pct.coerceIn(0f, 1f)
        if (color != targetColor) {
            targetColor = color
            colorAnim?.cancel()
            colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), arc.color, color).apply {
                duration = 380
                addUpdateListener { arc.color = it.animatedValue as Int; invalidate() }
                start()
            }
        }
        if (kotlin.math.abs(p - progress) < 0.0005f) return
        progressAnim?.cancel()
        // Recomeço (ex.: nova faixa) vai direto, sem dar a volta para trás.
        val jump = p < progress - 0.25f || p > progress + 0.5f
        if (jump) {
            progress = p
            invalidate()
            return
        }
        progressAnim = ValueAnimator.ofFloat(progress, p).apply {
            duration = 1000
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = density * bubbleScale
        val r = 17f * s
        if (width != lastW) {
            lastW = width
            fill.shader = RadialGradient(
                cx, cy - r * 0.24f, r * 1.1f,
                Color.parseColor("#141418"), Color.parseColor("#050506"), Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, fill)
        edge.strokeWidth = 1f * s
        canvas.drawCircle(cx, cy, r - 0.5f * s, edge)
        val ringR = 15f * s
        track.strokeWidth = 2f * s
        arc.strokeWidth = 2.2f * s
        canvas.drawCircle(cx, cy, ringR, track)
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        if (progress > 0.002f) canvas.drawArc(oval, -90f, 360f * progress, false, arc)
    }

    private val press = PathInterpolator(0.3f, 0f, 0.2f, 1f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(110).setInterpolator(press).start()
                return true
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(press).start()
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                    onTap()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(press).start()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
