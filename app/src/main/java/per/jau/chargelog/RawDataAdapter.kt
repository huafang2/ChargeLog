package per.jau.chargelog

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.utils.PowerLimitFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 将 BatteryManager 状态常量转换为对应的本地化标签。 */
internal fun batteryStatusLabel(context: Context, status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> context.getString(R.string.battery_status_charging)
    BatteryManager.BATTERY_STATUS_FULL -> context.getString(R.string.battery_status_full)
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> context.getString(R.string.battery_status_not_charging)
    BatteryManager.BATTERY_STATUS_DISCHARGING -> context.getString(R.string.battery_status_discharging)
    else -> context.getString(R.string.battery_status_unknown)
}

/** 原始数据对话框中的列表 Adapter，展示每条 ChargeRecord 的详情。 */
class RawDataAdapter(private val records: List<ChargeRecord>) :
    RecyclerView.Adapter<RawDataAdapter.ViewHolder>() {

    private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_raw_data_row, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            val record = records[position]
            holder.tvRawTime.text = format.format(Date(record.timestamp))
            holder.tvRawVoltage.text = String.format(Locale.getDefault(), "%.2f", record.voltage)
            holder.tvRawCurrent.text = String.format(Locale.getDefault(), "%.2f", record.current)
            holder.tvRawPower.text = String.format(Locale.getDefault(), "%.2f", record.power)
            holder.tvRawBattery.text = record.batteryLevel.toString()
            holder.tvRawBatteryStatus.text =
                batteryStatusLabel(holder.itemView.context, record.batteryStatus)
            holder.tvRawScreenState.text = when (record.screenState) {
                0 -> "锁屏"
                1 -> "亮屏"
                else -> "未知"
            }
            holder.tvRawMaxPower.text =
                PowerLimitFormatter.formatLimitPower(record.maxVoltage, record.maxCurrent)
        } catch (e: Exception) {
            android.util.Log.e("RawDataAdapter", "Error binding raw data row", e)
        }
    }

    override fun getItemCount() = records.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRawTime: TextView = view.findViewById(R.id.tvRawTime)
        val tvRawVoltage: TextView = view.findViewById(R.id.tvRawVoltage)
        val tvRawCurrent: TextView = view.findViewById(R.id.tvRawCurrent)
        val tvRawPower: TextView = view.findViewById(R.id.tvRawPower)
        val tvRawBattery: TextView = view.findViewById(R.id.tvRawBattery)
        val tvRawBatteryStatus: TextView = view.findViewById(R.id.tvRawBatteryStatus)
        val tvRawMaxPower: TextView = view.findViewById(R.id.tvRawMaxPower)
        val tvRawScreenState: TextView = view.findViewById(R.id.tvRawScreenState)
    }
}
