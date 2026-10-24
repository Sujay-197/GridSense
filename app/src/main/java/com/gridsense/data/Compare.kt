package com.gridsense.data

import com.gridsense.core.Metric
import com.gridsense.core.nodeLabel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The smallest per-node change counted as "changed". RSSI and RSRP use 3 dB, a halving or
 * doubling of power. RSRQ moves over a narrower range, so 2 dB. Latency and jitter use 5 ms,
 * about what a gateway ping wanders by on its own. With ping -c 10 one lost reply is 10 %.
 * DNS, TCP and loaded latency go beyond the first hop and vary more, so 10 ms; download 5 Mbps.
 */
fun changeThreshold(metric: Metric): Double = when (metric) {
    Metric.RSSI, Metric.CELL_DBM, Metric.CELL_RSRP, Metric.CELL_SINR -> 3.0
    Metric.CELL_RSRQ -> 2.0
    Metric.LATENCY, Metric.JITTER -> 5.0
    Metric.LOSS -> 10.0
    Metric.DNS, Metric.TCP_CONNECT, Metric.LOADED_LATENCY, Metric.BUFFERBLOAT -> 10.0
    Metric.DOWNLOAD -> 5.0
}

/** Why two surveys can't be compared, or null when they can. */
fun incompatibility(a: SurveyRoom, b: SurveyRoom): String? = when {
    a.id == b.id -> "That is the same survey."
    a.gridRows != b.gridRows || a.gridCols != b.gridCols ->
        "Different grids (${a.gridRows} x ${a.gridCols} and ${b.gridRows} x ${b.gridCols}): nodes can " +
            "only be matched on the same rows and columns."
    else -> null
}

/** One node in both surveys. [delta] is B - A; [gain] is signed so that positive is always better in B. */
data class NodeDelta(
    val row: Int,
    val col: Int,
    val x: Double,
    val y: Double,
    val a: Double,
    val b: Double,
    val delta: Double,
    val gain: Double
) {
    val label get() = nodeLabel(row, col)
}

/** Nodes matched on (row, col) that are enabled and logged in both surveys and have a value in both. */
fun compareNodes(
    pointsA: List<GridPoint>,
    samplesA: Map<Long, List<Sample>>,
    pointsB: List<GridPoint>,
    samplesB: Map<Long, List<Sample>>,
    metric: Metric
): List<NodeDelta> {
    fun usable(points: List<GridPoint>) =
        points.filter { it.enabled && it.status == STATUS_DONE }.associateBy { it.row to it.col }
    val a = usable(pointsA)
    val b = usable(pointsB)
    return a.keys.intersect(b.keys).sortedWith(compareBy({ it.first }, { it.second })).mapNotNull { key ->
        val pa = a.getValue(key)
        val pb = b.getValue(key)
        val va = pointValue(samplesA[pa.id].orEmpty(), metric, null) ?: return@mapNotNull null
        val vb = pointValue(samplesB[pb.id].orEmpty(), metric, null) ?: return@mapNotNull null
        val d = vb - va
        NodeDelta(key.first, key.second, pa.x, pa.y, va, vb, d, if (metric.higherIsBetter) d else -d)
    }
}

/** Sample standard deviation (N - 1); 0 for fewer than two values. */
fun sampleSd(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val m = values.average()
    return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
}

data class CompareSummary(
    val count: Int,
    val meanDelta: Double,
    val meanAbsDelta: Double,
    val sdDelta: Double,
    /** The node that got most better in B, if any did. */
    val bestImprovement: NodeDelta?,
    /** The node that got most worse in B, if any did. */
    val worstDegradation: NodeDelta?,
    val threshold: Double,
    val improved: Int,
    val degraded: Int
) {
    val changed get() = improved + degraded

    /** As a same-conditions pair, changes under ± this are measurement noise (about 95 %). */
    val repeatabilityBand get() = 2 * sdDelta
}

fun summarise(deltas: List<NodeDelta>, metric: Metric): CompareSummary? {
    if (deltas.isEmpty()) return null
    val t = changeThreshold(metric)
    return CompareSummary(
        count = deltas.size,
        meanDelta = deltas.map { it.delta }.average(),
        meanAbsDelta = deltas.map { abs(it.delta) }.average(),
        sdDelta = sampleSd(deltas.map { it.delta }),
        bestImprovement = deltas.filter { it.gain > 0 }.maxByOrNull { it.gain },
        worstDegradation = deltas.filter { it.gain < 0 }.minByOrNull { it.gain },
        threshold = t,
        improved = deltas.count { it.gain >= t },
        degraded = deltas.count { it.gain <= -t }
    )
}

/**
 * A repeatability figure from an earlier same-condition pair on the same grid, to quote when
 * reading a comparison: changes smaller than [band] are within measurement noise.
 */
data class Repeatability(val pairName: String, val count: Int, val band: Double)

/** Two surveys count as a same-condition pair when they share a non-blank condition. */
fun sameCondition(a: SurveyRoom, b: SurveyRoom) =
    a.condition.isNotBlank() && a.condition.trim().equals(b.condition.trim(), ignoreCase = true)

/** The node table as CSV, with a header naming both surveys. */
fun compareCsv(a: SurveyRoom, b: SurveyRoom, metric: Metric, deltas: List<NodeDelta>): String = buildString {
    fun cell(v: Any?): String {
        val text = v?.toString() ?: ""
        return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
    fun row(vararg v: Any?) = append(v.joinToString(",") { cell(it) }).append('\n')
    row("metric", metric.label, metric.unit)
    row("a", a.name, a.condition)
    row("b", b.name, b.condition)
    row("node", "row", "col", "a", "b", "delta_b_minus_a", "better_in_b")
    for (d in deltas) row(d.label, d.row, d.col, d.a, d.b, d.delta, d.gain > 0)
}
