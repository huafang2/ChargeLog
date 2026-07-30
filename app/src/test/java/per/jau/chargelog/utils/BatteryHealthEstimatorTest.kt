package per.jau.chargelog.utils

import android.os.BatteryManager
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
    fun subtractsNegativeCurrentWithoutBreakingOnBatteryLevelDrop() {
        val base = 10_000_000L
        val records = listOf(
            record(1L, base, 20, 1f),
            record(1L, base + 3_600_000L, 45, 1f),
            record(1L, base + 5_400_000L, 40, -1f),
            record(1L, base + 7_200_000L, 35, -1f),
            record(1L, base + 9_000_000L, 45, 1f),
            record(1L, base + 14_400_000L, 70, 1f)
        )

        val result = BatteryHealthEstimator.estimate(listOf(records), 4000f)

        assertTrue(result is BatteryHealthResult.Ready)
        val estimate = (result as BatteryHealthResult.Ready).estimate
        assertEquals(2750f, estimate.positiveChargedCapacityMah, 0.5f)
        assertEquals(750f, estimate.dischargedCapacityMah, 0.5f)
        assertEquals(2000f, estimate.netChargedCapacityMah, 0.5f)
        assertEquals(4000f, estimate.estimatedFullCapacityMah, 0.5f)
        assertEquals(50, estimate.totalBatterySpanPercent)
    }

    @Test
    fun newRecordsIncludeTransitionToFullAndIgnoreSamplesAfterFull() {
        val base = 20_000_000L
        val throughFull = listOf(
            record(2L, base, 50, 1f, BatteryManager.BATTERY_STATUS_CHARGING),
            record(2L, base + 7_200_000L, 100, 1f, BatteryManager.BATTERY_STATUS_CHARGING),
            record(2L, base + 7_560_000L, 100, 0f, BatteryManager.BATTERY_STATUS_FULL)
        )
        val withPostFullTail = throughFull + record(
            2L,
            base + 11_160_000L,
            100,
            2f,
            BatteryManager.BATTERY_STATUS_FULL
        )

        val withoutTail = BatteryHealthEstimator.estimate(listOf(throughFull), 4000f)
        val withTail = BatteryHealthEstimator.estimate(listOf(withPostFullTail), 4000f)

        assertTrue(withoutTail is BatteryHealthResult.Ready)
        assertTrue(withTail is BatteryHealthResult.Ready)
        val expected = (withoutTail as BatteryHealthResult.Ready).estimate
        val actual = (withTail as BatteryHealthResult.Ready).estimate
        assertEquals(2050f, actual.netChargedCapacityMah, 0.5f)
        assertEquals(expected.netChargedCapacityMah, actual.netChargedCapacityMah, 0.01f)
        assertEquals(expected.estimatedFullCapacityMah, actual.estimatedFullCapacityMah, 0.01f)
        assertTrue(!actual.hasUnknownBatteryStatus)
    }

    @Test
    fun legacyRecordsKeepTheEntireUnknownStatusTailAfterOneHundredPercent() {
        val base = 30_000_000L
        val records = listOf(
            record(3L, base, 50, 1f),
            record(3L, base + 7_200_000L, 100, 1f),
            record(3L, base + 10_800_000L, 100, 1f)
        )

        val result = BatteryHealthEstimator.estimate(listOf(records), 4000f)

        assertTrue(result is BatteryHealthResult.Ready)
        val estimate = (result as BatteryHealthResult.Ready).estimate
        assertEquals(3000f, estimate.netChargedCapacityMah, 0.5f)
        assertEquals(6000f, estimate.estimatedFullCapacityMah, 0.5f)
        assertTrue(estimate.hasUnknownBatteryStatus)
        assertTrue(estimate.hasLegacyFullTail)
    }

    @Test
    fun invalidSampleSplitsRunsInsteadOfBeingIntegratedAcross() {
        val base = 40_000_000L
        val records = listOf(
            record(4L, base, 20, 1f),
            record(4L, base + 3_600_000L, 45, 1f),
            record(4L, base + 5_400_000L, 45, Float.NaN),
            record(4L, base + 7_200_000L, 45, 1f),
            record(4L, base + 10_800_000L, 70, 1f)
        )

        val result = BatteryHealthEstimator.estimate(listOf(records), 4000f)

        assertTrue(result is BatteryHealthResult.Ready)
        val estimate = (result as BatteryHealthResult.Ready).estimate
        assertEquals(2000f, estimate.netChargedCapacityMah, 0.5f)
        assertEquals(4000f, estimate.estimatedFullCapacityMah, 0.5f)
        assertEquals(50, estimate.totalBatterySpanPercent)
        assertEquals(BatteryHealthEstimate.Confidence.LOW, estimate.confidence)
    }

    @Test
    fun backwardTimestampSplitsRunsWithoutReorderingSamples() {
        val base = 50_000_000L
        val records = listOf(
            record(5L, base, 20, 1f),
            record(5L, base + 3_600_000L, 45, 1f),
            record(5L, base + 1_800_000L, 20, 1f),
            record(5L, base + 5_400_000L, 45, 1f)
        )

        val result = BatteryHealthEstimator.estimate(listOf(records), 4000f)

        assertTrue(result is BatteryHealthResult.Ready)
        val estimate = (result as BatteryHealthResult.Ready).estimate
        assertEquals(2000f, estimate.netChargedCapacityMah, 0.5f)
        assertEquals(4000f, estimate.estimatedFullCapacityMah, 0.5f)
        assertEquals(50, estimate.totalBatterySpanPercent)
    }

    @Test
    fun duplicateTimestampSplitsRuns() {
        val base = 60_000_000L
        val records = listOf(
            record(6L, base, 20, 1f),
            record(6L, base + 3_600_000L, 45, 1f),
            record(6L, base + 3_600_000L, 20, 1f),
            record(6L, base + 7_200_000L, 45, 1f)
        )

        val result = BatteryHealthEstimator.estimate(listOf(records), 4000f)

        assertTrue(result is BatteryHealthResult.Ready)
        val estimate = (result as BatteryHealthResult.Ready).estimate
        assertEquals(2000f, estimate.netChargedCapacityMah, 0.5f)
        assertEquals(4000f, estimate.estimatedFullCapacityMah, 0.5f)
        assertEquals(50, estimate.totalBatterySpanPercent)
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

    private fun record(
        id: Long,
        timestamp: Long,
        level: Int,
        current: Float,
        batteryStatus: Int = BatteryManager.BATTERY_STATUS_UNKNOWN
    ) = ChargeRecord(
        sessionId = id,
        timestamp = timestamp,
        voltage = 4f,
        current = current,
        power = 4f * current,
        batteryLevel = level,
        batteryStatus = batteryStatus
    )
}
