package per.jau.chargelog

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** 历史记录列表 Adapter。 */
class HistoryAdapter(
    private val onClick: (ChargeSession) -> Unit,
    private val onLongClick: (ChargeSession) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var sessions = listOf<ChargeSession>()
    private val selectedIds = mutableSetOf<Long>()

    fun selectedSessionIds(): Set<Long> = selectedIds.toSet()

    @SuppressLint("NotifyDataSetChanged")
    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<ChargeSession>) {
        sessions = list
        val previousSelection = selectedIds.toSet()
        selectedIds.retainAll(list.mapTo(mutableSetOf()) { it.sessionId })
        notifyDataSetChanged()
        if (previousSelection != selectedIds) onSelectionChanged(selectedSessionIds())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_session, parent, false)
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
            powerBuilder.append(
                context.getString(
                    R.string.history_charge_power,
                    session.minChargePower,
                    session.maxChargePower
                )
            )
        }
        if (session.minDischargePower != null && session.maxDischargePower != null) {
            if (powerBuilder.isNotEmpty()) powerBuilder.append("\n")
            powerBuilder.append(
                context.getString(
                    R.string.history_discharge_power,
                    session.minDischargePower,
                    session.maxDischargePower
                )
            )
        }
        if (powerBuilder.isEmpty()) {
            powerBuilder.append(context.getString(R.string.history_power_empty))
        }
        holder.tvPowerRange.text = powerBuilder.toString()

        // Battery range
        val batChange = session.endBattery - session.startBattery
        val changeSign = if (batChange >= 0) "+$batChange%" else "$batChange%"
        holder.tvBatteryRange.text = context.getString(
            R.string.history_battery_range,
            session.startBattery,
            session.endBattery,
            changeSign
        )

        holder.checkHealthSelection.setOnCheckedChangeListener(null)
        holder.checkHealthSelection.isChecked = session.sessionId in selectedIds
        holder.checkHealthSelection.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds.add(session.sessionId) else selectedIds.remove(session.sessionId)
            onSelectionChanged(selectedSessionIds())
        }

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
        val checkHealthSelection: CheckBox = view.findViewById(R.id.checkHealthSelection)
    }
}
