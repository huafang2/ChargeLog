package per.jau.chargelog.utils

import android.os.BatteryManager
import per.jau.chargelog.data.ChargeRecord

data class BatteryHealthEstimate(
    val estimatedFullCapacityMah: Float,
    val positiveChargedCapacityMah: Float,
    val dischargedCapacityMah: Float,
    val netChargedCapacityMah: Float,
    val totalBatterySpanPercent: Int,
    val healthPercent: Float?,
    val confidence: Confidence,
    val hasUnknownBatteryStatus: Boolean,
    val hasLegacyFullTail: Boolean
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
        var totalPositiveMah = 0.0
        var totalDischargedMah = 0.0
        var totalNetMah = 0.0
        var totalSpan = 0
        var hasGap = false
        var hasUnknownStatus = false
        var hasLegacyFullTail = false
        var acceptedSessions = 0

        sessions.forEach { records ->
            if (records.size < 2) return@forEach

            fun isValid(record: ChargeRecord): Boolean =
                record.batteryLevel in 0..100 && record.current.isFinite()

            val intervals = records.zipWithNext()
                .mapNotNull { (a, b) ->
                    if (isValid(a) && isValid(b)) {
                        (b.timestamp - a.timestamp).takeIf { it > 0 }
                    } else {
                        null
                    }
                }
                .sorted()
            if (intervals.isEmpty()) return@forEach
            val medianInterval = intervals[intervals.size / 2]
            val maxAllowedGap = maxOf(60_000L, medianInterval * 3)

            var runStartLevel: Int? = null
            var runEndLevel = 0
            var runPositiveMah = 0.0
            var runDischargedMah = 0.0
            var runHasUnknownStatus = false
            var runHasLegacyFullTail = false

            var sessionPositiveMah = 0.0
            var sessionDischargedMah = 0.0
            var sessionNetMah = 0.0
            var sessionSpan = 0
            var sessionHasUnknownStatus = false
            var sessionHasLegacyFullTail = false
            var sessionAccepted = false

            fun finishRun() {
                val start = runStartLevel ?: return
                val span = runEndLevel - start
                val netMah = runPositiveMah - runDischargedMah
                if (span > 0 && netMah > 0.0) {
                    sessionPositiveMah += runPositiveMah
                    sessionDischargedMah += runDischargedMah
                    sessionNetMah += netMah
                    sessionSpan += span
                    sessionHasUnknownStatus = sessionHasUnknownStatus || runHasUnknownStatus
                    sessionHasLegacyFullTail = sessionHasLegacyFullTail || runHasLegacyFullTail
                    sessionAccepted = true
                }
                runStartLevel = null
                runPositiveMah = 0.0
                runDischargedMah = 0.0
                runHasUnknownStatus = false
                runHasLegacyFullTail = false
            }

            for ((a, b) in records.zipWithNext()) {
                if (a.batteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
                    finishRun()
                    break
                }
                if (!isValid(a) || !isValid(b)) {
                    hasGap = true
                    finishRun()
                    if (b.batteryStatus == BatteryManager.BATTERY_STATUS_FULL) break
                    continue
                }
                val deltaMs = b.timestamp - a.timestamp
                if (deltaMs <= 0 || deltaMs > maxAllowedGap) {
                    hasGap = true
                    finishRun()
                    if (b.batteryStatus == BatteryManager.BATTERY_STATUS_FULL) break
                    continue
                }
                if (runStartLevel == null) runStartLevel = a.batteryLevel
                runEndLevel = b.batteryLevel
                val integral = integrateCurrentInterval(a.current.toDouble(), b.current.toDouble(), deltaMs)
                runPositiveMah += integral.positiveMah
                runDischargedMah += integral.dischargedMah

                val intervalHasUnknown =
                    a.batteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN ||
                            b.batteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN
                runHasUnknownStatus = runHasUnknownStatus || intervalHasUnknown
                runHasLegacyFullTail = runHasLegacyFullTail || (
                    intervalHasUnknown && (a.batteryLevel == 100 || b.batteryLevel == 100)
                )

                if (b.batteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
                    finishRun()
                    break
                }
            }
            finishRun()

            if (sessionAccepted) {
                totalPositiveMah += sessionPositiveMah
                totalDischargedMah += sessionDischargedMah
                totalNetMah += sessionNetMah
                totalSpan += sessionSpan
                hasUnknownStatus = hasUnknownStatus || sessionHasUnknownStatus
                hasLegacyFullTail = hasLegacyFullTail || sessionHasLegacyFullTail
                acceptedSessions++
            }
        }

        if (totalSpan < MIN_TOTAL_SPAN_PERCENT) {
            return BatteryHealthResult.Insufficient(totalSpan)
        }
        if (totalNetMah <= 0.0 || acceptedSessions == 0) return BatteryHealthResult.Invalid

        val estimatedFullMah = (totalNetMah / (totalSpan / 100.0)).toFloat()
        if (!estimatedFullMah.isFinite() || estimatedFullMah !in 300f..30_000f) {
            return BatteryHealthResult.Invalid
        }
        val health = ratedCapacityMah
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let { estimatedFullMah / it * 100f }
        val confidence = when {
            hasGap || hasLegacyFullTail || (health != null && health !in 65f..135f) ->
                BatteryHealthEstimate.Confidence.LOW
            totalSpan >= 100 && acceptedSessions >= 2 -> BatteryHealthEstimate.Confidence.HIGH
            else -> BatteryHealthEstimate.Confidence.MEDIUM
        }

        return BatteryHealthResult.Ready(
            BatteryHealthEstimate(
                estimatedFullCapacityMah = estimatedFullMah,
                positiveChargedCapacityMah = totalPositiveMah.toFloat(),
                dischargedCapacityMah = totalDischargedMah.toFloat(),
                netChargedCapacityMah = totalNetMah.toFloat(),
                totalBatterySpanPercent = totalSpan,
                healthPercent = health,
                confidence = confidence,
                hasUnknownBatteryStatus = hasUnknownStatus,
                hasLegacyFullTail = hasLegacyFullTail
            )
        )
    }

    private data class CurrentIntegral(
        val positiveMah: Double,
        val dischargedMah: Double
    )

    private fun integrateCurrentInterval(
        startCurrentA: Double,
        endCurrentA: Double,
        deltaMs: Long
    ): CurrentIntegral {
        val durationHours = deltaMs / 3_600_000.0
        if (startCurrentA >= 0.0 && endCurrentA >= 0.0) {
            return CurrentIntegral(
                positiveMah = (startCurrentA + endCurrentA) / 2.0 * durationHours * 1000.0,
                dischargedMah = 0.0
            )
        }
        if (startCurrentA <= 0.0 && endCurrentA <= 0.0) {
            return CurrentIntegral(
                positiveMah = 0.0,
                dischargedMah = -(startCurrentA + endCurrentA) / 2.0 * durationHours * 1000.0
            )
        }

        val totalMagnitude = kotlin.math.abs(startCurrentA) + kotlin.math.abs(endCurrentA)
        val crossingFraction = kotlin.math.abs(startCurrentA) / totalMagnitude
        return if (startCurrentA > 0.0) {
            CurrentIntegral(
                positiveMah = startCurrentA * crossingFraction * durationHours * 500.0,
                dischargedMah = -endCurrentA * (1.0 - crossingFraction) * durationHours * 500.0
            )
        } else {
            CurrentIntegral(
                positiveMah = endCurrentA * (1.0 - crossingFraction) * durationHours * 500.0,
                dischargedMah = -startCurrentA * crossingFraction * durationHours * 500.0
            )
        }
    }
}
