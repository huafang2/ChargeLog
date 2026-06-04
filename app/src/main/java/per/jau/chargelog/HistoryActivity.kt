package per.jau.chargelog

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import per.jau.chargelog.data.ChargeDatabase
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
                .setTitle("清空所有历史数据")
                .setMessage("确定要清空所有充电历史记录吗？该操作无法恢复！")
                .setPositiveButton("清空") { _, _ ->
                    lifecycleScope.launch {
                        dao.deleteAllRecords()
                    }
                }
                .setNegativeButton("取消", null)
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
                    .setTitle("删除历史记录")
                    .setMessage("确定要删除这段充电历史记录吗？此操作无法撤销。")
                    .setPositiveButton("删除") { _, _ ->
                        lifecycleScope.launch {
                            dao.deleteRecordsBySession(session.sessionId)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        rvHistory.adapter = adapter

        loadHistory()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
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
}

class HistoryAdapter(
    private val onClick: (ChargeSession) -> Unit,
    private val onLongClick: (ChargeSession) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var sessions = listOf<ChargeSession>()
    private val dateFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())

    fun submitList(list: List<ChargeSession>) {
        sessions = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        val startStr = dateFormat.format(Date(session.startTime))
        val endStr = dateFormat.format(Date(session.endTime))
        val durationMins = (session.endTime - session.startTime) / 60000

        holder.tvSessionTime.text = "$startStr - $endStr"
        holder.tvSessionDuration.text = "时长: $durationMins 分钟"
        
        // Build power range string
        val powerBuilder = StringBuilder()
        if (session.minChargePower != null && session.maxChargePower != null) {
            powerBuilder.append(String.format(Locale.getDefault(), "充电功率: 最小 %.1f W - 最大 %.1f W", session.minChargePower, session.maxChargePower))
        }
        if (session.minDischargePower != null && session.maxDischargePower != null) {
            if (powerBuilder.isNotEmpty()) powerBuilder.append("\n")
            powerBuilder.append(String.format(Locale.getDefault(), "放电功率: 最小 %.1f W - 最大 %.1f W", session.minDischargePower, session.maxDischargePower))
        }
        if (powerBuilder.isEmpty()) {
            powerBuilder.append("功率: --")
        }
        holder.tvPowerRange.text = powerBuilder.toString()
        
        // Battery range
        val batChange = session.endBattery - session.startBattery
        val changeSign = if (batChange >= 0) "+$batChange%" else "$batChange%"
        holder.tvBatteryRange.text = "电量变化: ${session.startBattery}% -> ${session.endBattery}% ($changeSign)"

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
