package per.jau.chargelog.utils

import android.os.BatteryManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import per.jau.chargelog.data.ChargeRecord

class FastChargeLimitTest {
    @Test
    fun powerWatts_requiresFinitePositiveInputs() {
        assertEquals(27f, FastChargeLimit.powerWatts(9f, 3f)!!, 0.001f)
        assertNull(FastChargeLimit.powerWatts(null, 3f))
        assertNull(FastChargeLimit.powerWatts(9f, null))
        assertNull(FastChargeLimit.powerWatts(0f, 3f))
        assertNull(FastChargeLimit.powerWatts(9f, -1f))
        assertNull(FastChargeLimit.powerWatts(Float.NaN, 3f))
        assertNull(FastChargeLimit.powerWatts(Float.POSITIVE_INFINITY, 3f))
    }

    @Test
    fun contiguousSegments_breaksAtMissingOrInvalidSamples() {
        val records = listOf(
            record(1, 5f, 3f),
            record(2, 9f, 3f),
            record(3, null, null),
            record(4, 0f, 3f),
            record(5, 12f, 2f),
            record(6, Float.NaN, 2f),
            record(7, 20f, 2f)
        )

        val segments = FastChargeLimit.contiguousSegments(records)

        assertEquals(listOf(listOf(1L, 2L), listOf(5L), listOf(7L)),
            segments.map { segment -> segment.map { it.timestamp } })
    }

    @Test
    fun historicalPowerRange_multipliesWithinEachSnapshotAndTreatsNotChargingAsZero() {
        val records = listOf(
            record(1, 5f, 5f, BatteryManager.BATTERY_STATUS_CHARGING),
            record(2, 10f, 2f, BatteryManager.BATTERY_STATUS_FULL),
            record(3, 20f, 5f, BatteryManager.BATTERY_STATUS_NOT_CHARGING)
        )

        val range = FastChargeLimit.historicalPowerRange(records)

        assertEquals(0f, range!!.first, 0.001f)
        assertEquals(25f, range.second, 0.001f)
    }
    private fun record(
        timestamp: Long,
        maxVoltage: Float?,
        maxCurrent: Float?,
        batteryStatus: Int = BatteryManager.BATTERY_STATUS_UNKNOWN
    ) =
        ChargeRecord(
            sessionId = 1L,
            timestamp = timestamp,
            voltage = 4f,
            current = 1f,
            power = 4f,
            batteryLevel = 50,
            maxVoltage = maxVoltage,
            maxCurrent = maxCurrent,
            batteryStatus = batteryStatus
        )
}
