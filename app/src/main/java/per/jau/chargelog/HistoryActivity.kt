package per.jau.chargelog

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import per.jau.chargelog.data.ChargeDatabase
import per.jau.chargelog.data.ChargeRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ChargeSession(
    val startTime: Long,
    val endTime: Long,
    val sessionId: Long,
    val startBattery: Int,
    val endBattery: Int,
    val minChargePower: Float?,
    val maxChargePower: Float?,
    val minDischargePower: Float?,
    val maxDischargePower: Float?
)

class HistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var adapter: HistoryAdapter
    private lateinit var btnClearAll: Button

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

        // Enable standard action bar back arrow if present
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

        val dao = ChargeDatabase.getDatabase(this).chargeDao()

        rvHistory = findViewById(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(this)

        btnClearAll = findViewById(R.id.btnClearAll)
        btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_all_title)
                .setMessage(R.string.dialog_clear_all_msg)
                .setPositiveButton(R.string.clear) { _, _ ->
                    lifecycleScope.launch {
                        dao.deleteAllRecords()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        adapter = HistoryAdapter(
            onClick = { session ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("HISTORY_SESSION_ID", session.sessionId)
                }
                startActivity(intent)
            },
            onLongClick = { session ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_delete_session_title)
                    .setMessage(R.string.dialog_delete_session_msg)
                    .setPositiveButton(R.string.menu_delete_segment) { _, _ ->
                        lifecycleScope.launch {
                            dao.deleteRecordsBySession(session.sessionId)
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
        val dao = ChargeDatabase.getDatabase(this).chargeDao()
        lifecycleScope.launch {
            dao.getAllRecords().collectLatest { records ->
                if (records.isEmpty()) {
                    adapter.submitList(emptyList())
                    return@collectLatest
                }
                
                // Group by sessionId
                val grouped = records.groupBy { it.sessionId }
                val sessions = grouped.map { (sessionId, sessionRecords) ->
                    val sorted = sessionRecords.sortedBy { it.timestamp }
                    val start = sorted.first().timestamp
                    val end = sorted.last().timestamp
                    val startBat = sorted.first().batteryLevel
                    val endBat = sorted.last().batteryLevel
                    
                    // Split charge and discharge
                    val chargePoints = sorted.filter { it.power >= 0 }
                    val dischargePoints = sorted.filter { it.power < 0 }
                    
                    val minChargePower = if (chargePoints.isNotEmpty()) chargePoints.minOf { it.power } else null
                    val maxChargePower = if (chargePoints.isNotEmpty()) chargePoints.maxOf { it.power } else null
                    
                    val minDischargePower = if (dischargePoints.isNotEmpty()) dischargePoints.minOf { abs(it.power) } else null
                    val maxDischargePower = if (dischargePoints.isNotEmpty()) dischargePoints.maxOf { abs(it.power) } else null
                    
                    ChargeSession(
                        startTime = start,
                        endTime = end,
                        sessionId = sessionId,
                        startBattery = startBat,
                        endBattery = endBat,
                        minChargePower = minChargePower,
                        maxChargePower = maxChargePower,
                        minDischargePower = minDischargePower,
                        maxDischargePower = maxDischargePower
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

    private fun writeHistoryJsonToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = ChargeDatabase.getDatabase(this@HistoryActivity).chargeDao()
                val records = dao.getAllRecordsOnce()
                val grouped = records.groupBy { it.sessionId }
                
                val jsonArray = org.json.JSONArray()
                for ((sessionId, sessionRecords) in grouped) {
                    val sessionObj = org.json.JSONObject()
                    sessionObj.put("id", sessionId)
                    sessionObj.put("sessionId", sessionId)
                    
                    val recordsArray = org.json.JSONArray()
                    for (rec in sessionRecords) {
                        val recObj = org.json.JSONObject()
                        recObj.put("timestamp", rec.timestamp)
                        recObj.put("voltage", rec.voltage.toDouble())
                        recObj.put("current", rec.current.toDouble())
                        recObj.put("power", rec.power.toDouble())
                        recObj.put("batteryLevel", rec.batteryLevel)
                        recObj.put("screenState", rec.screenState)
                        
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
                
                val jsonArray = org.json.JSONArray(content)
                val dao = ChargeDatabase.getDatabase(this@HistoryActivity).chargeDao()
                val localRecords = dao.getAllRecordsOnce()
                
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
                        
                        val maxVoltage = if (recObj.isNull("maxVoltage")) null else recObj.optDouble("maxVoltage").toFloat()
                        val maxCurrent = if (recObj.isNull("maxCurrent")) null else recObj.optDouble("maxCurrent").toFloat()
                        
                        val importedRecord = ChargeRecord(
                            sessionId = importSessionId,
                            timestamp = timestamp,
                            voltage = voltage,
                            current = current,
                            power = power,
                            batteryLevel = batteryLevel,
                            screenState = screenState,
                            maxVoltage = maxVoltage,
                            maxCurrent = maxCurrent
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
                                        localRecord.maxCurrent == maxCurrent
                                
                                if (!isConsistent) {
                                    conflicts.add(Pair(localRecord, importedRecord))
                                }
                            }
                        }
                    }
                }
                
                launch(Dispatchers.Main) {
                    if (conflicts.isNotEmpty()) {
                        AlertDialog.Builder(this@HistoryActivity)
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
                val dao = ChargeDatabase.getDatabase(this@HistoryActivity).chargeDao()
                
                if (recordsToInsert.isNotEmpty()) {
                    dao.insertAll(recordsToInsert)
                }
                
                if (useImported && conflicts.isNotEmpty()) {
                    val recordsToUpdate = conflicts.map { (local, imported) ->
                        imported.copy(id = local.id)
                    }
                    dao.updateAll(recordsToUpdate)
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

class HistoryAdapter(
    private val onClick: (ChargeSession) -> Unit,
    private val onLongClick: (ChargeSession) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var sessions = listOf<ChargeSession>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<ChargeSession>) {
        sessions = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_session, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        val context = holder.itemView.context
        val durationMins = (session.endTime - session.startTime) / 60000

        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        holder.tvSessionTime.text = format.format(Date(session.startTime))
        holder.tvSessionDuration.text = context.getString(R.string.duration_mins, durationMins)
        
        // Build power range string
        val powerBuilder = StringBuilder()
        if (session.minChargePower != null && session.maxChargePower != null) {
            powerBuilder.append(context.getString(R.string.history_charge_power, session.minChargePower, session.maxChargePower))
        }
        if (session.minDischargePower != null && session.maxDischargePower != null) {
            if (powerBuilder.isNotEmpty()) powerBuilder.append("\n")
            powerBuilder.append(context.getString(R.string.history_discharge_power, session.minDischargePower, session.maxDischargePower))
        }
        if (powerBuilder.isEmpty()) {
            powerBuilder.append(context.getString(R.string.history_power_empty))
        }
        holder.tvPowerRange.text = powerBuilder.toString()
        
        // Battery range
        val batChange = session.endBattery - session.startBattery
        val changeSign = if (batChange >= 0) "+$batChange%" else "$batChange%"
        holder.tvBatteryRange.text = context.getString(R.string.history_battery_range, session.startBattery, session.endBattery, changeSign)

        holder.itemView.setOnClickListener {
            onClick(session)
        }
        holder.itemView.setOnLongClickListener {
            onLongClick(session)
            true
        }
    }

    override fun getItemCount() = sessions.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSessionTime: TextView = view.findViewById(R.id.tvSessionTime)
        val tvSessionDuration: TextView = view.findViewById(R.id.tvSessionDuration)
        val tvPowerRange: TextView = view.findViewById(R.id.tvPowerRange)
        val tvBatteryRange: TextView = view.findViewById(R.id.tvBatteryRange)
    }
}
