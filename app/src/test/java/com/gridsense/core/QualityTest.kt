package com.gridsense.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {

    @Test
    fun rssiBoundariesBelongToTheBetterBand() {
        assertEquals(QualityLevel.EXCELLENT, qualityLevel(Metric.RSSI, -50.0))
        assertEquals(QualityLevel.VERY_GOOD, qualityLevel(Metric.RSSI, -50.5))
        assertEquals(QualityLevel.VERY_GOOD, qualityLevel(Metric.RSSI, -60.0))
        assertEquals(QualityLevel.GOOD, qualityLevel(Metric.RSSI, -62.0))
        assertEquals(QualityLevel.FAIR, qualityLevel(Metric.RSSI, -70.0))
        assertEquals(QualityLevel.WEAK, qualityLevel(Metric.RSSI, -80.0))
        assertEquals(QualityLevel.POOR, qualityLevel(Metric.RSSI, -86.0))
    }

    @Test
    fun cellularScales() {
        assertEquals(QualityLevel.FAIR, qualityLevel(Metric.CELL_RSRP, -96.0))
        assertEquals(QualityLevel.POOR, qualityLevel(Metric.CELL_RSRP, -110.0))
        assertEquals(QualityLevel.FAIR, qualityLevel(Metric.CELL_DBM, -100.0))
        assertEquals(QualityLevel.GOOD, qualityLevel(Metric.CELL_RSRQ, -9.0))
        assertEquals(QualityLevel.POOR, qualityLevel(Metric.CELL_RSRQ, -16.0))
        assertEquals(QualityLevel.FAIR, qualityLevel(Metric.CELL_SINR, 0.0))
        assertEquals(QualityLevel.POOR, qualityLevel(Metric.CELL_SINR, -1.0))
        assertEquals(QualityLevel.EXCELLENT, qualityLevel(Metric.CELL_SINR, 25.0))
    }

    @Test
    fun lowerIsBetterScales() {
        assertEquals(QualityLevel.GOOD, qualityLevel(Metric.LATENCY, 20.0))
        assertEquals(QualityLevel.FAIR, qualityLevel(Metric.LATENCY, 21.0))
        assertEquals(QualityLevel.POOR, qualityLevel(Metric.LATENCY, 51.0))
        assertEquals(QualityLevel.GOOD, qualityLevel(Metric.JITTER, 0.0))
        assertEquals(QualityLevel.POOR, qualityLevel(Metric.JITTER, 31.0))
        assertEquals(QualityLevel.GOOD, qualityLevel(Metric.LOSS, 0.0))
        assertEquals(QualityLevel.POOR, qualityLevel(Metric.LOSS, 10.0))
    }

    @Test
    fun extremeValuesStillLandInABand() {
        for (m in Metric.entries) {
            qualityLevel(m, Double.MAX_VALUE)
            qualityLevel(m, -Double.MAX_VALUE)
        }
    }

    @Test
    fun everyScaleRunsBestToWorst() {
        for (m in Metric.entries) {
            val levels = qualityBands(m).map { it.level.ordinal }
            assertEquals(levels.sorted(), levels)
            assertTrue(qualityBands(m).last().limit.isInfinite())
        }
    }

    @Test
    fun titleCapitalisesTheWord() {
        assertEquals("Very good", QualityLevel.VERY_GOOD.title)
    }

    @Test
    fun bandLabelsForHigherIsBetter() {
        val labels = bandLabels(Metric.RSSI)
        assertEquals(QualityLevel.EXCELLENT to "≥ -50", labels.first())
        assertEquals(QualityLevel.VERY_GOOD to "-60 to -50", labels[1])
        assertEquals(QualityLevel.POOR to "< -80", labels.last())
    }

    @Test
    fun bandLabelsForLowerIsBetter() {
        val labels = bandLabels(Metric.LATENCY)
        assertEquals(
            listOf(
                QualityLevel.GOOD to "≤ 20",
                QualityLevel.FAIR to "20 to 50",
                QualityLevel.POOR to "> 50"
            ),
            labels
        )
    }
}
