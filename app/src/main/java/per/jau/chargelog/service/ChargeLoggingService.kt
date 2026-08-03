package per.jau.chargelog.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import per.jau.chargelog.R
import per.jau.chargelog.data.ChargeRepository
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.utils.BatteryUtils
import per.jau.chargelog.utils.BatteryDisplayState
import per.jau.chargelog.utils.BatteryFlow
import per.jau.chargelog.utils.HistoryRetention
import per.jau.chargelog.constants.PrefKeys
import androidx.core.content.edit
import kotlin.time.Duration.Companion.milliseconds

class ChargeLoggingService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var wakeLock: PowerManager.WakeLock
    private var loggingJob: Job? = null
    private lateinit var sessionInitializationJob: Job
    private val commandChannel = Channel<String>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
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
            .setContentTitle(getString(R.string.service_running_title))
            .setContentText(getString(R.string.service_running_desc))
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        val notification = builder.build()
            
        startForeground(NOTIFICATION_ID, notification)

        sessionInitializationJob = scope.launch {
            HistoryRetention.cleanup(this@ChargeLoggingService)
            initializeSessionState()
        }
        scope.launch {
            sessionInitializationJob.join()
            for (action in commandChannel) {
                handleServiceAction(action)
            }
        }

        startLoggingLoop()
    }

    private suspend fun initializeSessionState() {
        val repo = ChargeRepository.getInstance(this)
        val latest = repo.getLatestRecord()
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        val isRecording = prefs.getBoolean(PrefKeys.IS_RECORDING, false)
        val forceNew = prefs.getBoolean(PrefKeys.FORCE_NEW_SESSION, false)
        val existingStart = prefs.getLong(PrefKeys.CURRENT_SESSION_START, 0L)

        if (isRecording) {
            val canResume = !forceNew && latest != null &&
                    now - latest.timestamp in 0L until SESSION_RESUME_WINDOW_MS
            val sessionStart = if (canResume) {
                existingStart.takeIf { it > 0L && it <= latest.timestamp } ?: latest.timestamp
            } else {
                now
            }
            prefs.edit {
                putLong(PrefKeys.CURRENT_SESSION_START, sessionStart)
                putBoolean(PrefKeys.FORCE_NEW_SESSION, false)
            }
        } else if (existingStart <= 0L) {
            prefs.edit { putLong(PrefKeys.CURRENT_SESSION_START, now) }
        }
    }

    private fun handleServiceAction(action: String) {
        val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        when (action) {
            ACTION_START_RECORDING -> {
                val wasRecording = prefs.getBoolean(PrefKeys.IS_RECORDING, false)
                val forceNew = prefs.getBoolean(PrefKeys.FORCE_NEW_SESSION, false)
                val sessionId = RecordingSessionPolicy.sessionIdForStart(
                    wasRecording = wasRecording,
                    forceNew = forceNew,
                    existingSessionId = prefs.getLong(PrefKeys.CURRENT_SESSION_START, 0L),
                    now = System.currentTimeMillis()
                )
                prefs.edit {
                    putLong(PrefKeys.CURRENT_SESSION_START, sessionId)
                    putBoolean(PrefKeys.IS_RECORDING, true)
                    putBoolean(PrefKeys.FORCE_NEW_SESSION, false)
                }
                triggerImmediateNotificationUpdate(true)
                startLoggingLoop()
            }
            ACTION_STOP_RECORDING -> {
                prefs.edit {
                    putBoolean(PrefKeys.IS_RECORDING, false)
                    putBoolean(PrefKeys.FORCE_NEW_SESSION, true)
                }
                triggerImmediateNotificationUpdate(false)
                startLoggingLoop()
            }
            ACTION_EXIT_APP -> {
                prefs.edit {
                    putBoolean(PrefKeys.IS_RECORDING, false)
                    putBoolean(PrefKeys.FORCE_NEW_SESSION, true)
                    putBoolean(PrefKeys.USER_EXITED, true)
                }
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
    private fun triggerImmediateNotificationUpdate(isRecording: Boolean) {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, filter)
            if (batteryStatus != null) {
                val voltage = BatteryUtils.getVoltage(batteryStatus)
                val current = BatteryUtils.getCurrent(this, batteryStatus)
                val power = BatteryFlow.signedPowerWatts(voltage, current)
                val batteryLevel = BatteryUtils.getBatteryLevel(this, batteryStatus)
                updateNotification(voltage, current, power, batteryLevel, isRecording)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startLoggingLoop() {
        loggingJob?.cancel()
        val initializationJob = sessionInitializationJob
        loggingJob = scope.launch {
            initializationJob.join()
            val repo = ChargeRepository.getInstance(this@ChargeLoggingService)
            val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
            while (true) {
                try {
                    val isRecording = prefs.getBoolean(PrefKeys.IS_RECORDING, false)
                    val interval = prefs.getInt(PrefKeys.SAMPLING_INTERVAL_SECONDS, 5)
                    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
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
                        var latestBatteryStatus: Intent? = null

                        // Sample 5 times over 500ms to compute average
                        (0..<5).forEach { _ ->
                            val sampleFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                            val sampleStatus = registerReceiver(null, sampleFilter)
                            if (sampleStatus != null) {
                                latestBatteryStatus = sampleStatus
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
                            val avgPower = BatteryFlow.signedPowerWatts(avgVoltage, avgCurrent)
                            
                            val batteryStatusValue = latestBatteryStatus?.getIntExtra(
                                BatteryManager.EXTRA_STATUS,
                                BatteryManager.BATTERY_STATUS_UNKNOWN
                            ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
                            val isCharging = batteryStatusValue == BatteryManager.BATTERY_STATUS_CHARGING ||
                                    batteryStatusValue == BatteryManager.BATTERY_STATUS_FULL
                            var maxVLimit: Float? = null
                            var maxCLimit: Float? = null
                            if (isCharging && latestBatteryStatus != null) {
                                val maxCurrentMicro = latestBatteryStatus.getIntExtra("max_charging_current", -1)
                                val maxVoltageMicro = latestBatteryStatus.getIntExtra("max_charging_voltage", -1)
                                if (maxCurrentMicro > 0 && maxVoltageMicro > 0) {
                                    maxCLimit = maxCurrentMicro / 1_000_000f
                                    maxVLimit = maxVoltageMicro / 1_000_000f
                                }
                            }
                            
                            val sessionId = prefs.getLong(PrefKeys.CURRENT_SESSION_START, System.currentTimeMillis())

                            val record = ChargeRecord(
                                sessionId = sessionId,
                                timestamp = System.currentTimeMillis(),
                                voltage = avgVoltage,
                                current = avgCurrent,
                                power = avgPower,
                                batteryLevel = latestBatteryLevel,
                                screenState = if (isInteractive) 1 else 0,
                                maxVoltage = maxVLimit,
                                maxCurrent = maxCLimit,
                                batteryStatus = batteryStatusValue
                            )

                            val canPersist = RecordingSessionPolicy.shouldPersistSample(
                                isRecording = prefs.getBoolean(PrefKeys.IS_RECORDING, false),
                                activeSessionId = prefs.getLong(PrefKeys.CURRENT_SESSION_START, 0L),
                                sampleSessionId = sessionId
                            )
                            if (canPersist) {
                                repo.insert(record)

                                // Throttled notification update: only update if screen is on, or battery level changes, or every 10 samples
                                val lastNotificationLevel = prefs.getInt(PrefKeys.LAST_NOTIFICATION_BATTERY_LEVEL, -1)
                                val sampleCount = prefs.getInt(PrefKeys.BG_SAMPLE_COUNT, 0) + 1
                                prefs.edit { putInt(PrefKeys.BG_SAMPLE_COUNT, sampleCount) }

                                if (isInteractive || latestBatteryLevel != lastNotificationLevel || sampleCount % 10 == 0) {
                                    updateNotification(avgVoltage, avgCurrent, avgPower, latestBatteryLevel, isRecording = true)
                                    prefs.edit {
                                        putInt(
                                            PrefKeys.LAST_NOTIFICATION_BATTERY_LEVEL,
                                            latestBatteryLevel
                                        )
                                    }
                                }

                                updateBackgroundPowerStats(prefs, avgPower)
                            }
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
                                val power = BatteryFlow.signedPowerWatts(voltage, current)
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
                                val power = BatteryFlow.signedPowerWatts(voltage, current)
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
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val batteryStatusIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryStatus = batteryStatusIntent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val displayState = BatteryFlow.displayState(batteryStatus, current, batteryLevel)
        val stateTitle = when (displayState) {
            BatteryDisplayState.FULL -> getString(R.string.service_full, power)
            BatteryDisplayState.CHARGING -> getString(R.string.service_charging, power)
            BatteryDisplayState.DISCHARGING -> getString(R.string.service_discharging, power)
            BatteryDisplayState.IDLE -> getString(R.string.service_not_charging, power)
        }

        val recordStatus = if (isRecording) getString(R.string.service_status_recording) else getString(R.string.service_status_stopped)

        val limitText = if (batteryStatusIntent != null && displayState == BatteryDisplayState.CHARGING) {
            val maxCurrentMicro = batteryStatusIntent.getIntExtra("max_charging_current", -1)
            val maxVoltageMicro = batteryStatusIntent.getIntExtra("max_charging_voltage", -1)
            if (maxCurrentMicro > 0 && maxVoltageMicro > 0) {
                val maxCurrent = maxCurrentMicro / 1_000_000f
                val maxVoltage = maxVoltageMicro / 1_000_000f
                val maxPower = maxVoltage * maxCurrent
                getString(R.string.service_notif_limit, maxVoltage, maxCurrent, maxPower)
            } else ""
        } else ""
        val contentText = getString(R.string.service_notif_format, recordStatus, limitText, batteryLevel, voltage, current)

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
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        
        if (isRecording) {
            builder.addAction(0, getString(R.string.service_action_stop), stopPendingIntent)
        } else {
            builder.addAction(0, getString(R.string.service_action_start), startPendingIntent)
        }
        builder.addAction(0, getString(R.string.service_action_exit), exitPendingIntent)
        
        builder.extras.putBoolean("android.requestPromotedOngoing", true)

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { commandChannel.trySend(it) }
        return START_STICKY
    }
    override fun onDestroy() {
        super.onDestroy()
        loggingJob?.cancel()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        commandChannel.close()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun updateBackgroundPowerStats(prefs: android.content.SharedPreferences, currentPower: Float) {
        val appInBackground = prefs.getBoolean(PrefKeys.APP_IN_BACKGROUND, false)
        if (appInBackground && currentPower.isFinite() && currentPower != 0f) {
            prefs.edit {
                if (currentPower > 0) {
                    // Charging power range
                    val minP = prefs.getFloat(PrefKeys.BG_MIN_CHARGE_POWER, Float.MAX_VALUE)
                    val maxP = prefs.getFloat(PrefKeys.BG_MAX_CHARGE_POWER, -Float.MAX_VALUE)
                    val newMinP = if (currentPower < minP) currentPower else minP
                    val newMaxP = if (currentPower > maxP) currentPower else maxP
                    putFloat(PrefKeys.BG_MIN_CHARGE_POWER, newMinP)
                    putFloat(PrefKeys.BG_MAX_CHARGE_POWER, newMaxP)
                } else {
                    // Keep signed net power and convert any old unsigned range in place.
                    val storedMinP = prefs.getFloat(PrefKeys.BG_MIN_DISCHARGE_POWER, Float.MAX_VALUE)
                    val storedMaxP = prefs.getFloat(PrefKeys.BG_MAX_DISCHARGE_POWER, -Float.MAX_VALUE)
                    val minP = if (storedMinP >= 0f && storedMaxP >= 0f) -storedMaxP else storedMinP
                    val maxP = if (storedMinP >= 0f && storedMaxP >= 0f) -storedMinP else storedMaxP
                    val newMinP = if (currentPower < minP) currentPower else minP
                    val newMaxP = if (currentPower > maxP) currentPower else maxP
                    putFloat(PrefKeys.BG_MIN_DISCHARGE_POWER, newMinP)
                    putFloat(PrefKeys.BG_MAX_DISCHARGE_POWER, newMaxP)
                }

                putBoolean(PrefKeys.BG_STATS_RECORDED, true)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "ChargeLogServiceChannel"
        const val NOTIFICATION_ID = 1
        private const val SESSION_RESUME_WINDOW_MS = 5 * 60 * 1000L
        const val ACTION_START_RECORDING = "per.jau.chargelog.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "per.jau.chargelog.action.STOP_RECORDING"
        const val ACTION_EXIT_APP = "per.jau.chargelog.action.EXIT_APP"
    }
}
