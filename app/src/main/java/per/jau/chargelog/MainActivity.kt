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
import com.google.android.material.tabs.TabLayout
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

    private lateinit var lineChart: LineChart
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnHistory: Button
    private lateinit var btnClear: Button
    private lateinit var btnShowData: Button
    private lateinit var tabLayout: TabLayout

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
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        lineChart = findViewById(R.id.lineChart)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnHistory = findViewById(R.id.btnHistory)
        btnClear = findViewById(R.id.btnClear)
        btnShowData = findViewById(R.id.btnShowData)
        tabLayout = findViewById(R.id.tabLayout)

        tvCurrentVoltage = findViewById(R.id.tvCurrentVoltage)
        tvCurrentCurrent = findViewById(R.id.tvCurrentCurrent)
        tvCurrentPower = findViewById(R.id.tvCurrentPower)
        tvCurrentProtocol = findViewById(R.id.tvCurrentProtocol)

        setupTabs()
        setupChart()
        checkPermissions()

        // Start foreground service immediately on launch to keep notification active
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
        }

        btnStop.setOnClickListener {
            getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("IS_RECORDING", false)
                .putBoolean("FORCE_NEW_SESSION", true)
                .apply()
            tvStatus.text = "充电日志已停止"
        }

        btnHistory.setOnClickListener {
            if (btnHistory.text.toString() == "返回实时") {
                val newIntent = intent.apply {
                    removeExtra("HISTORY_SESSION_ID")
                }
                setIntent(newIntent)
                handleIntent(newIntent)
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
            tvStatus.text = "查看历史记录"
            btnStart.visibility = View.GONE
            btnStop.visibility = View.GONE
            btnClear.visibility = View.GONE
            btnShowData.visibility = View.VISIBLE
            btnHistory.text = "返回实时"
            observeData(historySessionId)
        } else {
            tvStatus.text = if (isRecording()) "正在记录充电数据..." else "充电日志已停止"
            btnStart.visibility = View.VISIBLE
            btnStop.visibility = View.VISIBLE
            btnClear.visibility = View.VISIBLE
            btnShowData.visibility = View.GONE
            btnHistory.text = "历史记录"
            observeData()
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
                    } else {
                        currentRecords = emptyList()
                        lineChart.clear()
                        tvCurrentVoltage.text = "电压: -- V"
                        tvCurrentCurrent.text = "电流: -- A"
                        tvCurrentPower.text = "功率: -- W"
                        tvCurrentProtocol.text = "电量: --"
                    }
                    return@collectLatest
                }
                currentRecords = records
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

        val dataSets = ArrayList<LineDataSet>()
        var currentSegment = ArrayList<Entry>()
        var isSegmentDischarging: Boolean? = null

        for (i in currentRecords.indices) {
            val record = currentRecords[i]
            val x = (record.timestamp - chartBaseTime).toFloat()
            val y = when (selectedTabIndex) {
                0 -> record.voltage
                1 -> record.current
                else -> record.power
            }
            val isDischarging = record.current < 0

            if (isSegmentDischarging == null) {
                isSegmentDischarging = isDischarging
                currentSegment.add(Entry(x, y))
            } else if (isSegmentDischarging == isDischarging) {
                currentSegment.add(Entry(x, y))
            } else {
                // Bridge point to make lines continuous
                currentSegment.add(Entry(x, y))

                val color = when (selectedTabIndex) {
                    0 -> Color.RED
                    1 -> Color.parseColor("#4488FF")
                    else -> if (isSegmentDischarging) Color.parseColor("#4488FF") else Color.GREEN
                }
                
                val label = if (dataSets.isEmpty()) {
                    when (selectedTabIndex) {
                        0 -> "电压 (V)"
                        1 -> "电流 (A)"
                        else -> "功率 (W)"
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
                currentSegment.add(Entry(x, y))
                isSegmentDischarging = isDischarging
            }
        }

        // Add the last segment
        if (currentSegment.isNotEmpty() && isSegmentDischarging != null) {
            val color = when (selectedTabIndex) {
                0 -> Color.RED
                1 -> Color.parseColor("#4488FF")
                else -> if (isSegmentDischarging) Color.parseColor("#4488FF") else Color.GREEN
            }
            
            val label = if (dataSets.isEmpty()) {
                when (selectedTabIndex) {
                    0 -> "电压 (V)"
                    1 -> "电流 (A)"
                    else -> "功率 (W)"
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

    override fun onDestroy() {
        val prefs = getSharedPreferences("ChargeLogPrefs", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
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
    }

    override fun getItemCount() = records.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRawTime: TextView = view.findViewById(R.id.tvRawTime)
        val tvRawVoltage: TextView = view.findViewById(R.id.tvRawVoltage)
        val tvRawCurrent: TextView = view.findViewById(R.id.tvRawCurrent)
        val tvRawPower: TextView = view.findViewById(R.id.tvRawPower)
    }
}
