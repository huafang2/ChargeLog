package per.jau.chargelog

import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import per.jau.chargelog.constants.PrefKeys
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.data.ChargeRepository
import per.jau.chargelog.service.ChargeLoggingService
import per.jau.chargelog.utils.BatteryHealthEstimate
import per.jau.chargelog.utils.BatteryHealthEstimator
import per.jau.chargelog.utils.BatteryHealthResult
import per.jau.chargelog.utils.BatteryUtils
import per.jau.chargelog.utils.SessionStatsCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit


class HistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var adapter: HistoryAdapter
    private lateinit var btnClearAll: Button
    private lateinit var btnEstimateHealth: Button

    private val exportJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            writeHistoryJsonToUri(uri)
        }
    }

    private val importJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            readHistoryJsonFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val mainView = findViewById<View>(R.id.history_main)
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

        val repo = ChargeRepository.getInstance(this)

        rvHistory = findViewById(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(this)

        btnClearAll = findViewById(R.id.btnClearAll)
        btnEstimateHealth = findViewById(R.id.btnEstimateHealth)
        btnEstimateHealth.setOnClickListener { beginHealthEstimate() }
        btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_clear_all_title)
                .setMessage(R.string.dialog_clear_all_msg)
                .setPositiveButton(R.string.clear) { _, _ ->
                    lifecycleScope.launch {
                        repo.deleteAllRecords()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        adapter = HistoryAdapter(
            onClick = { session ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra(PrefKeys.EXTRA_HISTORY_SESSION_ID, session.sessionId)
                }
                startActivity(intent)
            },
            onLongClick = { session ->
                val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
                val isActiveSession = prefs.getBoolean(PrefKeys.IS_RECORDING, false) &&
                        prefs.getLong(PrefKeys.CURRENT_SESSION_START, 0L) == session.sessionId
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_delete_session_title)
                    .setMessage(
                        if (isActiveSession) R.string.dialog_delete_active_session_msg
                        else R.string.dialog_delete_session_msg
                    )
                    .setPositiveButton(
                        if (isActiveSession) R.string.stop_and_delete else R.string.menu_delete_segment
                    ) { _, _ ->
                        if (isActiveSession) {
                            prefs.edit {
                                putBoolean(PrefKeys.IS_RECORDING, false)
                                putBoolean(PrefKeys.FORCE_NEW_SESSION, true)
                            }
                            startForegroundService(Intent(this, ChargeLoggingService::class.java).apply {
                                action = ChargeLoggingService.ACTION_STOP_RECORDING
                            })
                        }
                        lifecycleScope.launch {
                            val deleted = repo.deleteRecordsBySession(session.sessionId)
                            val message = if (deleted > 0) {
                                getString(R.string.history_delete_success, deleted)
                            } else {
                                getString(R.string.history_delete_not_found)
                            }
                            Toast.makeText(this@HistoryActivity, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )
        rvHistory.adapter = adapter

        loadHistory()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == R.id.menu_import_history) {
            importHistory()
            return true
        }
        if (item.itemId == R.id.menu_export_history) {
            exportHistory()
            return true
        }
        return super.onOptionsItemSelected(item)
    }


    private fun loadHistory() {
        val repo = ChargeRepository.getInstance(this)
        lifecycleScope.launch {
            repo.getAllRecords().collectLatest { records ->
                if (records.isEmpty()) {
                    adapter.submitList(emptyList())
                    return@collectLatest
                }
                
                // Group by sessionId
                val grouped = records.groupBy { it.sessionId }
                val sessions = grouped.mapNotNull { (sessionId, sessionRecords) ->
                    val sorted = sessionRecords.sortedBy { it.timestamp }
                    val stats = SessionStatsCalculator.calculate(sorted) ?: return@mapNotNull null
                    
                    ChargeSession(
                        startTime = sorted.first().timestamp,
                        endTime = sorted.last().timestamp,
                        sessionId = sessionId,
                        startBattery = stats.startBattery,
                        endBattery = stats.endBattery,
                        minChargePower = stats.minChargePower,
                        maxChargePower = stats.maxChargePower,
                        minDischargePower = stats.minDischargePower,
                        maxDischargePower = stats.maxDischargePower
                    )
                }
                
                adapter.submitList(sessions.sortedByDescending { it.sessionId })
            }
        }
    }

    private fun exportHistory() {
        val fileName = "ChargeLog_Backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"
        exportJsonLauncher.launch(fileName)
    }

    private fun beginHealthEstimate() {
        val selectedIds = adapter.selectedSessionIds()
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, R.string.health_select_records, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val records = ChargeRepository.getInstance(this@HistoryActivity)
                .getAllRecordsOnce()
                .filter { it.sessionId in selectedIds }
                .groupBy { it.sessionId }
                .values
                .map { it.sortedBy(ChargeRecord::timestamp) }
            val systemCapacity = BatteryUtils.getDesignCapacityMah()
            launch(Dispatchers.Main) {
                showCapacityInputDialog(records, systemCapacity)
            }
        }
    }

    private fun showCapacityInputDialog(
        sessions: List<List<ChargeRecord>>,
        systemCapacityMah: Float?
    ) {
        val prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        val manualCapacity = prefs.getFloat(PrefKeys.RATED_CAPACITY_MAH, 0f).takeIf { it > 0f }
        val input = EditText(this).apply {
            hint = getString(R.string.health_rated_capacity_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(manualCapacity?.let { String.format(Locale.US, "%.0f", it) } ?: "")
            selectAll()
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val systemText = systemCapacityMah?.let {
            getString(R.string.health_system_capacity, it)
        } ?: getString(R.string.health_system_capacity_unavailable)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.health_capacity_title)
            .setMessage(getString(R.string.health_capacity_input_message, sessions.size, systemText))
            .setView(container)
            .setPositiveButton(R.string.health_calculate) { _, _ ->
                val rawInput = input.text.toString().trim()
                val typed = rawInput.toFloatOrNull()
                if (rawInput.isNotEmpty() && (typed == null || typed !in 300f..30_000f)) {
                    Toast.makeText(this, R.string.health_invalid_rated_capacity, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (typed != null && typed in 300f..30_000f) {
                    prefs.edit { putFloat(PrefKeys.RATED_CAPACITY_MAH, typed) }
                } else if (rawInput.isEmpty()) {
                    prefs.edit { remove(PrefKeys.RATED_CAPACITY_MAH) }
                }
                val rated = typed?.takeIf { it in 300f..30_000f }
                    ?: systemCapacityMah
                showHealthResult(BatteryHealthEstimator.estimate(sessions, rated), rated)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHealthResult(result: BatteryHealthResult, ratedCapacityMah: Float?) {
        val message = when (result) {
            BatteryHealthResult.Invalid -> getString(R.string.health_invalid_net_data)
            is BatteryHealthResult.Ready -> {
                val estimate = result.estimate
                val confidence = when (estimate.confidence) {
                    BatteryHealthEstimate.Confidence.HIGH -> getString(R.string.health_confidence_high)
                    BatteryHealthEstimate.Confidence.MEDIUM -> getString(R.string.health_confidence_medium)
                    BatteryHealthEstimate.Confidence.LOW -> getString(R.string.health_confidence_low)
                }
                val details = if (ratedCapacityMah != null && estimate.healthPercent != null) {
                    getString(
                        R.string.health_result_with_rating,
                        estimate.estimatedFullCapacityMah,
                        ratedCapacityMah,
                        estimate.healthPercent,
                        estimate.totalBatterySpanPercent,
                        confidence
                    )
                } else {
                    getString(
                        R.string.health_result_without_rating,
                        estimate.estimatedFullCapacityMah,
                        estimate.totalBatterySpanPercent,
                        confidence
                    )
                }
                buildString {
                    append(details)
                    append("\n\n")
                    append(
                        getString(
                            R.string.health_charge_breakdown,
                            estimate.positiveChargedCapacityMah,
                            estimate.dischargedCapacityMah,
                            estimate.netChargedCapacityMah
                        )
                    )
                    append("\n")
                    append(
                        getString(
                            if (estimate.hasUnknownBatteryStatus) {
                                R.string.health_status_unknown
                            } else {
                                R.string.health_status_known
                            }
                        )
                    )
                    if (estimate.totalBatterySpanPercent <= BatteryHealthEstimator.LOW_CONFIDENCE_SPAN_PERCENT) {
                        append(
                            "\n\n${getString(
                                R.string.health_low_span_warning,
                                estimate.totalBatterySpanPercent
                            )}"
                        )
                    }
                    if (estimate.hasLegacyFullTail) {
                        append("\n\n${getString(R.string.health_legacy_full_tail_notice)}")
                    }
                    append("\n\n${getString(R.string.health_signed_result_notice)}")
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.health_capacity_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun writeHistoryJsonToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repo = ChargeRepository.getInstance(this@HistoryActivity)
                val records = repo.getAllRecordsOnce()
                val grouped = records.groupBy { it.sessionId }
                
                val jsonArray = JSONArray()
                for ((sessionId, sessionRecords) in grouped) {
                    val sessionObj = JSONObject()
                    sessionObj.put("id", sessionId)
                    sessionObj.put("sessionId", sessionId)
                    
                    val recordsArray = JSONArray()
                    for (rec in sessionRecords) {
                        val recObj = JSONObject()
                        recObj.put("timestamp", rec.timestamp)
                        recObj.put("voltage", rec.voltage.toDouble())
                        recObj.put("current", rec.current.toDouble())
                        recObj.put("power", rec.power.toDouble())
                        recObj.put("batteryLevel", rec.batteryLevel)
                        recObj.put("screenState", rec.screenState)
                        recObj.put("batteryStatus", rec.batteryStatus)
                        
                        if (rec.maxVoltage != null) recObj.put("maxVoltage", rec.maxVoltage.toDouble())
                        if (rec.maxCurrent != null) recObj.put("maxCurrent", rec.maxCurrent.toDouble())
                        
                        recordsArray.put(recObj)
                    }
                    sessionObj.put("records", recordsArray)
                    jsonArray.put(sessionObj)
                }
                
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.bufferedWriter().use { writer ->
                        writer.write(jsonArray.toString(2))
                    }
                }
                
                launch(Dispatchers.Main) {
                    Toast.makeText(this@HistoryActivity, getString(R.string.export_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) {
                    Toast.makeText(this@HistoryActivity, getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importHistory() {
        importJsonLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
    }

    private fun readHistoryJsonFromUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                } ?: throw Exception("Failed to read file")
                
                val jsonArray = JSONArray(content)
                val repo = ChargeRepository.getInstance(this@HistoryActivity)
                val localRecords = repo.getAllRecordsOnce()
                
                val localSessionMap = localRecords.groupBy { it.sessionId }
                    .mapValues { (_, recs) -> recs.associateBy { it.timestamp } }
                
                val recordsToInsert = mutableListOf<ChargeRecord>()
                val conflicts = mutableListOf<Pair<ChargeRecord, ChargeRecord>>()
                
                for (i in 0 until jsonArray.length()) {
                    val sessionObj = jsonArray.getJSONObject(i)
                    val importSessionId = if (sessionObj.has("id")) sessionObj.getLong("id") else sessionObj.getLong("sessionId")
                    val recordsArray = sessionObj.getJSONArray("records")
                    val localRecordMap = localSessionMap[importSessionId]
                    
                    for (j in 0 until recordsArray.length()) {
                        val recObj = recordsArray.getJSONObject(j)
                        val timestamp = recObj.getLong("timestamp")
                        val voltage = recObj.getDouble("voltage").toFloat()
                        val current = recObj.getDouble("current").toFloat()
                        val power = recObj.getDouble("power").toFloat()
                        val batteryLevel = recObj.getInt("batteryLevel")
                        val screenState = recObj.optInt("screenState", 2)
                        val batteryStatus = recObj.optInt("batteryStatus", BatteryManager.BATTERY_STATUS_UNKNOWN)
                        
                        val maxVoltage = if (recObj.has("maxVoltage") && !recObj.isNull("maxVoltage")) recObj.getDouble("maxVoltage").toFloat() else null
                        val maxCurrent = if (recObj.has("maxCurrent") && !recObj.isNull("maxCurrent")) recObj.getDouble("maxCurrent").toFloat() else null
                        
                        val importedRecord = ChargeRecord(
                            sessionId = importSessionId,
                            timestamp = timestamp,
                            voltage = voltage,
                            current = current,
                            power = power,
                            batteryLevel = batteryLevel,
                            screenState = screenState,
                            maxVoltage = maxVoltage,
                            maxCurrent = maxCurrent,
                            batteryStatus = batteryStatus
                        )
                        
                        if (localRecordMap == null) {
                            recordsToInsert.add(importedRecord)
                        } else {
                            val localRecord = localRecordMap[timestamp]
                            if (localRecord == null) {
                                recordsToInsert.add(importedRecord)
                            } else {
                                val isConsistent = localRecord.voltage == voltage &&
                                        localRecord.current == current &&
                                        localRecord.power == power &&
                                        localRecord.batteryLevel == batteryLevel &&
                                        localRecord.screenState == screenState &&
                                        localRecord.maxVoltage == maxVoltage &&
                                        localRecord.maxCurrent == maxCurrent &&
                                        localRecord.batteryStatus == batteryStatus
                                
                                if (!isConsistent) {
                                    conflicts.add(Pair(localRecord, importedRecord))
                                }
                            }
                        }
                    }
                }
                
                launch(Dispatchers.Main) {
                    if (conflicts.isNotEmpty()) {
                        MaterialAlertDialogBuilder(this@HistoryActivity)
                            .setTitle(getString(R.string.conflict_dialog_title))
                            .setMessage(getString(R.string.conflict_dialog_msg, conflicts.size))
                            .setPositiveButton(getString(R.string.conflict_use_imported)) { _, _ ->
                                performImport(recordsToInsert, conflicts, useImported = true)
                            }
                            .setNegativeButton(getString(R.string.conflict_use_local)) { _, _ ->
                                performImport(recordsToInsert, conflicts, useImported = false)
                            }
                            .setNeutralButton(R.string.cancel, null)
                            .show()
                    } else {
                        if (recordsToInsert.isEmpty()) {
                            Toast.makeText(this@HistoryActivity, getString(R.string.import_no_new_data), Toast.LENGTH_SHORT).show()
                        } else {
                            performImport(recordsToInsert, emptyList(), useImported = false)
                        }
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) {
                    Toast.makeText(this@HistoryActivity, getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performImport(
        recordsToInsert: List<ChargeRecord>,
        conflicts: List<Pair<ChargeRecord, ChargeRecord>>,
        useImported: Boolean
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repo = ChargeRepository.getInstance(this@HistoryActivity)
                
                if (recordsToInsert.isNotEmpty()) {
                    repo.insertAll(recordsToInsert)
                }
                
                if (conflicts.isNotEmpty()) {
                    if (useImported) {
                        val recordsToUpdate = conflicts.map { (local, imported) ->
                            imported.copy(id = local.id)
                        }
                        repo.updateAll(recordsToUpdate)
                    }
                }
                
                launch(Dispatchers.Main) {
                    val totalImported = recordsToInsert.size + (if (useImported) conflicts.size else 0)
                    Toast.makeText(this@HistoryActivity, getString(R.string.import_success_msg, totalImported), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) {
                    Toast.makeText(this@HistoryActivity, getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
