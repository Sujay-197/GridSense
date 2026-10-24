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

/** One DNS lookup: [Sample.probeMs] and [Sample.probeOk], name in [Sample.target]. */
const val KIND_DNS = "DNS"

/** One TCP connect attempt: [Sample.probeMs] and [Sample.probeOk], "host:port" in [Sample.target]. */
const val KIND_TCP = "TCP"

/** One ping reply received during the load test, RTT in [Sample.probeMs], host in [Sample.target]. */
const val KIND_LOADED_PING = "LOADED_PING"

/** The load test's download: [Sample.bytes] over [Sample.durationMs], URL in [Sample.target]. */
const val KIND_DOWNLOAD = "DOWNLOAD"

const val DEFAULT_DNS_NAME = "example.com"
const val DEFAULT_TCP_HOST = "1.1.1.1"
const val DEFAULT_TCP_PORT = 443

/** A third-party endpoint that may change, so it is a per-survey setting. */
const val DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=20000000"

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
    val createdAt: Long,
    /** The experimental condition, such as "door closed" or "router by the window". */
    @ColumnInfo(defaultValue = "")
    val condition: String = "",
    /** DNS and TCP tests at every node, about 3 to 5 s extra. */
    @ColumnInfo(defaultValue = "1")
    val extendedTests: Boolean = true,
    /** The download-under-load test. Off by default because it uses data. */
    @ColumnInfo(defaultValue = "0")
    val loadTest: Boolean = false,
    @ColumnInfo(defaultValue = DEFAULT_DNS_NAME)
    val dnsName: String = DEFAULT_DNS_NAME,
    @ColumnInfo(defaultValue = DEFAULT_TCP_HOST)
    val tcpHost: String = DEFAULT_TCP_HOST,
    @ColumnInfo(defaultValue = DEFAULT_TCP_PORT.toString())
    val tcpPort: Int = DEFAULT_TCP_PORT,
    @ColumnInfo(defaultValue = DEFAULT_DOWNLOAD_URL)
    val downloadUrl: String = DEFAULT_DOWNLOAD_URL
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
    val rttLossPct: Double? = null,
    // DNS, TCP and load-test probes, one row per attempt
    val probeMs: Double? = null,
    val probeOk: Boolean? = null,
    val bytes: Long? = null,
    val durationMs: Long? = null,
    val target: String? = null
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
