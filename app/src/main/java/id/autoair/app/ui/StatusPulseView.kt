package id.autoair.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import id.autoair.app.R

/**
 * Indikator status hero: titik pusat berwarna dikelilingi cincin yang
 * berdenyut keluar saat pemantauan aktif, diam saat berhenti.
 *
 * Digambar manual (bukan drawable berlapis) supaya denyutnya halus dan
 * warnanya mengikuti status jaringan tanpa perlu banyak resource.
 */
class StatusPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Mode { OFF, OK, WARN, BAD }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = ContextCompat.getColor(context, R.color.outline)
    }

    private var mode: Mode = Mode.OFF
    private var pulseFraction = 0f
    private var animator: ValueAnimator? = null

    fun setMode(newMode: Mode) {
        if (newMode == mode) return
        mode = newMode
        corePaint.color = ContextCompat.getColor(
            context,
            when (newMode) {
                Mode.OK -> R.color.ok
                Mode.WARN -> R.color.warn
                Mode.BAD -> R.color.bad
                Mode.OFF -> R.color.off
            }
        )
        pulsePaint.color = corePaint.color
        if (newMode == Mode.OFF) stopPulse() else startPulse()
        invalidate()
    }

    private fun startPulse() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        animator?.cancel()
        animator = null
        pulseFraction = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f
        val coreR = maxR * 0.30f

        // Cincin denyut: membesar dari inti ke tepi sambil memudar.
        if (mode != Mode.OFF) {
            val r = coreR + (maxR - coreR) * pulseFraction
            pulsePaint.alpha = ((1f - pulseFraction) * 140).toInt()
            pulsePaint.strokeWidth = dp(2f)
            canvas.drawCircle(cx, cy, r, pulsePaint)
        }

        // Cincin dasar statis sebagai bingkai.
        canvas.drawCircle(cx, cy, maxR - ringPaint.strokeWidth, ringPaint)

        // Inti status.
        canvas.drawCircle(cx, cy, coreR, corePaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
