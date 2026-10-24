package com.gridsense.data

import com.gridsense.core.Metric
import com.gridsense.core.QualityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryTest {

    private fun point(id: Long, status: String = STATUS_DONE, enabled: Boolean = true) = GridPoint(
        id = id, roomId = 1, seq = id.toInt(), row = 0, col = id.toInt(),
        x = 0.5, y = 0.5, enabled = enabled, status = status
    )

    private fun link(point: Long, rssi: Int) =
        Sample(pointId = point, ts = 0, kind = KIND_LINK, bssid = "b", rssiDbm = rssi)

    @Test
    fun emptySurveyHasNoProgressAndNoQuality() {
        val s = surveySummary(emptyList(), emptyMap(), Metric.RSSI)
        assertEquals(0, s.total)
        assertEquals(0f, s.progress)
        assertNull(s.primaryMedian)
        assertNull(s.level)
    }

    @Test
    fun countsEnabledNodesAndTakesTheMedianOfNodeMedians() {
        val points = listOf(
            point(1), point(2), point(3, STATUS_PENDING), point(4, enabled = false)
        )
        val samples = listOf(
            link(1, -50), link(1, -52), link(1, -54), // node median -52
            link(2, -62),
            link(3, -90), // pending, ignored
            link(4, -95) // disabled, ignored
        ).groupBy { it.pointId }
        val s = surveySummary(points, samples, Metric.RSSI)
        assertEquals(3, s.total)
        assertEquals(2, s.logged)
        assertEquals(-57.0, s.primaryMedian!!, 1e-9)
        assertEquals(QualityLevel.VERY_GOOD, s.level)
        assertEquals(2f / 3f, s.progress, 1e-6f)
    }

    @Test
    fun loggedNodesWithoutReadingsGiveNoQuality() {
        val s = surveySummary(listOf(point(1)), emptyMap(), Metric.CELL_DBM)
        assertEquals(1, s.logged)
        assertNull(s.level)
    }
}
