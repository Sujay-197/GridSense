package com.gridsense.data

import com.gridsense.core.Band
import com.gridsense.core.Metric
import com.gridsense.core.Obs
import com.gridsense.core.Pt
import com.gridsense.core.boundsOf
import com.gridsense.core.idw
import com.gridsense.core.median
import com.gridsense.core.pointInPolygon

/** The single number a point contributes to the heatmap for the selected metric. */
fun pointValue(samples: List<Sample>, metric: Metric, bssidFilter: String?): Double? =
    when (metric.band) {
        Band.WIFI -> {
            val rows = if (bssidFilter == null) {
                samples.filter { it.kind == KIND_LINK }
            } else {
                samples.filter { it.bssid == bssidFilter }
            }
            medianOrNull(rows.mapNotNull { it.rssiDbm?.toDouble() })
        }

        Band.CELLULAR -> {
            val rows = samples.filter { it.kind == KIND_CELL }
            val values = when (metric) {
                Metric.CELL_DBM -> rows.mapNotNull { it.cellDbm?.toDouble() }
                Metric.CELL_RSRP -> rows.mapNotNull { it.rsrp?.toDouble() }
                Metric.CELL_RSRQ -> rows.mapNotNull { it.rsrq?.toDouble() }
                Metric.CELL_SINR -> rows.mapNotNull { it.sinr?.toDouble() }
                else -> emptyList()
            }
            medianOrNull(values)
        }

        Band.IP -> {
            val ping = samples.lastOrNull { it.kind == KIND_PING }
            when (metric) {
                Metric.LATENCY -> ping?.rttAvgMs
                Metric.JITTER -> ping?.rttJitterMs
                Metric.LOSS -> ping?.rttLossPct
                else -> null
            }
        }
    }

private fun medianOrNull(values: List<Double>): Double? =
    if (values.isEmpty()) null else median(values)

/** Median RSSI of the connected AP, shown on the point sheet. */
fun medianLinkRssi(samples: List<Sample>): Double? =
    medianOrNull(samples.filter { it.kind == KIND_LINK }.mapNotNull { it.rssiDbm?.toDouble() })

/** Median signal of the serving cell, shown on the point sheet. */
fun medianCellDbm(samples: List<Sample>): Double? =
    medianOrNull(samples.filter { it.kind == KIND_CELL }.mapNotNull { it.cellDbm?.toDouble() })

fun observations(
    points: List<GridPoint>,
    samplesByPoint: Map<Long, List<Sample>>,
    metric: Metric,
    bssidFilter: String?
): List<Obs> = points
    .filter { it.enabled }
    .mapNotNull { p ->
        val v = pointValue(samplesByPoint[p.id].orEmpty(), metric, bssidFilter)
        if (v == null) null else Obs(p.x, p.y, v)
    }

/**
 * An IDW surface sampled on a regular raster and clipped to the room outline. Row 0 is the
 * top of the plan, so [originY] is the highest world y, matching the screen transform.
 */
class Raster(
    val width: Int,
    val height: Int,
    val originX: Double,
    val originY: Double,
    val cell: Double,
    val values: DoubleArray,
    val inside: BooleanArray,
    val min: Double,
    val max: Double
)

fun rasterize(polygonM: List<Pt>, obs: List<Obs>, cell: Double = 0.1): Raster? {
    if (obs.isEmpty() || polygonM.size < 3) return null
    val b = boundsOf(polygonM)
    val width = Math.ceil(b.width / cell).toInt().coerceAtLeast(1)
    val height = Math.ceil(b.height / cell).toInt().coerceAtLeast(1)
    val values = DoubleArray(width * height)
    val inside = BooleanArray(width * height)
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    for (row in 0 until height) {
        val y = b.maxY - (row + 0.5) * cell
        for (col in 0 until width) {
            val x = b.minX + (col + 0.5) * cell
            val index = row * width + col
            if (!pointInPolygon(polygonM, Pt(x, y))) continue
            val v = idw(obs, x, y)
            inside[index] = true
            values[index] = v
            if (v < min) min = v
            if (v > max) max = v
        }
    }
    if (min > max) return null
    return Raster(width, height, b.minX, b.maxY, cell, values, inside, min, max)
}

/** Maps a value to 0 (worst) .. 1 (best) for the colour ramp. */
fun quality(value: Double, min: Double, max: Double, higherIsBetter: Boolean): Double {
    val span = max - min
    val t = if (span < 1e-9) 0.5 else (value - min) / span
    return if (higherIsBetter) t else 1.0 - t
}

/** Red (worst) through yellow to green (best), returned as packed ARGB. */
fun rampArgb(q: Double, alpha: Int = 255): Int {
    val t = q.coerceIn(0.0, 1.0)
    val r: Int
    val g: Int
    if (t < 0.5) {
        r = 220
        g = (40 + (215 - 40) * (t / 0.5)).toInt()
    } else {
        r = (220 - (220 - 40) * ((t - 0.5) / 0.5)).toInt()
        g = 190
    }
    return (alpha shl 24) or (r shl 16) or (g shl 8) or 60
}
