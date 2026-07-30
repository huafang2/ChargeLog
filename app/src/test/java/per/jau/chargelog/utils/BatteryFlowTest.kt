package per.jau.chargelog.utils

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryFlowTest {
    @Test
    fun zeroCurrentDoesNotBecomeChargingWhenUnplugged() {
        assertEquals(
            BatteryFlowDirection.DISCHARGING,
            BatteryFlow.direction(BatteryManager.BATTERY_STATUS_DISCHARGING, 0f)
        )
        assertEquals(
            BatteryFlowDirection.IDLE,
            BatteryFlow.direction(BatteryManager.BATTERY_STATUS_NOT_CHARGING, 0f)
        )
    }

    @Test
    fun unknownStatusFallsBackToSignedNetCurrent() {
        assertEquals(
            BatteryFlowDirection.CHARGING,
            BatteryFlow.direction(BatteryManager.BATTERY_STATUS_UNKNOWN, 1f)
        )
        assertEquals(
            BatteryFlowDirection.DISCHARGING,
            BatteryFlow.direction(BatteryManager.BATTERY_STATUS_UNKNOWN, -1f)
        )
        assertEquals(
            BatteryFlowDirection.IDLE,
            BatteryFlow.direction(BatteryManager.BATTERY_STATUS_UNKNOWN, 0f)
        )
    }

    @Test
    fun explicitNonChargingStatusCorrectsContradictoryPositiveCurrent() {
        assertEquals(
            -1f,
            BatteryFlow.normalizeNetCurrent(1f, BatteryManager.BATTERY_STATUS_DISCHARGING),
            0.001f
        )
        assertEquals(
            -1f,
            BatteryFlow.normalizeNetCurrent(1f, BatteryManager.BATTERY_STATUS_NOT_CHARGING),
            0.001f
        )
    }

    @Test
    fun negativeCurrentWhileChargingIsPreservedForNetIntegration() {
        assertEquals(
            -1f,
            BatteryFlow.normalizeNetCurrent(-1f, BatteryManager.BATTERY_STATUS_CHARGING),
            0.001f
        )
    }
    @Test
    fun powerKeepsTheNetCurrentSign() {
        assertEquals(4f, BatteryFlow.signedPowerWatts(4f, 1f), 0.001f)
        assertEquals(-4f, BatteryFlow.signedPowerWatts(4f, -1f), 0.001f)
    }

    @Test
    fun notChargingStatusProducesNegativeLivePower() {
        val current = BatteryFlow.normalizeNetCurrent(
            1.25f,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING
        )

        assertEquals(-1.25f, current, 0.001f)
        assertEquals(-5f, BatteryFlow.signedPowerWatts(4f, current), 0.001f)
    }
}