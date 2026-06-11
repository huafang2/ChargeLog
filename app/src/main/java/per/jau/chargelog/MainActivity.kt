package per.jau.chargelog

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import per.jau.chargelog.data.ChargeDatabase
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.service.ChargeLoggingService
import per.jau.chargelog.utils.BatteryUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            writeCsvToUri(uri)
        }
    }

    private lateinit var lineChart: CustomLineChart
    private lateinit var sbChartScrubber: android.widget.SeekBar
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
    private lateinit var tvCurrentMaxPower: TextView

    private var currentRecords: List<ChargeRecord> = emptyList()
    private var selectedTabIndex = 2
    private var observeJob: Job? = null
    private var liveTextUpdateJob: Job? = null
    private var lastHighlightedX: Float? = null
    
    // For adaptive text coloring
    private var textColorPrimary: Int = Color.BLACK
    private var menuDeleteSegment: android.view.MenuItem? = null
    private var colorRealtime: Int = Color.GREEN
    private var colorSelected: Int = Color.RED
    private var colorSummary: Int = Color.BLUE

    private var chartBaseTime: Long = 0L

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "CURRENT_SESSION_START") {
            val historyStartTime = intent.getLongExtra("HISTORY_START_TIME", -1L)
            val historyEndTime = intent.getLongExtra("HISTORY_END_TIME", -1L)
            if (historyStartTime == -1L || historyEndTime == -1L) {
                observeData()
            }
        } else if (key == "IS_RECORDING") {
            val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
            if (historySessionId == -1L) {
                if (::lineChart.isInitialized) {
                    val highlight = lineChart.highlighted?.firstOrNull()
                    val record = highlight?.let { currentRecords.getOrNull(it.x.toInt()) }
                    updateStatusTitle(highlight != null, record)
                } else {
                    tvStatus.text = if (isRecording()) "正在记录充电数据..." else "充电日志已停止"
                }
                updateButtonStates()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState != null) {
            selectedTabIndex = savedInstanceState.getInt("SELECTED_TAB_INDEX", 2)
        }

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
        withStyledAttributes(attrs = intArrayOf(android.R.attr.textColorPrimary)) {
            textColorPrimary = getColor(0, Color.BLACK)
        }
        colorRealtime = ContextCompat.getColor(this, R.color.dashboard_value_realtime)
        colorSelected = ContextCompat.getColor(this, R.color.dashboard_value_selected)
        colorSummary = ContextCompat.getColor(this, R.color.dashboard_value_summary)

        val prefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
        prefs.edit {
            putBoolean("USER_EXITED", true)
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        lineChart = findViewById(R.id.lineChart)
        sbChartScrubber = findViewById(R.id.sbChartScrubber)
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
        tvCurrentMaxPower = findViewById(R.id.tvCurrentMaxPower)

        val tvFooter = findViewById<TextView>(R.id.tvFooter)
        tvFooter.text = "Created by Jau v${BuildConfig.VERSION_NAME}"
        tvFooter.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.github_dialog_title))
                .setMessage("https://github.com/huafang2/ChargeLog")
                .setPositiveButton(getString(R.string.github_dialog_open)) { _, _ ->
                    try {
                        val webIntent = Intent(Intent.ACTION_VIEW,
                            "https://github.com/huafang2/ChargeLog".toUri())
                        startActivity(webIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                .setNegativeButton(getString(R.string.github_dialog_reset), null)
                .show()
        }

        layoutInterval = findViewById(R.id.layoutInterval)
        switchBgReport = findViewById(R.id.switchBgReport)
        switchBgReport.isChecked = prefs.getBoolean("ENABLE_BG_REPORT", true)
        switchBgReport.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("ENABLE_BG_REPORT", isChecked) }
        }


        val tvIntervalLabel = findViewById<TextView>(R.id.tvIntervalLabel)
        val sbInterval = findViewById<android.widget.SeekBar>(R.id.sbInterval)
        val currentInterval = prefs.getInt("SAMPLING_INTERVAL_SECONDS", 5)
        sbInterval.progress = currentInterval - 1
        tvIntervalLabel.text = getString(R.string.sampling_interval, currentInterval)
        sbInterval.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val sec = progress + 1
                tvIntervalLabel.text = getString(R.string.sampling_interval, sec)
                prefs.edit { putInt("SAMPLING_INTERVAL_SECONDS", sec) }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        setupTabs()
        setupChart()
        setupScrubber()
        checkPermissions()

        // Start foreground service immediately on launch to keep background monitoring active
        val serviceIntent = Intent(this, ChargeLoggingService::class.java)
        startForegroundService(serviceIntent)

        handleIntent(intent)

        btnStart.setOnClickListener {
            getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
                .edit {
                    putBoolean("IS_RECORDING", true)
                }
            // Make sure service is running
            startForegroundService(Intent(this, ChargeLoggingService::class.java))
            tvStatus.text = getString(R.string.recording)
            updateButtonStates()
        }

        btnStop.setOnClickListener {
            getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
                .edit {
                    putBoolean("IS_RECORDING", false)
                        .putBoolean("FORCE_NEW_SESSION", true)
                }
            tvStatus.text = getString(R.string.stopped)
            updateButtonStates()
        }

        btnExit.setOnClickListener {
            getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
                .edit {
                    putBoolean("IS_RECORDING", false)
                        .putBoolean("FORCE_NEW_SESSION", true)
                        .putBoolean("USER_EXITED", true)
                }
            stopService(Intent(this, ChargeLoggingService::class.java))
            finishAffinity()
        }

        btnHistory.setOnClickListener {
            val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
            if (historySessionId != -1L) {
                finish()
            } else {
                val nextIntent = Intent(this, HistoryActivity::class.java)
                startActivity(nextIntent)
            }
        }

        btnClear.setOnClickListener {
            val isRecording = isRecording()
            val prefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
            val sessionStart = prefs.getLong("CURRENT_SESSION_START", 0L)
            if (isRecording) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_tip_title)
                    .setMessage(R.string.dialog_clear_confirm_msg)
                    .setPositiveButton(R.string.confirm_clear) { _, _ ->
                        lifecycleScope.launch {
                            // 1. Stop recording
                            prefs.edit {
                                putBoolean("IS_RECORDING", false)
                            }
                            tvStatus.text = getString(R.string.stopped)
                            updateButtonStates()
                            
                            // 2. Discard current session data by deleting records matching sessionId
                            val dao = ChargeDatabase.getDatabase(this@MainActivity).chargeDao()
                            if (sessionStart > 0L) {
                                dao.deleteRecordsBySession(sessionStart)
                            }
                            
                            // 3. Reset CURRENT_SESSION_START to now
                            val now = System.currentTimeMillis()
                            prefs.edit {
                                putLong("CURRENT_SESSION_START", now)
                                putBoolean("FORCE_NEW_SESSION", true)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                // Not recording: clear screen immediately without dialog, no database deletion
                val now = System.currentTimeMillis()
                prefs.edit {
                    putLong("CURRENT_SESSION_START", now)
                    putBoolean("FORCE_NEW_SESSION", true)
                }
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
                    summaryBuilder.append(getString(R.string.charge_power_summary, minCP, maxCP))
                    summaryBuilder.append(getString(R.string.charge_current_summary, minCC, maxCC))
                }

                if (dischargePoints.isNotEmpty()) {
                    val minDP = dischargePoints.minOf { abs(it.power) }
                    val maxDP = dischargePoints.maxOf { abs(it.power) }
                    val minDC = dischargePoints.minOf { abs(it.current) }
                    val maxDC = dischargePoints.maxOf { abs(it.current) }
                    summaryBuilder.append(getString(R.string.discharge_power_summary, minDP, maxDP))
                    summaryBuilder.append(getString(R.string.discharge_current_summary, minDC, maxDC))
                }

                val startBat = currentRecords.first().batteryLevel
                val endBat = currentRecords.last().batteryLevel
                val batChange = endBat - startBat
                val changeSign = if (batChange >= 0) "+$batChange%" else "$batChange%"
                summaryBuilder.append(getString(R.string.battery_change, startBat, endBat, changeSign))

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
            tvStatus.text = getString(R.string.viewing_history)
            btnHistory.text = getString(R.string.back_to_history)
            observeData(historySessionId)
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            tvStatus.text = if (isRecording()) getString(R.string.recording) else getString(R.string.stopped)
            btnHistory.text = getString(R.string.history)
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

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_voltage))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_current))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_power))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_battery))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTabIndex = tab?.position ?: 0
                updateChartData()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Select the restored tab index (Power index 2 by default)
        tabLayout.getTabAt(selectedTabIndex)?.select()
    }

    private fun setupChart() {
        lineChart.description.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.isDragEnabled = true
        lineChart.setScaleEnabled(true)
        lineChart.setPinchZoom(true)
        // Disable highlight per drag to make scrolling smoother, 
        // and use the scrubber for precise selection
        lineChart.isHighlightPerDragEnabled = false 
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
                    if (lastHighlightedX != null && lastHighlightedX == e.x && !isDraggingScrubber && !lineChart.isDraggingVerticalLine) {
                        // User tapped the already highlighted point: deselect
                        lastHighlightedX = null
                        lineChart.post {
                            lineChart.highlightValue(null, true)
                        }
                        menuDeleteSegment?.isVisible = false
                        return
                    }
                    
                    lastHighlightedX = e.x

                    // Find the closest record
                    val targetTime = chartBaseTime + e.x.toLong()
                    val index = currentRecords.indices.minByOrNull { abs(currentRecords[it].timestamp - targetTime) } ?: -1
                    if (index != -1) {
                        val record = currentRecords[index]
                        updateDashboardText(record, true)
                        menuDeleteSegment?.isVisible = true
                        // Sync scrubber if not dragging it
                        if (!isDraggingScrubber) {
                            sbChartScrubber.progress = index
                        }
                    }
                }
            }

            override fun onNothingSelected() {
                lastHighlightedX = null
                menuDeleteSegment?.isVisible = false
                if (currentRecords.isNotEmpty()) {
                    val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
                    if (historySessionId != -1L) {
                        updateDashboardWithExtremeValues()
                    } else {
                        updateDashboardText(currentRecords.last(), false)
                    }
                }
            }
        })

        // Tap vertical line to deselect
        lineChart.onChartGestureListener = object : com.github.mikephil.charting.listener.OnChartGestureListener {
            override fun onChartGestureStart(me: android.view.MotionEvent?, lastGesture: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: android.view.MotionEvent?, lastGesture: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?) {}
            override fun onChartLongPressed(me: android.view.MotionEvent?) {}
            override fun onChartDoubleTapped(me: android.view.MotionEvent?) {}
            
            override fun onChartSingleTapped(me: android.view.MotionEvent?) {
                if (me == null) return
                val xVal = lastHighlightedX ?: return
                val data = lineChart.data ?: return
                // Find the first dataset that is not empty
                val dataSet = data.dataSets.firstOrNull { it.entryCount > 0 } ?: return
                val trans = lineChart.getTransformer(dataSet.axisDependency)
                val pts = floatArrayOf(xVal, 0f)
                trans.pointValuesToPixel(pts)
                val pixelX = pts[0]
                
                val density = resources.displayMetrics.density
                val tolerance = 25f * density // 25 dp
                
                if (abs(me.x - pixelX) < tolerance) {
                    lineChart.post {
                        lineChart.highlightValue(null, true)
                    }
                }
            }
            
            override fun onChartFling(me1: android.view.MotionEvent?, me2: android.view.MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: android.view.MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(me: android.view.MotionEvent?, dX: Float, dY: Float) {}
        }
    }

    private var isDraggingScrubber = false
    private fun setupScrubber() {
        sbChartScrubber.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress >= 0 && progress < currentRecords.size) {
                    val record = currentRecords[progress]
                    val x = (record.timestamp - chartBaseTime).toFloat()
                    
                    // Highlight the point
                    val highlight = Highlight(x, 0, 0) // dataSetIndex 0
                    lineChart.highlightValue(highlight, true)
                    
                    // Center the chart on the highlighted point if zoomed
                    lineChart.centerViewToAnimated(x, lineChart.centerOfView.y, lineChart.data.getDataSetByIndex(0).axisDependency, 100)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isDraggingScrubber = true
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isDraggingScrubber = false
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
                val prefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
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
                        resetDashboardTextSize()
                        tvCurrentVoltage.text = "电压: -- V"
                        tvCurrentCurrent.text = "电流: -- A"
                        tvCurrentPower.text = "功率: -- W"
                        tvCurrentProtocol.text = "电量: --"
                        btnShowData.visibility = View.GONE
                        menuDeleteSegment?.isVisible = false
                    }
                    return@collectLatest
                }
                currentRecords = records
                btnShowData.visibility = View.VISIBLE
                chartBaseTime = records.first().timestamp

                // Update scrubber range
                if (records.size > 1) {
                    sbChartScrubber.max = records.size - 1
                    sbChartScrubber.visibility = View.VISIBLE
                } else {
                    sbChartScrubber.visibility = View.GONE
                }

                // If no point is selected, update dashboard with the latest record or extreme values
                if (lineChart.highlighted?.isNotEmpty() == true) {
                    // Let the selection listener handle the text
                } else {
                    if (sessionId != -1L) {
                        updateDashboardWithExtremeValues()
                    } else {
                        updateDashboardText(records.last(), false)
                    }
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
                    
                    // Only auto-scroll to end if not currently scrubbing or selecting a point
                    if (!isDraggingScrubber && lineChart.highlighted.isNullOrEmpty()) {
                        if (durationFloat > fifteenMins) {
                            this@MainActivity.lineChart.moveViewToX(/* xValue = */ durationFloat - fifteenMins)
                        } else {
                            lineChart.moveViewToX(/* xValue = */ 0f)
                        }
                    }
                } else {
                    // History Mode: show entire process
                    lineChart.setVisibleXRangeMaximum(durationFloat + 1000f)
                    lineChart.fitScreen()
                }
            }
        }
    }

    private fun resetDashboardTextSize() {
        tvCurrentVoltage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        tvCurrentCurrent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        tvCurrentPower.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        tvCurrentProtocol.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    private fun updateStatusTitle(isSelected: Boolean, record: ChargeRecord? = null) {
        val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
        val baseTitle = if (historySessionId != -1L) {
            getString(R.string.viewing_history)
        } else {
            if (isRecording()) getString(R.string.recording) else getString(R.string.stopped)
        }
        
        if (isSelected && record != null) {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
            val selectedTitle = if (historySessionId != -1L) {
                getString(R.string.title_viewing_history_selected, timeStr)
            } else {
                if (isRecording()) {
                    getString(R.string.title_recording_selected, timeStr)
                } else {
                    getString(R.string.title_stopped_selected, timeStr)
                }
            }
            val spannable = android.text.SpannableStringBuilder(selectedTitle)
            spannable.append("  ") // Two spaces: one for spacing, one as target for the ImageSpan
            val start = selectedTitle.length + 1
            
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_selected_pin)
            if (drawable != null) {
                val sizePx = (22 * resources.displayMetrics.density).toInt()
                drawable.setBounds(0, 0, sizePx, sizePx)
                val imageSpan = android.text.style.ImageSpan(drawable, android.text.style.ImageSpan.ALIGN_CENTER)
                spannable.setSpan(
                    imageSpan,
                    start,
                    start + 1,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            tvStatus.text = spannable
        } else {
            tvStatus.text = baseTitle
        }
    }

    private fun formatValueText(label: String, value: String, unit: String, color: Int): android.text.SpannableString {
        val fullText = "$label$value $unit"
        val spannable = android.text.SpannableString(fullText)
        val start = label.length
        val end = fullText.length
        
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(color),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    private fun updateMaxChargingLimitText(batteryStatus: Intent?) {
        if (batteryStatus != null) {
            val maxCurrentMicro = batteryStatus.getIntExtra("max_charging_current", -1)
            val maxVoltageMicro = batteryStatus.getIntExtra("max_charging_voltage", -1)
            
            if (maxCurrentMicro > 0 && maxVoltageMicro > 0) {
                val maxCurrent = maxCurrentMicro / 1_000_000f
                val maxVoltage = maxVoltageMicro / 1_000_000f
                val maxPower = maxVoltage * maxCurrent
                
                val valStr = getString(R.string.max_power_format, maxVoltage, maxCurrent, maxPower)
                tvCurrentMaxPower.text = formatValueText(getString(R.string.max_power_label), valStr, "", colorSummary)
                tvCurrentMaxPower.visibility = View.VISIBLE
            } else {
                tvCurrentMaxPower.visibility = View.GONE
            }
        } else {
            tvCurrentMaxPower.visibility = View.GONE
        }
    }

    private fun updateDashboardText(record: ChargeRecord, isHistorical: Boolean) {
        resetDashboardTextSize()
        updateStatusTitle(isHistorical, record)
        
        val activeColor = if (isHistorical) colorSelected else colorRealtime
        
        val valV = String.format(Locale.getDefault(), "%.2f", record.voltage)
        val valC = String.format(Locale.getDefault(), "%.2f", record.current)
        val valP = String.format(Locale.getDefault(), "%.2f", record.power)
        val valB = String.format(Locale.getDefault(), "%d", record.batteryLevel)
        
        tvCurrentVoltage.text = formatValueText(getString(R.string.voltage_label), valV, "V", activeColor)
        tvCurrentCurrent.text = formatValueText(getString(R.string.current_label), valC, "A", activeColor)
        tvCurrentPower.text = formatValueText(getString(R.string.power_label), valP, "W", activeColor)
        tvCurrentProtocol.text = formatValueText(getString(R.string.battery_label), valB, "%", activeColor)
        
        if (isHistorical) {
            if (record.maxVoltage != null && record.maxCurrent != null && record.maxVoltage > 0 && record.maxCurrent > 0) {
                val maxPower = record.maxVoltage * record.maxCurrent
                val valStr = getString(R.string.max_power_format, record.maxVoltage, record.maxCurrent, maxPower)
                tvCurrentMaxPower.text = formatValueText(getString(R.string.max_power_label), valStr, "", colorSummary)
                tvCurrentMaxPower.visibility = View.VISIBLE
            } else {
                tvCurrentMaxPower.visibility = View.GONE
            }
        } else {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, filter)
            updateMaxChargingLimitText(batteryStatus)
        }
    }

    private fun updateDashboardWithExtremeValues() {
        if (currentRecords.isEmpty()) return
        
        val voltages = currentRecords.map { it.voltage }
        val currents = currentRecords.map { it.current }
        val powers = currentRecords.map { it.power }
        val batteryLevels = currentRecords.map { it.batteryLevel }
        
        val minV = voltages.minOrNull() ?: 0f
        val maxV = voltages.maxOrNull() ?: 0f
        
        val minC = currents.minOrNull() ?: 0f
        val maxC = currents.maxOrNull() ?: 0f
        
        val minP = powers.minOrNull() ?: 0f
        val maxP = powers.maxOrNull() ?: 0f
        
        val startBat = batteryLevels.firstOrNull() ?: 0
        val endBat = batteryLevels.lastOrNull() ?: 0
        val batChange = endBat - startBat
        val changeSign = if (batChange >= 0) "+$batChange%" else "$batChange%"
        
        resetDashboardTextSize()
        updateStatusTitle(false)

        val valV = String.format(Locale.getDefault(), "%.2f ~ %.2f V", minV, maxV)
        val valC = String.format(Locale.getDefault(), "%.2f ~ %.2f A", minC, maxC)
        val valP = String.format(Locale.getDefault(), "%.2f ~ %.2f W", minP, maxP)
        val valB = "$startBat% -> $endBat% ($changeSign)"

        tvCurrentVoltage.text = formatExtremeValueText(getString(R.string.voltage_label), valV)
        tvCurrentCurrent.text = formatExtremeValueText(getString(R.string.current_label), valC)
        tvCurrentPower.text = formatExtremeValueText(getString(R.string.power_label), valP)
        tvCurrentProtocol.text = formatExtremeValueText(getString(R.string.battery_label), valB)
        tvCurrentMaxPower.visibility = View.GONE
    }

    private fun formatExtremeValueText(label: String, value: String): android.text.SpannableString {
        val fullText = "$label$value"
        val spannable = android.text.SpannableString(fullText)
        val density = resources.displayMetrics.density
        val valuePx = (14f * density).toInt()
        val start = label.length
        val end = fullText.length
        
        spannable.setSpan(
            android.text.style.AbsoluteSizeSpan(valuePx),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(colorSummary),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
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

        val dataSets = ArrayList<LineDataSet>()
        var currentSegment = ArrayList<Entry>()
        var isSegmentDischarging: Boolean? = null

        for (i in currentRecords.indices) {
            val record = currentRecords[i]
            val x = (record.timestamp - chartBaseTime).toFloat()
            val y = when (selectedTabIndex) {
                0 -> record.voltage
                1 -> record.current
                2 -> record.power
                else -> record.batteryLevel.toFloat()
            }
            val isDischarging = record.current < 0

            when (isSegmentDischarging) {
                null -> {
                    isSegmentDischarging = isDischarging
                    currentSegment.add(Entry(x, y, record))
                }
                isDischarging -> {
                    currentSegment.add(Entry(x, y, record))
                }
                else -> {
                    // Bridge point to make lines continuous
                    currentSegment.add(Entry(x, y, record))

                    val color = when (selectedTabIndex) {
                        0 -> Color.RED
                        1 -> "#4488FF".toColorInt()
                        2 -> if (isSegmentDischarging) "#4488FF".toColorInt() else Color.GREEN
                        else -> "#FF9800".toColorInt()
                    }

                    val label = if (dataSets.isEmpty()) {
                        when (selectedTabIndex) {
                            0 -> getString(R.string.chart_voltage)
                            1 -> getString(R.string.chart_current)
                            2 -> getString(R.string.chart_power)
                            else -> getString(R.string.chart_battery)
                        }
                    } else {
                        ""
                    }

                    val dataSet = createLineDataSet(currentSegment, label, color)
                    if (dataSets.isNotEmpty()) {
                        dataSet.form = Legend.LegendForm.NONE
                    }
                    dataSets.add(dataSet)

                    // Start new segment
                    currentSegment = ArrayList()
                    currentSegment.add(Entry(x, y, record))
                    isSegmentDischarging = isDischarging
                }
            }
        }

        // Add the last segment
        if (currentSegment.isNotEmpty() && isSegmentDischarging != null) {
            val color = when (selectedTabIndex) {
                0 -> Color.RED
                1 -> "#4488FF".toColorInt()
                2 -> if (isSegmentDischarging) "#4488FF".toColorInt() else Color.GREEN
                else -> "#FF9800".toColorInt()
            }
            
            val label = if (dataSets.isEmpty()) {
                when (selectedTabIndex) {
                    0 -> getString(R.string.chart_voltage)
                    1 -> getString(R.string.chart_current)
                    2 -> getString(R.string.chart_power)
                    else -> getString(R.string.chart_battery)
                }
            } else {
                ""
            }

            val dataSet = createLineDataSet(currentSegment, label, color)
            if (dataSets.isNotEmpty()) {
                dataSet.form = Legend.LegendForm.NONE
            }
            dataSets.add(dataSet)
        }

        val lineData = LineData(dataSets.map { it })
        lineChart.data = lineData
        lineChart.notifyDataSetChanged()
        lineChart.invalidate()
    }
    override fun onResume() {
        super.onResume()
        startLiveTextUpdateLoop()

        // Check and report background stats when returning to the foreground
        val prefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
        val userExited = prefs.getBoolean("USER_EXITED", false)
        if (userExited) {
            prefs.edit {
                putBoolean("USER_EXITED", false)
                    .putBoolean("APP_IN_BACKGROUND", false)
                    .putBoolean("BG_STATS_RECORDED", false)
            }
            return
        }

        val enableBgReport = prefs.getBoolean("ENABLE_BG_REPORT", true)
        val appInBackground = prefs.getBoolean("APP_IN_BACKGROUND", false)
        val bgStatsRecorded = prefs.getBoolean("BG_STATS_RECORDED", false)
        
        if (enableBgReport && appInBackground && bgStatsRecorded) {
            // Reset flags immediately
            prefs.edit {
                putBoolean("APP_IN_BACKGROUND", false)
                    .putBoolean("BG_STATS_RECORDED", false)
            }
            
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
            prefs.edit {
                putBoolean("APP_IN_BACKGROUND", false)
                    .putBoolean("BG_STATS_RECORDED", false)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Save initial background state when activity goes to background (including screen lock)
        val prefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
        val currentBattery = BatteryUtils.getBatteryLevel(this)
        prefs.edit {
            putBoolean("APP_IN_BACKGROUND", true)
                .putBoolean("BG_STATS_RECORDED", false)
                .putLong("BACKGROUND_START_TIME", System.currentTimeMillis())
                .putInt("BACKGROUND_START_BATTERY", currentBattery)
                // Power
                .putFloat("BG_MIN_CHARGE_POWER", Float.MAX_VALUE)
                .putFloat("BG_MAX_CHARGE_POWER", -Float.MAX_VALUE)
                .putFloat("BG_MIN_DISCHARGE_POWER", Float.MAX_VALUE)
                .putFloat("BG_MAX_DISCHARGE_POWER", -Float.MAX_VALUE)
        }
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
            append(getString(R.string.bg_stats_duration))
            if (durationMins > 0) {
                append(getString(R.string.bg_stats_mins_secs, durationMins, durationSecs))
            } else {
                append(getString(R.string.bg_stats_secs, durationSecs))
            }
            
            val changeSign = if (batteryChange >= 0) "+$batteryChange%" else "$batteryChange%"
            append(getString(R.string.bg_stats_battery_change, startBattery, endBattery, changeSign))
            
            append(getString(R.string.bg_stats_power_range))
            var hasStats = false
            if (minCP != Float.MAX_VALUE && maxCP != -Float.MAX_VALUE) {
                append(getString(R.string.bg_stats_charge_power, minCP, maxCP))
                hasStats = true
            }
            if (minDP != Float.MAX_VALUE && maxDP != -Float.MAX_VALUE) {
                if (hasStats) append("\n")
                append(getString(R.string.bg_stats_discharge_power, minDP, maxDP))
                hasStats = true
            }
            if (!hasStats) {
                append(getString(R.string.bg_stats_no_power))
            }
        }.toString()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bg_stats_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.confirm), null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        liveTextUpdateJob?.cancel()
    }

    private fun isRecording(): Boolean {
        val prefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
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
                            
                            resetDashboardTextSize()
                            val valV = String.format(Locale.getDefault(), "%.2f", voltage)
                            val valC = String.format(Locale.getDefault(), "%.2f", current)
                            val valP = String.format(Locale.getDefault(), "%.2f", power)
                            val valB = String.format(Locale.getDefault(), "%d", batteryLevel)
                            
                            tvCurrentVoltage.text = formatValueText(getString(R.string.voltage_label), valV, "V", colorRealtime)
                            tvCurrentCurrent.text = formatValueText(getString(R.string.current_label), valC, "A", colorRealtime)
                            tvCurrentPower.text = formatValueText(getString(R.string.power_label), valP, "W", colorRealtime)
                            tvCurrentProtocol.text = formatValueText(getString(R.string.battery_label), valB, "%", colorRealtime)
                            updateMaxChargingLimitText(batteryStatus)
                        }
                    }
                }
                kotlinx.coroutines.delay(1000L.milliseconds)
            }
        }
    }

    private fun writeCsvToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.bufferedWriter().use { writer ->
                        // CSV header
                        writer.write("时间,电压(V),电流(A),功率(W),电量(%),快充上限,屏幕状态\n")
                        
                        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        for (record in currentRecords) {
                            val timeStr = format.format(Date(record.timestamp))
                            val screenStateStr = when (record.screenState) {
                                0 -> "锁屏"
                                1 -> "亮屏"
                                else -> "未知"
                            }
                            val limitStr = if (record.maxVoltage != null && record.maxCurrent != null && record.maxVoltage > 0 && record.maxCurrent > 0) {
                                val maxPower = record.maxVoltage * record.maxCurrent
                                val vStr = if (record.maxVoltage % 1 == 0f) String.format(Locale.US, "%.0f", record.maxVoltage) else String.format(Locale.US, "%.1f", record.maxVoltage)
                                val cStr = if (record.maxCurrent % 1 == 0f) String.format(Locale.US, "%.0f", record.maxCurrent) else String.format(Locale.US, "%.1f", record.maxCurrent)
                                val pStr = if (maxPower % 1 == 0f) String.format(Locale.US, "%.0f", maxPower) else String.format(Locale.US, "%.1f", maxPower)
                                "${pStr}W(${vStr}V/${cStr}A)"
                            } else {
                                ""
                            }
                            writer.write(String.format(
                                Locale.getDefault(),
                                "%s,%.2f,%.2f,%.2f,%d,%s,%s\n",
                                timeStr, record.voltage, record.current, record.power, record.batteryLevel, limitStr, screenStateStr
                            ))
                        }
                    }
                }
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, getString(R.string.csv_export_success), android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, getString(R.string.csv_export_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        
        menuDeleteSegment = menu.findItem(R.id.menu_delete_segment)
        menuDeleteSegment?.isVisible = lineChart.highlighted?.isNotEmpty() == true
        
        // Hide theme/language options if we are viewing historical details
        val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
        val menuTheme = menu.findItem(R.id.menu_theme)
        val menuLanguage = menu.findItem(R.id.menu_language)
        if (historySessionId != -1L) {
            menuTheme?.isVisible = false
            menuLanguage?.isVisible = false
        } else {
            menuTheme?.isVisible = true
            menuLanguage?.isVisible = true
            // Set correct icon for current theme
            val themePrefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
            val currentTheme = themePrefs.getInt("THEME_MODE", 0)
            val iconRes = when (currentTheme) {
                1 -> R.drawable.ic_theme_light
                2 -> R.drawable.ic_theme_dark
                else -> R.drawable.ic_theme_auto
            }
            menuTheme?.setIcon(iconRes)
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val historySessionId = intent.getLongExtra("HISTORY_SESSION_ID", -1L)
        if (item.itemId == android.R.id.home) {
            if (historySessionId != -1L) {
                finish()
                return true
            }
        }
        
        if (item.itemId == R.id.menu_delete_segment) {
            val highlight = lineChart.highlighted?.firstOrNull() ?: return true
            val targetTime = chartBaseTime + highlight.x.toLong()
            val index = currentRecords.indices.minByOrNull { abs(currentRecords[it].timestamp - targetTime) } ?: -1
            if (index == -1) return true
            val record = currentRecords[index]

            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
            val options = arrayOf(
                getString(R.string.crop_delete_before),
                getString(R.string.crop_delete_after),
                getString(R.string.crop_delete_single)
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.crop_data_title, timeStr))
                .setItems(options) { _, which ->
                    lifecycleScope.launch {
                        val dao = ChargeDatabase.getDatabase(this@MainActivity).chargeDao()
                        val sessionStart = record.sessionId
                        when (which) {
                            0 -> { // Delete before
                                dao.deleteRecordsBefore(sessionStart, record.timestamp)
                            }
                            1 -> { // Delete after
                                dao.deleteRecordsAfter(sessionStart, record.timestamp)
                            }
                            2 -> { // Delete single
                                dao.deleteSingleRecord(sessionStart, record.timestamp)
                            }
                        }
                        lineChart.highlightValue(null, true)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return true
        }
        
        if (item.itemId == R.id.menu_lang_cn) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
            return true
        }
        if (item.itemId == R.id.menu_lang_en) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            return true
        }
        
        val themePrefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
        val activeTheme = themePrefs.getInt("THEME_MODE", 0)
        var newTheme: Int? = null
        when (item.itemId) {
            R.id.menu_theme_auto -> newTheme = 0
            R.id.menu_theme_light -> newTheme = 1
            R.id.menu_theme_dark -> newTheme = 2
        }
        
        if (newTheme != null && newTheme != activeTheme) {
            themePrefs.edit { putInt("THEME_MODE", newTheme) }
            val newNightMode = when (newTheme) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(newNightMode)
            return true
        }
        
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("SELECTED_TAB_INDEX", selectedTabIndex)
    }


    override fun onDestroy() {
        val prefs = getSharedPreferences("ChargeLogPrefs", MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (isFinishing) {
            prefs.edit { putBoolean("USER_EXITED", true) }
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

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        holder.tvRawTime.text = format.format(Date(record.timestamp))
        holder.tvRawVoltage.text = String.format(Locale.getDefault(), "%.2f", record.voltage)
        holder.tvRawCurrent.text = String.format(Locale.getDefault(), "%.2f", record.current)
        holder.tvRawPower.text = String.format(Locale.getDefault(), "%.2f", record.power)
        holder.tvRawBattery.text = record.batteryLevel.toString()
        holder.tvRawScreenState.text = when (record.screenState) {
            0 -> "锁屏"
            1 -> "亮屏"
            else -> "未知"
        }
        val maxV = record.maxVoltage
        val maxC = record.maxCurrent
        if (maxV != null && maxC != null && maxV > 0 && maxC > 0) {
            val maxPower = maxV * maxC
            val vStr = if (maxV % 1 == 0f) String.format(Locale.US, "%.0f", maxV) else String.format(Locale.US, "%.1f", maxV)
            val cStr = if (maxC % 1 == 0f) String.format(Locale.US, "%.0f", maxC) else String.format(Locale.US, "%.1f", maxC)
            val pStr = if (maxPower % 1 == 0f) String.format(Locale.US, "%.0f", maxPower) else String.format(Locale.US, "%.1f", maxPower)
            holder.tvRawMaxPower.text = "${pStr}W(${vStr}V/${cStr}A)"
        } else {
            holder.tvRawMaxPower.text = ""
        }
    }

    override fun getItemCount() = records.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRawTime: TextView = view.findViewById(R.id.tvRawTime)
        val tvRawVoltage: TextView = view.findViewById(R.id.tvRawVoltage)
        val tvRawCurrent: TextView = view.findViewById(R.id.tvRawCurrent)
        val tvRawPower: TextView = view.findViewById(R.id.tvRawPower)
        val tvRawBattery: TextView = view.findViewById(R.id.tvRawBattery)
        val tvRawMaxPower: TextView = view.findViewById(R.id.tvRawMaxPower)
        val tvRawScreenState: TextView = view.findViewById(R.id.tvRawScreenState)
    }
}
