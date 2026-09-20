package per.jau.chargelog

import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.utils.FastChargeLimit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

enum class ChartMetric(val unit: String, val digits: Int) {
    VOLTAGE("V", 2), CURRENT("A", 2), POWER("W", 2), BATTERY("%", 0);
    fun value(record: ChargeRecord): Float = when (this) {
        VOLTAGE -> record.voltage
        CURRENT -> record.current
        POWER -> record.power
        BATTERY -> record.batteryLevel.toFloat()
    }
    fun format(value: Float): String =
        if (value.isFinite()) String.format(Locale.getDefault(), "%." + digits + "f %s", value, unit) else "—"
}

/** Stable full-session domain; only drawing coordinates are normalized. */
data class ChartRange(val min: Float, val max: Float) {
    fun toPlot(value: Float): Float = ((value.toDouble() - min) / (max.toDouble() - min) * 100.0).toFloat()
    fun fromPlot(value: Float): Float = (min + value.toDouble() / 100.0 * (max.toDouble() - min)).toFloat()
    companion object {
        fun forMetric(metric: ChartMetric, records: List<ChargeRecord>): ChartRange {
            if (metric == ChartMetric.BATTERY) return ChartRange(0f, 100f)
            val values = records.map(metric::value).filter { it.isFinite() }.toMutableList()
            if (metric == ChartMetric.POWER) {
                values += records.mapNotNull { FastChargeLimit.powerWatts(it.maxVoltage, it.maxCurrent) }
            }
            if (values.isEmpty()) return ChartRange(0f, 1f)
            val low = values.min()
            val high = values.max()
            val padding = max((high.toDouble() - low) * .08, max(abs(low.toDouble()) * .02, .1))
            return ChartRange(
                (low - padding).coerceAtLeast(-Float.MAX_VALUE.toDouble()).toFloat(),
                (high + padding).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
            )
        }
    }
}

data class ChartSelection(val metrics: Set<ChartMetric>, val active: ChartMetric) {
    val ordered: List<ChartMetric> get() = ChartMetric.entries.filter { it in metrics }
    fun toggle(metric: ChartMetric): ChartSelection {
        if (metric in metrics && metrics.size == 1) return this
        val next = if (metric in metrics) metrics - metric else metrics + metric
        return ChartSelection(next, active.takeIf { it in next } ?: ChartMetric.entries.first { it in next })
    }
    fun cycle(direction: Int): ChartSelection {
        val items = ordered
        return copy(active = items[Math.floorMod(items.indexOf(active) + direction, items.size)])
    }
    companion object {
        fun restore(names: Collection<String>?, active: String?, legacyIndex: Int = 2): ChartSelection {
            val metrics = names?.mapNotNull { name -> ChartMetric.entries.find { it.name == name } }?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: setOf(ChartMetric.entries.getOrNull(legacyIndex) ?: ChartMetric.POWER)
            return ChartSelection(metrics, ChartMetric.entries.find { it.name == active && it in metrics }
                ?: ChartMetric.entries.first { it in metrics })
        }
    }
}

data class ChartSeries(val metric: ChartMetric, val isLimit: Boolean = false)
