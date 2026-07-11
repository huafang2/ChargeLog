package per.jau.chargelog.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import per.jau.chargelog.data.ChargeRecord

class BatteryHealthEstimatorTest {
    @Test
    fun combinesSelectedSessionsWithoutIntegratingBetweenThem() {
        val first = session(1L, startLevel = 20, endLevel = 45)
        val second = session(2L, startLevel = 50, endLevel = 75)

        val result = BatteryHealthEstimator.estimate(listOf(first, second), 5000f)

        assertTrue(result is BatteryHealthResult.Ready)
        val estimate = (result as BatteryHealthResult.Ready).estimate
        assertEquals(4000f, estimate.estimatedFullCapacityMah, 0.5f)
        assertEquals(80f, estimate.healthPercent!!, 0.1f)
        assertEquals(50, estimate.totalBatterySpanPercent)
    }

    @Test
    fun requiresFiftyPercentOfCombinedChargingSpan() {
        val result = BatteryHealthEstimator.estimate(
            listOf(session(1L, startLevel = 20, endLevel = 49)),
            5000f
        )

        assertEquals(BatteryHealthResult.Insufficient(29), result)
    }

    @Test
    fun usesLongestContinuousChargingPartOfEachHistoryRecord() {
        val charging = session(1L, startLevel = 10, endLevel = 60).toMutableList()
        charging += record(1L, charging.last().timestamp + 60_000L, 59, -0.5f)

        val result = BatteryHealthEstimator.estimate(listOf(charging), 4000f)

        assertTrue(result is BatteryHealthResult.Ready)
        assertEquals(
            4000f,
            (result as BatteryHealthResult.Ready).estimate.estimatedFullCapacityMah,
            0.5f
        )
    }

    private fun session(id: Long, startLevel: Int, endLevel: Int): List<ChargeRecord> {
        val span = endLevel - startLevel
        val durationMs = span * 144_000L // 1 A into a 4000 mAh battery
        return listOf(
            record(id, id * 10_000_000L, startLevel, 1f),
            record(id, id * 10_000_000L + durationMs / 2, startLevel + span / 2, 1f),
            record(id, id * 10_000_000L + durationMs, endLevel, 1f)
        )
    }

    private fun record(id: Long, timestamp: Long, level: Int, current: Float) = ChargeRecord(
        sessionId = id,
        timestamp = timestamp,
        voltage = 4f,
        current = current,
        power = 4f * current,
        batteryLevel = level
    )
}
