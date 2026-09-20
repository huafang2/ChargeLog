package per.jau.chargelog

import org.junit.Assert.*
import org.junit.Test
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.utils.FastChargeLimit

class ChartMetricTest {
    private fun record(time: Long = 0, voltage: Float = 4f, current: Float = 2f,
                       power: Float = 8f, level: Int = 50, maxV: Float? = null, maxA: Float? = null) =
        ChargeRecord(sessionId = 1, timestamp = time, voltage = voltage, current = current,
            power = power, batteryLevel = level, maxVoltage = maxV, maxCurrent = maxA)

    @Test fun rangesRoundTripAndPreserveNegativeReadings() {
        val records = listOf(record(current = -3f, power = -12f), record(current = 2f, power = 8f))
        ChartMetric.entries.forEach { metric ->
            val range = ChartRange.forMetric(metric, records)
            records.forEach {
                val value = metric.value(it)
                assertEquals(value, range.fromPlot(range.toPlot(value)), .0001f)
                assertTrue(range.toPlot(value) in 0f..100f)
            }
        }
        assertEquals(ChartRange(0f, 100f), ChartRange.forMetric(ChartMetric.BATTERY, records))
    }

    @Test fun emptyConstantAndSingleRecordHaveFiniteNonzeroDomains() {
        for (records in listOf(emptyList(), listOf(record()), listOf(record(), record()))) {
            ChartMetric.entries.forEach {
                val range = ChartRange.forMetric(it, records)
                assertTrue(range.max > range.min)
                assertTrue(range.min.isFinite() && range.max.isFinite())
                assertTrue(range.toPlot(range.min).isFinite())
            }
        }
    }

    @Test fun powerDomainIncludesChargerLimitAndExcludesInvalidValues() {
        val records = listOf(record(power = -5f, maxV = 20f, maxA = 5f),
            record(power = Float.NaN, maxV = Float.NaN, maxA = 2f))
        val range = ChartRange.forMetric(ChartMetric.POWER, records)
        assertTrue(range.min < -5f)
        assertTrue(range.max > 100f)
        assertEquals(100f, range.fromPlot(range.toPlot(100f)), .0001f)
    }

    @Test fun missingLimitsStayDisconnected() {
        val records = listOf(record(1, maxV = 9f, maxA = 3f), record(2),
            record(3, maxV = 5f, maxA = 2f))
        val segments = FastChargeLimit.contiguousSegments(records)
        assertEquals(listOf(listOf(1L), listOf(3L)), segments.map { it.map { r -> r.timestamp } })
    }

    @Test fun all15CombinationsRestoreCycleAndKeepOneSelection() {
        for (mask in 1..15) {
            val metrics = ChartMetric.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet()
            val initial = ChartSelection.restore(metrics.map { it.name }, metrics.last().name)
            assertEquals(metrics, initial.metrics)
            assertEquals(metrics.last(), initial.active)
            var state = initial
            repeat(metrics.size) { state = state.cycle(1) }
            assertEquals(initial, state)
            assertEquals(initial, initial.cycle(-1).cycle(1))
            metrics.forEach {
                val toggled = initial.toggle(it)
                assertTrue(toggled.metrics.isNotEmpty())
                assertTrue(toggled.active in toggled.metrics)
                if (metrics.size == 1) assertEquals(initial, toggled)
            }
        }
    }

    @Test fun invalidPreferencesAndLegacySelectionHaveSafeDefaults() {
        assertEquals(ChartMetric.POWER, ChartSelection.restore(null, null).active)
        assertEquals(ChartMetric.CURRENT, ChartSelection.restore(null, null, 1).active)
        assertEquals(ChartMetric.POWER, ChartSelection.restore(listOf("unknown"), "unknown", 99).active)
        val restored = ChartSelection.restore(listOf("VOLTAGE", "BATTERY"), "POWER")
        assertEquals(ChartMetric.VOLTAGE, restored.active)
        assertEquals(ChartMetric.BATTERY, restored.toggle(ChartMetric.VOLTAGE).active)
    }

    @Test fun changingAxisDoesNotChangeAnyMetricDomain() {
        val records = listOf(record(), record(1, voltage = 4.2f, current = 1f, power = 4.2f, level = 80))
        val ranges = ChartMetric.entries.associateWith { ChartRange.forMetric(it, records) }
        var selection = ChartSelection(ChartMetric.entries.toSet(), ChartMetric.POWER)
        repeat(4) {
            selection = selection.cycle(1)
            selection.metrics.forEach { metric ->
                assertEquals(ranges.getValue(metric), ChartRange.forMetric(metric, records))
            }
        }
    }
}
