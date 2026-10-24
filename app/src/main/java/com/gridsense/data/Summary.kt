package com.gridsense.data

import com.gridsense.core.Metric
import com.gridsense.core.QualityLevel
import com.gridsense.core.median
import com.gridsense.core.qualityLevel

/** What a survey card on Home shows: progress and the primary metric's typical value. */
data class SurveySummary(
    val total: Int,
    val logged: Int,
    val primaryMedian: Double?,
    val level: QualityLevel?
) {
    val progress: Float get() = if (total == 0) 0f else logged.toFloat() / total
}

/**
 * Progress counts enabled nodes only. The median is taken over the logged nodes' own medians
 * of [metric], so a node with more readings does not count for more.
 */
fun surveySummary(
    points: List<GridPoint>,
    samplesByPoint: Map<Long, List<Sample>>,
    metric: Metric
): SurveySummary {
    val active = points.filter { it.enabled }
    val logged = active.filter { it.status == STATUS_DONE }
    val values = logged.mapNotNull { pointValue(samplesByPoint[it.id].orEmpty(), metric, null) }
    val m = if (values.isEmpty()) null else median(values)
    return SurveySummary(active.size, logged.size, m, m?.let { qualityLevel(metric, it) })
}
