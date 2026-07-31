package per.jau.chargelog.utils

import android.content.Context
import per.jau.chargelog.constants.PrefKeys
import per.jau.chargelog.data.ChargeRepository
import java.util.concurrent.TimeUnit

object HistoryRetention {
    const val PREF_KEY_DAYS = "HISTORY_RETENTION_DAYS"
    const val FOREVER = 0
    val OPTIONS_DAYS = intArrayOf(FOREVER, 7, 30, 90, 180, 365)

    suspend fun cleanup(context: Context, now: Long = System.currentTimeMillis()): Int {
        val prefs = context.getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val retentionDays = prefs.getInt(PREF_KEY_DAYS, FOREVER)
        if (retentionDays <= FOREVER) return 0

        val retentionMs = TimeUnit.DAYS.toMillis(retentionDays.toLong())
        val cutoff = now - retentionMs
        val activeSessionId = if (prefs.getBoolean(PrefKeys.IS_RECORDING, false)) {
            prefs.getLong(PrefKeys.CURRENT_SESSION_START, NO_ACTIVE_SESSION)
        } else {
            NO_ACTIVE_SESSION
        }
        return ChargeRepository.getInstance(context)
            .deleteSessionsEndingBefore(cutoff, activeSessionId)
    }

    private const val NO_ACTIVE_SESSION = Long.MIN_VALUE
}