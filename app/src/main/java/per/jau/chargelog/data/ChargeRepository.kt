package per.jau.chargelog.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * 统一的数据仓库，封装对 ChargeDao 的所有数据库访问逻辑。
 */
class ChargeRepository(private val dao: ChargeDao) {

    suspend fun insert(record: ChargeRecord) = dao.insert(record)

    suspend fun insertAll(records: List<ChargeRecord>) = dao.insertAll(records)

    suspend fun updateAll(records: List<ChargeRecord>) = dao.updateAll(records)

    suspend fun getLatestRecord(): ChargeRecord? = dao.getLatestRecord()

    suspend fun getAllRecordsOnce(): List<ChargeRecord> = dao.getAllRecordsOnce()

    fun getRecordsBySession(sessionId: Long): Flow<List<ChargeRecord>> = dao.getRecordsBySession(sessionId)

    fun getAllRecords(): Flow<List<ChargeRecord>> = dao.getAllRecords()

    suspend fun deleteRecordsBySession(sessionId: Long): Int = dao.deleteRecordsBySession(sessionId)

    suspend fun deleteRecordsBefore(sessionId: Long, timestamp: Long) = dao.deleteRecordsBefore(sessionId, timestamp)

    suspend fun deleteRecordsAfter(sessionId: Long, timestamp: Long) = dao.deleteRecordsAfter(sessionId, timestamp)

    suspend fun deleteSingleRecord(sessionId: Long, timestamp: Long) = dao.deleteSingleRecord(sessionId, timestamp)

    suspend fun deleteSessionsEndingBefore(cutoff: Long, activeSessionId: Long): Int =
        dao.deleteSessionsEndingBefore(cutoff, activeSessionId)

    suspend fun deleteAllRecords() = dao.deleteAllRecords()

    companion object {
        @Volatile
        private var INSTANCE: ChargeRepository? = null

        fun getInstance(context: Context): ChargeRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = ChargeRepository(
                    ChargeDatabase.getDatabase(context.applicationContext).chargeDao()
                )
                INSTANCE = instance
                instance
            }
        }
    }
}
