package per.jau.chargelog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ChargeRecord::class], version = 6, exportSchema = false)
abstract class ChargeDatabase : RoomDatabase() {
    abstract fun chargeDao(): ChargeDao

    companion object {
        @Volatile
        private var INSTANCE: ChargeDatabase? = null

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_records ADD COLUMN screenState INTEGER NOT NULL DEFAULT 2")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_records ADD COLUMN maxVoltage REAL")
                db.execSQL("ALTER TABLE charge_records ADD COLUMN maxCurrent REAL")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Existing rows predate status logging and must retain legacy estimation behavior.
                db.execSQL("ALTER TABLE charge_records ADD COLUMN batteryStatus INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getDatabase(context: Context): ChargeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChargeDatabase::class.java,
                    "charge_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(false)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
