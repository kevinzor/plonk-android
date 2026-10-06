package land.plonk.app.ui

import android.animation.LayoutTransition
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import land.plonk.app.R

/**
 * The app's own screen over the game: the branded loader while the game page loads, and the
 * "can't reach Plonk" screen when it can't.
 *
 * It only draws. [land.plonk.app.LoadController] decides what to show and when to retry. One view
 * does both jobs so moving from loading to an error (or back on Retry) is a short animation of the
 * same logo rather than one screen swapped for another.
 */
class StatusScreen(
    context: Context,
) : FrameLayout(context) {
    /** Why the game isn't showing. Each has its own words; the logo and Retry stay the same. */
    enum class Problem(
        @param:StringRes val title: Int,
        @param:StringRes val body: Int,
    ) {
        /** The phone has no network at all. */
        OFFLINE(R.string.status_offline_title, R.string.status_offline_body),

        /** There is a network, but the game server didn't answer. */
        UNREACHABLE(R.string.status_unreachable_title, R.string.status_unreachable_body),

        /** The server answered that it is restarting (HTTP 502-504), or the game said so. */
        UPDATING(R.string.status_updating_title, R.string.status_updating_body),

        /** The game's renderer died again and again: wait a little instead of looping. */
        CRASHED(R.string.status_crashed_title, R.string.status_crashed_body),
    }

    /** Called when the player taps Retry. */
    var onRetry: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val logo = PlonkLogoView(context)
    private val bar = LoadingBar(context)
    private val title = text(20f, Palette.TITLE, medium = true)
    private val body = text(15f, Palette.BODY)
    private val retry = retryButton()
    private val hint = text(13f, Palette.HINT).apply { accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }
    private val problemViews = listOf(title, body, retry, hint)

    init {
        setBackgroundColor(Palette.BG)
        // Swallow touches so nothing reaches the half-loaded page underneath.
        isClickable = true
        isFocusable = false
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
                val gutter = dp(24)
                setPadding(gutter, 0, gutter, 0)
            }
        column.addView(logo, wrap())
        column.addView(bar, wrap(top = 8))
        column.addView(title, wrap(top = 4))
        column.addView(body.apply { maxWidth = dp(320) }, wrap(top = 8))
        column.addView(retry, wrap(top = 28))
        column.addView(hint, wrap(top = 16))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        showLoading()
    }

    /** The loader: logo and load bar, nothing to read. Restarts the bar from empty. */
    fun showLoading() {
        bar.reset()
        bar.visibility = VISIBLE
        problemViews.forEach { it.visibility = GONE }
        accessibilityPaneTitle = context.getString(R.string.status_loading)
        appear()
    }

    fun setProgress(percent: Int) = bar.setProgress(percent)

    /** The error screen for [problem], with Retry. [hintText] is the small line under it. */
    fun showProblem(
        problem: Problem,
        hintText: CharSequence?,
    ) {
        bar.visibility = GONE
        title.setText(problem.title)
        body.setText(problem.body)
        setHint(hintText)
        problemViews.forEach { it.visibility = VISIBLE }
        if (hintText == null) hint.visibility = GONE
        // TalkBack announces a pane when it appears or its title changes.
        accessibilityPaneTitle = title.text
        appear()
    }

    /** Update the small status line ("Trying again in 8 s") without redrawing the rest. */
    fun setHint(text: CharSequence?) {
        hint.text = text
        if (title.visibility == VISIBLE) hint.visibility = if (text == null) GONE else VISIBLE
    }

    /** Fade out and get out of the way of the game. */
    fun dismiss() {
        if (visibility != VISIBLE) return
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction { visibility = GONE }
            .start()
    }

    private fun appear() {
        animate().cancel()
        if (visibility != VISIBLE) alpha = 0f
        visibility = VISIBLE
        bringToFront()
        animate().alpha(1f).setDuration(FADE_MS).start()
    }

    private fun retryButton(): TextView =
        text(16f, Palette.ON_GOLD, medium = true).apply {
            setText(R.string.retry)
            minHeight = dp(48)
            minWidth = dp(168)
            setPadding(dp(32), 0, dp(32), 0)
            isAllCaps = false
            isClickable = true
            isFocusable = true
            val pill =
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.GOLD_LIGHT, Palette.GOLD)).apply {
                    cornerRadius = dp(24).toFloat()
                }
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), pill, null)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onRetry()
            }
            accessibilityDelegate = ButtonRole
        }

    private fun text(
        sp: Float,
        color: Int,
        medium: Boolean = false,
    ): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            gravity = Gravity.CENTER
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
        }

    private fun wrap(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    private fun dp(v: Int) = (v * density).toInt()

    /** TalkBack reads the pill as a button, not as text. */
    private object ButtonRole : AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfo,
        ) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    private companion object {
        const val FADE_MS = 220L
    }
}
