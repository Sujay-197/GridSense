package com.gridsense.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import com.gridsense.data.GridPoint
import com.gridsense.data.Router
import com.gridsense.data.Sample
import com.gridsense.data.SurveyRoom
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val AUTHORITY = "com.gridsense.fileprovider"

fun exportsDir(context: Context): File {
    val dir = File(context.getExternalFilesDir(null), "exports")
    dir.mkdirs()
    return dir
}

fun baseName(room: SurveyRoom): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val safe = room.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "room" }
    return safe + "_" + stamp
}

fun writePng(context: Context, image: ImageBitmap, base: String): File {
    val file = File(exportsDir(context), base + "_heatmap.png")
    file.outputStream().use { out ->
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return file
}

private fun csvCell(value: Any?): String {
    val text = value?.toString() ?: ""
    return if (text.contains(',') || text.contains('"') || text.contains('\n')) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else {
        text
    }
}

private fun csvRow(cells: List<Any?>): String = cells.joinToString(",") { csvCell(it) }

fun writeCsvs(
    context: Context,
    room: SurveyRoom,
    points: List<GridPoint>,
    samples: List<Sample>,
    routers: List<Router>,
    base: String
): List<File> {
    val pointById = points.associateBy { it.id }

    val pointsFile = File(exportsDir(context), base + "_points.csv")
    pointsFile.bufferedWriter().use { w ->
        w.write(csvRow(listOf("point_id", "seq", "x_m", "y_m", "source", "enabled", "status")))
        w.newLine()
        for (p in points) {
            w.write(csvRow(listOf(p.id, p.seq, p.x, p.y, p.source, p.enabled, p.status)))
            w.newLine()
        }
    }

    val samplesFile = File(exportsDir(context), base + "_samples.csv")
    samplesFile.bufferedWriter().use { w ->
        w.write(
            csvRow(
                listOf(
                    "sample_id", "point_id", "seq", "x_m", "y_m", "position_source", "ts_epoch_ms",
                    "kind", "bssid", "ssid", "freq_mhz", "rssi_dbm", "link_speed_mbps",
                    "cell_tech", "cell_dbm", "cell_level", "rsrp", "rsrq", "sinr",
                    "cell_id", "tac", "pci", "arfcn", "operator",
                    "ping_host", "rtt_avg_ms", "rtt_jitter_ms", "rtt_loss_pct"
                )
            )
        )
        w.newLine()
        for (s in samples) {
            val p = pointById[s.pointId]
            w.write(
                csvRow(
                    listOf(
                        s.id, s.pointId, p?.seq, p?.x, p?.y, p?.source, s.ts, s.kind, s.bssid, s.ssid,
                        s.freqMhz, s.rssiDbm, s.linkSpeedMbps,
                        s.cellTech, s.cellDbm, s.cellLevel, s.rsrp, s.rsrq, s.sinr,
                        s.cellId, s.tac, s.pci, s.arfcn, s.operator,
                        s.pingHost, s.rttAvgMs, s.rttJitterMs, s.rttLossPct
                    )
                )
            )
            w.newLine()
        }
    }

    val routersFile = File(exportsDir(context), base + "_routers.csv")
    routersFile.bufferedWriter().use { w ->
        w.write(csvRow(listOf("router_id", "x_m", "y_m", "bssid", "label")))
        w.newLine()
        for (r in routers) {
            w.write(csvRow(listOf(r.id, r.x, r.y, r.bssid, r.label)))
            w.newLine()
        }
    }

    return listOf(pointsFile, samplesFile, routersFile)
}

private fun jsonString(value: String?): String {
    if (value == null) return "null"
    val sb = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    return sb.append("\"").toString()
}

private fun jsonNumber(value: Any?): String = value?.toString() ?: "null"

fun writeJson(
    context: Context,
    room: SurveyRoom,
    points: List<GridPoint>,
    samples: List<Sample>,
    routers: List<Router>,
    base: String
): File {
    val file = File(exportsDir(context), base + ".json")
    file.bufferedWriter().use { w ->
        w.write("{\n")
        w.write("  \"room\": {")
        w.write("\"id\": " + room.id)
        w.write(", \"name\": " + jsonString(room.name))
        w.write(", \"mode\": " + jsonString(room.mode))
        w.write(", \"outline_source\": " + jsonString(room.outlineSource))
        w.write(", \"samples_per_point\": " + room.samplesPerPoint)
        w.write(", \"ping_host\": " + jsonString(room.pingHost))
        w.write(", \"closure_error_m\": " + jsonNumber(room.closureErrorM))
        w.write(", \"polygon_m\": " + jsonString(room.polygon))
        w.write(", \"created_at\": " + room.createdAt)
        w.write("},\n")

        w.write("  \"points\": [\n")
        points.forEachIndexed { index, p ->
            w.write(
                "    {\"id\": " + p.id + ", \"seq\": " + p.seq +
                    ", \"x_m\": " + p.x + ", \"y_m\": " + p.y +
                    ", \"source\": " + jsonString(p.source) +
                    ", \"enabled\": " + p.enabled + ", \"status\": " + jsonString(p.status) + "}"
            )
            w.write(if (index == points.lastIndex) "\n" else ",\n")
        }
        w.write("  ],\n")

        w.write("  \"samples\": [\n")
        samples.forEachIndexed { index, s ->
            w.write(
                "    {\"id\": " + s.id + ", \"point_id\": " + s.pointId + ", \"ts\": " + s.ts +
                    ", \"kind\": " + jsonString(s.kind) +
                    ", \"bssid\": " + jsonString(s.bssid) +
                    ", \"ssid\": " + jsonString(s.ssid) +
                    ", \"freq_mhz\": " + jsonNumber(s.freqMhz) +
                    ", \"rssi_dbm\": " + jsonNumber(s.rssiDbm) +
                    ", \"link_speed_mbps\": " + jsonNumber(s.linkSpeedMbps) +
                    ", \"cell_tech\": " + jsonString(s.cellTech) +
                    ", \"cell_dbm\": " + jsonNumber(s.cellDbm) +
                    ", \"cell_level\": " + jsonNumber(s.cellLevel) +
                    ", \"rsrp\": " + jsonNumber(s.rsrp) +
                    ", \"rsrq\": " + jsonNumber(s.rsrq) +
                    ", \"sinr\": " + jsonNumber(s.sinr) +
                    ", \"cell_id\": " + jsonNumber(s.cellId) +
                    ", \"tac\": " + jsonNumber(s.tac) +
                    ", \"pci\": " + jsonNumber(s.pci) +
                    ", \"arfcn\": " + jsonNumber(s.arfcn) +
                    ", \"operator\": " + jsonString(s.operator) +
                    ", \"ping_host\": " + jsonString(s.pingHost) +
                    ", \"rtt_avg_ms\": " + jsonNumber(s.rttAvgMs) +
                    ", \"rtt_jitter_ms\": " + jsonNumber(s.rttJitterMs) +
                    ", \"rtt_loss_pct\": " + jsonNumber(s.rttLossPct) + "}"
            )
            w.write(if (index == samples.lastIndex) "\n" else ",\n")
        }
        w.write("  ],\n")

        w.write("  \"routers\": [\n")
        routers.forEachIndexed { index, r ->
            w.write(
                "    {\"id\": " + r.id + ", \"x_m\": " + r.x + ", \"y_m\": " + r.y +
                    ", \"bssid\": " + jsonString(r.bssid) +
                    ", \"label\": " + jsonString(r.label) + "}"
            )
            w.write(if (index == routers.lastIndex) "\n" else ",\n")
        }
        w.write("  ]\n")
        w.write("}\n")
    }
    return file
}

fun share(context: Context, files: List<File>, mimeType: String) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, AUTHORITY, it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(intent, "Share GridSense export")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
