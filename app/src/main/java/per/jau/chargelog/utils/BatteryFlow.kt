package per.jau.chargelog.utils

import android.os.BatteryManager
import kotlin.math.abs

enum class BatteryFlowDirection {
    CHARGING,
    DISCHARGING,
    IDLE
}

object BatteryFlow {
    fun signedPowerWatts(voltage: Float, current: Float): Float = voltage * current

    fun normalizeNetCurrent(current: Float, batteryStatus: Int): Float = when (batteryStatus) {
        BatteryManager.BATTERY_STATUS_DISCHARGING,
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> -abs(current)
        else -> current
    }

    fun direction(batteryStatus: Int, current: Float): BatteryFlowDirection = when (batteryStatus) {
        BatteryManager.BATTERY_STATUS_CHARGING,
        BatteryManager.BATTERY_STATUS_FULL -> BatteryFlowDirection.CHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryFlowDirection.DISCHARGING
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryFlowDirection.IDLE
        else -> when {
            current > 0f -> BatteryFlowDirection.CHARGING
            current < 0f -> BatteryFlowDirection.DISCHARGING
            else -> BatteryFlowDirection.IDLE
        }
    }
}