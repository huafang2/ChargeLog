package per.jau.chargelog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import per.jau.chargelog.utils.BatteryUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import per.jau.chargelog.data.ChargeDatabase
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.service.ChargeLoggingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            writeCsvToUri(uri)
        }
    }

    private lateinit var lineChart: LineChart
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnHistory: Button
    private lateinit var btnClear: Button
    private lateinit var btnShowData: Button
    private lateinit var btnExit: Button
    private lateinit var layoutBgReportBanner: View
    private lateinit var tabLayout: TabLayout
    private lateinit var layoutInterval: View
    private lateinit var switchBgReport: androidx.appcompat.widget.SwitchCompat

    private lateinit var tvCurrentVoltage: TextView
    private lateinit var tvCurrentCurrent: TextView
    private lateinit var tvCurrentPower: TextView
    private lateinit var tvCurrentProtocol: TextView

    private var currentRecords: List<ChargeRecord> = emptyList()
    private var selectedTabIndex = 2
    private var observeJob: Job? = null
    private var liveTextUpdateJob: Job? = null
    
    // For adaptive text coloring
    private var textColorPrimary: Int = Color.BLACK

    private var chartBaseTime: Long = 0L

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "CURRENT_SESSION_START") {
            val historyStartTime = intent.getLongExtra("HISTORY_START_TIME", -1L)
            val historyEndTime = intent.getLongExtra("HISTORY_END_TIME", -1L)
            if (historyStartTime == -1L || historyEndTime == -1L) {
                observeData()
            }
        } else if (key == "IS_RECORDING") {
            val isRecording = isRecording()
            val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
            if (historySessionId == -1L) {
                tvStatus.text = if (isRecording) "正在记录充电数据..." else "充电日志已停止"
                updateButtonStates()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mainView = findViewById<View>(R.id.main)
        val pLeft = mainView.paddingLeft
        val pTop = mainView.paddingTop
        val pRight = mainView.paddingRight
        val pBottom = mainView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left + pLeft,
                systemBars.top + pTop,
                systemBars.right + pRight,
                systemBars.bottom + pBottom
            )
            insets
        }
        
        // Resolve theme text color
        val typedArray = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        textColorPrimary = typedArray.getColor(0, Color.BLACK)
        typedArray.recycle()

        val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("USER_EXITED", true)
            .apply()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        lineChart = findViewById(R.id.lineChart)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnHistory = findViewById(R.id.btnHistory)
        btnClear = findViewById(R.id.btnClear)
        btnShowData = findViewById(R.id.btnShowData)
        btnExit = findViewById(R.id.btnExit)
        layoutBgReportBanner = findViewById(R.id.layoutBgReportBanner)
        val btnBannerClose = findViewById<Button>(R.id.btnBannerClose)
        btnBannerClose.setOnClickListener {
            layoutBgReportBanner.visibility = View.GONE
        }
        tabLayout = findViewById(R.id.tabLayout)

        tvCurrentVoltage = findViewById(R.id.tvCurrentVoltage)
        tvCurrentCurrent = findViewById(R.id.tvCurrentCurrent)
        tvCurrentPower = findViewById(R.id.tvCurrentPower)
        tvCurrentProtocol = findViewById(R.id.tvCurrentProtocol)

        layoutInterval = findViewById(R.id.layoutInterval)
        switchBgReport = findViewById(R.id.switchBgReport)
        switchBgReport.isChecked = prefs.getBoolean("ENABLE_BG_REPORT", true)
        switchBgReport.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("ENABLE_BG_REPORT", isChecked).apply()
        }

        val tvIntervalLabel = findViewById<TextView>(R.id.tvIntervalLabel)
        val sbInterval = findViewById<android.widget.SeekBar>(R.id.sbInterval)
        val currentInterval = prefs.getInt("SAMPLING_INTERVAL_SECONDS", 5)
        sbInterval.progress = currentInterval - 1
        tvIntervalLabel.text = "采样间隔: ${currentInterval}秒"
        sbInterval.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val sec = progress + 1
                tvIntervalLabel.text = "采样间隔: ${sec}秒"
                prefs.edit().putInt("SAMPLING_INTERVAL_SECONDS", sec).apply()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        setupTabs()
        setupChart()
        checkPermissions()

        // Start foreground service immediately on launch to keep background monitoring active
        val serviceIntent = Intent(this, ChargeLoggingService::class.java)
        startForegroundService(serviceIntent)

        handleIntent(intent)

        btnStart.setOnClickListener {
            getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("IS_RECORDING", true)
                .apply()
            // Make sure service is running
            startForegroundService(Intent(this, ChargeLoggingService::class.java))
            tvStatus.text = "正在记录充电数据..."
            updateButtonStates()
        }

        btnStop.setOnClickListener {
            getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("IS_RECORDING", false)
                .putBoolean("FORCE_NEW_SESSION", true)
                .apply()
            tvStatus.text = "充电日志已停止"
            updateButtonStates()
        }

        btnExit.setOnClickListener {
            getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("IS_RECORDING", false)
                .putBoolean("FORCE_NEW_SESSION", true)
                .putBoolean("USER_EXITED", true)
                .apply()
            stopService(Intent(this, ChargeLoggingService::class.java))
            finishAffinity()
        }

        btnHistory.setOnClickListener {
            if (btnHistory.text.toString() == "返回历史") {
                finish()
            } else {
                val intent = Intent(this, HistoryActivity::class.java)
                startActivity(intent)
            }
        }

        btnClear.setOnClickListener {
            val isRecording = isRecording()
            val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
            val sessionStart = prefs.getLong("CURRENT_SESSION_START", 0L)
            if (isRecording) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("当前正在记录充电日志。清空屏幕将停止当前记录，且本次记录的数据将会被丢弃（不保存到历史记录）。确认继续吗？")
                    .setPositiveButton("确认清空") { _, _ ->
                        lifecycleScope.launch {
                            // 1. Stop recording
                            prefs.edit()
                                .putBoolean("IS_RECORDING", false)
                                .apply()
                            tvStatus.text = "充电日志已停止"
                            updateButtonStates()
                            
                            // 2. Discard current session data by deleting records matching sessionId
                            val dao = ChargeDatabase.getDatabase(this@MainActivity).chargeDao()
                            if (sessionStart > 0L) {
                                dao.deleteRecordsBySession(sessionStart)
                            }
                            
                            // 3. Reset CURRENT_SESSION_START to now
                            val now = System.currentTimeMillis()
                            prefs.edit()
                                .putLong("CURRENT_SESSION_START", now)
                                .putBoolean("FORCE_NEW_SESSION", true)
                                .apply()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                // Not recording: clear screen immediately without dialog, no database deletion
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putLong("CURRENT_SESSION_START", now)
                    .putBoolean("FORCE_NEW_SESSION", true)
                    .apply()
            }
        }

        btnShowData.setOnClickListener {
            if (currentRecords.isNotEmpty()) {
                val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_raw_data, null)
                val rvRawData = dialogView.findViewById<RecyclerView>(R.id.rvRawData)
                rvRawData.layoutManager = LinearLayoutManager(this)
                rvRawData.adapter = RawDataAdapter(currentRecords)
                
                val dialog = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .create()
                
                val tvRawSummary = dialogView.findViewById<TextView>(R.id.tvRawSummary)
                val chargePoints = currentRecords.filter { it.power >= 0 }
                val dischargePoints = currentRecords.filter { it.power < 0 }

                val summaryBuilder = StringBuilder()

                if (chargePoints.isNotEmpty()) {
                    val minCP = chargePoints.minOf { it.power }
                    val maxCP = chargePoints.maxOf { it.power }
                    val minCC = chargePoints.minOf { it.current }
                    val maxCC = chargePoints.maxOf { it.current }
                    summaryBuilder.append(String.format(
                        Locale.getDefault(),
                        "充电功率: 最小 %.2f W - 最大 %.2f W\n充电电流: 最小 %.2f A - 最大 %.2f A\n",
                        minCP, maxCP, minCC, maxCC
                    ))
                }

                if (dischargePoints.isNotEmpty()) {
                    val minDP = dischargePoints.minOf { abs(it.power) }
                    val maxDP = dischargePoints.maxOf { abs(it.power) }
                    val minDC = dischargePoints.minOf { abs(it.current) }
                    val maxDC = dischargePoints.maxOf { abs(it.current) }
                    summaryBuilder.append(String.format(
                        Locale.getDefault(),
                        "放电功率: 最小 %.2f W - 最大 %.2f W\n放电电流: 最小 %.2f A - 最大 %.2f A\n",
                        minDP, maxDP, minDC, maxDC
                    ))
                }

                val startBat = currentRecords.first().batteryLevel
                val endBat = currentRecords.last().batteryLevel
                val batChange = endBat - startBat
                val changeSign = if (batChange >= 0) "+$batChange%" else "$batChange%"
                summaryBuilder.append("电量变化: $startBat% -> $endBat% ($changeSign)")

                tvRawSummary.text = summaryBuilder.toString()
                tvRawSummary.visibility = View.VISIBLE

                dialogView.findViewById<View>(R.id.btnExportCSV).setOnClickListener {
                    dialog.dismiss()
                    val fileName = "ChargeLog_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
                    exportCsvLauncher.launch(fileName)
                }

                dialogView.findViewById<View>(R.id.btnCloseDialog).setOnClickListener {
                    dialog.dismiss()
                }
                dialog.show()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
        
        if (historySessionId != -1L) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            tvStatus.text = "查看历史记录"
            btnHistory.text = "返回历史"
            observeData(historySessionId)
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            tvStatus.text = if (isRecording()) "正在记录充电数据..." else "充电日志已停止"
            btnHistory.text = "历史记录"
            observeData()
        }
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
        if (historySessionId != -1L) {
            btnStart.visibility = View.GONE
            btnStop.visibility = View.GONE
            btnClear.visibility = View.GONE
            btnExit.visibility = View.GONE
            btnHistory.visibility = View.GONE
            layoutBgReportBanner.visibility = View.GONE
            layoutInterval.visibility = View.GONE
            switchBgReport.visibility = View.GONE
        } else {
            val recording = isRecording()
            if (recording) {
                btnStart.visibility = View.GONE
                btnStop.visibility = View.VISIBLE
            } else {
                btnStart.visibility = View.VISIBLE
                btnStop.visibility = View.GONE
            }
            btnClear.visibility = View.VISIBLE
            btnExit.visibility = View.VISIBLE
            btnHistory.visibility = View.VISIBLE
            layoutInterval.visibility = View.VISIBLE
            switchBgReport.visibility = View.VISIBLE
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (ChargeLoggingService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("电压"))
        tabLayout.addTab(tabLayout.newTab().setText("电流"))
        tabLayout.addTab(tabLayout.newTab().setText("功率"))
        tabLayout.addTab(tabLayout.newTab().setText("电量"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTabIndex = tab?.position ?: 0
                updateChartData()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Select the third tab (Power, index 2) by default
        tabLayout.getTabAt(2)?.select()
    }

    private fun setupChart() {
        lineChart.description.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.isDragEnabled = true
        lineChart.setScaleEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.isHighlightPerDragEnabled = true
        lineChart.isHighlightPerTapEnabled = true

        val xAxis = lineChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.textColor = textColorPrimary
        xAxis.valueFormatter = object : ValueFormatter() {
            private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            override fun getFormattedValue(value: Float): String {
                return format.format(Date(chartBaseTime + value.toLong()))
            }
        }
        
        lineChart.axisLeft.textColor = textColorPrimary
        lineChart.axisRight.isEnabled = false
        lineChart.legend.textColor = textColorPrimary

        lineChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null && currentRecords.isNotEmpty()) {
                    // Find the closest record
                    val targetTime = chartBaseTime + e.x.toLong()
                    val record = currentRecords.minByOrNull { abs(it.timestamp - targetTime) }
                    if (record != null) {
                        updateDashboardText(record, true)
                    }
                }
            }

            override fun onNothingSelected() {
                if (currentRecords.isNotEmpty()) {
                    updateDashboardText(currentRecords.last(), false)
                }
            }
        })
    }

    private fun observeData(sessionId: Long = -1L) {
        observeJob?.cancel() // Cancel any ongoing observation
        val dao = ChargeDatabase.getDatabase(this).chargeDao()
        
        observeJob = lifecycleScope.launch {
            val targetSessionId = if (sessionId != -1L) {
                sessionId
            } else {
                val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
                prefs.getLong("CURRENT_SESSION_START", System.currentTimeMillis())
            }
            val flow = dao.getRecordsBySession(targetSessionId)

            flow.collectLatest { records ->
                if (records.isEmpty()) {
                    val latest = dao.getLatestRecord()
                    if (latest != null) {
                        currentRecords = listOf(latest)
                        chartBaseTime = latest.timestamp
                        updateDashboardText(latest, false)
                        updateChartData()
                        btnShowData.visibility = View.VISIBLE
                    } else {
                        currentRecords = emptyList()
                        lineChart.clear()
                        tvCurrentVoltage.text = "电压: -- V"
                        tvCurrentCurrent.text = "电流: -- A"
                        tvCurrentPower.text = "功率: -- W"
                        tvCurrentProtocol.text = "电量: --"
                        btnShowData.visibility = View.GONE
                    }
                    return@collectLatest
                }
                currentRecords = records
                btnShowData.visibility = View.VISIBLE
                chartBaseTime = records.first().timestamp

                // If no point is selected, update dashboard with the latest record
                if (lineChart.highlighted?.isNotEmpty() == true) {
                    // Let the selection listener handle the text
                } else {
                    updateDashboardText(records.last(), false)
                }

                updateChartData()

                val firstTimestamp = records.first().timestamp
                val lastTimestamp = records.last().timestamp
                val duration = lastTimestamp - firstTimestamp
                val durationFloat = duration.toFloat()

                if (sessionId == -1L) {
                    // Live Mode: restrict to 15 minutes window
                    val fifteenMins = 15 * 60 * 1000f
                    lineChart.setVisibleXRangeMaximum(fifteenMins)
                    if (durationFloat > fifteenMins) {
                        lineChart.moveViewToX(durationFloat - fifteenMins)
                    } else {
                        lineChart.moveViewToX(0f)
                    }
                } else {
                    // History Mode: show entire process
                    lineChart.setVisibleXRangeMaximum(durationFloat + 1000f)
                    lineChart.fitScreen()
                }
            }
        }
    }

    private fun updateDashboardText(record: ChargeRecord, isHistorical: Boolean) {
        val prefix = if (isHistorical) "[选中] " else ""
        tvCurrentVoltage.text = String.format(Locale.getDefault(), "${prefix}电压: %.2f V", record.voltage)
        tvCurrentCurrent.text = String.format(Locale.getDefault(), "${prefix}电流: %.2f A", record.current)
        tvCurrentPower.text = String.format(Locale.getDefault(), "${prefix}功率: %.2f W", record.power)
        tvCurrentProtocol.text = String.format(Locale.getDefault(), "${prefix}电量: %d%%", record.batteryLevel)
    }

    private fun createLineDataSet(entries: List<Entry>, label: String, color: Int): LineDataSet {
        val dataSet = LineDataSet(entries, label)
        dataSet.color = color
        dataSet.setDrawCircles(false)
        dataSet.valueTextColor = textColorPrimary
        dataSet.isHighlightEnabled = true
        dataSet.setDrawHighlightIndicators(true)
        dataSet.setDrawVerticalHighlightIndicator(true)
        dataSet.setDrawHorizontalHighlightIndicator(false)
        dataSet.highLightColor = textColorPrimary // Highlight line color
        dataSet.highlightLineWidth = 1.5f
        return dataSet
    }

    private fun updateChartData() {
        if (currentRecords.isEmpty()) return

        val entries = ArrayList<Entry>()
        for (i in currentRecords.indices) {
            val record = currentRecords[i]
            val x = (record.timestamp - chartBaseTime).toFloat()
            val y = when (selectedTabIndex) {
                0 -> record.voltage
                1 -> record.current
                2 -> record.power
                else -> record.batteryLevel.toFloat()
            }
            entries.add(Entry(x, y))
        }

        val color = when (selectedTabIndex) {
            0 -> Color.RED
            1 -> Color.parseColor("#4488FF")
            2 -> Color.GREEN
            else -> Color.parseColor("#FF9800")
        }

        val label = when (selectedTabIndex) {
            0 -> "电压 (V)"
            1 -> "电流 (A)"
            2 -> "功率 (W)"
            else -> "电量 (%)"
        }

        val dataSet = createLineDataSet(entries, label, color)
        val lineData = LineData(dataSet)
        lineChart.data = lineData
        lineChart.notifyDataSetChanged()
        lineChart.invalidate()
    }
    override fun onResume() {
        super.onResume()
        startLiveTextUpdateLoop()

        // Check and report background stats when returning to the foreground
        val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
        val userExited = prefs.getBoolean("USER_EXITED", false)
        if (userExited) {
            prefs.edit()
                .putBoolean("USER_EXITED", false)
                .putBoolean("APP_IN_BACKGROUND", false)
                .putBoolean("BG_STATS_RECORDED", false)
                .apply()
            return
        }

        val enableBgReport = prefs.getBoolean("ENABLE_BG_REPORT", true)
        val appInBackground = prefs.getBoolean("APP_IN_BACKGROUND", false)
        val bgStatsRecorded = prefs.getBoolean("BG_STATS_RECORDED", false)
        
        if (enableBgReport && appInBackground && bgStatsRecorded) {
            // Reset flags immediately
            prefs.edit()
                .putBoolean("APP_IN_BACKGROUND", false)
                .putBoolean("BG_STATS_RECORDED", false)
                .apply()
            
            val startTime = prefs.getLong("BACKGROUND_START_TIME", 0L)
            val startBattery = prefs.getInt("BACKGROUND_START_BATTERY", -1)
            
            // Power ranges (charging & discharging)
            val minCP = prefs.getFloat("BG_MIN_CHARGE_POWER", Float.MAX_VALUE)
            val maxCP = prefs.getFloat("BG_MAX_CHARGE_POWER", -Float.MAX_VALUE)
            val minDP = prefs.getFloat("BG_MIN_DISCHARGE_POWER", Float.MAX_VALUE)
            val maxDP = prefs.getFloat("BG_MAX_DISCHARGE_POWER", -Float.MAX_VALUE)
            
            val endBattery = BatteryUtils.getBatteryLevel(this)
            val batteryChange = if (startBattery != -1) endBattery - startBattery else 0
            val durationMs = System.currentTimeMillis() - startTime
            val durationMins = durationMs / 60000
            val durationSecs = (durationMs % 60000) / 1000
            
            if (durationMs > 2000) {
                var dialogShown = false
                val showDialogOnce = {
                    if (!dialogShown) {
                        dialogShown = true
                        showBackgroundStatsDialog(
                            minCP, maxCP, minDP, maxDP,
                            startBattery, endBattery, batteryChange, durationMins, durationSecs
                        )
                    }
                }
                val btnBannerView = findViewById<Button>(R.id.btnBannerView)
                layoutBgReportBanner.visibility = View.VISIBLE
                btnBannerView.setOnClickListener {
                    layoutBgReportBanner.visibility = View.GONE
                    showDialogOnce()
                }
            }
        } else {
            // Just clear flags if not showing report
            prefs.edit()
                .putBoolean("APP_IN_BACKGROUND", false)
                .putBoolean("BG_STATS_RECORDED", false)
                .apply()
        }
    }

    override fun onStop() {
        super.onStop()
        // Save initial background state when activity goes to background (including screen lock)
        val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
        val currentBattery = BatteryUtils.getBatteryLevel(this)
        prefs.edit()
            .putBoolean("APP_IN_BACKGROUND", true)
            .putBoolean("BG_STATS_RECORDED", false)
            .putLong("BACKGROUND_START_TIME", System.currentTimeMillis())
            .putInt("BACKGROUND_START_BATTERY", currentBattery)
            // Power
            .putFloat("BG_MIN_CHARGE_POWER", Float.MAX_VALUE)
            .putFloat("BG_MAX_CHARGE_POWER", -Float.MAX_VALUE)
            .putFloat("BG_MIN_DISCHARGE_POWER", Float.MAX_VALUE)
            .putFloat("BG_MAX_DISCHARGE_POWER", -Float.MAX_VALUE)
            .apply()
    }

    private fun showBackgroundStatsDialog(
        minCP: Float, maxCP: Float, minDP: Float, maxDP: Float,
        startBattery: Int,
        endBattery: Int,
        batteryChange: Int,
        durationMins: Long,
        durationSecs: Long
    ) {
        val message = StringBuilder().apply {
            append("后台运行时间: ")
            if (durationMins > 0) {
                append("${durationMins}分")
            }
            append("${durationSecs}秒\n\n")
            
            append("电池电量变化:\n")
            append("进入后台时: $startBattery%\n")
            append("返回前台时: $endBattery%\n")
            val changeSign = if (batteryChange >= 0) "+$batteryChange%" else "$batteryChange%"
            append("电量变化: $changeSign\n\n")
            
            append("后台期间功率范围:\n")
            var hasStats = false
            if (minCP != Float.MAX_VALUE && maxCP != -Float.MAX_VALUE) {
                append(String.format(Locale.getDefault(), "充电功率: 最小 %.2f W - 最大 %.2f W\n", minCP, maxCP))
                hasStats = true
            }
            if (minDP != Float.MAX_VALUE && maxDP != -Float.MAX_VALUE) {
                if (hasStats) append("\n")
                append(String.format(Locale.getDefault(), "放电功率: 最小 %.2f W - 最大 %.2f W\n", minDP, maxDP))
                hasStats = true
            }
            if (!hasStats) {
                append("无功率记录")
            }
        }.toString()

        AlertDialog.Builder(this)
            .setTitle("后台运行统计报告")
            .setMessage(message)
            .setPositiveButton("我知道了", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        liveTextUpdateJob?.cancel()
    }

    private fun isRecording(): Boolean {
        val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("IS_RECORDING", false)
    }

    private fun startLiveTextUpdateLoop() {
        liveTextUpdateJob?.cancel()
        liveTextUpdateJob = lifecycleScope.launch {
            while (true) {
                val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
                if (historySessionId == -1L && !isRecording()) {
                    // Update only if no active chart selection
                    if (lineChart.highlighted.isNullOrEmpty()) {
                        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        val batteryStatus = registerReceiver(null, filter)
                        if (batteryStatus != null) {
                            val voltage = BatteryUtils.getVoltage(batteryStatus)
                            val current = BatteryUtils.getCurrent(this@MainActivity)
                            val power = voltage * current
                            val batteryLevel = BatteryUtils.getBatteryLevel(this@MainActivity)
                            
                            tvCurrentVoltage.text = String.format(Locale.getDefault(), "电压: %.2f V", voltage)
                            tvCurrentCurrent.text = String.format(Locale.getDefault(), "电流: %.2f A", current)
                            tvCurrentPower.text = String.format(Locale.getDefault(), "功率: %.2f W", power)
                            tvCurrentProtocol.text = String.format(Locale.getDefault(), "电量: %d%%", batteryLevel)
                        }
                    }
                }
                kotlinx.coroutines.delay(1000L)
            }
        }
    }

    private fun writeCsvToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.bufferedWriter().use { writer ->
                        // CSV header
                        writer.write("时间,电压(V),电流(A),功率(W),电量(%)\n")
                        
                        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        for (record in currentRecords) {
                            val timeStr = format.format(Date(record.timestamp))
                            writer.write(String.format(
                                Locale.getDefault(),
                                "%s,%.2f,%.2f,%.2f,%d\n",
                                timeStr, record.voltage, record.current, record.power, record.batteryLevel
                            ))
                        }
                    }
                }
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, "CSV 导出成功", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, "导出失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
            if (historySessionId != -1L) {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (isFinishing) {
            prefs.edit().putBoolean("USER_EXITED", true).apply()
        }
        super.onDestroy()
    }
}

class RawDataAdapter(private val records: List<ChargeRecord>) : RecyclerView.Adapter<RawDataAdapter.ViewHolder>() {
    private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_raw_data_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        holder.tvRawTime.text = format.format(Date(record.timestamp))
        holder.tvRawVoltage.text = String.format(Locale.getDefault(), "%.2f V", record.voltage)
        holder.tvRawCurrent.text = String.format(Locale.getDefault(), "%.2f A", record.current)
        holder.tvRawPower.text = String.format(Locale.getDefault(), "%.2f W", record.power)
        holder.tvRawBattery.text = "${record.batteryLevel}%"
    }

    override fun getItemCount() = records.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRawTime: TextView = view.findViewById(R.id.tvRawTime)
        val tvRawVoltage: TextView = view.findViewById(R.id.tvRawVoltage)
        val tvRawCurrent: TextView = view.findViewById(R.id.tvRawCurrent)
        val tvRawPower: TextView = view.findViewById(R.id.tvRawPower)
        val tvRawBattery: TextView = view.findViewById(R.id.tvRawBattery)
    }
}
