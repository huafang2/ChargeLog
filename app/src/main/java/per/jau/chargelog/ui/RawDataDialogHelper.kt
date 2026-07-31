package per.jau.chargelog.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import per.jau.chargelog.R
import per.jau.chargelog.RawDataAdapter
import per.jau.chargelog.data.ChargeRecord
import per.jau.chargelog.utils.SessionStatsCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原始数据对话框弹窗构建与展示逻辑 helper。
 */
object RawDataDialogHelper {

    fun show(
        context: Context,
        recordsSnapshot: List<ChargeRecord>,
        onExportCsv: (fileName: String) -> Unit
    ) {
        if (recordsSnapshot.isEmpty()) {
            Toast.makeText(context, "暂无数据", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_raw_data, null)
            val rvRawData = dialogView.findViewById<RecyclerView>(R.id.rvRawData)
            rvRawData.layoutManager = LinearLayoutManager(context)
            rvRawData.adapter = RawDataAdapter(recordsSnapshot)

            val dialog = MaterialAlertDialogBuilder(context)
                .setView(dialogView)
                .create()

            val tvRawSummary = dialogView.findViewById<TextView>(R.id.tvRawSummary)
            val stats = SessionStatsCalculator.calculate(recordsSnapshot)
            val summaryBuilder = StringBuilder()

            if (stats != null) {
                if (stats.minChargePower != null && stats.maxChargePower != null &&
                    stats.minChargeCurrent != null && stats.maxChargeCurrent != null) {
                    val minCP = String.format(Locale.getDefault(), "%.2f", stats.minChargePower)
                    val maxCP = String.format(Locale.getDefault(), "%.2f", stats.maxChargePower)
                    val minCC = String.format(Locale.getDefault(), "%.2f", stats.minChargeCurrent)
                    val maxCC = String.format(Locale.getDefault(), "%.2f", stats.maxChargeCurrent)
                    summaryBuilder.append(context.getString(R.string.charge_power_summary, minCP, maxCP))
                    summaryBuilder.append(context.getString(R.string.charge_current_summary, minCC, maxCC))
                }

                if (stats.minDischargePower != null && stats.maxDischargePower != null &&
                    stats.minDischargeCurrent != null && stats.maxDischargeCurrent != null) {
                    val minDP = String.format(Locale.getDefault(), "%.2f", stats.minDischargePower)
                    val maxDP = String.format(Locale.getDefault(), "%.2f", stats.maxDischargePower)
                    val minDC = String.format(Locale.getDefault(), "%.2f", stats.minDischargeCurrent)
                    val maxDC = String.format(Locale.getDefault(), "%.2f", stats.maxDischargeCurrent)
                    summaryBuilder.append(context.getString(R.string.discharge_power_summary, minDP, maxDP))
                    summaryBuilder.append(context.getString(R.string.discharge_current_summary, minDC, maxDC))
                }

                val changeSign = if (stats.batteryChange >= 0) "+${stats.batteryChange}%" else "${stats.batteryChange}%"
                summaryBuilder.append(context.getString(R.string.battery_change, stats.startBattery, stats.endBattery, changeSign))
            }

            tvRawSummary.text = summaryBuilder.toString()
            tvRawSummary.visibility = View.VISIBLE

            dialogView.findViewById<View>(R.id.btnExportCSV)?.setOnClickListener {
                dialog.dismiss()
                val fileName = "ChargeLog_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
                onExportCsv(fileName)
            }

            dialogView.findViewById<View>(R.id.btnCloseDialog)?.setOnClickListener {
                dialog.dismiss()
            }
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("RawDataDialogHelper", "Error showing raw data dialog", e)
            Toast.makeText(context, "打开数据失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
