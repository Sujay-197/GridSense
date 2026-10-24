package com.gridsense.data

import com.gridsense.core.DEFAULT_WIDTH_MHZ
import com.gridsense.core.channelCenterMhz
import com.gridsense.core.dbmToMw
import com.gridsense.core.isDfs
import com.gridsense.core.median
import com.gridsense.core.spectralOverlap
import kotlin.math.log10

/** A change of channel is only advised when it is at least this much quieter: a factor of 2. */
const val CHANNEL_CHANGE_DB = 3.0

/** The non-overlapping 2.4 GHz channels a router should sit on. */
val CANDIDATES_24 = listOf(1, 6, 11)

/** 20 MHz 5 GHz channels: UNII-1, the DFS range, and UNII-3. */
val CANDIDATES_5 = (36..64 step 4) + (100..144 step 4) + (149..165 step 4)

/** One access point, deduplicated across the nodes that heard it. */
data class AirNetwork(
    val bssid: String,
    /** Blank for a hidden network. */
    val ssid: String,
    val freqMhz: Int,
    val channel: Int?,
    val widthMhz: Int,
    /** Centre of the whole occupied block, which differs from [freqMhz] above 20 MHz. */
    val centerMhz: Double,
    /** Median over the nodes that heard it of each node's median reading. */
    val rssiDbm: Double,
    val nodesSeen: Int,
    /** The connected network, or another radio of the same router. */
    val own: Boolean
) {
    val band24 get() = freqMhz < 3000
    val band5 get() = freqMhz in 5000..5924
    val label get() = ssid.ifBlank { "(hidden)" }
    val lowMhz get() = centerMhz - widthMhz / 2.0
    val highMhz get() = centerMhz + widthMhz / 2.0
}

private fun octets(bssid: String): List<String>? =
    bssid.lowercase().split(':').takeIf { it.size == 6 }

/**
 * Two BSSIDs belong to the same device when they differ only in the first and/or last octet:
 * routers derive their 5 GHz, guest and hidden backhaul radios that way.
 */
fun sameDevice(a: String, b: String): Boolean {
    val x = octets(a) ?: return a.equals(b, ignoreCase = true)
    val y = octets(b) ?: return false
    return (1..4).all { x[it] == y[it] }
}

private fun <T> mostCommon(values: List<T>): T? =
    values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

/** Every BSSID heard by a scan or the connection, strongest first. */
fun airNetworks(logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): List<AirNetwork> {
    val ids = logged.map { it.id }.toSet()
    val rows = ids.flatMap { byPoint[it].orEmpty() }
    val links = rows.filter { it.kind == KIND_LINK && it.bssid != null && it.freqMhz != null }
    val connectedSsid = mostCommon(links.mapNotNull { it.ssid?.takeIf(String::isNotBlank) })
    val connectedBssids = links.mapNotNull { it.bssid?.lowercase() }.distinct()

    fun isOwn(bssid: String, ssid: String) =
        (connectedSsid != null && ssid == connectedSsid) ||
            connectedBssids.any { it == bssid || sameDevice(it, bssid) }

    fun build(bssid: String, group: List<Sample>): AirNetwork {
        val perNode = group.groupBy { it.pointId }.values.map { node -> median(node.map { it.rssiDbm!!.toDouble() }) }
        val freq = mostCommon(group.map { it.freqMhz!! })!!
        val width = mostCommon(group.mapNotNull { it.channelWidthMhz }) ?: DEFAULT_WIDTH_MHZ
        val centre0 = mostCommon(group.mapNotNull { it.centerFreq0Mhz })
        val ssid = mostCommon(group.map { it.ssid.orEmpty() }).orEmpty()
        return AirNetwork(
            bssid = bssid,
            ssid = ssid,
            freqMhz = freq,
            channel = wifiChannel(freq),
            widthMhz = width,
            centerMhz = if (width > DEFAULT_WIDTH_MHZ && centre0 != null) centre0.toDouble() else freq.toDouble(),
            rssiDbm = median(perNode),
            nodesSeen = perNode.size,
            own = isOwn(bssid, ssid)
        )
    }

    val scans = rows.filter {
        (it.kind == KIND_SCAN || it.kind == KIND_SCAN_CACHED) &&
            it.bssid != null && it.freqMhz != null && it.rssiDbm != null
    }
    val heard = scans.groupBy { it.bssid!!.lowercase() }.map { (bssid, group) -> build(bssid, group) }
    // The connected AP is not always in the scan list; add it from the connection rows.
    val missing = links.filter { it.rssiDbm != null }
        .groupBy { it.bssid!!.lowercase() }
        .filterKeys { b -> heard.none { it.bssid == b } }
        .map { (bssid, group) -> build(bssid, group) }
    return (heard + missing).sortedByDescending { it.rssiDbm }
}

data class ChannelScore(val channel: Int, val scoreMw: Double)

/**
 * The quietest candidate for one band. [current] is the connected channel on this band, or null
 * when the phone was not connected there. [change] is true only when [best] beats the current
 * channel by at least [CHANNEL_CHANGE_DB].
 */
data class ChannelAdvice(
    val band5: Boolean,
    val current: Int?,
    val currentScoreMw: Double?,
    val scores: List<ChannelScore>,
    val best: ChannelScore,
    val change: Boolean,
    /** How much quieter [best] is than the current channel, in dB; infinite when best is silent. */
    val gainDb: Double?
)

/**
 * Interference score of a 20 MHz channel: each other network's power in mW weighted by the
 * fraction of the channel its block overlaps.
 */
fun channelScore(channel: Int, band5: Boolean, others: List<AirNetwork>): Double {
    val centre = channelCenterMhz(channel, band5).toDouble()
    return others.sumOf { n ->
        spectralOverlap(centre, DEFAULT_WIDTH_MHZ.toDouble(), n.centerMhz, n.widthMhz.toDouble()) * dbmToMw(n.rssiDbm)
    }
}

fun gainDb(currentMw: Double, bestMw: Double): Double = when {
    currentMw <= 0.0 -> 0.0
    bestMw <= 0.0 -> Double.POSITIVE_INFINITY
    else -> 10 * log10(currentMw / bestMw)
}

/** Picks the quietest candidate; ties go to the current channel, then to non-DFS, then lower. */
fun adviseChannel(
    band5: Boolean,
    candidates: List<Int>,
    current: Int?,
    others: List<AirNetwork>
): ChannelAdvice {
    val scores = candidates.map { ChannelScore(it, channelScore(it, band5, others)) }
    val best = scores.minWith(
        compareBy<ChannelScore> { it.scoreMw }
            .thenBy { if (it.channel == current) 0 else 1 }
            .thenBy { if (band5 && isDfs(it.channel)) 1 else 0 }
            .thenBy { it.channel }
    )
    val currentScore = current?.let { channelScore(it, band5, others) }
    val gain = currentScore?.let { gainDb(it, best.scoreMw) }
    val change = current != null && best.channel != current && gain != null && gain >= CHANNEL_CHANGE_DB
    return ChannelAdvice(band5, current, currentScore, scores, best, change, gain)
}

/** Everything the Channels view and report section need. */
data class ChannelReport(
    val networks: List<AirNetwork>,
    /** The connected network, if any. */
    val connected: AirNetwork?,
    val advice24: ChannelAdvice?,
    val advice5: ChannelAdvice?,
    /** Other networks on the connected 5 GHz channel. */
    val coChannel5: List<AirNetwork>,
    /** Other networks whose 5 GHz block overlaps the connected one without sharing its channel. */
    val overlapping5: List<AirNetwork>
)

fun channelReport(logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): ChannelReport? {
    val networks = airNetworks(logged, byPoint)
    if (networks.isEmpty()) return null
    val linkBssids = logged.flatMap { byPoint[it.id].orEmpty() }
        .filter { it.kind == KIND_LINK }.mapNotNull { it.bssid?.lowercase() }
    val connectedBssid = mostCommon(linkBssids)
    val connected = networks.firstOrNull { it.bssid == connectedBssid }
    val others = networks.filter { !it.own }

    val advice24 = if (networks.any { it.band24 }) {
        adviseChannel(
            band5 = false,
            candidates = CANDIDATES_24,
            current = connected?.takeIf { it.band24 }?.channel,
            others = others.filter { it.band24 }
        )
    } else null

    val others5 = others.filter { it.band5 }
    val own5 = connected?.takeIf { it.band5 }
    val advice5 = if (networks.any { it.band5 }) {
        adviseChannel(band5 = true, candidates = CANDIDATES_5, current = own5?.channel, others = others5)
    } else null
    val co = if (own5 == null) emptyList() else others5.filter { it.channel == own5.channel }
    val overlapping = if (own5 == null) emptyList() else others5.filter {
        it.channel != own5.channel &&
            spectralOverlap(own5.centerMhz, own5.widthMhz.toDouble(), it.centerMhz, it.widthMhz.toDouble()) > 0
    }
    return ChannelReport(networks, connected, advice24, advice5, co, overlapping)
}
