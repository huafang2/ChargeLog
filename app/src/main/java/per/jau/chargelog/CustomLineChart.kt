package per.jau.chargelog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import androidx.core.graphics.toColorInt
import java.util.Locale
import kotlin.math.abs

class CustomLineChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LineChart(context, attrs, defStyleAttr) {

    private var isDraggingVerticalLine = false
    private val touchTolerance = 35f * resources.displayMetrics.density // 35dp hit area for dragging

    // Drawing paints
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }

    override fun setData(data: LineData?) {
        super.setData(data)
        // Disable default highlight indicator drawing from MPAndroidChart
        // since we draw a custom premium 3D vertical line ourselves!
        if (data != null) {
            for (i in 0 until data.dataSetCount) {
                val dataSet = data.getDataSetByIndex(i) as? LineDataSet ?: continue
                dataSet.setDrawVerticalHighlightIndicator(false)
                dataSet.setDrawHorizontalHighlightIndicator(false)
                
                // Initialize default visual state: 130/255 transparency, 2f width
                val baseColor = dataSet.color
                val red = Color.red(baseColor)
                val green = Color.green(baseColor)
                val blue = Color.blue(baseColor)
                dataSet.color = Color.argb(130, red, green, blue)
                dataSet.lineWidth = 2f
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hList = highlighted
        val currentHighlight = if (hList != null && hList.isNotEmpty()) hList[0] else null

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (currentHighlight != null) {
                    val chartData = data
                    if (chartData != null) {
                        val dataSet = chartData.getDataSetByIndex(currentHighlight.dataSetIndex) as? LineDataSet
                        if (dataSet != null) {
                            // Calculate current highlight pixel X
                            val pts = floatArrayOf(currentHighlight.x, currentHighlight.y)
                            getTransformer(dataSet.axisDependency).pointValuesToPixel(pts)
                            val drawX = pts[0]

                            if (abs(event.x - drawX) <= touchTolerance) {
                                isDraggingVerticalLine = true
                                parent?.requestDisallowInterceptTouchEvent(true)
                                isDragEnabled = false // Intercept panning
                                updateHighlightForTouch(event.x, event.y)
                                invalidate()
                                return true
                            }
                        }
                    }
                }
                // If not scrubbing the vertical line, we might be panning the chart. Darken the lines!
                setLineActiveState(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingVerticalLine) {
                    updateHighlightForTouch(event.x, event.y)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingVerticalLine) {
                    isDraggingVerticalLine = false
                    isDragEnabled = true
                    invalidate()
                    return true
                }
                // Stop panning, restore lines to normal state
                setLineActiveState(false)
            }
        }

        return super.onTouchEvent(event)
    }

    private fun updateHighlightForTouch(x: Float, y: Float) {
        val chartData = data ?: return
        if (chartData.dataSetCount == 0) return

        // Get the X value under the touch point using the first dataset's transformer
        val dataSet0 = chartData.getDataSetByIndex(0) ?: return
        val pts = floatArrayOf(x, y)
        getTransformer(dataSet0.axisDependency).pixelsToValue(pts)
        val touchValX = pts[0]

        // Find the entry in ALL datasets that is closest to touchValX
        var closestEntry: Entry? = null
        var closestDiff = Float.MAX_VALUE
        var closestDataSetIndex = 0

        for (i in 0 until chartData.dataSetCount) {
            val dataSet = chartData.getDataSetByIndex(i) as? LineDataSet ?: continue
            val entry = getClosestEntry(dataSet, touchValX) ?: continue
            val diff = abs(entry.x - touchValX)
            if (diff < closestDiff) {
                closestDiff = diff
                closestEntry = entry
                closestDataSetIndex = i
            }
        }

        // Highlight the closest entry
        if (closestEntry != null) {
            val highlight = Highlight(closestEntry.x, closestEntry.y, closestDataSetIndex)
            highlightValue(highlight, true)
        }
    }

    private fun getClosestEntry(dataSet: LineDataSet, targetX: Float): Entry? {
        val entryCount = dataSet.entryCount
        if (entryCount == 0) return null

        var low = 0
        var high = entryCount - 1
        var closestEntry: Entry? = null
        var minDiff = Float.MAX_VALUE

        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = dataSet.getEntryForIndex(mid) ?: break
            val diff = abs(entry.x - targetX)

            if (diff < minDiff) {
                minDiff = diff
                closestEntry = entry
            }

            if (entry.x < targetX) {
                low = mid + 1
            } else if (entry.x > targetX) {
                high = mid - 1
            } else {
                return entry // Exact match
            }
        }
        return closestEntry
    }

    private fun setLineActiveState(active: Boolean) {
        val chartData = data ?: return
        for (i in 0 until chartData.dataSetCount) {
            val dataSet = chartData.getDataSetByIndex(i) as? LineDataSet ?: continue
            val baseColor = dataSet.color
            val red = Color.red(baseColor)
            val green = Color.green(baseColor)
            val blue = Color.blue(baseColor)
            if (active) {
                dataSet.color = Color.argb(255, red, green, blue)
                dataSet.lineWidth = 4f
            } else {
                dataSet.color = Color.argb(130, red, green, blue)
                dataSet.lineWidth = 2f
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCustomHighlight(canvas)
    }

    private fun drawCustomHighlight(canvas: Canvas) {
        val hList = highlighted ?: return
        if (hList.isEmpty()) return
        val h = hList[0]

        val top = viewPortHandler.contentTop()
        val bottom = viewPortHandler.contentBottom()
        val left = viewPortHandler.contentLeft()
        val right = viewPortHandler.contentRight()

        val chartData = data ?: return
        val dataSet = chartData.getDataSetByIndex(h.dataSetIndex) as? LineDataSet ?: return
        
        // Calculate current highlight pixel coordinate
        val pts = floatArrayOf(h.x, h.y)
        getTransformer(dataSet.axisDependency).pointValuesToPixel(pts)
        val drawX = pts[0]
        val drawY = pts[1]

        if (drawX < left || drawX > right) return

        val baseColor = dataSet.color
        val activeColor = Color.rgb(Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

        // Adaptive high-contrast vertical line color
        val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNight = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val verticalLineColor = if (isNight) {
            "#FFC107".toColorInt() // High-contrast amber yellow for dark background
        } else {
            "#212121".toColorInt() // High-contrast charcoal black for light background
        }

        // 1. Draw glowing / 3D vertical line
        if (isDraggingVerticalLine) {
            // Glow layer
            highlightPaint.color = Color.argb(60, Color.red(verticalLineColor), Color.green(verticalLineColor), Color.blue(verticalLineColor))
            highlightPaint.strokeWidth = 7f * resources.displayMetrics.density
            canvas.drawLine(drawX, top, drawX, bottom, highlightPaint)

            // Inner main line
            highlightPaint.color = Color.argb(220, Color.red(verticalLineColor), Color.green(verticalLineColor), Color.blue(verticalLineColor))
            highlightPaint.strokeWidth = 3f * resources.displayMetrics.density
            canvas.drawLine(drawX, top, drawX, bottom, highlightPaint)

            // White core line (3D reflection)
            highlightPaint.color = Color.WHITE
            highlightPaint.strokeWidth = 1f * resources.displayMetrics.density
            canvas.drawLine(drawX, top, drawX, bottom, highlightPaint)
        } else {
            // Static selected vertical line
            highlightPaint.color = Color.argb(160, Color.red(verticalLineColor), Color.green(verticalLineColor), Color.blue(verticalLineColor))
            highlightPaint.strokeWidth = 2f * resources.displayMetrics.density
            canvas.drawLine(drawX, top, drawX, bottom, highlightPaint)
        }

        // 2. Draw intersection dot
        if (drawY >= top && drawY <= bottom) {
            // Glow outer ring
            dotPaint.color = Color.argb(60, Color.red(verticalLineColor), Color.green(verticalLineColor), Color.blue(verticalLineColor))
            canvas.drawCircle(drawX, drawY, 9f * resources.displayMetrics.density, dotPaint)

            // Main dot ring (scrubber theme color)
            dotPaint.color = verticalLineColor
            canvas.drawCircle(drawX, drawY, 6f * resources.displayMetrics.density, dotPaint)

            // Inner center dot (chart line color)
            dotPaint.color = activeColor
            canvas.drawCircle(drawX, drawY, 3.5f * resources.displayMetrics.density, dotPaint)

            // White center reflection dot
            dotPaint.color = Color.WHITE
            canvas.drawCircle(drawX, drawY, 1.5f * resources.displayMetrics.density, dotPaint)
        }

        // 3. Draw tooltip displaying value
        val entry = dataSet.getEntryForXValue(h.x, h.y) ?: return
        val valueText = formatValue(entry.y, dataSet.label ?: "")

        val textWidth = textPaint.measureText(valueText)
        val textHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
        val paddingHorizontal = 10f * resources.displayMetrics.density
        val paddingVertical = 6f * resources.displayMetrics.density

        val tooltipWidth = textWidth + paddingHorizontal * 2
        val tooltipHeight = textHeight + paddingVertical * 2
        val cornerRadius = 6f * resources.displayMetrics.density

        // Position above the intersection dot
        val tooltipX = drawX
        var tooltipY = drawY - 18f * resources.displayMetrics.density - tooltipHeight / 2f

        // If tooltip runs off the top of the viewport, position it below instead
        if (tooltipY - tooltipHeight / 2f < top) {
            tooltipY = drawY + 18f * resources.displayMetrics.density + tooltipHeight / 2f
        }

        var rectLeft = tooltipX - tooltipWidth / 2f
        var rectRight = tooltipX + tooltipWidth / 2f

        // Ensure tooltip stays inside left/right bounds
        if (rectLeft < left) {
            val offset = left - rectLeft
            rectLeft += offset
            rectRight += offset
        } else if (rectRight > right) {
            val offset = rectRight - right
            rectLeft -= offset
            rectRight -= offset
        }

        val rectTop = tooltipY - tooltipHeight / 2f
        val rectBottom = tooltipY + tooltipHeight / 2f
        val rect = RectF(rectLeft, rectTop, rectRight, rectBottom)

        // Drop shadow for 3D depth
        tooltipBgPaint.color = Color.argb(40, 0, 0, 0)
        val shadowOffset = 2f * resources.displayMetrics.density
        val shadowRect = RectF(rect.left + shadowOffset, rect.top + shadowOffset, rect.right + shadowOffset, rect.bottom + shadowOffset)
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, tooltipBgPaint)

        // Tooltip background
        tooltipBgPaint.color = "#E6262626".toColorInt() // Dark mode neutral grey
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, tooltipBgPaint)

        // Tooltip active accent border
        tooltipBorderPaint.color = verticalLineColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, tooltipBorderPaint)

        // Tooltip text
        textPaint.color = Color.WHITE
        val textY = tooltipY - (textPaint.fontMetrics.descent + textPaint.fontMetrics.ascent) / 2f
        canvas.drawText(valueText, rect.centerX(), textY, textPaint)
    }

    private fun formatValue(value: Float, label: String): String {
        return when {
            label.contains("电压") -> String.format(Locale.getDefault(), "%.2f V", value)
            label.contains("电流") -> String.format(Locale.getDefault(), "%.2f A", value)
            label.contains("功率") -> String.format(Locale.getDefault(), "%.2f W", value)
            label.contains("电量") -> String.format(Locale.getDefault(), "%.0f%%", value)
            else -> String.format(Locale.getDefault(), "%.2f", value)
        }
    }
}
