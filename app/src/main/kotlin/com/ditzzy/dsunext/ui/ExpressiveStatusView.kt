package com.ditzzy.dsunext.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.ditzzy.dsunext.R
import com.google.android.material.color.MaterialColors

class ExpressiveStatusView : View {
    private val backShape: Path = createScallopedShape(BACK_LOBES, BACK_DEPTH)

    private val frontShape: Path = createScallopedShape(FRONT_LOBES, FRONT_DEPTH)

    private val backPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val frontPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ripplePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val overshoot: Interpolator = OvershootInterpolator(1.6f)

    private val decelerate: Interpolator = DecelerateInterpolator(1.5f)

    private var icon: Drawable? = null

    private var rippleColor: Int = 0

    private var hasStatus: Boolean = false

    // 0..1 progress of each animation, read while drawing
    private var enter: Float = 1f

    private var spin: Float = 0f

    private var ripple: Float = 0f

    private var enterAnimator: ValueAnimator? = null

    private var spinAnimator: ValueAnimator? = null

    private var rippleAnimator: ValueAnimator? = null

    constructor(context: Context) : this(context, null) {

    }

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0) {

    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {

        backPaint.setStyle(Paint.Style.FILL)
        frontPaint.setStyle(Paint.Style.FILL)
        ripplePaint.setStyle(Paint.Style.STROKE)
        ripplePaint.setStrokeWidth((getResources().getDisplayMetrics().density * 2f))
    }

    /** Shows the result and plays the entrance animation. */
    fun setStatus(success: Boolean) {
        val container: Int = resolve((if (success) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorErrorContainer))
        val accent: Int = resolve((if (success) androidx.appcompat.R.attr.colorPrimary else androidx.appcompat.R.attr.colorError))
        val onAccent: Int = resolve((if (success) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnError))
        backPaint.setColor(container)
        frontPaint.setColor(accent)
        rippleColor = accent
        val drawable: Drawable? = AppCompatResources.getDrawable(getContext(), (if (success) R.drawable.ic_check else R.drawable.ic_close))
        icon = (if ((drawable != null)) drawable!!.mutate() else null)
        if ((icon != null)) {
            icon!!.setTint(onAccent)
        }
        hasStatus = true
        restartAnimations()
        invalidate()
    }

    private fun resolve(attr: Int): Int {
        return MaterialColors.getColor(this, attr)
    }

    protected override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size: Int = Math.round((getResources().getDisplayMetrics().density * DEFAULT_SIZE_DP))
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    protected override fun onDraw(canvas: Canvas) {
        if (!hasStatus) {
            return
        }
        val cx: Float = (getWidth() / 2f)
        val cy: Float = (getHeight() / 2f)
        val half: Float = (Math.min(getWidth(), getHeight()) / 2f)
        val backScale: Float = overshoot.getInterpolation(clamp01((enter / 0.8f)))
        val frontScale: Float = overshoot.getInterpolation(clamp01(((enter - 0.15f) / 0.85f)))
        val iconScale: Float = overshoot.getInterpolation(clamp01(((enter - 0.4f) / 0.6f)))
        drawRipples(canvas, cx, cy, half)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate((spin * 360f))
        canvas.scale(((half * BACK_RADIUS) * backScale), ((half * BACK_RADIUS) * backScale))
        canvas.drawPath(backShape, backPaint)
        canvas.restore()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate((-spin * 360f))
        canvas.scale(((half * FRONT_RADIUS) * frontScale), ((half * FRONT_RADIUS) * frontScale))
        canvas.drawPath(frontShape, frontPaint)
        canvas.restore()
        if ((icon != null)) {
            val iconHalf: Int = Math.round((half * ICON_RADIUS))
            icon!!.setBounds(-iconHalf, -iconHalf, iconHalf, iconHalf)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(iconScale, iconScale)
            icon!!.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawRipples(canvas: Canvas, cx: Float, cy: Float, half: Float) {
        // Ripples appear once the badge has mostly popped in
        val visibility: Float = clamp01(((enter - 0.6f) / 0.4f))
        if (((visibility <= 0f) || (rippleAnimator == null))) {
            return
        }
        val from: Float = (half * FRONT_RADIUS)
        run {
            var i: Int = 0
            while ((i < RIPPLE_COUNT)) {
                val t: Float = ((ripple + ((i).toFloat() / (RIPPLE_COUNT).toFloat())) % 1f)
                val radius: Float = (from + ((half - from) * decelerate.getInterpolation(t)))
                val alpha: Float = (((1f - t) * 0.5f) * visibility)
                ripplePaint.setColor(ColorUtils.setAlphaComponent(rippleColor, Math.round((alpha * 255f))))
                canvas.drawCircle(cx, cy, radius, ripplePaint)
                i++
            }
        }
    }

    private fun restartAnimations() {
        cancelAnimations()
        if (!hasStatus) {
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            enter = 1f
            spin = 0f
            ripple = 0f
            return
        }
        enter = 0f
        enterAnimator = ValueAnimator.ofFloat(0f, 1f)
        enterAnimator!!.setDuration(ENTER_MS)
        enterAnimator!!.setInterpolator(LinearInterpolator())
        enterAnimator!!.addUpdateListener({ a ->
            enter = (a.getAnimatedValue() as Float)
            invalidate()
        })
        enterAnimator!!.start()
        spinAnimator = endless(SPIN_MS, { value ->
            (value).also { spin = it }
        })
        rippleAnimator = endless(RIPPLE_MS, { value ->
            (value).also { ripple = it }
        })
    }

    private fun endless(duration: Long, consumer: FloatConsumer): ValueAnimator {
        val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)
        animator.setDuration(duration)
        animator.setInterpolator(LinearInterpolator())
        animator.setRepeatCount(ValueAnimator.INFINITE)
        animator.addUpdateListener({ a ->
            consumer.accept((a.getAnimatedValue() as Float))
            invalidate()
        })
        animator.start()
        return animator
    }

    private fun cancelAnimations() {
        for (animator in arrayOf(enterAnimator, spinAnimator, rippleAnimator)) {
            if ((animator != null)) {
                animator.cancel()
            }
        }
        enterAnimator = null
        spinAnimator = null
        rippleAnimator = null
    }

    private fun setAmbientPaused(paused: Boolean) {
        for (animator in arrayOf(spinAnimator, rippleAnimator)) {
            if ((animator == null)) {
                continue
            }
            if (paused) {
                animator.pause()
            } else if (animator.isPaused()) {
                animator.resume()
            }
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        setAmbientPaused(!isVisible)
    }

    protected override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setAmbientPaused((visibility != VISIBLE))
    }

    protected override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    protected override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (((hasStatus && (enterAnimator == null)) && (spinAnimator == null))) {
            restartAnimations()
        }
    }

    private fun interface FloatConsumer {
        fun accept(value: Float)
    }

    companion object {
        private const val BACK_LOBES: Int = 12

        private const val BACK_DEPTH: Float = 0.06f

        private const val FRONT_LOBES: Int = 9

        private const val FRONT_DEPTH: Float = 0.08f

        private const val PATH_STEPS: Int = 360

        private const val ENTER_MS: Long = 900L

        private const val SPIN_MS: Long = 30000L

        private const val RIPPLE_MS: Long = 2800L

        private const val RIPPLE_COUNT: Int = 2

        private const val DEFAULT_SIZE_DP: Float = 240f

        private const val BACK_RADIUS: Float = 0.88f

        private const val FRONT_RADIUS: Float = 0.60f

        private const val ICON_RADIUS: Float = 0.31f

        private fun clamp01(value: Float): Float {
            return Math.max(0f, Math.min(1f, value))
        }

        /**
         * A closed shape of unit radius whose edge swells and dips `lobes` times, which gives the
         * rounded "cookie" and "sunny" silhouettes of Material 3 Expressive.
         */
        private fun createScallopedShape(lobes: Int, depth: Float): Path {
            val path: Path = Path()
            run {
                var i: Int = 0
                while ((i <= PATH_STEPS)) {
                    val angle: Double = (((2.0 * Math.PI) * (i).toDouble()) / (PATH_STEPS).toDouble())
                    val radius: Double = ((1.0 + ((depth * Math.cos(((lobes).toDouble() * angle)))).toDouble()) / (1.0 + (depth).toDouble()))
                    val x: Float = ((radius * Math.cos(angle))).toFloat()
                    val y: Float = ((radius * Math.sin(angle))).toFloat()
                    if ((i == 0)) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                    i++
                }
            }
            path.close()
            return path
        }
    }
}
