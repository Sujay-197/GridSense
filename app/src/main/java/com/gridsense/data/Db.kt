package com.gridsense.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SurveyRoom::class, GridPoint::class, Sample::class, Router::class],
    version = 3,
    exportSchema = false
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): SurveyDao

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                Db::class.java,
                "gridsense.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
