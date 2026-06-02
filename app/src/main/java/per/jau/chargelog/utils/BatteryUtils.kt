package per.jau.chargelog.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

object BatteryUtils {

    fun getVoltage(intent: Intent): Float {
        // Try sysfs first for higher precision, fallback to standard API
        val sysfsVoltage = readSysfs("/sys/class/power_supply/battery/voltage_now")
        if (sysfsVoltage != null) {
            return when {
                sysfsVoltage > 1000000 -> sysfsVoltage / 1000000f // microvolts to Volts
                sysfsVoltage > 1000 -> sysfsVoltage / 1000f       // millivolts to Volts
                else -> sysfsVoltage.toFloat()
            }
        }

        val voltageExtra = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (voltageExtra > 0) {
            // Some devices report in mV, some in V
            return if (voltageExtra > 100) voltageExtra / 1000f else voltageExtra.toFloat()
        }
        return 0f
    }

    fun getCurrent(context: Context, batteryStatus: Intent? = null): Float {
        // Determine charging state dynamically to sign current correctly
        val status = if (batteryStatus != null) {
            batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        } else {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
            intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        }
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val sysfsCurrent = readSysfs("/sys/class/power_supply/battery/current_now")
        val currentVal = if (sysfsCurrent != null) {
            Math.abs(sysfsCurrent) / 1000000f // microamps to Amps
        } else {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (currentNow != Int.MIN_VALUE && currentNow != Int.MAX_VALUE) {
                Math.abs(currentNow.toLong()) / 1000000f // microamps to Amps
            } else {
                0f
            }
        }

        // Return positive current for charging, negative for discharging
        return if (isCharging) currentVal else -currentVal
    }

    fun getBatteryLevel(context: Context, batteryStatus: Intent? = null): Int {
        val status = batteryStatus ?: run {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(null, filter)
        } ?: return -1
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            return (level * 100 / scale.toFloat()).toInt()
        }
        return -1
    }

    private fun readSysfs(path: String): Long? {
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val value = file.readText().trim()
                return value.toLongOrNull()
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun readSysfsString(path: String): String? {
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return file.readText().trim()
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }
}
