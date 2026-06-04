package per.jau.chargelog

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ChargeLogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize theme mode on application startup
        val themePrefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
        val currentTheme = themePrefs.getInt("THEME_MODE", 0)
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
