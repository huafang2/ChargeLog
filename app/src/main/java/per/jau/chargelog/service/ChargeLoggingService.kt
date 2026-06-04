package per.jau.chargelog.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import per.jau.chargelog.R
import per.jau.chargelog.data.ChargeDatabase
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.utils.BatteryUtils
import java.util.Locale
import kotlin.math.abs
import androidx.core.content.edit
import kotlin.time.Duration.Companion.milliseconds

class ChargeLoggingService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var wakeLock: PowerManager.WakeLock
    private var loggingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeLog::LoggingWakeLock")
        
        createNotificationChannel()
        
        val openIntent = Intent(this, per.jau.chargelog.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Initial silent placeholder notification
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("充电日志正在运行")
            .setContentText("正在后台记录充电数据...")
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        val notification = builder.build()
            
        startForeground(NOTIFICATION_ID, notification)

        // Initialize session start time in SharedPreferences
        val launch = scope.launch {
            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)

            prefs.edit {
                ->
            }
        }

        startLoggingLoop()
    }

    private fun triggerImmediateNotificationUpdate(isRecording: Boolean) {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, filter)
            if (batteryStatus != null) {
                val voltage = BatteryUtils.getVoltage(batteryStatus)
                val current = BatteryUtils.getCurrent(this, batteryStatus)
                val power = voltage * current
                val batteryLevel = BatteryUtils.getBatteryLevel(this, batteryStatus)
                updateNotification(voltage, current, power, batteryLevel, isRecording)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startLoggingLoop() {
        loggingJob?.cancel()
        loggingJob = scope.launch {
            val db = ChargeDatabase.getDatabase(this@ChargeLoggingService)
            val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
            while (true) {
                try {
                    val isRecording = prefs.getBoolean("IS_RECORDING", false)
                    val interval = prefs.getInt("SAMPLING_INTERVAL_SECONDS", 5)
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val isInteractive = powerManager.isInteractive
                    // If system Battery Saver is active, double the interval to save extra battery
                    val activeInterval = if (powerManager.isPowerSaveMode) interval * 2 else interval

                    if (isRecording) {
                        // Check if actively charging
                        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        val batteryStatus = registerReceiver(null, filter)
                        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL

                        if (charging) {
                            if (!wakeLock.isHeld) {
                                wakeLock.acquire(10*60*1000L /*10 minutes*/)
                            }
                        } else {
                            if (wakeLock.isHeld) {
                                wakeLock.release()
                            }
                            // Acquire wake lock for 1 second during sampling window
                            wakeLock.acquire(1000)
                        }

                        var sumVoltage = 0f
                        var sumCurrent = 0f
                        var count = 0
                        var latestBatteryLevel = -1

                        // Sample 5 times over 500ms to compute average
                        (0..<5).forEach { i ->
                            val sampleFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                            val sampleStatus = registerReceiver(null, sampleFilter)
                            if (sampleStatus != null) {
                                val voltage = BatteryUtils.getVoltage(sampleStatus)
                                val current = BatteryUtils.getCurrent(this@ChargeLoggingService, sampleStatus)
                                sumVoltage += voltage
                                sumCurrent += current
                                count++

                                val batLevel = BatteryUtils.getBatteryLevel(this@ChargeLoggingService, sampleStatus)
                                if (batLevel >= 0) {
                                    latestBatteryLevel = batLevel
                                }
                            }
                            kotlinx.coroutines.delay(100L.milliseconds)
                        }

                        if (count > 0) {
                            val avgVoltage = sumVoltage / count
                            val avgCurrent = sumCurrent / count
                            val avgPower = avgVoltage * avgCurrent
                            
                            val sessionId = prefs.getLong("CURRENT_SESSION_START", System.currentTimeMillis())

                            val record = ChargeRecord(
                                sessionId = sessionId,
                                timestamp = System.currentTimeMillis(),
                                voltage = avgVoltage,
                                current = avgCurrent,
                                power = avgPower,
                                batteryLevel = latestBatteryLevel,
                                screenState = if (isInteractive) 1 else 0
                            )

                            db.chargeDao().insert(record)
                            
                            // Throttled notification update: only update if screen is on, or battery level changes, or every 10 samples
                            val lastNotificationLevel = prefs.getInt("LAST_NOTIFICATION_BATTERY_LEVEL", -1)
                            val sampleCount = prefs.getInt("BG_SAMPLE_COUNT", 0) + 1
                            prefs.edit { putInt("BG_SAMPLE_COUNT", sampleCount) }

                            if (isInteractive || latestBatteryLevel != lastNotificationLevel || sampleCount % 10 == 0) {
                                updateNotification(avgVoltage, avgCurrent, avgPower, latestBatteryLevel, isRecording = true)
                                prefs.edit {
                                    putInt(
                                        "LAST_NOTIFICATION_BATTERY_LEVEL",
                                        latestBatteryLevel
                                    )
                                }
                            }
                            
                            updateBackgroundPowerStats(prefs, avgPower)
                        }

                        if (!charging && wakeLock.isHeld) {
                            wakeLock.release()
                        }

                        val delayMs = maxOf(100L, (activeInterval * 1000L) - 500L)
                        kotlinx.coroutines.delay(delayMs.milliseconds)
                    } else {
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }

                        if (isInteractive) {
                            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                            val batteryStatus = registerReceiver(null, filter)
                            if (batteryStatus != null) {
                                val voltage = BatteryUtils.getVoltage(batteryStatus)
                                val current = BatteryUtils.getCurrent(this@ChargeLoggingService, batteryStatus)
                                val power = voltage * current
                                val batteryLevel = BatteryUtils.getBatteryLevel(this@ChargeLoggingService, batteryStatus)
                                updateNotification(voltage, current, power, batteryLevel, isRecording = false)
                                updateBackgroundPowerStats(prefs, power)
                            }
                            kotlinx.coroutines.delay(5000L.milliseconds)
                        } else {
                            // Screen off & not recording: sleep 30s to conserve battery, no notification updates
                            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                            val batteryStatus = registerReceiver(null, filter)
                            if (batteryStatus != null) {
                                val voltage = BatteryUtils.getVoltage(batteryStatus)
                                val current = BatteryUtils.getCurrent(this@ChargeLoggingService, batteryStatus)
                                val power = voltage * current
                                updateBackgroundPowerStats(prefs, power)
                            }
                            kotlinx.coroutines.delay(30000L.milliseconds)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    kotlinx.coroutines.delay(5000L.milliseconds)
                }
            }
        }
    }

    private fun updateNotification(voltage: Float, current: Float, power: Float, batteryLevel: Int, isRecording: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val isDischarging = current < 0
        val stateTitle = if (isDischarging) {
            String.format(Locale.getDefault(), "放电: %.2f W", power)
        } else {
            String.format(Locale.getDefault(), "充电: %.2f W", power)
        }
        
        val recordStatus = if (isRecording) "记录中" else "已停止"
        val contentText = String.format(
            Locale.getDefault(),
            "状态: %s\t|\t电量: %d%%\n电压: %.2f V\t|\t电流: %.2f A",
            recordStatus, batteryLevel, voltage, current
        )

        val openIntent = Intent(this, per.jau.chargelog.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(this, ChargeLoggingService::class.java).apply {
            action = ACTION_START_RECORDING
        }
        val startPendingIntent = PendingIntent.getService(
            this,
            3,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ChargeLoggingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitIntent = Intent(this, ChargeLoggingService::class.java).apply {
            action = ACTION_EXIT_APP
        }
        val exitPendingIntent = PendingIntent.getService(
            this,
            2,
            exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(stateTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_stat_battery)//(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        
        if (isRecording) {
            builder.addAction(0, "停止统计", stopPendingIntent)
        } else {
            builder.addAction(0, "开始统计", startPendingIntent)
        }
        builder.addAction(0, "退出后台", exitPendingIntent)
        
        builder.extras.putBoolean("android.requestPromotedOngoing", true)

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
                prefs.edit {
                    putBoolean("IS_RECORDING", true)
                }
                triggerImmediateNotificationUpdate(true)
                startLoggingLoop()
            }
            ACTION_STOP_RECORDING -> {
                val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
                prefs.edit {
                    putBoolean("IS_RECORDING", false)
                        .putBoolean("FORCE_NEW_SESSION", true)
                }
                triggerImmediateNotificationUpdate(false)
                startLoggingLoop()
            }
            ACTION_EXIT_APP -> {
                val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
                prefs.edit {
                    putBoolean("IS_RECORDING", false)
                        .putBoolean("FORCE_NEW_SESSION", true)
                        .putBoolean("USER_EXITED", true)
                }
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        loggingJob?.cancel()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "充电日志服务通道",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun updateBackgroundPowerStats(prefs: android.content.SharedPreferences, currentPower: Float) {
        val appInBackground = prefs.getBoolean("APP_IN_BACKGROUND", false)
        if (appInBackground) {
            prefs.edit {
                if (currentPower >= 0) {
                    // Charging power range
                    val minP = prefs.getFloat("BG_MIN_CHARGE_POWER", Float.MAX_VALUE)
                    val maxP = prefs.getFloat("BG_MAX_CHARGE_POWER", -Float.MAX_VALUE)
                    val newMinP = if (currentPower < minP) currentPower else minP
                    val newMaxP = if (currentPower > maxP) currentPower else maxP
                    putFloat("BG_MIN_CHARGE_POWER", newMinP)
                    putFloat("BG_MAX_CHARGE_POWER", newMaxP)
                } else {
                    // Discharging power range (use absolute value)
                    val absP = abs(currentPower)
                    val minP = prefs.getFloat("BG_MIN_DISCHARGE_POWER", Float.MAX_VALUE)
                    val maxP = prefs.getFloat("BG_MAX_DISCHARGE_POWER", -Float.MAX_VALUE)
                    val newMinP = if (absP < minP) absP else minP
                    val newMaxP = if (absP > maxP) absP else maxP
                    putFloat("BG_MIN_DISCHARGE_POWER", newMinP)
                    putFloat("BG_MAX_DISCHARGE_POWER", newMaxP)
                }

                putBoolean("BG_STATS_RECORDED", true)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "ChargeLogServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_RECORDING = "per.jau.chargelog.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "per.jau.chargelog.action.STOP_RECORDING"
        const val ACTION_EXIT_APP = "per.jau.chargelog.action.EXIT_APP"
    }
}
