package com.gridsense.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema steps from version 4, the first build whose surveys must survive an upgrade.
 * Every step only adds columns (nullable or with a default), so existing rows are kept.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No schema change: this step moves the database off destructive migration.
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_4_5)
