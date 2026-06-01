package per.jau.chargelog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
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
            .setSmallIcon(R.drawable.ic_stat_battery)//(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        val notification = builder.build()
            
        startForeground(NOTIFICATION_ID, notification)

        // Initialize session start time in SharedPreferences
        val db = ChargeDatabase.getDatabase(this)
        scope.launch {
            val latest = db.chargeDao().getLatestRecord()
            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
            val existingStart = prefs.getLong("CURRENT_SESSION_START", 0L)
            val forceNew = prefs.getBoolean("FORCE_NEW_SESSION", false)
            
            val sessionStart = if (!forceNew && latest != null && (now - latest.timestamp) < 5 * 60 * 1000) {
                if (existingStart > 0L && existingStart <= latest.timestamp) {
                    existingStart
                } else {
                    latest.timestamp
                }
            } else {
                now
            }
            prefs.edit()
                .putLong("CURRENT_SESSION_START", sessionStart)
                .putBoolean("FORCE_NEW_SESSION", false)
                .apply()
        }
        
        wakeLock.acquire()
        startLoggingLoop()
    }

    private fun startLoggingLoop() {
        loggingJob?.cancel()
        loggingJob = scope.launch {
            val db = ChargeDatabase.getDatabase(this@ChargeLoggingService)
            val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
            while (true) {
                try {
                    val isRecording = prefs.getBoolean("IS_RECORDING", false)
                    if (isRecording) {
                        var sumVoltage = 0f
                        var sumCurrent = 0f
                        var count = 0
                        var latestBatteryLevel = -1

                        // Sample 10 times over 1 second to compute moving average
                        for (i in 0 until 10) {
                            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                            val batteryStatus = registerReceiver(null, filter)
                            if (batteryStatus != null) {
                                val voltage = BatteryUtils.getVoltage(batteryStatus)
                                val current = BatteryUtils.getCurrent(this@ChargeLoggingService)
                                sumVoltage += voltage
                                sumCurrent += current
                                count++
                                
                                val batLevel = BatteryUtils.getBatteryLevel(this@ChargeLoggingService)
                                if (batLevel >= 0) {
                                    latestBatteryLevel = batLevel
                                }
                            }
                            kotlinx.coroutines.delay(100L)
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
                                batteryLevel = latestBatteryLevel
                            )

                            db.chargeDao().insert(record)
                            
                            // Update dynamic notification
                            updateNotification(avgVoltage, avgCurrent, avgPower, latestBatteryLevel, isRecording = true)
                            updateBackgroundPowerStats(prefs, avgPower)
                        }
                    } else {
                        // Just update notification with real-time value without recording to database
                        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        val batteryStatus = registerReceiver(null, filter)
                        if (batteryStatus != null) {
                            val voltage = BatteryUtils.getVoltage(batteryStatus)
                            val current = BatteryUtils.getCurrent(this@ChargeLoggingService)
                            val power = voltage * current
                            val batteryLevel = BatteryUtils.getBatteryLevel(this@ChargeLoggingService)
                            updateNotification(voltage, current, power, batteryLevel, isRecording = false)
                            updateBackgroundPowerStats(prefs, power)
                        }
                        kotlinx.coroutines.delay(1000L)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    kotlinx.coroutines.delay(1000L)
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(stateTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_stat_battery)//(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        builder.extras.putBoolean("android.requestPromotedOngoing", true)

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            val minP = prefs.getFloat("BG_MIN_POWER", Float.MAX_VALUE)
            val maxP = prefs.getFloat("BG_MAX_POWER", -Float.MAX_VALUE)
            
            val newMin = if (currentPower < minP) currentPower else minP
            val newMax = if (currentPower > maxP) currentPower else maxP
            
            prefs.edit()
                .putFloat("BG_MIN_POWER", newMin)
                .putFloat("BG_MAX_POWER", newMax)
                .apply()
        }
    }

    companion object {
        const val CHANNEL_ID = "ChargeLogServiceChannel"
        const val NOTIFICATION_ID = 1
    }
}
