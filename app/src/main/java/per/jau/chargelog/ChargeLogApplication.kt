package per.jau.chargelog

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import per.jau.chargelog.constants.PrefKeys

class ChargeLogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize theme mode on application startup
        val themePrefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        val currentTheme = themePrefs.getInt(PrefKeys.THEME_MODE, 0)
        val targetNightMode = when (currentTheme) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        
        if (AppCompatDelegate.getDefaultNightMode() != targetNightMode) {
            AppCompatDelegate.setDefaultNightMode(targetNightMode)
        }
    }
}
