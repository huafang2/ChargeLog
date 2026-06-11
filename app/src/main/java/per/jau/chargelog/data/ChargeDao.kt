package per.jau.chargelog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargeDao {
    @Insert
    suspend fun insert(record: ChargeRecord)

    @Query("SELECT * FROM charge_records ORDER BY timestamp ASC")
    suspend fun getAllRecordsOnce(): List<ChargeRecord>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<ChargeRecord>)

    @androidx.room.Update
    suspend fun updateAll(records: List<ChargeRecord>)

    @Query("SELECT * FROM charge_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecord(): ChargeRecord?

    @Query("SELECT * FROM charge_records WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getRecordsBySession(sessionId: Long): Flow<List<ChargeRecord>>

    @Query("SELECT * FROM charge_records ORDER BY timestamp ASC")
    fun getAllRecords(): Flow<List<ChargeRecord>>

    @Query("DELETE FROM charge_records WHERE timestamp < :time")
    suspend fun deleteOldRecords(time: Long)

    @Query("DELETE FROM charge_records WHERE sessionId = :sessionId")
    suspend fun deleteRecordsBySession(sessionId: Long)

    @Query("DELETE FROM charge_records WHERE sessionId = :sessionId AND timestamp < :timestamp")
    suspend fun deleteRecordsBefore(sessionId: Long, timestamp: Long)

    @Query("DELETE FROM charge_records WHERE sessionId = :sessionId AND timestamp > :timestamp")
    suspend fun deleteRecordsAfter(sessionId: Long, timestamp: Long)

    @Query("DELETE FROM charge_records WHERE sessionId = :sessionId AND timestamp = :timestamp")
    suspend fun deleteSingleRecord(sessionId: Long, timestamp: Long)

    @Query("DELETE FROM charge_records")
    suspend fun deleteAllRecords()
}
