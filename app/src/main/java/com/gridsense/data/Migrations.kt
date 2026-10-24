package com.gridsense.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A schema step that only runs [statements], each adding a nullable column or one with a
 * default, so existing rows are kept. Tests read [statements] to check them against the
 * exported schemas.
 */
class AddColumns(from: Int, to: Int, val statements: List<String>) : Migration(from, to) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (sql in statements) db.execSQL(sql)
    }
}

/** No schema change: this step moved the database off destructive migration. */
val MIGRATION_4_5 = AddColumns(4, 5, emptyList())

/** Channel width, centre frequency and Wi-Fi standard of scanned access points. */
val MIGRATION_5_6 = AddColumns(
    5, 6,
    listOf(
        "ALTER TABLE sample ADD COLUMN channelWidthMhz INTEGER",
        "ALTER TABLE sample ADD COLUMN centerFreq0Mhz INTEGER",
        "ALTER TABLE sample ADD COLUMN wifiStandard INTEGER"
    )
)

/** How far the router sits above the phone, for 3D distances in the path-loss fit. */
val MIGRATION_6_7 = AddColumns(
    6, 7,
    listOf("ALTER TABLE router ADD COLUMN heightDiffM REAL NOT NULL DEFAULT 0")
)

val ALL_MIGRATIONS: Array<AddColumns> = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
