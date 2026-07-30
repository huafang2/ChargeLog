package per.jau.chargelog

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.google.android.material.tabs.TabLayout
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A fixed TabLayout whose indicator follows a horizontal drag continuously.
 * The selected tab is committed only after release, so expensive chart updates
 * are not triggered for every move event.
 */
class LiquidTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.tabStyle
) : TabLayout(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private fun positionFor(x: Float): Float {
        if (width <= 0 || tabCount <= 0) return selectedTabPosition.coerceAtLeast(0).toFloat()
        val leftToRight = (x.coerceIn(0f, width.toFloat()) / width * tabCount - 0.5f)
            .coerceIn(0f, (tabCount - 1).toFloat())
        return if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            tabCount - 1 - leftToRight
        } else {
            leftToRight
        }
    }

    private fun moveIndicator(x: Float) {
        val position = positionFor(x)
        val basePosition = floor(position).toInt()
        setScrollPosition(basePosition, position - basePosition, false)
    }

    private fun finishSelection(x: Float) {
        val target = positionFor(x).roundToInt().coerceIn(0, tabCount - 1)
        if (target != selectedTabPosition) {
            getTabAt(target)?.select()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else {
            setScrollPosition(target, 0f, true)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = abs(event.x - downX)
                val deltaY = abs(event.y - downY)
                if (deltaX > touchSlop && deltaX >= deltaY) dragging = true
                if (dragging) moveIndicator(event.x)
                return true
            }

            MotionEvent.ACTION_UP -> {
                finishSelection(event.x)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                setScrollPosition(selectedTabPosition.coerceAtLeast(0), 0f, true)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
