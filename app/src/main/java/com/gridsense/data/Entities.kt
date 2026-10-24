package com.gridsense.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gridsense.core.Pt

const val STATUS_PENDING = "PENDING"
const val STATUS_LOGGING = "LOGGING"
const val STATUS_DONE = "DONE"

/** A row written once per 500 ms poll of the Wi-Fi connection info. */
const val KIND_LINK = "LINK"

/** A row written once per 500 ms poll of the serving cell. */
const val KIND_CELL = "CELL"

/** A single row per point carrying the ping-derived latency, jitter and loss. */
const val KIND_PING = "PING"

/** A neighbour AP seen by a fresh scan. */
const val KIND_SCAN = "SCAN"

/** A neighbour AP taken from the cached scan list because startScan was throttled. */
const val KIND_SCAN_CACHED = "SCAN_CACHED"

@Entity(tableName = "survey_room")
data class SurveyRoom(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** WIFI or CELLULAR: which radio the survey is mainly about. Both are always recorded. */
    val mode: String,
    val samplesPerPoint: Int,
    /** Explicit ping target, or null to pick one from the active transport. */
    val pingHost: String?,
    /** Outline corners in metres, encoded as "x,y;x,y;...", origin at the starting corner. */
    val polygon: String,
    val gridRows: Int,
    val gridCols: Int,
    /** Side of one grid cell in metres. Only sets the scale of the map. */
    val cellSizeM: Double,
    val createdAt: Long
)

@Entity(
    tableName = "grid_point",
    foreignKeys = [ForeignKey(
        entity = SurveyRoom::class,
        parentColumns = ["id"],
        childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("roomId")]
)
data class GridPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    /** Position in the grid, row-major from cell A1. */
    val seq: Int,
    val row: Int,
    val col: Int,
    /** Metres from the origin corner, at the centre of the cell. */
    val x: Double,
    val y: Double,
    val enabled: Boolean,
    val status: String
)

@Entity(
    tableName = "sample",
    foreignKeys = [ForeignKey(
        entity = GridPoint::class,
        parentColumns = ["id"],
        childColumns = ["pointId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("pointId")]
)
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointId: Long,
    val ts: Long,
    val kind: String,
    // Wi-Fi
    val bssid: String? = null,
    val ssid: String? = null,
    val freqMhz: Int? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    /** Scan rows only: occupied width (20, 40, 80, 160, 320). Null on rows from before v6. */
    val channelWidthMhz: Int? = null,
    /** Scan rows only: centre of the whole occupied block for channels wider than 20 MHz. */
    val centerFreq0Mhz: Int? = null,
    /** Scan rows only: ScanResult.wifiStandard on Android 11+, null on 10. */
    val wifiStandard: Int? = null,
    // Cellular
    val cellTech: String? = null,
    val cellDbm: Int? = null,
    val cellLevel: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val cellId: Long? = null,
    val tac: Int? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val operator: String? = null,
    // Ping
    val pingHost: String? = null,
    val rttAvgMs: Double? = null,
    val rttJitterMs: Double? = null,
    val rttLossPct: Double? = null
)

@Entity(
    tableName = "router",
    foreignKeys = [ForeignKey(
        entity = SurveyRoom::class,
        parentColumns = ["id"],
        childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("roomId")]
)
data class Router(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    /** Metres from the origin corner, free placement. */
    val x: Double,
    val y: Double,
    val bssid: String,
    val label: String,
    /** Router height above the phone in metres; 0 treats them as level. */
    @ColumnInfo(defaultValue = "0")
    val heightDiffM: Double = 0.0
)

fun encodePolygon(corners: List<Pt>): String =
    corners.joinToString(";") { it.x.toString() + "," + it.y.toString() }

fun decodePolygon(encoded: String): List<Pt> =
    if (encoded.isBlank()) emptyList()
    else encoded.split(";").mapNotNull {
        val parts = it.split(",")
        val x = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
        val y = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
        if (x == null || y == null) null else Pt(x, y)
    }
