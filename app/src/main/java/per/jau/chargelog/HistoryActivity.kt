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

data class ChargeSession(
    val startTime: Long,
    val endTime: Long,
    val maxPower: Float,
    val minPower: Float,
    val maxVoltage: Float,
    val minVoltage: Float,
    val maxCurrent: Float,
    val minCurrent: Float,
    val sessionId: Long
)

class HistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var adapter: HistoryAdapter
    private lateinit var btnClearAll: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // Enable edge-to-edge support for consistent UI with MainActivity
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

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
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                    val maxP = sorted.maxOf { it.power }
                    val minP = sorted.minOf { it.power }
                    val maxV = sorted.maxOf { it.voltage }
                    val minV = sorted.minOf { it.voltage }
                    val maxC = sorted.maxOf { it.current }
                    val minC = sorted.minOf { it.current }
                    
                    ChargeSession(
                        startTime = start,
                        endTime = end,
                        maxPower = maxP,
                        minPower = minP,
                        maxVoltage = maxV,
                        minVoltage = minV,
                        maxCurrent = maxC,
                        minCurrent = minC,
                        sessionId = sessionId
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
        
        holder.tvVoltageRange.text = String.format(Locale.getDefault(), "电压: %.1f - %.1f V", session.minVoltage, session.maxVoltage)
        holder.tvCurrentRange.text = String.format(Locale.getDefault(), "电流: %.2f - %.2f A", session.minCurrent, session.maxCurrent)
        holder.tvPowerRange.text = String.format(Locale.getDefault(), "功率: %.1f W - %.1f W", session.minPower, session.maxPower)

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
        val tvVoltageRange: TextView = view.findViewById(R.id.tvVoltageRange)
        val tvCurrentRange: TextView = view.findViewById(R.id.tvCurrentRange)
        val tvPowerRange: TextView = view.findViewById(R.id.tvPowerRange)
    }
}
