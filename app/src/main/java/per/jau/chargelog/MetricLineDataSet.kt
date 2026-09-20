package per.jau.chargelog

import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet

class MetricLineDataSet(entries: List<Entry>, label: String, val series: ChartSeries) :
    LineDataSet(entries, label)
