package com.gridsense.data

import com.gridsense.core.Metric
import com.gridsense.core.QualityLevel
import com.gridsense.core.median
import com.gridsense.core.qualityLevel

/** Lookups and connects per node. */
const val PROBE_ATTEMPTS = 5

/** The load test stops after this long even if the download has not finished. */
const val LOAD_TEST_MAX_MS = 8_000L

/** Ping interval during the load test; non-root Linux ping refuses anything shorter. */
const val LOADED_PING_INTERVAL_S = 0.2

/** Download goodput in Mbps, or null when no time elapsed. */
fun goodputMbps(bytes: Long, durationMs: Long): Double? =
    if (durationMs <= 0) null else bytes * 8.0 / (durationMs / 1000.0) / 1e6

/** Loaded minus idle round trip: how much queueing a busy link adds. */
fun bufferbloatMs(loadedMedianMs: Double, idleMs: Double): Double = loadedMedianMs - idleMs

/** Waveform-style grade: below 5 ms excellent, below 30 ms good, below 60 ms fair, else poor. */
fun bufferbloatGrade(ms: Double): QualityLevel = qualityLevel(Metric.BUFFERBLOAT, ms)

/** The byte cap in a `bytes=N` query parameter, which is how the default URL sets its size. */
fun downloadCapBytes(url: String): Long? =
    Regex("""[?&]bytes=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()

/** "about 20 MB per node", or a time-bounded description when the URL has no byte cap. */
fun dataUsePerNode(url: String): String {
    val cap = downloadCapBytes(url)
    return if (cap == null) "up to ${LOAD_TEST_MAX_MS / 1000} s of download per node"
    else "about ${Math.round(cap / 1e6)} MB per node"
}

/** One kind of probe at one node: the median of the successful attempts, and the failures. */
data class ProbeStats(val medianMs: Double?, val ok: Int, val failed: Int) {
    val attempts get() = ok + failed
}

private fun probes(rows: List<Sample>): ProbeStats? {
    if (rows.isEmpty()) return null
    val good = rows.filter { it.probeOk == true && it.probeMs != null }.map { it.probeMs!! }
    return ProbeStats(if (good.isEmpty()) null else median(good), good.size, rows.size - good.size)
}

/** Everything about latency at one node, aggregated from its per-attempt rows. */
data class NodeLatency(
    /** The idle ping's average RTT, the baseline for bufferbloat. */
    val idleMs: Double?,
    val dns: ProbeStats?,
    val tcp: ProbeStats?,
    val loadedMedianMs: Double?,
    val loadedReplies: Int,
    /** Null when no load test ran at this node. */
    val download: Sample?
) {
    val loadTested get() = download != null
    val downloadOk get() = download?.probeOk == true
    val bufferbloatMs: Double?
        get() = if (loadedMedianMs != null && idleMs != null) bufferbloatMs(loadedMedianMs, idleMs) else null
    val goodputMbps: Double?
        get() = download?.let { d ->
            val bytes = d.bytes ?: return@let null
            val ms = d.durationMs ?: return@let null
            if (bytes <= 0) null else goodputMbps(bytes, ms)
        }
}

fun nodeLatency(samples: List<Sample>): NodeLatency {
    val loaded = samples.filter { it.kind == KIND_LOADED_PING && it.probeMs != null }.map { it.probeMs!! }
    return NodeLatency(
        idleMs = samples.lastOrNull { it.kind == KIND_PING }?.rttAvgMs,
        dns = probes(samples.filter { it.kind == KIND_DNS }),
        tcp = probes(samples.filter { it.kind == KIND_TCP }),
        loadedMedianMs = if (loaded.isEmpty()) null else median(loaded),
        loadedReplies = loaded.size,
        download = samples.lastOrNull { it.kind == KIND_DOWNLOAD }
    )
}

/** The node's value for one of the latency-beyond-ping metrics. */
fun latencyValue(samples: List<Sample>, metric: Metric): Double? {
    val n = nodeLatency(samples)
    return when (metric) {
        Metric.DNS -> n.dns?.medianMs
        Metric.TCP_CONNECT -> n.tcp?.medianMs
        Metric.LOADED_LATENCY -> n.loadedMedianMs
        Metric.BUFFERBLOAT -> n.bufferbloatMs
        Metric.DOWNLOAD -> n.goodputMbps
        else -> null
    }
}
