package com.gridsense.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Bump together with a new entry in [ALL_MIGRATIONS]; never fall back to a destructive one. */
const val DB_VERSION = 9

@Database(
    entities = [SurveyRoom::class, GridPoint::class, Sample::class, Router::class],
    version = DB_VERSION,
    exportSchema = true
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
            ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
        }
    }
}
