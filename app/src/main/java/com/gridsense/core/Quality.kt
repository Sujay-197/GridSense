package com.gridsense.core

/** The shared quality scale used by grid dots, fixed-scale heatmaps, legends, chips and the report. */
enum class QualityLevel(val word: String) {
    EXCELLENT("excellent"),
    VERY_GOOD("very good"),
    GOOD("good"),
    FAIR("fair"),
    WEAK("weak"),
    POOR("poor");

    /** "Very good" for chips and legends; the report uses [word] mid-sentence. */
    val title: String get() = word.replaceFirstChar { it.uppercase() }
}

/**
 * One band of a metric's scale. For a higher-is-better metric a value at or above [limit] earns
 * [level]; for a lower-is-better one a value at or below it does. The last band of every scale
 * has an infinite limit, so every value lands somewhere.
 */
data class QualityBand(val level: QualityLevel, val limit: Double)

private fun bands(vararg pairs: Pair<QualityLevel, Double>) = pairs.map { QualityBand(it.first, it.second) }

private val NEG = Double.NEGATIVE_INFINITY
private val POS = Double.POSITIVE_INFINITY

/** Best band first. Wi-Fi follows common RSSI guidance; cellular follows usual LTE RSRP/RSRQ/SINR tables. */
fun qualityBands(metric: Metric): List<QualityBand> = when (metric) {
    Metric.RSSI -> bands(
        QualityLevel.EXCELLENT to -50.0,
        QualityLevel.VERY_GOOD to -60.0,
        QualityLevel.GOOD to -67.0,
        QualityLevel.FAIR to -70.0,
        QualityLevel.WEAK to -80.0,
        QualityLevel.POOR to NEG
    )
    Metric.CELL_DBM, Metric.CELL_RSRP -> bands(
        QualityLevel.EXCELLENT to -80.0,
        QualityLevel.GOOD to -90.0,
        QualityLevel.FAIR to -100.0,
        QualityLevel.POOR to NEG
    )
    Metric.CELL_RSRQ -> bands(
        QualityLevel.GOOD to -10.0,
        QualityLevel.FAIR to -15.0,
        QualityLevel.POOR to NEG
    )
    Metric.CELL_SINR -> bands(
        QualityLevel.EXCELLENT to 20.0,
        QualityLevel.GOOD to 13.0,
        QualityLevel.FAIR to 0.0,
        QualityLevel.POOR to NEG
    )
    Metric.LATENCY -> bands(
        QualityLevel.GOOD to 20.0,
        QualityLevel.FAIR to 50.0,
        QualityLevel.POOR to POS
    )
    Metric.JITTER -> bands(
        QualityLevel.GOOD to 5.0,
        QualityLevel.FAIR to 30.0,
        QualityLevel.POOR to POS
    )
    // With ping -c 10 every lost reply is 10 %, so any loss at all is poor.
    Metric.LOSS -> bands(
        QualityLevel.GOOD to 0.0,
        QualityLevel.FAIR to 1.0,
        QualityLevel.POOR to POS
    )
}

fun qualityLevel(metric: Metric, value: Double): QualityLevel {
    val scale = qualityBands(metric)
    val hit = scale.firstOrNull { band ->
        if (metric.higherIsBetter) value >= band.limit else value <= band.limit
    }
    return (hit ?: scale.last()).level
}

private fun plain(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else value.toString()

/**
 * Legend text for each band of a fixed scale, best first: "≥ -50", "-60 to -50", "< -80" for
 * a higher-is-better metric, "≤ 20", "20 to 50", "> 50" for a lower-is-better one.
 */
fun bandLabels(metric: Metric): List<Pair<QualityLevel, String>> {
    val scale = qualityBands(metric)
    return scale.mapIndexed { i, band ->
        val previous = scale.getOrNull(i - 1)?.limit
        val text = when {
            previous == null && metric.higherIsBetter -> "≥ " + plain(band.limit)
            previous == null -> "≤ " + plain(band.limit)
            band.limit.isInfinite() -> (if (metric.higherIsBetter) "< " else "> ") + plain(previous)
            metric.higherIsBetter -> plain(band.limit) + " to " + plain(previous)
            else -> plain(previous) + " to " + plain(band.limit)
        }
        band.level to text
    }
}
