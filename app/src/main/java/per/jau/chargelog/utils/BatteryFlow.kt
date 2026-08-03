package per.jau.chargelog.utils

import android.os.BatteryManager
import kotlin.math.abs

enum class BatteryFlowDirection {
    CHARGING,
    DISCHARGING,
    IDLE
}

enum class BatteryDisplayState {
    CHARGING,
    DISCHARGING,
    FULL,
    IDLE
}

object BatteryFlow {
    fun signedPowerWatts(voltage: Float, current: Float): Float = voltage * current

    fun isConfirmedFull(batteryStatus: Int, batteryLevel: Int): Boolean =
        batteryStatus == BatteryManager.BATTERY_STATUS_FULL && batteryLevel == 100

    fun normalizeNetCurrent(current: Float, batteryStatus: Int): Float = when (batteryStatus) {
        BatteryManager.BATTERY_STATUS_CHARGING,
        BatteryManager.BATTERY_STATUS_FULL -> abs(current)
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

    fun displayState(
        batteryStatus: Int,
        current: Float,
        batteryLevel: Int
    ): BatteryDisplayState = when {
        isConfirmedFull(batteryStatus, batteryLevel) -> BatteryDisplayState.FULL
        batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL ->
            BatteryDisplayState.CHARGING
        batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING ->
            BatteryDisplayState.DISCHARGING
        batteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
            BatteryDisplayState.IDLE
        current > 0f -> BatteryDisplayState.CHARGING
        current < 0f -> BatteryDisplayState.DISCHARGING
        else -> BatteryDisplayState.IDLE
    }
}