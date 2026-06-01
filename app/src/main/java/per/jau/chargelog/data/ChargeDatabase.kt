package per.jau.chargelog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ChargeRecord::class], version = 3, exportSchema = false)
abstract class ChargeDatabase : RoomDatabase() {
    abstract fun chargeDao(): ChargeDao

    companion object {
        @Volatile
        private var INSTANCE: ChargeDatabase? = null

        fun getDatabase(context: Context): ChargeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChargeDatabase::class.java,
                    "charge_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
