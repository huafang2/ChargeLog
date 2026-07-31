package per.jau.chargelog.utils

import java.util.Locale

/**
 * 快充上限功率字符串格式化工具。
 * 将 maxVoltage / maxCurrent（单位：V / A）格式化为 "XW(YV/ZA)" 形式，
 * 整数值不显示小数位，非整数值保留一位小数。
 */
object PowerLimitFormatter {
    /**
     * 返回格式化后的限制功率字符串。
     * 若 maxVoltage 或 maxCurrent 为 null / <= 0，则返回空字符串。
     */
    fun formatLimitPower(maxVoltage: Float?, maxCurrent: Float?): String {
        if (maxVoltage == null || maxCurrent == null || maxVoltage <= 0f || maxCurrent <= 0f) return ""
        val maxPower = maxVoltage * maxCurrent
        val vStr = formatCompact(maxVoltage)
        val cStr = formatCompact(maxCurrent)
        val pStr = formatCompact(maxPower)
        return "${pStr}W(${vStr}V/${cStr}A)"
    }

    private fun formatCompact(value: Float): String =
        if (value % 1 == 0f) String.format(Locale.US, "%.0f", value)
        else String.format(Locale.US, "%.1f", value)
}
