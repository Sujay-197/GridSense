package com.gridsense.data

import com.gridsense.core.median
import com.gridsense.core.nodeLabel
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sqrt

/** Nodes closer than this are left out: log10(d) runs away as d approaches 0. */
const val MIN_FIT_DISTANCE_M = 0.5

/** Below this many nodes the exponent is shown with a warning. */
const val MIN_TRUSTED_NODES = 4

/** Below this max/min distance ratio the slope rests on too short a span to trust. */
const val MIN_DISTANCE_RATIO = 2.0

/** One node's reading of the router. [fromLink] is false when it came from a neighbour scan. */
data class PathLossPoint(
    val pointId: Long,
    val label: String,
    val distanceM: Double,
    val rssiDbm: Double,
    val fromLink: Boolean
)

/** Straight-line fit y = a + b x by ordinary least squares. */
data class LineFit(
    val intercept: Double,
    val slope: Double,
    /** Null when every y is the same, so there is no variance to explain. */
    val r2: Double?,
    /** Residual standard deviation with N - 2 degrees of freedom; 0 for two points. */
    val sigma: Double
)

fun leastSquares(xs: List<Double>, ys: List<Double>): LineFit? {
    val n = xs.size
    if (n < 2 || ys.size != n) return null
    val mx = xs.average()
    val my = ys.average()
    val sxx = xs.sumOf { (it - mx) * (it - mx) }
    if (sxx < 1e-12) return null
    val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
    val slope = sxy / sxx
    val intercept = my - slope * mx
    val ssRes = xs.indices.sumOf { val r = ys[it] - (intercept + slope * xs[it]); r * r }
    val ssTot = ys.sumOf { (it - my) * (it - my) }
    val r2 = if (ssTot < 1e-12) null else 1 - ssRes / ssTot
    val sigma = if (n > 2) sqrt(ssRes / (n - 2)) else 0.0
    return LineFit(intercept, slope, r2, sigma)
}

/** Free-space path loss at 1 m: 20 log10(f in MHz) - 27.55, about 40 dB at 2.4 GHz. */
fun fsplAt1m(freqMhz: Double): Double = 20 * log10(freqMhz) - 27.55

/** What a path-loss exponent says about the room. */
fun exponentBand(n: Double): String = when {
    n < 2.0 -> "below free space, which points to guided propagation such as a corridor or strong reflections"
    n < 2.5 -> "close to free space, as with a clear line of sight"
    n < 3.5 -> "typical for indoors"
    n < 4.5 -> "obstructed"
    else -> "heavily obstructed"
}

/**
 * RSSI(d) = P0 - 10 n log10(d / 1 m), fitted to one router's readings. [n] is the path-loss
 * exponent, [p0Dbm] the RSSI at 1 m and [sigmaDb] the shadowing spread around the line.
 */
data class PathLossFit(
    val router: Router,
    val points: List<PathLossPoint>,
    /** Nodes left out for being closer than [MIN_FIT_DISTANCE_M]. */
    val tooClose: List<PathLossPoint>,
    val n: Double,
    val p0Dbm: Double,
    val r2: Double?,
    val sigmaDb: Double,
    val minDistanceM: Double,
    val maxDistanceM: Double,
    val freqMhz: Int?,
    /** P0 + FSPL(1 m): a rough estimate of the router's radiated power. */
    val eirpDbm: Double?
) {
    val count get() = points.size
    val fewNodes get() = count < MIN_TRUSTED_NODES
    val narrowRange get() = maxDistanceM / minDistanceM < MIN_DISTANCE_RATIO
    val trustworthy get() = !fewNodes && !narrowRange

    /** The model's RSSI at [d] metres. */
    fun predict(d: Double): Double = p0Dbm - 10 * n * log10(d)
}

/** Horizontal distance from node to router, plus the router's height when given. */
fun routerDistance(router: Router, x: Double, y: Double): Double =
    sqrt(hypot(x - router.x, y - router.y).let { it * it } + router.heightDiffM * router.heightDiffM)

/**
 * Each logged node's median RSSI for the router's BSSID: from the connection when the phone was
 * associated with it there, otherwise from the neighbour scans.
 */
fun pathLossPoints(router: Router, logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): List<PathLossPoint> =
    logged.mapNotNull { p ->
        val rows = byPoint[p.id].orEmpty().filter { it.bssid.equals(router.bssid, ignoreCase = true) && it.rssiDbm != null }
        val link = rows.filter { it.kind == KIND_LINK }
        val scan = rows.filter { it.kind == KIND_SCAN || it.kind == KIND_SCAN_CACHED }
        val used = link.ifEmpty { scan }
        if (used.isEmpty()) null
        else PathLossPoint(
            p.id,
            nodeLabel(p.row, p.col),
            routerDistance(router, p.x, p.y),
            median(used.map { it.rssiDbm!!.toDouble() }),
            fromLink = link.isNotEmpty()
        )
    }

/** Null when fewer than two nodes at different distances are usable. */
fun pathLossFit(router: Router, logged: List<GridPoint>, byPoint: Map<Long, List<Sample>>): PathLossFit? {
    val all = pathLossPoints(router, logged, byPoint)
    val (used, close) = all.partition { it.distanceM >= MIN_FIT_DISTANCE_M }
    val line = leastSquares(used.map { 10 * log10(it.distanceM) }, used.map { it.rssiDbm }) ?: return null
    val freqs = logged.flatMap { byPoint[it.id].orEmpty() }
        .filter { it.bssid.equals(router.bssid, ignoreCase = true) }
        .mapNotNull { it.freqMhz }
    val freq = freqs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return PathLossFit(
        router = router,
        points = used,
        tooClose = close,
        n = -line.slope,
        p0Dbm = line.intercept,
        r2 = line.r2,
        sigmaDb = line.sigma,
        minDistanceM = used.minOf { it.distanceM },
        maxDistanceM = used.maxOf { it.distanceM },
        freqMhz = freq,
        eirpDbm = freq?.let { line.intercept + fsplAt1m(it.toDouble()) }
    )
}

/** Plain-language caveats for a fit, empty when it can be taken at face value. */
fun pathLossWarnings(fit: PathLossFit): List<String> = buildList {
    if (fit.fewNodes) {
        add("Only ${fit.count} node${if (fit.count == 1) "" else "s"} could be used; at least $MIN_TRUSTED_NODES are needed for a trustworthy exponent.")
    }
    if (fit.narrowRange) {
        add(
            String.format(
                java.util.Locale.US,
                "The distances only span %.1f to %.1f m, under a factor of %.0f, so the slope rests on too short a range to trust.",
                fit.minDistanceM, fit.maxDistanceM, MIN_DISTANCE_RATIO
            )
        )
    }
}
