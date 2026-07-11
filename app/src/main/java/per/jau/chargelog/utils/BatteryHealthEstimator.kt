package per.jau.chargelog.utils

import per.jau.chargelog.data.ChargeRecord

data class BatteryHealthEstimate(
    val estimatedFullCapacityMah: Float,
    val chargedCapacityMah: Float,
    val totalBatterySpanPercent: Int,
    val healthPercent: Float?,
    val confidence: Confidence
) {
    enum class Confidence { LOW, MEDIUM, HIGH }
}

sealed class BatteryHealthResult {
    data class Ready(val estimate: BatteryHealthEstimate) : BatteryHealthResult()
    data class Insufficient(val totalBatterySpanPercent: Int) : BatteryHealthResult()
    data object Invalid : BatteryHealthResult()
}

object BatteryHealthEstimator {
    const val MIN_TOTAL_SPAN_PERCENT = 50

    fun estimate(
        sessions: List<List<ChargeRecord>>,
        ratedCapacityMah: Float?
    ): BatteryHealthResult {
        var totalChargedMah = 0.0
        var totalSpan = 0
        var hasGap = false
        var acceptedSessions = 0

        sessions.forEach { records ->
            val sorted = records
                .filter { it.batteryLevel in 0..100 && it.current.isFinite() }
                .sortedBy { it.timestamp }
            if (sorted.size < 2) return@forEach

            val intervals = sorted.zipWithNext()
                .map { (a, b) -> b.timestamp - a.timestamp }
                .filter { it > 0 }
                .sorted()
            if (intervals.isEmpty()) return@forEach
            val medianInterval = intervals[intervals.size / 2]
            val maxAllowedGap = maxOf(60_000L, medianInterval * 3)

            var runStartLevel: Int? = null
            var runEndLevel = 0
            var runMah = 0.0
            var bestSpan = 0
            var bestMah = 0.0

            fun finishRun() {
                val start = runStartLevel ?: return
                val span = runEndLevel - start
                if (span > bestSpan && runMah > 0.0) {
                    bestSpan = span
                    bestMah = runMah
                }
                runStartLevel = null
                runMah = 0.0
            }

            sorted.zipWithNext().forEach { (a, b) ->
                val deltaMs = b.timestamp - a.timestamp
                if (deltaMs <= 0) return@forEach
                if (deltaMs > maxAllowedGap) {
                    hasGap = true
                    finishRun()
                    return@forEach
                }
                if (a.current < 0f || b.current < 0f || b.batteryLevel < a.batteryLevel) {
                    finishRun()
                    return@forEach
                }
                if (runStartLevel == null) runStartLevel = a.batteryLevel
                runEndLevel = b.batteryLevel
                val averageCurrentA = (a.current.toDouble() + b.current.toDouble()) / 2.0
                runMah += averageCurrentA * deltaMs / 3_600_000.0 * 1000.0
            }
            finishRun()

            if (bestSpan > 0 && bestMah > 0.0) {
                totalChargedMah += bestMah
                totalSpan += bestSpan
                acceptedSessions++
            }
        }

        if (totalSpan < MIN_TOTAL_SPAN_PERCENT) {
            return BatteryHealthResult.Insufficient(totalSpan)
        }
        if (totalChargedMah <= 0.0 || acceptedSessions == 0) return BatteryHealthResult.Invalid

        val estimatedFullMah = (totalChargedMah / (totalSpan / 100.0)).toFloat()
        if (!estimatedFullMah.isFinite() || estimatedFullMah !in 300f..30_000f) {
            return BatteryHealthResult.Invalid
        }
        val health = ratedCapacityMah
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let { estimatedFullMah / it * 100f }
        val confidence = when {
            hasGap || (health != null && health !in 65f..135f) -> BatteryHealthEstimate.Confidence.LOW
            totalSpan >= 100 && acceptedSessions >= 2 -> BatteryHealthEstimate.Confidence.HIGH
            else -> BatteryHealthEstimate.Confidence.MEDIUM
        }

        return BatteryHealthResult.Ready(
            BatteryHealthEstimate(
                estimatedFullCapacityMah = estimatedFullMah,
                chargedCapacityMah = totalChargedMah.toFloat(),
                totalBatterySpanPercent = totalSpan,
                healthPercent = health,
                confidence = confidence
            )
        )
    }
}
