package com.arrestme.benchmark.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        SessionTransitionEntity::class,
        AppRuleEntity::class,
        DiagnosticEventEntity::class,
        UsageTransitionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ArrestDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun appRuleDao(): AppRuleDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
    abstract fun usageTransitionDao(): UsageTransitionDao

    companion object {
        @Volatile
        private var instance: ArrestDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN overlayStatusBar INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN disableEmergencyExit INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): ArrestDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArrestDatabase::class.java,
                    "arrest_me_benchmark.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
