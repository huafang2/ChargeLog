package per.jau.chargelog.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

object BatteryUtils {

    fun getDesignCapacityMah(): Float? {
        val root = File("/sys/class/power_supply")
        val supplies = buildList {
            add(File(root, "battery"))
            root.listFiles()?.forEach { supply ->
                if (supply.name != "battery" &&
                    readSysfsString(File(supply, "type").path)?.equals("Battery", true) == true
                ) add(supply)
            }
        }
        for (supply in supplies.distinctBy { it.path }) {
            val raw = readSysfs(File(supply, "charge_full_design").path) ?: continue
            val capacityMah = if (raw > 100_000L) raw / 1000f else raw.toFloat()
            if (capacityMah in 300f..30_000f) return capacityMah
        }
        return null
    }

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

    @Suppress("UNUSED_PARAMETER")
    fun getCurrent(context: Context, batteryStatus: Intent? = null): Float {
        // CURRENT_NOW is signed net current at the battery: positive enters, negative leaves.
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (currentNow != Int.MIN_VALUE && currentNow != Int.MAX_VALUE) {
            return currentNow / 1_000_000f
        }

        // Keep the kernel-defined sign when the framework property is unavailable.
        return readSysfs("/sys/class/power_supply/battery/current_now")?.div(1_000_000f) ?: 0f
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            // Ignore
        }
        return null
    }
}
