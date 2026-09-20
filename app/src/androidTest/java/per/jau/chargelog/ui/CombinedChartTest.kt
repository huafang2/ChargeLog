package per.jau.chargelog.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import per.jau.chargelog.*
import per.jau.chargelog.constants.PrefKeys
import per.jau.chargelog.data.ChargeRecord

@RunWith(AndroidJUnit4::class)
class CombinedChartTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun setField(activity: MainActivity, name: String, value: Any?) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun call(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun selection(activity: MainActivity) =
        MainActivity::class.java.getDeclaredField("chartSelection").apply { isAccessible = true }
            .get(activity) as ChartSelection
    private fun records() = (0..20).map { index ->
        ChargeRecord(sessionId = 999999, timestamp = 100000L + index * 5000,
            voltage = 3.7f + index * .025f, current = if (index < 10) 2f else -.5f,
            power = if (index < 10) 8f else -2f, batteryLevel = 40 + index,
            maxVoltage = if (index in 8..12) null else 9f, maxCurrent = 3f,
            screenState = if (index in 5..15) 0 else 1)
    }
    private fun launch(): ActivityScenario<MainActivity> {
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        context.getSharedPreferences(PrefKeys.PREFS_NAME, 0).edit()
            .remove(PrefKeys.CHART_METRICS).remove(PrefKeys.CHART_ACTIVE_METRIC).commit()
        return ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .putExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, 999999L))
    }

    @Test fun allCombinationsKeepGeometryAndShowRawReadings() {
        launch().use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val records = records()
                setField(activity, "currentRecords", records)
                setField(activity, "chartBaseTime", records.first().timestamp)
                val chart = activity.findViewById<CustomLineChart>(R.id.lineChart)
                val legend = activity.findViewById<ChipGroup>(R.id.chartLegend)
                for (mask in 1..15) {
                    val metrics = ChartMetric.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet()
                    setField(activity, "chartSelection", ChartSelection(metrics, metrics.first()))
                    setField(activity, "selectedRecordTimestamp", records[10].timestamp)
                    call(activity, "updateChartData")
                    assertTrue(chart.data.dataSets.all { (it as MetricLineDataSet).series.metric in metrics })
                    assertEquals(metrics, chart.data.dataSets.map { (it as MetricLineDataSet).series.metric }.toSet())
                    assertEquals(metrics.size, legend.childCount)
                    metrics.forEach { metric ->
                        val card = legend.findViewWithTag<Chip>(metric)
                        assertNotNull(card)
                        assertTrue(card.text.toString().isNotBlank())
                        assertTrue(card.contentDescription.contains(metric.unit))
                    }
                    chart.draw(Canvas(Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)))
                    chart.zoom(2f, 1f, 0f, 0f)
                    val beforeBounds = android.graphics.RectF(chart.viewPortHandler.contentRect)
                    val beforeMatrix = FloatArray(9).also { chart.viewPortHandler.matrixTouch.getValues(it) }
                    val pointsBefore = chart.data.dataSets.flatMap { set ->
                        (0 until set.entryCount).map { set.getEntryForIndex(it).y }
                    }
                    if (metrics.size > 1) {
                        legend.findViewWithTag<Chip>(metrics.last()).performClick()
                        assertEquals(metrics.last(), selection(activity).active)
                        chart.draw(Canvas(Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)))
                        val after = FloatArray(9).also { chart.viewPortHandler.matrixTouch.getValues(it) }
                        assertArrayEquals(beforeMatrix, after, .001f)
                        assertEquals(beforeBounds, chart.viewPortHandler.contentRect)
                        assertEquals(pointsBefore, chart.data.dataSets.flatMap { set ->
                            (0 until set.entryCount).map { set.getEntryForIndex(it).y }
                        })
                    }
                    val readout = activity.findViewById<TextView>(R.id.chartReadout).text.toString()
                    metrics.forEach { assertTrue(readout.contains(it.format(it.value(records[10])))) }
                    if (ChartMetric.POWER in metrics) {
                        assertTrue(readout.contains("—"))
                        assertEquals(2, chart.data.dataSets.count { (it as MetricLineDataSet).series.isLimit })
                    }
                    val highlight = chart.highlightForX(50000f)!!
                    assertEquals(50000f, highlight.x, .01f)
                    assertFalse((chart.data.getDataSetByIndex(highlight.dataSetIndex) as MetricLineDataSet).series.isLimit)
                    chart.fitScreen()
                }
                val chips = activity.findViewById<ChipGroup>(R.id.metricChips)
                for (index in 0 until chips.childCount) (chips.getChildAt(index) as Chip).isChecked = true
                val readoutBefore = activity.findViewById<TextView>(R.id.chartReadout).text.toString()
                val cursorBefore = chart.highlighted.first().x
                setField(activity, "currentRecords", records + records.last().copy(timestamp = records.last().timestamp + 5000))
                call(activity, "updateChartData")
                assertEquals(cursorBefore, chart.highlighted.first().x, .01f)
                assertEquals(readoutBefore, activity.findViewById<TextView>(R.id.chartReadout).text.toString())
                val main = activity.findViewById<View>(R.id.main)
                main.measure(View.MeasureSpec.makeMeasureSpec(main.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                main.layout(0, 0, main.measuredWidth, main.measuredHeight)
                val preview = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
                main.draw(Canvas(preview))
                java.io.File(activity.filesDir, "combined-chart.png").outputStream().use {
                    preview.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                preview.recycle()
                setField(activity, "currentRecords", emptyList<ChargeRecord>())
                call(activity, "updateChartData")
                assertNull(chart.data)
                setField(activity, "currentRecords", records.take(1))
                call(activity, "updateChartData")
                assertNotNull(chart.highlightForX(0f))
            }
        }
    }

    @Test fun legendTapAndRecreationRestoreSelection() {
        launch().use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val chips = activity.findViewById<ChipGroup>(R.id.metricChips)
                (chips.getChildAt(0) as Chip).performClick()
                assertEquals(setOf(ChartMetric.POWER, ChartMetric.VOLTAGE), selection(activity).metrics)
                val legend = activity.findViewById<ChipGroup>(R.id.chartLegend)
                val voltage = legend.findViewWithTag<Chip>(ChartMetric.VOLTAGE)
                assertNotNull(voltage)
                assertTrue(voltage.contentDescription.contains(activity.getString(R.string.chart_legend_available)))
                voltage.performClick()
                assertEquals(ChartMetric.VOLTAGE, selection(activity).active)
                assertTrue(legend.findViewWithTag<Chip>(ChartMetric.VOLTAGE).contentDescription
                    .contains(activity.getString(R.string.chart_legend_current)))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(setOf(ChartMetric.POWER, ChartMetric.VOLTAGE), selection(activity).metrics)
                assertEquals(ChartMetric.VOLTAGE, selection(activity).active)
                val chips = activity.findViewById<ChipGroup>(R.id.metricChips)
                (chips.getChildAt(0) as Chip).performClick()
                assertEquals(ChartMetric.POWER, selection(activity).active)
                (chips.getChildAt(2) as Chip).performClick()
                assertEquals(setOf(ChartMetric.POWER), selection(activity).metrics)
                assertEquals(1, activity.findViewById<ChipGroup>(R.id.chartLegend).childCount)
            }
        }
    }

    @Test fun cursorDragReadsOneRecordAndTapClearsIt() {
        launch().use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val records = records()
                setField(activity, "currentRecords", records)
                setField(activity, "chartBaseTime", records.first().timestamp)
                setField(activity, "chartSelection", ChartSelection(ChartMetric.entries.toSet(), ChartMetric.POWER))
                call(activity, "updateChartData")
                val chart = activity.findViewById<CustomLineChart>(R.id.lineChart)
                val legend = activity.findViewById<ChipGroup>(R.id.chartLegend)
                chart.draw(Canvas(Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)))
                chart.highlightValue(chart.highlightForX(25000f), true)
                val coordinates = floatArrayOf(25000f, 50f, 75000f, 50f)
                chart.getTransformer(com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT)
                    .pointValuesToPixel(coordinates)
                val now = android.os.SystemClock.uptimeMillis()
                fun touch(action: Int, x: Float, time: Long) {
                    val event = MotionEvent.obtain(now, now + time, action, x, coordinates[1], 0)
                    chart.onTouchEvent(event)
                    event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN, coordinates[0], 0)
                touch(MotionEvent.ACTION_MOVE, coordinates[2], 50)
                touch(MotionEvent.ACTION_UP, coordinates[2], 100)
                assertEquals(75000f, chart.highlighted.first().x, .01f)
                val readout = activity.findViewById<TextView>(R.id.chartReadout)
                ChartMetric.entries.forEach { assertTrue(readout.text.contains(it.format(it.value(records[15])))) }
                touch(MotionEvent.ACTION_DOWN, coordinates[2], 150)
                touch(MotionEvent.ACTION_UP, coordinates[2], 200)
                assertTrue(chart.highlighted.isNullOrEmpty())
                assertEquals(View.GONE, readout.visibility)
                assertTrue(chart.isDragEnabled)
            }
        }
    }
    @Test fun narrowAndLargeFontLayoutsKeepChartAndReadoutMeasurable() {
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    fontScale = 1.5f
                    uiMode = Configuration.UI_MODE_TYPE_NORMAL or night
                    densityDpi = 160
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_ChargeLog)
                val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
                root.findViewById<TextView>(R.id.chartReadout).apply {
                    visibility = View.VISIBLE
                    text = "12:34:56 · 放电\n电压 4.20 V    电流 -0.50 A\n功率 -2.10 W    电量 100 %\n充电器最大功率 120.00 W"
                }
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                assertTrue(root.findViewById<CustomLineChart>(R.id.lineChart).height >= 200)
                for (id in listOf(R.id.chartReadout)) {
                    val text = root.findViewById<TextView>(id)
                    assertTrue(text.layout.height <= text.height - text.paddingTop - text.paddingBottom)
                    for (line in 0 until text.lineCount) assertEquals(0, text.layout.getEllipsisCount(line))
                }
            }
        }
    }
}
