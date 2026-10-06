package land.plonk.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import land.plonk.app.R
import kotlin.math.abs

/**
 * A slim gold-to-lava load bar, in place of the stock Material progress bar.
 *
 * WebView reports progress in big jumps (10, 70, 100), so the bar eases toward the latest value
 * instead of snapping, and a soft highlight sweeps along the filled part so a slow network still
 * looks alive. It only animates while it is on screen, and holds still when the player has turned
 * animations off in system settings.
 */
class LoadingBar(
    context: Context,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val barHeight = 3 * density
    private val sweepWidth = 56 * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TRACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepMatrix = Matrix()
    private val sweepPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader =
                LinearGradient(0f, 0f, sweepWidth, 0f, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }
    private val rect = RectF()

    private var target = 0f
    private var shown = 0f

    init {
        contentDescription = context.getString(R.string.status_loading)
    }

    /** Move toward [percent] (0..100). */
    fun setProgress(percent: Int) {
        target = percent.coerceIn(0, 100).toFloat()
        invalidate()
    }

    /** Start again from empty, without easing back down. */
    fun reset() {
        target = 0f
        shown = 0f
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(
            resolveSize((DEFAULT_WIDTH_DP * density).toInt(), widthMeasureSpec),
            resolveSize((barHeight + 2 * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        fillPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Palette.GOLD_LIGHT, Palette.LAVA, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val top = (height - barHeight) / 2
        val radius = barHeight / 2
        rect.set(0f, top, w, top + barHeight)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val animate = ValueAnimator.areAnimatorsEnabled()
        shown = if (animate) shown + (target - shown) * EASE else target
        if (abs(target - shown) < 0.2f) shown = target
        // Always show a sliver, so "just started" doesn't look like "not started".
        val fillEnd = maxOf(w * shown / 100f, barHeight * 2)
        rect.right = fillEnd
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (animate) {
            drawSweep(canvas, fillEnd, radius)
            if (isShown) postInvalidateOnAnimation()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val type = AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT
        info.rangeInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AccessibilityNodeInfo.RangeInfo(type, 0f, 100f, target)
            } else {
                @Suppress("DEPRECATION")
                AccessibilityNodeInfo.RangeInfo.obtain(type, 0f, 100f, target)
            }
    }

    /** A highlight that travels left to right along the filled part, once every [SWEEP_MS]. */
    private fun drawSweep(
        canvas: Canvas,
        fillEnd: Float,
        radius: Float,
    ) {
        val t = (SystemClock.uptimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat()
        sweepMatrix.setTranslate(-sweepWidth + t * (fillEnd + sweepWidth), 0f)
        sweepPaint.shader.setLocalMatrix(sweepMatrix)
        canvas.save()
        canvas.clipRect(0f, rect.top, fillEnd, rect.bottom)
        canvas.drawRoundRect(rect, radius, radius, sweepPaint)
        canvas.restore()
    }

    private companion object {
        const val DEFAULT_WIDTH_DP = 176
        const val SWEEP_MS = 1400L
        const val EASE = 0.08f
    }
}
