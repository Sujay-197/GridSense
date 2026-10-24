package com.gridsense.core

import kotlin.math.abs

/** A position in metres inside the room's own coordinate system. */
data class Pt(val x: Double, val y: Double)

data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}

fun boundsOf(poly: List<Pt>): Bounds {
    var minX = poly[0].x
    var maxX = poly[0].x
    var minY = poly[0].y
    var maxY = poly[0].y
    for (p in poly) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return Bounds(minX, minY, maxX, maxY)
}

/** Ray-casting test. Points exactly on an edge may fall either way. */
fun pointInPolygon(poly: List<Pt>, p: Pt): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y)) {
            val xAtY = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            if (p.x < xAtY) inside = !inside
        }
        j = i
    }
    return inside
}

fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/** Jitter as defined for this survey: the mean absolute difference of consecutive RTTs. */
fun meanAbsDiff(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    var total = 0.0
    for (i in 1 until values.size) total += abs(values[i] - values[i - 1])
    return total / (values.size - 1)
}

/** One measured value at a known position, used as an IDW control point. */
data class Obs(val x: Double, val y: Double, val v: Double)

/** Inverse distance weighting with p = 2. */
fun idw(obs: List<Obs>, x: Double, y: Double): Double {
    var numerator = 0.0
    var denominator = 0.0
    for (o in obs) {
        val dx = o.x - x
        val dy = o.y - y
        val d2 = dx * dx + dy * dy
        if (d2 < 1e-12) return o.v
        val w = 1.0 / d2
        numerator += w * o.v
        denominator += w
    }
    return numerator / denominator
}
