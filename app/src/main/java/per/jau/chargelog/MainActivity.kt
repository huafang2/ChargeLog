package per.jau.chargelog

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import per.jau.chargelog.constants.PrefKeys
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.data.ChargeRepository
import per.jau.chargelog.service.ChargeLoggingService
import per.jau.chargelog.ui.RawDataDialogHelper
import per.jau.chargelog.utils.BatteryDisplayState
import per.jau.chargelog.utils.BatteryFlow
import per.jau.chargelog.utils.BatteryUtils
import per.jau.chargelog.utils.FastChargeLimit
import per.jau.chargelog.utils.HistoryRetention
import per.jau.chargelog.utils.PowerLimitFormatter
import per.jau.chargelog.utils.SessionStatsCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds


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
    private lateinit var layoutHistoryRetention: View
    private lateinit var layoutSettingsRow: View
    private lateinit var tvHistoryRetentionValue: TextView
    private lateinit var switchBgReport: androidx.appcompat.widget.SwitchCompat

    private lateinit var tvCurrentVoltage: TextView
    private lateinit var tvCurrentCurrent: TextView
    private lateinit var tvCurrentPower: TextView
    private lateinit var tvCurrentProtocol: TextView
    private lateinit var layoutMaxChargingLimit: View
    private lateinit var tvCurrentMaxPower: TextView
    private lateinit var layoutPowerSummaryBanner: View
    private lateinit var tvPowerSummaryText: TextView
    private lateinit var layoutPowerPeakSummary: View
    private lateinit var tvPowerPeakCharge: TextView
    private lateinit var tvPowerPeakDischarge: TextView
    private var maxChargingLimitAnimator: AnimatorSet? = null
    private var maxChargingLimitTargetVisible = false
    private var maxChargingLimitVoltage: Float? = null
    private var maxChargingLimitCurrent: Float? = null
    private var maxChargingLimitRangeMin: Float? = null
    private var maxChargingLimitRangeMax: Float? = null

    private var currentRecords: List<ChargeRecord> = emptyList()
    private var selectedTabIndex = 2
    private var observeJob: Job? = null
    private var liveTextUpdateJob: Job? = null
    private var lastHighlightedX: Float? = null
    private var selectedRecordTimestamp: Long? = null
    
    // For adaptive text coloring
    private var textColorPrimary: Int = Color.BLACK
    private var menuDeleteSegment: android.view.MenuItem? = null
    private var colorRealtime: Int = Color.GREEN
    private var colorSelected: Int = Color.RED
    private var colorSummary: Int = Color.BLUE

    private var chartBaseTime: Long = 0L

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PrefKeys.CURRENT_SESSION_START) {
            val historyStartTime = intent.getLongExtra("HISTORY_START_TIME", -1L)
            val historyEndTime = intent.getLongExtra("HISTORY_END_TIME", -1L)
            if (historyStartTime == -1L || historyEndTime == -1L) {
                observeData()
            }
        } else if (key == PrefKeys.IS_RECORDING) {
            val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
            if (historySessionId == -1L && ::tvStatus.isInitialized) {
                refreshStatusForCurrentMode()
                updateButtonStates()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
        }

        if (savedInstanceState != null) {
            selectedTabIndex = savedInstanceState.getInt("SELECTED_TAB_INDEX", 2)
            selectedRecordTimestamp = savedInstanceState
                .getLong("SELECTED_RECORD_TIMESTAMP", Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
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

        val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        prefs.edit {
            putBoolean(PrefKeys.USER_EXITED, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        lifecycleScope.launch(Dispatchers.IO) {
            HistoryRetention.cleanup(this@MainActivity)
        }

        lineChart = findViewById(R.id.lineChart)
        sbChartScrubber = findViewById(R.id.sbChartScrubber)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatus.setOnClickListener {
            if (selectedRecordTimestamp != null) {
                clearChartSelection()
            }
        }
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnHistory = findViewById(R.id.btnHistory)
        btnClear = findViewById(R.id.btnClear)
        btnShowData = findViewById(R.id.btnShowData)
        btnExit = findViewById(R.id.btnExit)
        layoutBgReportBanner = findViewById(R.id.layoutBgReportBanner)
        val btnBannerClose = findViewById<Button>(R.id.btnBannerClose)
        btnBannerClose.setOnClickListener {
            setViewsVisibleAnimated(layoutBgReportBanner to false)
        }
        tabLayout = findViewById(R.id.tabLayout)

        tvCurrentVoltage = findViewById(R.id.tvCurrentVoltage)
        tvCurrentCurrent = findViewById(R.id.tvCurrentCurrent)
        tvCurrentPower = findViewById(R.id.tvCurrentPower)
        tvCurrentProtocol = findViewById(R.id.tvCurrentProtocol)
        layoutMaxChargingLimit = findViewById(R.id.layoutMaxChargingLimit)
        tvCurrentMaxPower = findViewById(R.id.tvCurrentMaxPower)
        layoutPowerSummaryBanner = findViewById(R.id.layoutPowerSummaryBanner)
        tvPowerSummaryText = findViewById(R.id.tvPowerSummaryText)
        layoutPowerPeakSummary = findViewById(R.id.layoutPowerPeakSummary)
        tvPowerPeakCharge = findViewById(R.id.tvPowerPeakCharge)
        tvPowerPeakDischarge = findViewById(R.id.tvPowerPeakDischarge)
        restoreMaxChargingLimitState(savedInstanceState)

        val tvFooter = findViewById<TextView>(R.id.tvFooter)
        tvFooter.text = getString(R.string.footer_version_build, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE)
        tvFooter.setOnClickListener {
            MaterialAlertDialogBuilder(this)
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
        layoutHistoryRetention = findViewById(R.id.layoutHistoryRetention)
        layoutSettingsRow = findViewById(R.id.layoutSettingsRow)
        tvHistoryRetentionValue = findViewById(R.id.tvHistoryRetentionValue)
        switchBgReport = findViewById(R.id.switchBgReport)
        switchBgReport.isChecked = prefs.getBoolean(PrefKeys.ENABLE_BG_REPORT, true)
        switchBgReport.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(PrefKeys.ENABLE_BG_REPORT, isChecked) }
        }


        val tvIntervalLabel = findViewById<TextView>(R.id.tvIntervalLabel)
        val sbInterval = findViewById<android.widget.SeekBar>(R.id.sbInterval)
        val currentInterval = prefs.getInt(PrefKeys.SAMPLING_INTERVAL_SECONDS, 5)
        sbInterval.progress = currentInterval - 1
        tvIntervalLabel.text = getString(R.string.sampling_interval, currentInterval)
        sbInterval.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val sec = progress + 1
                tvIntervalLabel.text = getString(R.string.sampling_interval, sec)
                prefs.edit { putInt(PrefKeys.SAMPLING_INTERVAL_SECONDS, sec) }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        setupHistoryRetention(prefs)
        setupTabs()
        setupChart()
        setupScrubber()
        checkPermissions()

        // Start foreground service immediately on launch to keep background monitoring active
        val serviceIntent = Intent(this, ChargeLoggingService::class.java)
        startForegroundService(serviceIntent)

        handleIntent(intent)

        btnStart.setOnClickListener {
            val startIntent = Intent(this, ChargeLoggingService::class.java).apply {
                action = ChargeLoggingService.ACTION_START_RECORDING
            }
            startForegroundService(startIntent)
            refreshStatusForCurrentMode()
            setViewsVisibleAnimated(
                btnStart to false,
                btnStop to true
            )
        }

        btnStop.setOnClickListener {
            getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE).edit {
                putBoolean(PrefKeys.IS_RECORDING, false)
                putBoolean(PrefKeys.FORCE_NEW_SESSION, true)
            }
            val stopIntent = Intent(this, ChargeLoggingService::class.java).apply {
                action = ChargeLoggingService.ACTION_STOP_RECORDING
            }
            startForegroundService(stopIntent)
            refreshStatusForCurrentMode()
            setViewsVisibleAnimated(
                btnStart to true,
                btnStop to false
            )
        }

        btnExit.setOnClickListener {
            getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
                .edit {
                    putBoolean(PrefKeys.IS_RECORDING, false)
                        .putBoolean(PrefKeys.FORCE_NEW_SESSION, true)
                        .putBoolean(PrefKeys.USER_EXITED, true)
                }
            stopService(Intent(this, ChargeLoggingService::class.java))
            finishAffinity()
        }

        btnHistory.setOnClickListener {
            val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
            if (historySessionId != -1L) {
                finish()
            } else {
                val nextIntent = Intent(this, HistoryActivity::class.java)
                startActivity(nextIntent)
            }
        }

        btnClear.setOnClickListener {
            val isRecording = isRecording()
            val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
            val sessionStart = prefs.getLong(PrefKeys.CURRENT_SESSION_START, 0L)
            if (isRecording) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_tip_title)
                    .setMessage(R.string.dialog_clear_confirm_msg)
                    .setPositiveButton(R.string.confirm_clear) { _, _ ->
                        lifecycleScope.launch {
                            // 1. Stop recording
                            prefs.edit {
                                putBoolean(PrefKeys.IS_RECORDING, false)
                            }
                            refreshStatusForCurrentMode()
                            updateButtonStates()
                            
                            // 2. Discard current session data by deleting records matching sessionId
                            val repo = ChargeRepository.getInstance(this@MainActivity)
                            if (sessionStart > 0L) {
                                repo.deleteRecordsBySession(sessionStart)
                            }
                            
                            // 3. Reset CURRENT_SESSION_START to now
                            val now = System.currentTimeMillis()
                            prefs.edit {
                                putLong(PrefKeys.CURRENT_SESSION_START, now)
                                putBoolean(PrefKeys.FORCE_NEW_SESSION, true)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                // Not recording: clear screen immediately without dialog, no database deletion
                val now = System.currentTimeMillis()
                prefs.edit {
                    putLong(PrefKeys.CURRENT_SESSION_START, now)
                    putBoolean(PrefKeys.FORCE_NEW_SESSION, true)
                }
            }
        }

        btnShowData.setOnClickListener {
            RawDataDialogHelper.show(this, currentRecords) { fileName ->
                exportCsvLauncher.launch(fileName)
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
        val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
        
        if (historySessionId != -1L) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            refreshStatusForCurrentMode()
            btnHistory.text = getString(R.string.back_to_history)
            observeData(historySessionId)
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            refreshStatusForCurrentMode()
            btnHistory.text = getString(R.string.history)
            observeData()
        }
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
        if (historySessionId != -1L) {
            setViewsVisibleAnimated(
                btnStart to false,
                btnStop to false,
                btnClear to false,
                btnExit to false,
                btnHistory to false,
                layoutBgReportBanner to false,
                layoutInterval to false,
                layoutSettingsRow to false
            )
            return
        }

        val recording = isRecording()
        setViewsVisibleAnimated(
            btnStart to !recording,
            btnStop to recording,
            btnClear to true,
            btnExit to true,
            btnHistory to true,
            layoutInterval to true,
            layoutSettingsRow to true
        )
    }

    private fun setViewsVisibleAnimated(vararg changes: Pair<View, Boolean>) {
        val pendingChanges = changes.filter { (view, visible) ->
            view.visibility != if (visible) View.VISIBLE else View.GONE
        }
        if (pendingChanges.isEmpty()) return

        val root = findViewById<ViewGroup>(R.id.main)
        if (root.isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            val changeBounds = ChangeBounds().apply {
                excludeTarget(layoutMaxChargingLimit, true)
            }
            val fade = Fade().apply {
                excludeTarget(layoutMaxChargingLimit, true)
            }
            val transition = TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(changeBounds)
                addTransition(fade)
                duration = 200L
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            }
            TransitionManager.beginDelayedTransition(root, transition)
        }

        pendingChanges.forEach { (view, visible) ->
            view.animate().cancel()
            view.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun setupHistoryRetention(prefs: android.content.SharedPreferences) {
        fun labelFor(days: Int): String = if (days == HistoryRetention.FOREVER) {
            getString(R.string.history_retention_forever)
        } else {
            getString(R.string.history_retention_days, days)
        }

        val selectedDays = prefs.getInt(
            HistoryRetention.PREF_KEY_DAYS,
            HistoryRetention.FOREVER
        )
        tvHistoryRetentionValue.text = labelFor(selectedDays)
        layoutHistoryRetention.setOnClickListener {
            val options = HistoryRetention.OPTIONS_DAYS
            val currentDays = prefs.getInt(
                HistoryRetention.PREF_KEY_DAYS,
                HistoryRetention.FOREVER
            )

            val popup = androidx.appcompat.widget.PopupMenu(
                this,
                layoutHistoryRetention,
                Gravity.END
            )
            options.forEachIndexed { index, days ->
                val title = android.text.SpannableString(labelFor(days))
                if (days == currentDays) {
                    title.setSpan(
                        android.text.style.ForegroundColorSpan(colorSummary),
                        0,
                        title.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    title.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        0,
                        title.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                popup.menu.add(
                    android.view.Menu.NONE,
                    index,
                    index,
                    title
                )
            }
            popup.setOnMenuItemClickListener { item ->
                val days = options.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                prefs.edit { putInt(HistoryRetention.PREF_KEY_DAYS, days) }
                tvHistoryRetentionValue.text = labelFor(days)
                val message = if (days == HistoryRetention.FOREVER) {
                    R.string.history_retention_saved
                } else {
                    R.string.history_retention_next_start
                }
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            popup.show()
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
                if (e == null || currentRecords.isEmpty()) return

                val targetTime = chartBaseTime + e.x.toLong()
                val index = currentRecords.indices.minByOrNull {
                    abs(currentRecords[it].timestamp - targetTime)
                } ?: -1
                if (index == -1) return

                val record = currentRecords[index]
                if (selectedRecordTimestamp == record.timestamp &&
                    lineChart.highlighted?.isNotEmpty() == true &&
                    !isDraggingScrubber &&
                    !lineChart.isDraggingVerticalLine
                ) {
                    clearChartSelection()
                    return
                }

                selectedRecordTimestamp = record.timestamp
                lastHighlightedX = e.x
                updateDashboardText(record, true)
                updatePowerTabSummary(index)
                menuDeleteSegment?.isVisible = true
                if (!isDraggingScrubber) {
                    sbChartScrubber.progress = index
                }
            }

            override fun onNothingSelected() {
                selectedRecordTimestamp = null
                lastHighlightedX = null
                menuDeleteSegment?.isVisible = false
                if (currentRecords.isNotEmpty()) {
                    val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
                    if (historySessionId != -1L) {
                        updateDashboardWithExtremeValues()
                    } else {
                        updateDashboardText(currentRecords.last(), false)
                    }
                } else {
                    refreshStatusForCurrentMode()
                }
                updatePowerTabSummary()
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
                    clearChartSelection()
                }
            }
            
            override fun onChartFling(me1: android.view.MotionEvent?, me2: android.view.MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: android.view.MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(me: android.view.MotionEvent?, dX: Float, dY: Float) {}
        }
    }

    private fun clearChartSelection() {
        selectedRecordTimestamp = null
        lastHighlightedX = null
        menuDeleteSegment?.isVisible = false
        lineChart.post {
            lineChart.highlightValue(null, true)
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
                    updatePowerTabSummary(progress)
                    
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

    @SuppressLint("SetTextI18n")
    private fun observeData(sessionId: Long = -1L) {
        observeJob?.cancel() // Cancel any ongoing observation
        val repo = ChargeRepository.getInstance(this)
        
        observeJob = lifecycleScope.launch {
            val targetSessionId = if (sessionId != -1L) {
                sessionId
            } else {
                val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
                prefs.getLong(PrefKeys.CURRENT_SESSION_START, System.currentTimeMillis())
            }
            val flow = repo.getRecordsBySession(targetSessionId)

            flow.collectLatest { records ->
                if (records.isEmpty()) {
                    currentRecords = emptyList()
                    selectedRecordTimestamp = null
                    lastHighlightedX = null
                    lineChart.clear()
                    setViewsVisibleAnimated(
                        btnShowData to false,
                        sbChartScrubber to false
                    )
                    menuDeleteSegment?.isVisible = false
                    val latest = repo.getLatestRecord()
                    if (latest != null && sessionId == -1L) {
                        chartBaseTime = latest.timestamp
                        updateDashboardText(latest, false)
                    } else {
                        resetDashboardTextSize()
                        tvCurrentVoltage.text = "${getString(R.string.voltage_label)}-- V"
                        tvCurrentCurrent.text = "${getString(R.string.current_label)}-- A"
                        tvCurrentPower.text = "${getString(R.string.power_label)}-- W"
                        tvCurrentProtocol.text = "${getString(R.string.battery_label)}--"
                        animateMaxChargingLimitVisibility(false)
                    }
                    refreshStatusForCurrentMode()
                    updatePowerTabSummary()
                    return@collectLatest
                }
                currentRecords = records
                chartBaseTime = records.first().timestamp

                // Update scrubber range
                val showScrubber = records.size > 1
                if (showScrubber) {
                    sbChartScrubber.max = records.size - 1
                }
                setViewsVisibleAnimated(
                    btnShowData to true,
                    sbChartScrubber to showScrubber
                )

                val selectedIndexBeforeUpdate = selectedRecordTimestamp?.let { timestamp ->
                    records.indexOfFirst { it.timestamp == timestamp }.takeIf { it >= 0 }
                }
                if (selectedRecordTimestamp != null && selectedIndexBeforeUpdate == null) {
                    selectedRecordTimestamp = null
                    lastHighlightedX = null
                    menuDeleteSegment?.isVisible = false
                    lineChart.highlightValue(null, false)
                }

                // Keep dashboard and power summary on the same timestamp while selected.
                if (selectedIndexBeforeUpdate != null && selectedRecordTimestamp != null) {
                    val selectedRecord = records[selectedIndexBeforeUpdate]
                    updateDashboardText(selectedRecord, true)
                    updatePowerTabSummary(selectedIndexBeforeUpdate)
                } else if (sessionId != -1L) {
                    updateDashboardWithExtremeValues()
                } else {
                    updateDashboardText(records.last(), false)
                }

                updateChartData()

                selectedRecordTimestamp?.let { timestamp ->
                    val selectedIndex = records.indexOfFirst { it.timestamp == timestamp }
                    if (selectedIndex >= 0) {
                        lastHighlightedX = (records[selectedIndex].timestamp - chartBaseTime).toFloat()
                        menuDeleteSegment?.isVisible = true
                        sbChartScrubber.progress = selectedIndex
                        lineChart.post {
                            lineChart.highlightValue(
                                Highlight(lastHighlightedX ?: 0f, 0, 0),
                                false
                            )
                        }
                    }
                }

                val firstTimestamp = records.first().timestamp
                val lastTimestamp = records.last().timestamp
                val duration = lastTimestamp - firstTimestamp
                val durationFloat = duration.toFloat()

                if (sessionId == -1L) {
                    // Live Mode: restrict to 15 minutes window
                    val fifteenMins = 15 * 60 * 1000f
                    lineChart.setVisibleXRangeMaximum(fifteenMins)
                    
                    // Only auto-scroll to end if not currently scrubbing or selecting a point
                    if (!isDraggingScrubber && selectedRecordTimestamp == null) {
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
        tvCurrentProtocol.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        tvCurrentMaxPower.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun selectedRecordIndex(): Int? =
        selectedRecordTimestamp?.let { timestamp ->
            currentRecords.indexOfFirst { it.timestamp == timestamp }.takeIf { it >= 0 }
        }

    private fun selectedRecord(): ChargeRecord? =
        selectedRecordIndex()?.let { currentRecords[it] }

    private fun refreshRealtimeStatusFromBroadcast() {
        val batteryStatusIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryStatusIntent == null) {
            updateStatusTitle(false, realtimeState = BatteryDisplayState.IDLE)
            return
        }
        val batteryStatus = batteryStatusIntent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )
        val batteryLevel = BatteryUtils.getBatteryLevel(this, batteryStatusIntent)
        val current = BatteryUtils.getCurrent(this, batteryStatusIntent)
        updateStatusTitle(
            false,
            realtimeState = BatteryFlow.displayState(batteryStatus, current, batteryLevel)
        )
    }

    private fun refreshStatusForCurrentMode() {
        val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
        if (historySessionId != -1L) {
            selectedRecord()?.let { updateStatusTitle(true, it) }
                ?: updateStatusTitle(false)
            return
        }

        selectedRecord()?.let {
            updateStatusTitle(true, it)
            return
        }

        if (isRecording()) {
            currentRecords.lastOrNull()?.let { updateStatusTitle(false, it) }
                ?: refreshRealtimeStatusFromBroadcast()
        } else {
            refreshRealtimeStatusFromBroadcast()
        }
    }

    private fun statusLabel(state: BatteryDisplayState): String = when (state) {
        BatteryDisplayState.CHARGING -> getString(R.string.status_charging)
        BatteryDisplayState.DISCHARGING -> getString(R.string.status_discharging)
        BatteryDisplayState.FULL -> getString(R.string.status_full)
        BatteryDisplayState.IDLE -> getString(R.string.status_idle)
    }

    private fun applyStatusBadgeStyle(state: BatteryDisplayState?, selected: Boolean) {
        val backgroundRes = when {
            selected -> R.color.status_selected_bg
            state == BatteryDisplayState.CHARGING -> R.color.status_charging_bg
            state == BatteryDisplayState.DISCHARGING -> R.color.status_discharging_bg
            state == BatteryDisplayState.FULL -> R.color.status_full_bg
            else -> R.color.status_idle_bg
        }
        val textRes = when {
            selected -> R.color.status_selected_text
            state == BatteryDisplayState.CHARGING -> R.color.status_charging_text
            state == BatteryDisplayState.DISCHARGING -> R.color.status_discharging_text
            state == BatteryDisplayState.FULL -> R.color.status_full_text
            else -> R.color.status_idle_text
        }
        tvStatus.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, backgroundRes))
            cornerRadius = 19f * resources.displayMetrics.density
        }
        tvStatus.setTextColor(ContextCompat.getColor(this, textRes))
    }

    private fun setStatusTextWithPin(title: String) {
        val spannable = android.text.SpannableStringBuilder(title)
        spannable.append("  ")
        val start = title.length + 1
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_selected_pin)
        if (drawable != null) {
            val sizePx = (18 * resources.displayMetrics.density).toInt()
            drawable.setBounds(0, 0, sizePx, sizePx)
            val imageSpan = android.text.style.ImageSpan(
                drawable,
                android.text.style.ImageSpan.ALIGN_CENTER
            )
            spannable.setSpan(
                imageSpan,
                start,
                start + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvStatus.text = spannable
    }

    private fun updateStatusTitle(
        isSelected: Boolean = false,
        record: ChargeRecord? = null,
        realtimeState: BatteryDisplayState? = null
    ) {
        val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
        if (isSelected && record != null) {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
            val title = getString(R.string.title_selected_live, timeStr)
            setStatusTextWithPin(title)
            applyStatusBadgeStyle(null, selected = true)
            return
        }

        if (historySessionId != -1L) {
            tvStatus.text = getString(R.string.viewing_history)
            applyStatusBadgeStyle(BatteryDisplayState.IDLE, selected = false)
            return
        }

        val state = realtimeState ?: record?.let {
            BatteryFlow.displayState(it.batteryStatus, it.current, it.batteryLevel)
        } ?: BatteryDisplayState.IDLE
        val suffix = if (isRecording()) {
            getString(R.string.status_recording_suffix)
        } else {
            getString(R.string.status_not_recording_suffix)
        }
        tvStatus.text = getString(R.string.status_live_format, statusLabel(state), suffix)
        applyStatusBadgeStyle(state, selected = false)
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
        val maxCurrentMicro = batteryStatus?.getIntExtra("max_charging_current", -1) ?: -1
        val maxVoltageMicro = batteryStatus?.getIntExtra("max_charging_voltage", -1) ?: -1
        val maxCurrent = maxCurrentMicro.takeIf { it > 0 }?.div(1_000_000f)
        val maxVoltage = maxVoltageMicro.takeIf { it > 0 }?.div(1_000_000f)
        updateMaxChargingLimit(maxVoltage, maxCurrent)
    }

    private fun updateMaxChargingLimit(maxVoltage: Float?, maxCurrent: Float?) {
        val maxPower = FastChargeLimit.powerWatts(maxVoltage, maxCurrent)
        if (maxPower == null || maxVoltage == null || maxCurrent == null) {
            maxChargingLimitVoltage = null
            maxChargingLimitCurrent = null
            maxChargingLimitRangeMin = null
            maxChargingLimitRangeMax = null
            animateMaxChargingLimitVisibility(false)
            return
        }

        maxChargingLimitVoltage = maxVoltage
        maxChargingLimitCurrent = maxCurrent
        maxChargingLimitRangeMin = null
        maxChargingLimitRangeMax = null
        val value = getString(R.string.max_power_format, maxVoltage, maxCurrent, maxPower)
        tvCurrentMaxPower.text = formatValueText(
            getString(R.string.max_power_label),
            value,
            "",
            colorSummary
        )
        animateMaxChargingLimitVisibility(true)
    }

    private fun updateHistoricalMaxChargingLimitRange() {
        val range = FastChargeLimit.historicalPowerRange(currentRecords)
        if (range == null) {
            maxChargingLimitVoltage = null
            maxChargingLimitCurrent = null
            maxChargingLimitRangeMin = null
            maxChargingLimitRangeMax = null
            animateMaxChargingLimitVisibility(false)
            return
        }

        val (minPower, maxPower) = range
        maxChargingLimitVoltage = null
        maxChargingLimitCurrent = null
        maxChargingLimitRangeMin = minPower
        maxChargingLimitRangeMax = maxPower
        tvCurrentMaxPower.text = formatValueText(
            getString(R.string.max_power_label),
            getString(R.string.max_power_range_format, minPower, maxPower),
            "",
            colorSummary
        )
        animateMaxChargingLimitVisibility(true)
    }
    private fun animateMaxChargingLimitVisibility(show: Boolean) {
        if (maxChargingLimitTargetVisible == show) {
            if (maxChargingLimitAnimator != null) return
            if (show && layoutMaxChargingLimit.isVisible) return
            if (!show && layoutMaxChargingLimit.isGone) return
        }
        maxChargingLimitTargetVisible = show

        val parentWidth = (layoutMaxChargingLimit.parent as? View)?.width ?: 0
        if (show && parentWidth <= 0) {
            layoutMaxChargingLimit.post {
                if (maxChargingLimitTargetVisible) animateMaxChargingLimitVisibility(true)
            }
            return
        }

        maxChargingLimitAnimator?.removeAllListeners()
        maxChargingLimitAnimator?.cancel()
        maxChargingLimitAnimator = null

        if (!ValueAnimator.areAnimatorsEnabled()) {
            applyMaxChargingLimitVisibility(show)
            return
        }

        val slideDistance = 12f * resources.displayMetrics.density
        val layoutParams = layoutMaxChargingLimit.layoutParams
        val horizontalMargins = (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.leftMargin + it.rightMargin
        } ?: 0
        val measuredWidth = (parentWidth - horizontalMargins).coerceAtLeast(1)

        if (show && layoutMaxChargingLimit.visibility != View.VISIBLE) {
            layoutMaxChargingLimit.visibility = View.VISIBLE
            layoutMaxChargingLimit.alpha = 0f
            layoutMaxChargingLimit.translationY = -slideDistance
            layoutMaxChargingLimit.scaleX = 0.96f
            layoutMaxChargingLimit.scaleY = 0.96f
            layoutMaxChargingLimit.elevation = 0f
        }

        layoutMaxChargingLimit.measure(
            View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val expandedHeight = layoutMaxChargingLimit.measuredHeight.coerceAtLeast(1)
        val startHeight = layoutMaxChargingLimit.height.coerceAtLeast(0)
        val endHeight = if (show) expandedHeight else 0

        layoutParams.height = startHeight
        layoutMaxChargingLimit.layoutParams = layoutParams

        val heightAnimator = ValueAnimator.ofInt(startHeight, endHeight).apply {
            addUpdateListener { valueAnimator ->
                val params = layoutMaxChargingLimit.layoutParams
                params.height = valueAnimator.animatedValue as Int
                layoutMaxChargingLimit.layoutParams = params
            }
        }
        val alphaAnimator = ObjectAnimator.ofFloat(
            layoutMaxChargingLimit,
            View.ALPHA,
            layoutMaxChargingLimit.alpha,
            if (show) 1f else 0f
        )
        val slideAnimator = ObjectAnimator.ofFloat(
            layoutMaxChargingLimit,
            View.TRANSLATION_Y,
            layoutMaxChargingLimit.translationY,
            if (show) 0f else -slideDistance
        )
        val scaleXAnimator = ObjectAnimator.ofFloat(
            layoutMaxChargingLimit,
            View.SCALE_X,
            layoutMaxChargingLimit.scaleX,
            if (show) 1.025f else 0.97f,
            if (show) 1f else 0.97f
        )
        val scaleYAnimator = ObjectAnimator.ofFloat(
            layoutMaxChargingLimit,
            View.SCALE_Y,
            layoutMaxChargingLimit.scaleY,
            if (show) 1.025f else 0.97f,
            if (show) 1f else 0.97f
        )
        val restingElevation = 3f * resources.displayMetrics.density
        val elevationAnimator = ObjectAnimator.ofFloat(
            layoutMaxChargingLimit,
            "elevation",
            layoutMaxChargingLimit.elevation,
            if (show) 8f * resources.displayMetrics.density else 0f,
            if (show) restingElevation else 0f
        )

        val animatorSet = AnimatorSet().apply {
            playTogether(
                heightAnimator,
                alphaAnimator,
                slideAnimator,
                scaleXAnimator,
                scaleYAnimator,
                elevationAnimator
            )
            duration = 280L
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && maxChargingLimitAnimator === animation) {
                        applyMaxChargingLimitVisibility(show)
                        maxChargingLimitAnimator = null
                    }
                }
            })
        }
        maxChargingLimitAnimator = animatorSet
        animatorSet.start()
    }

    private fun applyMaxChargingLimitVisibility(show: Boolean) {
        val params = layoutMaxChargingLimit.layoutParams
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutMaxChargingLimit.layoutParams = params
        layoutMaxChargingLimit.alpha = if (show) 1f else 0f
        layoutMaxChargingLimit.translationY = if (show) 0f else -12f * resources.displayMetrics.density
        layoutMaxChargingLimit.scaleX = if (show) 1f else 0.97f
        layoutMaxChargingLimit.scaleY = if (show) 1f else 0.97f
        layoutMaxChargingLimit.elevation = if (show) 3f * resources.displayMetrics.density else 0f
        layoutMaxChargingLimit.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun restoreMaxChargingLimitState(savedInstanceState: Bundle?) {
        if (savedInstanceState?.getBoolean("MAX_CHARGING_LIMIT_VISIBLE", false) != true) return

        val maxVoltage = savedInstanceState.getFloat("MAX_CHARGING_LIMIT_VOLTAGE", Float.NaN)
        val maxCurrent = savedInstanceState.getFloat("MAX_CHARGING_LIMIT_CURRENT", Float.NaN)
        val maxPower = FastChargeLimit.powerWatts(maxVoltage, maxCurrent)
        if (maxPower != null) {
            maxChargingLimitVoltage = maxVoltage
            maxChargingLimitCurrent = maxCurrent
            maxChargingLimitRangeMin = null
            maxChargingLimitRangeMax = null
            tvCurrentMaxPower.text = formatValueText(
                getString(R.string.max_power_label),
                getString(R.string.max_power_format, maxVoltage, maxCurrent, maxPower),
                "",
                colorSummary
            )
            maxChargingLimitTargetVisible = true
            applyMaxChargingLimitVisibility(true)
            return
        }

        val minPower = savedInstanceState.getFloat("MAX_CHARGING_LIMIT_RANGE_MIN", Float.NaN)
        val maxRangePower = savedInstanceState.getFloat("MAX_CHARGING_LIMIT_RANGE_MAX", Float.NaN)
        if (!minPower.isFinite() || !maxRangePower.isFinite() || minPower < 0f || maxRangePower < minPower) return
        maxChargingLimitVoltage = null
        maxChargingLimitCurrent = null
        maxChargingLimitRangeMin = minPower
        maxChargingLimitRangeMax = maxRangePower
        tvCurrentMaxPower.text = formatValueText(
            getString(R.string.max_power_label),
            getString(R.string.max_power_range_format, minPower, maxRangePower),
            "",
            colorSummary
        )
        maxChargingLimitTargetVisible = true
        applyMaxChargingLimitVisibility(true)
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
            updateMaxChargingLimit(record.maxVoltage, record.maxCurrent)
        } else {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, filter)
            updateMaxChargingLimitText(batteryStatus)
        }
    }

    private fun updateDashboardWithExtremeValues() {
        val stats = SessionStatsCalculator.calculate(currentRecords) ?: return
        val changeSign = if (stats.batteryChange >= 0) "+${stats.batteryChange}%" else "${stats.batteryChange}%"
        
        resetDashboardTextSize()
        val summaryLabelSizeSp = 17f
        tvCurrentVoltage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, summaryLabelSizeSp)
        tvCurrentCurrent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, summaryLabelSizeSp)
        tvCurrentPower.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, summaryLabelSizeSp)
        tvCurrentProtocol.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, summaryLabelSizeSp)
        updateStatusTitle(false)

        val valV = String.format(Locale.getDefault(), "%.2f～%.2f", stats.minVoltage, stats.maxVoltage)
        val valC = String.format(Locale.getDefault(), "%.2f～%.2f", stats.minCurrent, stats.maxCurrent)
        val valP = String.format(Locale.getDefault(), "%.2f～%.2f", stats.minPower, stats.maxPower)
        val valB = "${stats.startBattery}→${stats.endBattery}($changeSign)"

        tvCurrentVoltage.text = formatExtremeValueText(getString(R.string.history_summary_voltage_label), valV, 10f)
        tvCurrentCurrent.text = formatExtremeValueText(getString(R.string.history_summary_current_label), valC, 10f)
        tvCurrentPower.text = formatExtremeValueText(getString(R.string.history_summary_power_label), valP, 10f)
        tvCurrentProtocol.text = formatExtremeValueText(getString(R.string.history_summary_battery_label), valB, 10f)
        updateHistoricalMaxChargingLimitRange()
    }

    private fun formatExtremeValueText(label: String, value: String, valueSizeSp: Float = 14f): android.text.SpannableString {
        val fullText = "$label$value"
        val spannable = android.text.SpannableString(fullText)
        val density = resources.displayMetrics.density
        val valuePx = (valueSizeSp * density).toInt()
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

    private fun addFastChargeLimitDataSets(dataSets: MutableList<LineDataSet>) {
        var legendAdded = false
        FastChargeLimit.contiguousSegments(currentRecords).forEach { segment ->
            val entries = segment.mapNotNull { record ->
                FastChargeLimit.powerWatts(record.maxVoltage, record.maxCurrent)?.let { limitPower ->
                    Entry((record.timestamp - chartBaseTime).toFloat(), limitPower, record)
                }
            }
            if (entries.isEmpty()) return@forEach

            val dataSet = createLineDataSet(
                entries,
                if (legendAdded) "" else getString(R.string.chart_fast_charge_limit),
                colorSummary
            ).apply {
                lineWidth = 2f
                enableDashedLine(12f, 6f, 0f)
                setDrawCircles(entries.size == 1)
                if (entries.size == 1) {
                    setCircleColor(colorSummary)
                    circleRadius = 3f
                    setDrawCircleHole(false)
                }
                if (legendAdded) form = Legend.LegendForm.NONE
            }
            dataSets.add(dataSet)
            legendAdded = true
        }
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

        if (selectedTabIndex == 2) {
            addFastChargeLimitDataSets(dataSets)
        }

        val lineData = LineData(dataSets.map { it })
        lineChart.data = lineData
        lineChart.notifyDataSetChanged()
        lineChart.invalidate()
        updatePowerTabSummary(selectedRecordIndex())
    }

    private fun updatePowerTabSummary(targetIndex: Int? = null) {
        if (!::layoutPowerSummaryBanner.isInitialized || !::tvPowerSummaryText.isInitialized) return
        if (selectedTabIndex != 2 || currentRecords.isEmpty()) {
            setViewsVisibleAnimated(
                layoutPowerSummaryBanner to false,
                layoutPowerPeakSummary to false
            )
            return
        }

        val firstRecord = currentRecords.first()
        val selectedIndex = selectedRecordIndex()
        val endIndex = when {
            targetIndex != null && targetIndex in currentRecords.indices -> targetIndex
            selectedIndex != null -> selectedIndex
            else -> currentRecords.size - 1
        }
        val targetRecord = currentRecords[endIndex]

        val startBat = firstRecord.batteryLevel
        val endBat = targetRecord.batteryLevel
        val batChange = endBat - startBat
        val changeSign = if (batChange >= 0) "+$batChange%" else "$batChange%"

        val energy = SessionStatsCalculator.calculateEnergy(currentRecords.take(endIndex + 1))
        val netMah = energy.netMah
        val netWh = energy.netWh

        val isSelected = selectedIndex != null && selectedIndex == endIndex
        val isDischarging = netMah < -0.5 || (netMah in -0.5..0.5 && batChange < 0)

        val mahWhStr = if (abs(netMah) > 0.5) {
            val absMah = abs(netMah).toInt()
            val absWhFormatted = String.format(Locale.getDefault(), "%.1f", abs(netWh))
            getString(R.string.power_charged_mah_wh, absMah, absWhFormatted)
        } else {
            ""
        }

        val text = when {
            isDischarging && isSelected -> getString(R.string.power_discharged_summary_selected, changeSign, startBat, endBat, mahWhStr)
            isDischarging -> getString(R.string.power_discharged_summary_total, changeSign, startBat, endBat, mahWhStr)
            isSelected -> getString(R.string.power_charged_summary_selected, changeSign, startBat, endBat, mahWhStr)
            else -> getString(R.string.power_charged_summary_total, changeSign, startBat, endBat, mahWhStr)
        }

        tvPowerSummaryText.text = text
        val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
        val stats = SessionStatsCalculator.calculate(currentRecords)
        val maxChargePower = stats?.maxChargePower
        val maxDischargePower = stats?.minDischargePower?.let(::abs)

        if (historySessionId == -1L && (maxChargePower != null || maxDischargePower != null)) {
            if (maxChargePower != null) {
                tvPowerPeakCharge.text = getString(R.string.power_peak_charge, maxChargePower)
            }
            if (maxDischargePower != null) {
                tvPowerPeakDischarge.text = getString(R.string.power_peak_discharge, maxDischargePower)
            }
            setViewsVisibleAnimated(
                layoutPowerSummaryBanner to true,
                layoutPowerPeakSummary to true,
                tvPowerPeakCharge to (maxChargePower != null),
                tvPowerPeakDischarge to (maxDischargePower != null)
            )
        } else {
            setViewsVisibleAnimated(
                layoutPowerSummaryBanner to true,
                layoutPowerPeakSummary to false
            )
        }
    }
    override fun onResume() {
        super.onResume()
        startLiveTextUpdateLoop()

        // Check and report background stats when returning to the foreground
        val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        val userExited = prefs.getBoolean(PrefKeys.USER_EXITED, false)
        if (userExited) {
            prefs.edit {
                putBoolean(PrefKeys.USER_EXITED, false)
                    .putBoolean(PrefKeys.APP_IN_BACKGROUND, false)
                    .putBoolean(PrefKeys.BG_STATS_RECORDED, false)
            }
            return
        }

        val enableBgReport = prefs.getBoolean(PrefKeys.ENABLE_BG_REPORT, true)
        val appInBackground = prefs.getBoolean(PrefKeys.APP_IN_BACKGROUND, false)
        val bgStatsRecorded = prefs.getBoolean(PrefKeys.BG_STATS_RECORDED, false)
        
        if (enableBgReport && appInBackground && bgStatsRecorded) {
            // Reset flags immediately
            prefs.edit {
                putBoolean(PrefKeys.APP_IN_BACKGROUND, false)
                    .putBoolean(PrefKeys.BG_STATS_RECORDED, false)
            }
            
            val startTime = prefs.getLong(PrefKeys.BACKGROUND_START_TIME, 0L)
            val startBattery = prefs.getInt(PrefKeys.BACKGROUND_START_BATTERY, -1)
            
            // Power ranges (charging & discharging)
            val minCP = prefs.getFloat(PrefKeys.BG_MIN_CHARGE_POWER, Float.MAX_VALUE)
            val maxCP = prefs.getFloat(PrefKeys.BG_MAX_CHARGE_POWER, -Float.MAX_VALUE)
            val storedMinDP = prefs.getFloat(PrefKeys.BG_MIN_DISCHARGE_POWER, Float.MAX_VALUE)
            val storedMaxDP = prefs.getFloat(PrefKeys.BG_MAX_DISCHARGE_POWER, -Float.MAX_VALUE)
            val minDP = if (storedMinDP >= 0f && storedMaxDP >= 0f) -storedMaxDP else storedMinDP
            val maxDP = if (storedMinDP >= 0f && storedMaxDP >= 0f) -storedMinDP else storedMaxDP
            
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
                setViewsVisibleAnimated(layoutBgReportBanner to true)
                btnBannerView.setOnClickListener {
                    setViewsVisibleAnimated(layoutBgReportBanner to false)
                    showDialogOnce()
                }
            }
        } else {
            // Just clear flags if not showing report
            prefs.edit {
                putBoolean(PrefKeys.APP_IN_BACKGROUND, false)
                    .putBoolean(PrefKeys.BG_STATS_RECORDED, false)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Save initial background state when activity goes to background (including screen lock)
        val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        val currentBattery = BatteryUtils.getBatteryLevel(this)
        prefs.edit {
            putBoolean(PrefKeys.APP_IN_BACKGROUND, true)
                .putBoolean(PrefKeys.BG_STATS_RECORDED, false)
                .putLong(PrefKeys.BACKGROUND_START_TIME, System.currentTimeMillis())
                .putInt(PrefKeys.BACKGROUND_START_BATTERY, currentBattery)
                // Power
                .putFloat(PrefKeys.BG_MIN_CHARGE_POWER, Float.MAX_VALUE)
                .putFloat(PrefKeys.BG_MAX_CHARGE_POWER, -Float.MAX_VALUE)
                .putFloat(PrefKeys.BG_MIN_DISCHARGE_POWER, Float.MAX_VALUE)
                .putFloat(PrefKeys.BG_MAX_DISCHARGE_POWER, -Float.MAX_VALUE)
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

        MaterialAlertDialogBuilder(this)
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
        val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(PrefKeys.IS_RECORDING, false)
    }

    private fun startLiveTextUpdateLoop() {
        liveTextUpdateJob?.cancel()
        liveTextUpdateJob = lifecycleScope.launch {
            while (true) {
                val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
                if (historySessionId == -1L && selectedRecordTimestamp == null) {
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    val batteryStatus = registerReceiver(null, filter)
                    if (batteryStatus != null) {
                        val batteryStatusValue = batteryStatus.getIntExtra(
                            BatteryManager.EXTRA_STATUS,
                            BatteryManager.BATTERY_STATUS_UNKNOWN
                        )
                        val voltage = BatteryUtils.getVoltage(batteryStatus)
                        val current = BatteryUtils.getCurrent(this@MainActivity, batteryStatus)
                        val power = BatteryFlow.signedPowerWatts(voltage, current)
                        val batteryLevel = BatteryUtils.getBatteryLevel(this@MainActivity, batteryStatus)
                        val displayState = BatteryFlow.displayState(
                            batteryStatusValue,
                            current,
                            batteryLevel
                        )

                        if (isRecording()) {
                            // During recording the latest database sample is authoritative.
                            refreshStatusForCurrentMode()
                        } else {
                            updateStatusTitle(false, realtimeState = displayState)
                            resetDashboardTextSize()
                            val valV = String.format(Locale.getDefault(), "%.2f", voltage)
                            val valC = String.format(Locale.getDefault(), "%.2f", current)
                            val valP = String.format(Locale.getDefault(), "%.2f", power)
                            val valB = String.format(Locale.getDefault(), "%d", batteryLevel)

                            tvCurrentVoltage.text = formatValueText(
                                getString(R.string.voltage_label), valV, "V", colorRealtime
                            )
                            tvCurrentCurrent.text = formatValueText(
                                getString(R.string.current_label), valC, "A", colorRealtime
                            )
                            tvCurrentPower.text = formatValueText(
                                getString(R.string.power_label), valP, "W", colorRealtime
                            )
                            tvCurrentProtocol.text = formatValueText(
                                getString(R.string.battery_label), valB, "%", colorRealtime
                            )
                            updateMaxChargingLimitText(batteryStatus)
                        }
                    } else {
                        updateStatusTitle(false, realtimeState = BatteryDisplayState.IDLE)
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
                        writer.write("时间,电压(V),电流(A),功率(W),电量(%),电池状态,快充上限,屏幕状态\n")
                        
                        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        for (record in currentRecords) {
                            val timeStr = format.format(Date(record.timestamp))
                            val screenStateStr = when (record.screenState) {
                                0 -> "锁屏"
                                1 -> "亮屏"
                                else -> "未知"
                            }
                            val batteryStatusStr = batteryStatusLabel(this@MainActivity, record.batteryStatus)
                            val limitStr = PowerLimitFormatter.formatLimitPower(record.maxVoltage, record.maxCurrent)
                            writer.write(String.format(
                                Locale.getDefault(),
                                "%s,%.2f,%.2f,%.2f,%d,%s,%s,%s\n",
                                timeStr, record.voltage, record.current, record.power, record.batteryLevel,
                                batteryStatusStr, limitStr, screenStateStr
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
        menuDeleteSegment?.isVisible = selectedRecordTimestamp != null
        
        // Hide theme/language options if we are viewing historical details
        val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
        val menuTheme = menu.findItem(R.id.menu_theme)
        val menuLanguage = menu.findItem(R.id.menu_language)
        if (historySessionId != -1L) {
            menuTheme?.isVisible = false
            menuLanguage?.isVisible = false
        } else {
            menuTheme?.isVisible = true
            menuLanguage?.isVisible = true
            // Set correct icon for current theme
            val themePrefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
            val currentTheme = themePrefs.getInt(PrefKeys.THEME_MODE, 0)
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
        val historySessionId = intent.getLongExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, -1L)
        if (item.itemId == android.R.id.home) {
            if (historySessionId != -1L) {
                finish()
                return true
            }
        }
        
        if (item.itemId == R.id.menu_delete_segment) {
            val record = selectedRecord() ?: return true

            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
            val options = arrayOf(
                getString(R.string.crop_delete_before),
                getString(R.string.crop_delete_after),
                getString(R.string.crop_delete_single)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.crop_data_title, timeStr))
                .setItems(options) { _, which ->
                    lifecycleScope.launch {
                        val repo = ChargeRepository.getInstance(this@MainActivity)
                        val sessionStart = record.sessionId
                        when (which) {
                            0 -> { // Delete before
                                repo.deleteRecordsBefore(sessionStart, record.timestamp)
                            }
                            1 -> { // Delete after
                                repo.deleteRecordsAfter(sessionStart, record.timestamp)
                            }
                            2 -> { // Delete single
                                repo.deleteSingleRecord(sessionStart, record.timestamp)
                            }
                        }
                        clearChartSelection()
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
        
        val themePrefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        val activeTheme = themePrefs.getInt(PrefKeys.THEME_MODE, 0)
        var newTheme: Int? = null
        when (item.itemId) {
            R.id.menu_theme_auto -> newTheme = 0
            R.id.menu_theme_light -> newTheme = 1
            R.id.menu_theme_dark -> newTheme = 2
        }
        
        if (newTheme != null && newTheme != activeTheme) {
            themePrefs.edit { putInt(PrefKeys.THEME_MODE, newTheme) }
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
        selectedRecordTimestamp?.let { outState.putLong("SELECTED_RECORD_TIMESTAMP", it) }
        outState.putBoolean("MAX_CHARGING_LIMIT_VISIBLE", maxChargingLimitTargetVisible)
        maxChargingLimitVoltage?.let { outState.putFloat("MAX_CHARGING_LIMIT_VOLTAGE", it) }
        maxChargingLimitCurrent?.let { outState.putFloat("MAX_CHARGING_LIMIT_CURRENT", it) }
        maxChargingLimitRangeMin?.let { outState.putFloat("MAX_CHARGING_LIMIT_RANGE_MIN", it) }
        maxChargingLimitRangeMax?.let { outState.putFloat("MAX_CHARGING_LIMIT_RANGE_MAX", it) }
    }


    override fun onDestroy() {
        maxChargingLimitAnimator?.removeAllListeners()
        maxChargingLimitAnimator?.cancel()
        maxChargingLimitAnimator = null
        val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (isFinishing) {
            prefs.edit { putBoolean(PrefKeys.USER_EXITED, true) }
        }
        super.onDestroy()
    }
}
