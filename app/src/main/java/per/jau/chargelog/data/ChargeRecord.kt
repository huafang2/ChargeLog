package per.jau.chargelog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "charge_records")
data class ChargeRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val voltage: Float, // in Volts
    val current: Float, // in Amps
    val power: Float,   // in Watts
    val batteryLevel: Int // Replaced protocol with batteryLevel
)
