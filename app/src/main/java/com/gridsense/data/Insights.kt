package com.gridsense.data

import com.gridsense.core.Metric
import com.gridsense.core.isDfs
import com.gridsense.core.QualityLevel
import com.gridsense.core.median
import com.gridsense.core.nodeLabel
import com.gridsense.core.qualityLevel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** A headline value of a section with its place on the shared quality scale. */
data class Rating(val label: String, val value: String, val level: QualityLevel)

/** One titled block of the plain-language survey report. */
data class InsightSection(
    val title: String,
    val lines: List<String>,
    val ratings: List<Rating> = emptyList()
)

/** Below this, a node's Wi-Fi is usually not good enough for video calls or streaming. */
const val WIFI_STREAMING_DBM = -67.0

/**
 * A node-to-node spread this small is about the size of ordinary reading-to-reading variation
 * from how the phone is held, the surveyor's body and multipath, so it is not a real pattern.
 */
const val UNIFORM_SPREAD_DB = 6.0

/** A difference between opposite edges of the grid big enough to point at the router. */
const val GRADIENT_DB = 4.0

/** A 2.4 GHz neighbour stronger than this on an overlapping channel can slow the link down. */
const val INTERFERING_DBM = -70.0

fun wifiBand(rssi: Double): String = qualityLevel(Metric.RSSI, rssi).word

fun rsrpBand(rsrp: Double): String = qualityLevel(Metric.CELL_RSRP, rsrp).word

fun rsrqBand(rsrq: Double): String = qualityLevel(Metric.CELL_RSRQ, rsrq).word

fun sinrBand(sinr: Double): String = qualityLevel(Metric.CELL_SINR, sinr).word

/** The Wi-Fi channel number for a centre frequency, or null for one we do not recognise. */
fun wifiChannel(freqMhz: Int): Int? = when (freqMhz) {
    in 2412..2472 -> (freqMhz - 2407) / 5
    2484 -> 14
    in 5000..5900 -> (freqMhz - 5000) / 5
    in 5925..7125 -> (freqMhz - 5950) / 5
    else -> null
}

private fun bandName(freqMhz: Int): String = when {
    freqMhz < 3000 -> "2.4 GHz"
    freqMhz < 5925 -> "5 GHz"
    else -> "6 GHz"
}

/** Whole numbers print without a decimal, so medians read as "-54" or "-58.5". */
private fun num(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString()
    else String.format(Locale.US, "%.1f", value)

private fun stdev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}

private fun labelOf(p: GridPoint) = nodeLabel(p.row, p.col)

private fun joinLabels(points: List<GridPoint>) = points.joinToString(", ") { labelOf(it) }

/**
 * Turns a survey into short, plain-language findings: how good the coverage is, whether the
 * differences between nodes are real, where the router probably is, what else is on the air,
 * how good the cellular signal is, and anything that makes the data less trustworthy.
 */
fun buildInsights(
    points: List<GridPoint>,
    samples: List<Sample>,
    routers: List<Router> = emptyList()
): List<InsightSection> {
    val active = points.filter { it.enabled }
    val logged = active.filter { it.status == STATUS_DONE }
    val byPoint = samples.groupBy { it.pointId }
    val sections = ArrayList<InsightSection>()

    sections += overview(active, logged, byPoint)
    if (logged.isEmpty()) return sections

    wifiCoverage(logged, byPoint)?.let { sections += it }
    pathLoss(logged, byPoint, routers)?.let { sections += it }
    airwaves(logged, byPoint)?.let { sections += it }
    channels(logged, byPoint)?.let { sections += it }
    cellular(logged, byPoint)?.let { sections += it }
    latency(logged, byPoint)?.let { sections += it }
    dataQuality(logged, byPoint)?.let { sections += it }
    return sections
}

private fun overview(
    active: List<GridPoint>,
    logged: List<GridPoint>,
    byPoint: Map<Long, List<Sample>>
): InsightSection {
    val lines = ArrayList<String>()
    lines += "${logged.size} of ${active.size} nodes logged."
    val pending = active.filter { it.status != STATUS_DONE }
    if (pending.isNotEmpty()) {
        lines += "Still to log: ${joinLabels(pending)}. Findings below only cover the logged nodes."
    }
    val perNode = logged.map { p -> byPoint[p.id].orEmpty().count { it.kind == KIND_LINK }.toDouble() }
    if (perNode.any { it > 0 }) {
        lines += "Each node has about ${num(median(perNode))} Wi-Fi readings, and the figures " +
            "below are medians of those, which ignores the odd outlier."
    }
    return InsightSection("Overview", lines)
}

private fun wifiCoverage(logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): InsightSection? {
    val readings = logged.associateWith { p ->
        byPoint[p.id].orEmpty().filter { it.kind == KIND_LINK }.mapNotNull { it.rssiDbm?.toDouble() }
    }.filterValues { it.isNotEmpty() }
    if (readings.isEmpty()) return null
    val medians = readings.mapValues { median(it.value) }
    val lines = ArrayList<String>()

    val links = logged.flatMap { byPoint[it.id].orEmpty() }.filter { it.kind == KIND_LINK }
    val main = links.groupingBy { it.bssid }.eachCount().maxByOrNull { it.value }?.key
    val mainRow = links.firstOrNull { it.bssid == main }
    if (mainRow?.freqMhz != null) {
        val channel = wifiChannel(mainRow.freqMhz)
        lines += "Connected to ${mainRow.ssid ?: "an unnamed network"} on " +
            bandName(mainRow.freqMhz) + (if (channel != null) ", channel $channel" else "") + "."
    }
    val bssids = links.mapNotNull { it.bssid }.distinct()
    if (bssids.size > 1) {
        lines += "The phone was associated with ${bssids.size} different access points during " +
            "the survey, so it roamed between them."
    }

    val worst = medians.minByOrNull { it.value }!!
    val best = medians.maxByOrNull { it.value }!!
    val overall = median(readings.values.flatten())
    lines += "Signal ranges from ${num(worst.value)} to ${num(best.value)} dBm across the " +
        "room (median ${num(overall)}): ${wifiBand(worst.value)} at worst, " +
        "${wifiBand(best.value)} at best."
    lines += "Weakest node ${labelOf(worst.key)} (${num(worst.value)} dBm), strongest " +
        "${labelOf(best.key)} (${num(best.value)} dBm)."

    val below = medians.filterValues { it < WIFI_STREAMING_DBM }.keys.sortedBy { it.seq }
    lines += if (below.isEmpty()) {
        "No node is below ${num(WIFI_STREAMING_DBM)} dBm, the usual minimum for reliable video " +
            "calls and streaming, so there is no dead zone."
    } else {
        "${below.size} node${if (below.size == 1) " is" else "s are"} below " +
            "${num(WIFI_STREAMING_DBM)} dBm, the usual minimum for reliable video calls and " +
            "streaming: ${joinLabels(below)}."
    }

    val spread = best.value - worst.value
    val noise = median(readings.values.map { stdev(it) })
    if (spread <= UNIFORM_SPREAD_DB) {
        lines += "The node-to-node spread is only ${num(spread)} dB. Readings at a single spot " +
            "already vary by about ±${num(noise)} dB from how the phone is held, your body and " +
            "reflections, so treat the room as uniformly covered rather than reading much into " +
            "the heatmap colours."
    } else {
        lines += "The node-to-node spread is ${num(spread)} dB. Readings at one spot only varied " +
            "by about ±${num(noise)} dB while logging, but a repeat survey can move a node by " +
            "several dB depending on how the phone is held and where you stand, so confirm the " +
            "pattern with a second pass before relying on it."
        gradient(medians, horizontal = false)?.let { lines += it }
        gradient(medians, horizontal = true)?.let { lines += it }
    }

    val speeds = logged.associateWith { p ->
        byPoint[p.id].orEmpty().filter { it.kind == KIND_LINK }.mapNotNull { it.linkSpeedMbps?.toDouble() }
    }.filterValues { it.isNotEmpty() }.mapValues { median(it.value) }
    if (speeds.isNotEmpty()) {
        val slow = speeds.minByOrNull { it.value }!!
        lines += "Link speed is ${num(speeds.values.min())} to ${num(speeds.values.max())} Mbps " +
            "(median ${num(median(speeds.values.toList()))}); slowest at ${labelOf(slow.key)}."
    }

    lines += "The heatmap colour scale stretches from the weakest node to the strongest, so red " +
        "means weakest in this room, not necessarily bad. Quote the dBm figures alongside it."
    return InsightSection(
        "Wi-Fi coverage",
        lines,
        listOf(
            Rating("Median RSSI", "${num(overall)} dBm", qualityLevel(Metric.RSSI, overall)),
            Rating("Weakest node", "${num(worst.value)} dBm", qualityLevel(Metric.RSSI, worst.value))
        )
    )
}

/** Compares the first and last row (or column) to suggest which side the router is on. */
private fun gradient(medians: Map<GridPoint, Double>, horizontal: Boolean): String? {
    val key: (GridPoint) -> Int = if (horizontal) { p -> p.col } else { p -> p.row }
    val lines = medians.keys.map(key).distinct().sorted()
    if (lines.size < 2) return null
    val first = medians.filterKeys { key(it) == lines.first() }.values.average()
    val last = medians.filterKeys { key(it) == lines.last() }.values.average()
    val diff = first - last
    if (abs(diff) < GRADIENT_DB) return null
    val name: (Int) -> String = if (horizontal) {
        { c -> "column " + nodeLabel(0, c).dropLast(1) }
    } else {
        { r -> "row " + (r + 1) }
    }
    val (strong, weak) = if (diff > 0) lines.first() to lines.last() else lines.last() to lines.first()
    return "Signal is ${num(abs(diff))} dB stronger along ${name(strong)} than ${name(weak)}, so " +
        "the router is probably on the ${name(strong)} side."
}

private fun airwaves(logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): InsightSection? {
    val all = logged.flatMap { byPoint[it.id].orEmpty() }
    val links = all.filter { it.kind == KIND_LINK && it.freqMhz != null }
    val scans = all.filter { (it.kind == KIND_SCAN || it.kind == KIND_SCAN_CACHED) && it.rssiDbm != null }
    if (scans.isEmpty() || links.isEmpty()) return null
    val lines = ArrayList<String>()

    val mySsid = links.groupingBy { it.ssid }.eachCount().maxByOrNull { it.value }?.key
    val myFreq = links.groupingBy { it.freqMhz!! }.eachCount().maxByOrNull { it.value }!!.key

    if (myFreq < 3000 && mySsid != null) {
        val fiveGhz = scans.filter { it.ssid == mySsid && (it.freqMhz ?: 0) >= 5000 }
        if (fiveGhz.isNotEmpty()) {
            val level = median(fiveGhz.map { it.rssiDbm!!.toDouble() })
            val ch = wifiChannel(fiveGhz.first().freqMhz!!)
            lines += "Your router also broadcasts $mySsid on 5 GHz" +
                (if (ch != null) " (channel $ch)" else "") + ", reaching about ${num(level)} dBm " +
                "here, but the phone stayed on 2.4 GHz. 5 GHz is usually faster at close range " +
                "and less crowded, at the cost of range through walls."
        }
    }

    val hidden = scans.filter { it.ssid.isNullOrBlank() }.mapNotNull { it.bssid }.distinct()
    if (hidden.isNotEmpty()) {
        lines += "${hidden.size} hidden network${if (hidden.size == 1) " was" else "s were"} also " +
            "seen. Hidden networks on 5 GHz next to your router's are often its own guest or " +
            "mesh networks."
    }

    if (lines.isEmpty()) return null
    return InsightSection("Bands and neighbours", lines)
}

private fun cellular(logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): InsightSection? {
    val cells = logged.associateWith { p -> byPoint[p.id].orEmpty().filter { it.kind == KIND_CELL } }
        .filterValues { it.isNotEmpty() }
    if (cells.isEmpty()) return null
    val all = cells.values.flatten()
    val lines = ArrayList<String>()

    val ratings = ArrayList<Rating>()
    val operator = all.mapNotNull { it.operator }.firstOrNull()
    val techs = all.mapNotNull { it.cellTech }.distinct()
    lines += "Serving network: " + (operator ?: "unknown operator") +
        (if (techs.isNotEmpty()) " " + techs.joinToString("/") else "") + "."

    val strength = cells.mapValues { (_, rows) ->
        rows.mapNotNull { (it.rsrp ?: it.cellDbm)?.toDouble() }
    }.filterValues { it.isNotEmpty() }.mapValues { median(it.value) }
    if (strength.isNotEmpty()) {
        val worst = strength.minByOrNull { it.value }!!
        val best = strength.maxByOrNull { it.value }!!
        val overall = median(strength.values.toList())
        ratings += Rating("Median RSRP", "${num(overall)} dBm", qualityLevel(Metric.CELL_RSRP, overall))
        lines += "Signal strength (RSRP) is ${num(worst.value)} to ${num(best.value)} dBm, median " +
            "${num(overall)}: ${rsrpBand(overall)} overall. Weakest at ${labelOf(worst.key)}, " +
            "strongest at ${labelOf(best.key)}."
    }
    val rsrq = all.mapNotNull { it.rsrq?.toDouble() }
    val sinr = all.mapNotNull { it.sinr?.toDouble() }
    if (rsrq.isNotEmpty() || sinr.isNotEmpty()) {
        val parts = ArrayList<String>()
        if (rsrq.isNotEmpty()) {
            ratings += Rating("RSRQ", "${num(median(rsrq))} dB", qualityLevel(Metric.CELL_RSRQ, median(rsrq)))
        }
        if (sinr.isNotEmpty()) {
            ratings += Rating("SINR", "${num(median(sinr))} dB", qualityLevel(Metric.CELL_SINR, median(sinr)))
        }
        if (rsrq.isNotEmpty()) parts += "RSRQ ${num(median(rsrq))} dB (${rsrqBand(median(rsrq))})"
        if (sinr.isNotEmpty()) parts += "SINR ${num(median(sinr))} dB (${sinrBand(median(sinr))})"
        lines += "Signal quality: " + parts.joinToString(", ") + ". Quality says how clean the " +
            "signal is; a strong but noisy signal still gives slow data."
    }

    val switching = cells.filterValues { rows -> rows.mapNotNull { it.pci }.distinct().size > 1 }
    if (switching.isNotEmpty()) {
        val pcis = all.mapNotNull { it.pci }.distinct().sorted()
        lines += "The phone switched between towers (PCI ${pcis.joinToString(", ")}) at " +
            "${switching.size} of ${cells.size} nodes. That is typical of a weak indoor signal " +
            "near the edge of two cells."
    }
    return InsightSection("Cellular", lines, ratings)
}

private fun pathLoss(
    logged: List<GridPoint>,
    byPoint: Map<Long, List<Sample>>,
    routers: List<Router>
): InsightSection? {
    if (routers.isEmpty()) return null
    val lines = ArrayList<String>()
    for (router in routers) {
        val fit = pathLossFit(router, logged, byPoint)
        if (fit == null) {
            lines += "${router.label}: too few nodes heard ${router.bssid} at different distances of " +
                "${num(MIN_FIT_DISTANCE_M)} m or more to fit a path-loss model."
            continue
        }
        lines += "${router.label}: path-loss exponent n = ${num(round1(fit.n))}, " +
            "${exponentBand(fit.n)}. RSSI at 1 m (P0) ${num(round1(fit.p0Dbm))} dBm, " +
            "shadowing σ ${num(round1(fit.sigmaDb))} dB" +
            (fit.r2?.let { ", R² ${String.format(Locale.US, "%.2f", it)}" } ?: "") +
            ", from ${fit.count} nodes at ${String.format(Locale.US, "%.1f to %.1f m", fit.minDistanceM, fit.maxDistanceM)}."
        val warnings = pathLossWarnings(fit)
        if (warnings.isNotEmpty()) {
            lines += warnings.joinToString(" ") + " Treat this n as indicative, not a measurement."
        }
        if (fit.tooClose.isNotEmpty()) {
            lines += "Left out ${fit.tooClose.joinToString(", ") { it.label }}, closer than " +
                "${num(MIN_FIT_DISTANCE_M)} m, where the log-distance model breaks down."
        }
        fit.eirpDbm?.let {
            lines += "Estimated EIRP about ${num(round1(it))} dBm (P0 plus the free-space loss over the " +
                "first metre). This is an estimate, not a measurement of the transmitter."
        }
    }
    return InsightSection("Path loss", lines)
}

/** Enabled, logged nodes: the only ones any analysis looks at. */
fun loggedNodes(points: List<GridPoint>) = points.filter { it.enabled && it.status == STATUS_DONE }

/** The report's Channels section on its own, for the Analysis tab. */
fun channelInsights(points: List<GridPoint>, samples: List<Sample>): InsightSection? =
    channels(loggedNodes(points), samples.groupBy { it.pointId })

/** The caveat every channel finding carries. */
const val CHANNEL_CAVEAT = "A phone scan is a snapshot of beacons: it shows how strong neighbours " +
    "are, not how busy they are. Real airtime use can't be measured without monitor mode."

private fun channelList(channels: Collection<Int>) = channels.joinToString(", ")

private fun channels(logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): InsightSection? {
    val report = channelReport(logged, byPoint) ?: return null
    val all = logged.flatMap { byPoint[it.id].orEmpty() }
    val links = all.filter { it.kind == KIND_LINK && it.freqMhz != null }
    val scans = all.filter { (it.kind == KIND_SCAN || it.kind == KIND_SCAN_CACHED) && it.rssiDbm != null }
    val lines = ArrayList<String>()

    val nets = report.networks
    val own = nets.count { it.own }
    lines += "Heard ${nets.size} access point${if (nets.size == 1) "" else "s"}: " +
        "${nets.count { it.band24 }} on 2.4 GHz and ${nets.count { it.band5 }} on 5 GHz" +
        nets.count { it.freqMhz >= 5925 }.let { if (it > 0) " (plus $it on 6 GHz)" else "" } +
        (if (own > 0) ", $own of them your own router's" else "") + "."

    if (scans.isNotEmpty() && links.isNotEmpty()) {
        val mySsid = links.groupingBy { it.ssid }.eachCount().maxByOrNull { it.value }?.key
        val myFreq = links.groupingBy { it.freqMhz!! }.eachCount().maxByOrNull { it.value }!!.key
        val myChannel = wifiChannel(myFreq)
        val neighbours = scans.filter { !it.ssid.isNullOrBlank() && it.ssid != mySsid }
            .groupBy { it.bssid }
            .mapValues { (_, rows) -> rows.first() to median(rows.map { it.rssiDbm!!.toDouble() }) }
            .values.sortedByDescending { it.second }
        if (neighbours.isEmpty()) {
            lines += "No other networks were heard, so there is nothing competing for the air."
        } else {
            lines += "${neighbours.size} neighbouring network${if (neighbours.size == 1) "" else "s"} " +
                "heard."
            val overlapping = if (myFreq < 3000 && myChannel != null) {
                neighbours.filter { (row, _) ->
                    val ch = row.freqMhz?.let { wifiChannel(it) }
                    row.freqMhz != null && row.freqMhz < 3000 && ch != null && abs(ch - myChannel) < 4
                }
            } else {
                neighbours.filter { (row, _) -> row.freqMhz == myFreq }
            }
            val loud = overlapping.filter { it.second > INTERFERING_DBM }
            val (top, topLevel) = neighbours.first()
            val topCh = top.freqMhz?.let { wifiChannel(it) }
            lines += if (loud.isNotEmpty()) {
                "Possible interference: ${loud.joinToString(", ") { "${it.first.ssid} (${num(it.second)} dBm)" }} " +
                    "${if (loud.size == 1) "shares" else "share"} airtime with your channel $myChannel."
            } else {
                "Interference looks low: the strongest neighbour, ${top.ssid}, is at ${num(topLevel)} dBm" +
                    (if (topCh != null) " on channel $topCh" else "") +
                    when (overlapping.size) {
                        0 -> ", and none overlap your channel."
                        1 -> ", and the one that overlaps your channel is weak."
                        else -> ", and the ${overlapping.size} that overlap your channel are all weak."
                    }
            }
        }
    }

    report.advice24?.let { a ->
        val current = a.current
        if (current != null) {
            lines += if (a.change) {
                "Channel ${a.best.channel} would be quieter than your channel $current by " +
                    (if (a.gainDb!!.isInfinite()) "a wide margin: no neighbour overlaps it" else "${num(round1(a.gainDb))} dB") +
                    ". Consider moving the router's 2.4 GHz radio there."
            } else {
                "Channel $current is fine: none of channels ${channelList(CANDIDATES_24)} is at least " +
                    "${num(CHANNEL_CHANGE_DB)} dB quieter, so changing would not clearly help."
            }
            if (current !in CANDIDATES_24) {
                lines += "Channel $current overlaps two of the standard channels 1, 6 and 11, so it " +
                    "collides with networks on both."
            }
        } else {
            lines += "The quietest 2.4 GHz channel here is ${a.best.channel}."
        }
    }

    val own5 = report.connected?.takeIf { it.band5 }
    report.advice5?.let { a ->
        if (own5 != null) {
            lines += "On 5 GHz your channel ${own5.channel} (${own5.widthMhz} MHz) has " +
                "${report.coChannel5.size} co-channel and ${report.overlapping5.size} overlapping " +
                "network${if (report.overlapping5.size == 1) "" else "s"}" +
                (if (report.coChannel5.isNotEmpty()) ": " + report.coChannel5.joinToString(", ") { it.label } else "") + "."
            lines += if (a.change) {
                "Channel ${a.best.channel} is the least occupied 5 GHz channel and would be quieter."
            } else {
                "Channel ${own5.channel} is already among the least occupied 5 GHz channels."
            }
        } else {
            lines += "The least occupied 5 GHz channel here is ${a.best.channel}."
        }
        val dfs = listOfNotNull(own5?.channel, a.best.channel).filter { isDfs(it) }.distinct()
        if (dfs.isNotEmpty()) {
            lines += "Channel ${channelList(dfs)} is a DFS channel (52 to 144): the router must " +
                "leave it if it detects radar, which costs a short outage."
        }
    }

    lines += CHANNEL_CAVEAT
    return InsightSection("Channels", lines)
}

private fun round1(v: Double) = Math.round(v * 10) / 10.0

private fun latency(logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): InsightSection? {
    val pings = logged.associateWith { p -> byPoint[p.id].orEmpty().lastOrNull { it.kind == KIND_PING } }
        .filterValues { it != null }.mapValues { it.value!! }
    if (pings.isEmpty()) return null
    val lines = ArrayList<String>()
    val hosts = pings.values.mapNotNull { it.pingHost }.distinct()

    val answered = pings.filterValues { it.rttAvgMs != null }
    if (answered.isEmpty()) {
        lines += if (hosts.isEmpty()) {
            "No ping target could be chosen, so latency, jitter and loss were not measured."
        } else if (hosts.any { it.startsWith("fe80", ignoreCase = true) }) {
            "Every ping went unanswered because the target, ${hosts.first()}, is an IPv6 " +
                "link-local address that ping cannot reach. Older GridSense builds picked it by " +
                "mistake; update the app and re-log to get latency, jitter and loss."
        } else {
            "Every ping to ${hosts.joinToString(", ")} went unanswered. Some routers and networks " +
                "block ping; try setting a different ping target, such as 8.8.8.8."
        }
        return InsightSection("Latency, jitter and loss", lines)
    }

    val ratings = ArrayList<Rating>()
    lines += "Pinged ${hosts.joinToString(", ")}."
    val lat = answered.mapValues { it.value.rttAvgMs!! }
    val slow = lat.maxByOrNull { it.value }!!
    val latMedian = median(lat.values.toList())
    ratings += Rating("Median latency", "${num(latMedian)} ms", qualityLevel(Metric.LATENCY, latMedian))
    lines += "Latency median ${num(latMedian)} ms, worst ${num(slow.value)} ms at " +
        "${labelOf(slow.key)}: " + when {
            latMedian <= 20 -> "low, fine for anything including gaming and calls."
            latMedian <= 50 -> "moderate, fine for browsing and video."
            else -> "high, noticeable in calls and games."
        }
    val jitter = answered.values.mapNotNull { it.rttJitterMs }
    if (jitter.isNotEmpty()) {
        val j = median(jitter)
        ratings += Rating("Median jitter", "${num(j)} ms", qualityLevel(Metric.JITTER, j))
        lines += "Jitter median ${num(j)} ms: " + when {
            j <= 5 -> "steady."
            j <= 30 -> "some variation, usually fine."
            else -> "unsteady, which hurts calls more than raw latency does."
        }
    }
    val lossy = pings.filterValues { (it.rttLossPct ?: 0.0) > 0 }
    val worstLoss = answered.values.mapNotNull { it.rttLossPct }.maxOrNull()
    if (worstLoss != null) {
        ratings += Rating("Worst loss", "${num(worstLoss)} %", qualityLevel(Metric.LOSS, worstLoss))
    }
    lines += if (lossy.isEmpty()) {
        "No packets were lost at any node."
    } else {
        "Packet loss at ${lossy.size} node${if (lossy.size == 1) "" else "s"}: " +
            lossy.entries.joinToString(", ") { "${labelOf(it.key)} ${num(it.value.rttLossPct!!)}%" } + "."
    }
    return InsightSection("Latency, jitter and loss", lines, ratings)
}

private fun dataQuality(logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): InsightSection? {
    val fresh = logged.filter { p -> byPoint[p.id].orEmpty().any { it.kind == KIND_SCAN } }
    val cached = logged.filter { p ->
        byPoint[p.id].orEmpty().let { rows -> rows.none { it.kind == KIND_SCAN } && rows.any { it.kind == KIND_SCAN_CACHED } }
    }
    if (cached.isEmpty()) return null
    return InsightSection(
        "Data quality",
        listOf(
            "Only ${fresh.size} of ${logged.size} nodes have a fresh neighbour scan " +
                "(${joinLabels(fresh)}); the rest reused Android's cached list, because it " +
                "allows four scans every two minutes. Waiting about 30 seconds between nodes " +
                "gives every node a fresh scan."
        )
    )
}

/** The whole report as plain text, for sharing or pasting into a write-up. */
fun insightsAsText(title: String, sections: List<InsightSection>): String = buildString {
    append(title).append('\n')
    for (section in sections) {
        append('\n').append(section.title).append('\n')
        for (line in section.lines) append("- ").append(line).append('\n')
    }
}
