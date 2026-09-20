package per.jau.chargelog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.graphics.withClip
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.highlight.Highlight
import per.jau.chargelog.data.ChargeRecord
import kotlin.math.abs

/** Shared time cursor; real values are displayed outside the plot by MainActivity. */
class CustomLineChart @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LineChart(context, attrs, defStyleAttr) {
    var isDraggingVerticalLine = false
        private set
    var activeMetric = ChartMetric.POWER
        set(value) {
            field = value
            updateLineStyles()
        }
    var baseTime = 0L
    var screenRecords: List<ChargeRecord> = emptyList()
        set(value) {
            field = value
            // Compute once per data update, never sort duplicated series during drawing.
            val bands = mutableListOf<Pair<Long, Long>>()
            var start: Long? = null
            value.forEach { record ->
                if (record.screenState == 0 && start == null) start = record.timestamp
                if (record.screenState != 0 && start != null) {
                    bands.add(start!! to record.timestamp)
                    start = null
                }
            }
            if (start != null && value.isNotEmpty()) bands.add(start!! to value.last().timestamp)
            screenOffBands = bands
        }
    private var screenOffBands = emptyList<Pair<Long, Long>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val displayDensity get() = resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var hasMoved = false
    private var previousDragEnabled = true

    override fun setData(data: LineData?) {
        super.setData(data)
        data?.dataSets?.forEach {
            (it as? MetricLineDataSet)?.setDrawVerticalHighlightIndicator(false)
            (it as? MetricLineDataSet)?.setDrawHorizontalHighlightIndicator(false)
        }
        updateLineStyles()
    }

    private fun updateLineStyles() {
        data?.dataSets?.forEach { set ->
            val metricSet = set as? MetricLineDataSet ?: return@forEach
            metricSet.lineWidth = if (metricSet.series.metric == activeMetric && !metricSet.series.isLimit) 2.8f else 1.6f
        }
        invalidate()
    }

    /** Resolve the actual dataset/entry, including segmented data, instead of assuming index zero. */
    fun highlightForX(x: Float): Highlight? {
        val sets = data?.dataSets ?: return null
        val candidates = sets.mapIndexedNotNull { index, set ->
            val metricSet = set as? MetricLineDataSet ?: return@mapIndexedNotNull null
            if (metricSet.series.isLimit || set.entryCount == 0) return@mapIndexedNotNull null
            val entry = set.getEntryForXValue(x, Float.NaN) ?: return@mapIndexedNotNull null
            Triple(index, metricSet, entry)
        }
        val closest = candidates.minWithOrNull(compareBy(
            { abs(it.third.x - x) },
            { if (it.second.series.metric == activeMetric) 0 else 1 }
        )) ?: return null
        return Highlight(closest.third.x, closest.third.y, closest.first)
    }

    private fun updateHighlightForTouch(x: Float, y: Float) {
        val point = floatArrayOf(x, y)
        getTransformer(YAxis.AxisDependency.LEFT).pixelsToValue(point)
        highlightValue(highlightForX(point[0]), true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                hasMoved = false
                val selected = highlighted?.firstOrNull()
                if (selected != null) {
                    val point = floatArrayOf(selected.x, 0f)
                    getTransformer(YAxis.AxisDependency.LEFT).pointValuesToPixel(point)
                    if (abs(event.x - point[0]) <= 24f * displayDensity) {
                        isDraggingVerticalLine = true
                        previousDragEnabled = isDragEnabled
                        isDragEnabled = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - startX) > slop || abs(event.y - startY) > slop) hasMoved = true
                if (isDraggingVerticalLine) {
                    updateHighlightForTouch(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingVerticalLine) {
                    isDraggingVerticalLine = false
                    isDragEnabled = previousDragEnabled
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !hasMoved) {
                        highlightValue(null, true)
                        performClick()
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        drawScreenOffBands(canvas)
        super.onDraw(canvas)
        val h = highlighted?.firstOrNull() ?: return
        val point = floatArrayOf(h.x, 0f)
        val transformer = getTransformer(YAxis.AxisDependency.LEFT)
        transformer.pointValuesToPixel(point)
        val bounds = viewPortHandler.contentRect
        if (point[0] < bounds.left || point[0] > bounds.right) return
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        canvas.withClip(bounds) {
            paint.color = if (night) Color.LTGRAY else Color.DKGRAY
            paint.strokeWidth = displayDensity
            paint.style = Paint.Style.STROKE
            drawLine(point[0], bounds.top, point[0], bounds.bottom, paint)
            data?.dataSets?.forEach { set ->
                val entry = set.getEntryForXValue(h.x, Float.NaN) ?: return@forEach
                if (abs(entry.x - h.x) > .5f) return@forEach
                val dot = floatArrayOf(entry.x, entry.y)
                transformer.pointValuesToPixel(dot)
                paint.color = set.color
                paint.style = Paint.Style.FILL
                drawCircle(dot[0], dot[1], 3.5f * displayDensity, paint)
            }
        }
    }

    private fun drawScreenOffBands(canvas: Canvas) {
        if (data == null) return
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        paint.color = if (night) Color.argb(30, 255, 255, 255) else Color.argb(18, 0, 0, 0)
        paint.style = Paint.Style.FILL
        val bounds = viewPortHandler.contentRect
        val transformer = getTransformer(YAxis.AxisDependency.LEFT)
        canvas.withClip(bounds) {
            screenOffBands.forEach { (start, end) ->
                val points = floatArrayOf((start - baseTime).toFloat(), 0f, (end - baseTime).toFloat(), 0f)
                transformer.pointValuesToPixel(points)
                drawRect(points[0], bounds.top, points[2], bounds.bottom, paint)
            }
        }
    }
}
