package land.plonk.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import androidx.core.content.ContextCompat
import land.plonk.app.R
import kotlin.math.min

/**
 * The PLONK logo from the splash screen, so the loading and error screens look like the splash
 * carried on instead of a different app.
 *
 * The splash bitmap is a square with the logo in the middle of a soft olive glow. This view shows
 * its middle band (2:1) and fades the edges into the page colour with an elliptical vignette, so
 * the logo floats on #06070c with no visible box around it.
 */
class PlonkLogoView(
    context: Context,
) : View(context) {
    private val logo = ContextCompat.getDrawable(context, R.drawable.ic_splash)!!
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maxWidth = (MAX_WIDTH_DP * resources.displayMetrics.density).toInt()

    init {
        contentDescription = context.getString(R.string.app_name)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maxWidth else min(available, maxWidth)
        setMeasuredDimension(w, w / 2)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        // The bitmap is drawn w x w, centred, so the band the view shows is its middle half.
        logo.setBounds(0, (h - w) / 2, w, (h + w) / 2)
        if (w == 0 || h == 0) return
        val r = w / 2f
        vignette.shader =
            RadialGradient(
                0f,
                0f,
                r,
                intArrayOf(Palette.BG_CLEAR, Palette.BG_CLEAR, Palette.BG),
                floatArrayOf(0f, 0.46f, 1f),
                Shader.TileMode.CLAMP,
            ).apply {
                // Squash the circle into an ellipse that touches all four edges.
                setLocalMatrix(
                    Matrix().apply {
                        setScale(1f, h.toFloat() / w)
                        postTranslate(w / 2f, h / 2f)
                    },
                )
            }
    }

    override fun onDraw(canvas: Canvas) {
        logo.draw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
    }

    private companion object {
        const val MAX_WIDTH_DP = 360
    }
}
