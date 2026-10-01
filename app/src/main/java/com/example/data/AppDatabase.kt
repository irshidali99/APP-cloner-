package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.ClonePreset
import com.example.model.CloneRecord

@Database(entities = [CloneRecord::class, ClonePreset::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cloneRecordDao(): CloneRecordDao
    abstract fun clonePresetDao(): ClonePresetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Adds the runtime columns of version 7. A real migration keeps the existing clone records; a
         * destructive one would delete every stored clone each time a column is added.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE clone_records ADD COLUMN runtimeSummary TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE clone_records ADD COLUMN runtimeResetCode TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_cloner_database"
                ).addMigrations(MIGRATION_6_7).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
