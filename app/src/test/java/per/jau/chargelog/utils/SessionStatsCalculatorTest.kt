package per.jau.chargelog.utils

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Test
import per.jau.chargelog.data.ChargeRecord

class SessionStatsCalculatorTest {
    @Test
    fun signedCurrentIsIntegratedAndNegativeCurrentIsSubtracted() {
        val result = SessionStatsCalculator.calculateEnergy(
            listOf(
                record(0, 1f, 4f),
                record(60_000, -0.5f, -2f),
                record(120_000, -0.5f, -2f)
            )
        )

        assertEquals(-4.1667, result.netMah, 0.001)
        assertEquals(-0.01667, result.netWh, 0.0001)
    }

    @Test
    fun enteringFullIsIncludedButPositiveMaintenanceAfterFullIsExcluded() {
        val result = SessionStatsCalculator.calculateEnergy(
            listOf(
                record(0, 1f, 4f),
                record(60_000, 1f, 4f, BatteryManager.BATTERY_STATUS_FULL),
                record(120_000, 1f, 4f, BatteryManager.BATTERY_STATUS_FULL),
                record(180_000, -1f, -4f, BatteryManager.BATTERY_STATUS_NOT_CHARGING),
                record(240_000, -1f, -4f, BatteryManager.BATTERY_STATUS_NOT_CHARGING)
            )
        )

        assertEquals(0.0, result.netMah, 0.001)
        assertEquals(0.0, result.netWh, 0.0001)
    }

    @Test
    fun largeSamplingGapIsNotIntegrated() {
        val result = SessionStatsCalculator.calculateEnergy(
            listOf(
                record(0, 1f, 4f),
                record(60_000, 1f, 4f),
                record(120_000, 1f, 4f),
                record(720_000, 1f, 4f)
            )
        )

        assertEquals(33.3333, result.netMah, 0.001)
        assertEquals(0.13333, result.netWh, 0.0001)
    }

    private fun record(
        timestamp: Long,
        current: Float,
        power: Float,
        status: Int = BatteryManager.BATTERY_STATUS_CHARGING
    ) = ChargeRecord(
        sessionId = 1L,
        timestamp = timestamp,
        voltage = 4f,
        current = current,
        power = power,
        batteryLevel = 50,
        batteryStatus = status
    )
}