package com.arrestme.benchmark.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        SessionTransitionEntity::class,
        AppRuleEntity::class,
        DiagnosticEventEntity::class,
        UsageTransitionEntity::class
    ],
    version = 1,
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

        fun getInstance(context: Context): ArrestDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArrestDatabase::class.java,
                    "arrest_me_benchmark.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
