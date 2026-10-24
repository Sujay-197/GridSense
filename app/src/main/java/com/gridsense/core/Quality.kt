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
 * [level]; for a lower-is-better one a value at or below it does. With [inclusive] false the
 * limit itself falls in the next band ("below 5 ms" rather than "up to 5 ms"). The last band of
 * every scale has an infinite limit, so every value lands somewhere.
 */
data class QualityBand(val level: QualityLevel, val limit: Double, val inclusive: Boolean = true)

private fun bands(vararg pairs: Pair<QualityLevel, Double>) = pairs.map { QualityBand(it.first, it.second) }

private fun strictBands(vararg pairs: Pair<QualityLevel, Double>) =
    pairs.map { QualityBand(it.first, it.second, inclusive = false) }

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
    // A lookup from a nearby resolver is tens of ms; past 150 ms pages feel slow to start.
    Metric.DNS -> bands(
        QualityLevel.GOOD to 50.0,
        QualityLevel.FAIR to 150.0,
        QualityLevel.POOR to POS
    )
    // About one round trip to a public anycast host.
    Metric.TCP_CONNECT -> bands(
        QualityLevel.GOOD to 50.0,
        QualityLevel.FAIR to 150.0,
        QualityLevel.POOR to POS
    )
    Metric.LOADED_LATENCY -> bands(
        QualityLevel.GOOD to 50.0,
        QualityLevel.FAIR to 100.0,
        QualityLevel.POOR to POS
    )
    // Waveform-style bufferbloat grades: below 5 ms excellent, below 30 good, below 60 fair.
    Metric.BUFFERBLOAT -> strictBands(
        QualityLevel.EXCELLENT to 5.0,
        QualityLevel.GOOD to 30.0,
        QualityLevel.FAIR to 60.0,
        QualityLevel.POOR to POS
    )
    Metric.DOWNLOAD -> bands(
        QualityLevel.EXCELLENT to 100.0,
        QualityLevel.GOOD to 25.0,
        QualityLevel.FAIR to 5.0,
        QualityLevel.POOR to NEG
    )
}

fun qualityLevel(metric: Metric, value: Double): QualityLevel {
    val scale = qualityBands(metric)
    val hit = scale.firstOrNull { band ->
        when {
            metric.higherIsBetter && band.inclusive -> value >= band.limit
            metric.higherIsBetter -> value > band.limit
            band.inclusive -> value <= band.limit
            else -> value < band.limit
        }
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
        val prevBand = scale.getOrNull(i - 1)
        val previous = prevBand?.limit
        val text = when {
            previous == null && metric.higherIsBetter -> (if (band.inclusive) "≥ " else "> ") + plain(band.limit)
            previous == null -> (if (band.inclusive) "≤ " else "< ") + plain(band.limit)
            band.limit.isInfinite() && metric.higherIsBetter -> (if (prevBand.inclusive) "< " else "≤ ") + plain(previous)
            band.limit.isInfinite() -> (if (prevBand.inclusive) "> " else "≥ ") + plain(previous)
            metric.higherIsBetter -> plain(band.limit) + " to " + plain(previous)
            else -> plain(previous) + " to " + plain(band.limit)
        }
        band.level to text
    }
}
