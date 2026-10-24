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

/** The experimental condition of a survey, such as "door closed", for comparisons. */
val MIGRATION_7_8 = AddColumns(
    7, 8,
    listOf("ALTER TABLE survey_room ADD COLUMN condition TEXT NOT NULL DEFAULT ''")
)

/** Latency beyond ping: per-survey test settings and per-attempt probe rows. */
val MIGRATION_8_9 = AddColumns(
    8, 9,
    listOf(
        "ALTER TABLE survey_room ADD COLUMN extendedTests INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE survey_room ADD COLUMN loadTest INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE survey_room ADD COLUMN dnsName TEXT NOT NULL DEFAULT '$DEFAULT_DNS_NAME'",
        "ALTER TABLE survey_room ADD COLUMN tcpHost TEXT NOT NULL DEFAULT '$DEFAULT_TCP_HOST'",
        "ALTER TABLE survey_room ADD COLUMN tcpPort INTEGER NOT NULL DEFAULT $DEFAULT_TCP_PORT",
        "ALTER TABLE survey_room ADD COLUMN downloadUrl TEXT NOT NULL DEFAULT '$DEFAULT_DOWNLOAD_URL'",
        "ALTER TABLE sample ADD COLUMN probeMs REAL",
        "ALTER TABLE sample ADD COLUMN probeOk INTEGER",
        "ALTER TABLE sample ADD COLUMN bytes INTEGER",
        "ALTER TABLE sample ADD COLUMN durationMs INTEGER",
        "ALTER TABLE sample ADD COLUMN target TEXT"
    )
)

val ALL_MIGRATIONS: Array<AddColumns> =
    arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
