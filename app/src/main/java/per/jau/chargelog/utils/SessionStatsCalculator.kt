package per.jau.chargelog.utils

import per.jau.chargelog.data.ChargeRecord

data class ChargeStats(
    val minVoltage: Float = 0f,
    val maxVoltage: Float = 0f,
    val minCurrent: Float = 0f,
    val maxCurrent: Float = 0f,
    val minPower: Float = 0f,
    val maxPower: Float = 0f,
    val startBattery: Int = 0,
    val endBattery: Int = 0,
    val batteryChange: Int = 0,
    val minChargePower: Float? = null,
    val maxChargePower: Float? = null,
    val minDischargePower: Float? = null,
    val maxDischargePower: Float? = null,
    val minChargeCurrent: Float? = null,
    val maxChargeCurrent: Float? = null,
    val minDischargeCurrent: Float? = null,
    val maxDischargeCurrent: Float? = null
)

data class SessionEnergy(
    val netMah: Double,
    val netWh: Double
)

/**
 * 统计数据计算工具，消除 MainActivity、HistoryActivity、RawDataDialogHelper 中重复的极值与区间计算逻辑。
 */
object SessionStatsCalculator {

    fun calculate(records: List<ChargeRecord>): ChargeStats? {
        if (records.isEmpty()) return null

        val voltages = records.map { it.voltage }
        val currents = records.map { it.current }
        val powers = records.map { it.power }

        val startBat = records.first().batteryLevel
        val endBat = records.last().batteryLevel

        val chargePoints = records.filter { it.power > 0 }
        val dischargePoints = records.filter { it.power < 0 }

        return ChargeStats(
            minVoltage = voltages.minOrNull() ?: 0f,
            maxVoltage = voltages.maxOrNull() ?: 0f,
            minCurrent = currents.minOrNull() ?: 0f,
            maxCurrent = currents.maxOrNull() ?: 0f,
            minPower = powers.minOrNull() ?: 0f,
            maxPower = powers.maxOrNull() ?: 0f,
            startBattery = startBat,
            endBattery = endBat,
            batteryChange = endBat - startBat,
            minChargePower = chargePoints.minOfOrNull { it.power },
            maxChargePower = chargePoints.maxOfOrNull { it.power },
            minDischargePower = dischargePoints.minOfOrNull { it.power },
            maxDischargePower = dischargePoints.maxOfOrNull { it.power },
            minChargeCurrent = chargePoints.minOfOrNull { it.current },
            maxChargeCurrent = chargePoints.maxOfOrNull { it.current },
            minDischargeCurrent = dischargePoints.minOfOrNull { it.current },
            maxDischargeCurrent = dischargePoints.maxOfOrNull { it.current }
        )
    }
    /**
     * 按记录原始顺序对电池端净电流/功率做有符号梯形积分。
     * 异常点和采样断点不会被跨越；进入 FULL 的区间计入，FULL 后仅扣除实际放电。
     */
    fun calculateEnergy(records: List<ChargeRecord>): SessionEnergy {
        if (records.size < 2) return SessionEnergy(0.0, 0.0)

        fun isValid(record: ChargeRecord): Boolean =
            record.current.isFinite() && record.power.isFinite()

        val validIntervals = records.zipWithNext().mapNotNull { (a, b) ->
            if (isValid(a) && isValid(b)) {
                (b.timestamp - a.timestamp).takeIf { it > 0L }
            } else {
                null
            }
        }.sorted()
        if (validIntervals.isEmpty()) return SessionEnergy(0.0, 0.0)

        val medianInterval = validIntervals[validIntervals.size / 2]
        val maxAllowedGap = maxOf(60_000L, medianInterval * 3)
        var netMah = 0.0
        var netWh = 0.0

        for ((a, b) in records.zipWithNext()) {
            val deltaMs = b.timestamp - a.timestamp
            if (isValid(a) && isValid(b) && deltaMs in 1..maxAllowedGap) {
                val durationHours = deltaMs / 3_600_000.0
                val averageCurrent = (a.current + b.current) / 2.0
                val isPositiveMaintenanceAfterFull =
                    BatteryFlow.isConfirmedFull(a.batteryStatus, a.batteryLevel) &&
                            averageCurrent > 0.0
                if (!isPositiveMaintenanceAfterFull) {
                    netMah += averageCurrent * durationHours * 1000.0
                    netWh += (a.power + b.power) / 2.0 * durationHours
                }
            }
        }

        return SessionEnergy(netMah, netWh)
    }
}
