package per.jau.chargelog.utils

import android.os.BatteryManager

import per.jau.chargelog.data.ChargeRecord

object FastChargeLimit {
    fun powerWatts(maxVoltage: Float?, maxCurrent: Float?): Float? {
        if (maxVoltage == null || maxCurrent == null) return null
        if (!maxVoltage.isFinite() || !maxCurrent.isFinite()) return null
        if (maxVoltage <= 0f || maxCurrent <= 0f) return null

        return (maxVoltage * maxCurrent).takeIf { it.isFinite() && it > 0f }
    }

    fun historicalPowerRange(records: List<ChargeRecord>): Pair<Float, Float>? {
        val powers = records.mapNotNull { record ->
            when (record.batteryStatus) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> powerWatts(record.maxVoltage, record.maxCurrent)
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                BatteryManager.BATTERY_STATUS_DISCHARGING -> 0f
                else -> powerWatts(record.maxVoltage, record.maxCurrent)
            }
        }
        if (powers.isEmpty()) return null
        return (powers.minOrNull() ?: return null) to (powers.maxOrNull() ?: return null)
    }
    fun contiguousSegments(records: List<ChargeRecord>): List<List<ChargeRecord>> {
        val segments = mutableListOf<List<ChargeRecord>>()
        var currentSegment = mutableListOf<ChargeRecord>()

        records.forEach { record ->
            if (powerWatts(record.maxVoltage, record.maxCurrent) != null) {
                currentSegment.add(record)
            } else if (currentSegment.isNotEmpty()) {
                segments.add(currentSegment)
                currentSegment = mutableListOf()
            }
        }

        if (currentSegment.isNotEmpty()) {
            segments.add(currentSegment)
        }
        return segments
    }
}
