package per.jau.chargelog.constants

/**
 * 统一的 SharedPreferences key 常量，消除全局各处的魔法字符串。
 */
object PrefKeys {
    /** SharedPreferences 文件名 */
    const val PREFS_NAME = "ChargeLogPrefs"

    // ── 录制状态 ──────────────────────────────────────────────────────────────
    const val IS_RECORDING = "IS_RECORDING"
    const val CURRENT_SESSION_START = "CURRENT_SESSION_START"
    const val FORCE_NEW_SESSION = "FORCE_NEW_SESSION"
    const val USER_EXITED = "USER_EXITED"

    // ── 设置项 ────────────────────────────────────────────────────────────────
    const val SAMPLING_INTERVAL_SECONDS = "SAMPLING_INTERVAL_SECONDS"
    const val THEME_MODE = "THEME_MODE"
    const val ENABLE_BG_REPORT = "ENABLE_BG_REPORT"
    const val RATED_CAPACITY_MAH = "RATED_CAPACITY_MAH"

    // ── 后台统计 ──────────────────────────────────────────────────────────────
    const val APP_IN_BACKGROUND = "APP_IN_BACKGROUND"
    const val BG_STATS_RECORDED = "BG_STATS_RECORDED"
    const val BACKGROUND_START_TIME = "BACKGROUND_START_TIME"
    const val BACKGROUND_START_BATTERY = "BACKGROUND_START_BATTERY"
    const val BG_MIN_CHARGE_POWER = "BG_MIN_CHARGE_POWER"
    const val BG_MAX_CHARGE_POWER = "BG_MAX_CHARGE_POWER"
    const val BG_MIN_DISCHARGE_POWER = "BG_MIN_DISCHARGE_POWER"
    const val BG_MAX_DISCHARGE_POWER = "BG_MAX_DISCHARGE_POWER"

    // ── 通知节流 ──────────────────────────────────────────────────────────────
    const val LAST_NOTIFICATION_BATTERY_LEVEL = "LAST_NOTIFICATION_BATTERY_LEVEL"
    const val BG_SAMPLE_COUNT = "BG_SAMPLE_COUNT"

    // ── Intent Extra ──────────────────────────────────────────────────────────
    const val EXTRA_HISTORY_SESSION_ID = "HISTORY_SESSION_ID"
}
