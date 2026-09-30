package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.ClonePreset
import com.example.model.CloneRecord

@Database(entities = [CloneRecord::class, ClonePreset::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cloneRecordDao(): CloneRecordDao
    abstract fun clonePresetDao(): ClonePresetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_cloner_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
