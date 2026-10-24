package com.gridsense.data

import com.gridsense.core.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareTest {

    private fun room(id: Long, rows: Int = 3, cols: Int = 3, condition: String = "") = SurveyRoom(
        id = id, name = "S$id", mode = "WIFI", samplesPerPoint = 10, pingHost = null, polygon = "",
        gridRows = rows, gridCols = cols, cellSizeM = 1.0, createdAt = 0, condition = condition
    )

    /** A 3 x 3 grid for one survey; ids are offset so the two surveys never share point ids. */
    private fun grid(offset: Long, status: (Int) -> String = { STATUS_DONE }, enabled: (Int) -> Boolean = { true }) =
        (0 until 9).map { i ->
            GridPoint(
                id = offset + i, roomId = offset, seq = i, row = i / 3, col = i % 3,
                x = (i % 3) + 0.5, y = (i / 3) + 0.5, enabled = enabled(i), status = status(i)
            )
        }

    private fun wifi(points: List<GridPoint>, rssi: List<Int>) =
        points.zip(rssi).map { (p, r) -> Sample(pointId = p.id, ts = 0, kind = KIND_LINK, bssid = "b", rssiDbm = r) }
            .groupBy { it.pointId }

    private fun pings(points: List<GridPoint>, ms: List<Double>) =
        points.zip(ms).map { (p, v) -> Sample(pointId = p.id, ts = 0, kind = KIND_PING, rttAvgMs = v) }
            .groupBy { it.pointId }

    private val a = grid(100)
    private val b = grid(200)
    // Real-room shape: -62 to -50 dBm.
    private val rssiA = listOf(-55, -58, -55, -54, -54, -54, -56, -52, -62)

    @Test
    fun nodesAreMatchedOnRowAndColumn() {
        // B's points are listed in a different order; matching must not depend on it.
        val shuffledB = b.reversed()
        val rssiB = rssiA.map { it + 4 }
        val d = compareNodes(a, wifi(a, rssiA), shuffledB, wifi(b, rssiB), Metric.RSSI)
        assertEquals(9, d.size)
        assertEquals("A1", d.first().label)
        assertTrue(d.all { it.delta == 4.0 })
        assertEquals(-55.0, d.first().a, 0.0)
        assertEquals(-51.0, d.first().b, 0.0)
    }

    @Test
    fun unloggedOrDisabledNodesOnEitherSideAreExcluded() {
        val partA = grid(100, status = { if (it == 0) STATUS_PENDING else STATUS_DONE })
        val partB = grid(200, enabled = { it != 8 })
        val d = compareNodes(partA, wifi(partA, rssiA), partB, wifi(partB, rssiA), Metric.RSSI)
        assertEquals(7, d.size)
        assertFalse(d.any { it.label == "A1" })
        assertFalse(d.any { it.label == "C3" })
    }

    @Test
    fun nodesWithoutAValueOnEitherSideAreExcluded() {
        val samplesB = wifi(b.drop(1), rssiA.drop(1))
        assertEquals(8, compareNodes(a, wifi(a, rssiA), b, samplesB, Metric.RSSI).size)
    }

    @Test
    fun theSignFollowsHigherIsBetter() {
        // RSSI up 3 dB is better; latency up 10 ms is worse.
        val rssi = compareNodes(a, wifi(a, rssiA), b, wifi(b, rssiA.map { it + 3 }), Metric.RSSI)
        assertTrue(rssi.all { it.delta > 0 && it.gain > 0 })
        val ms = List(9) { 4.0 }
        val lat = compareNodes(a, pings(a, ms), b, pings(b, ms.map { it + 10 }), Metric.LATENCY)
        assertTrue(lat.all { it.delta == 10.0 && it.gain == -10.0 })
    }

    @Test
    fun summaryNumbers() {
        val rssiB = listOf(-50, -58, -55, -54, -60, -54, -56, -52, -61) // +5, 0, 0, 0, -6, 0, 0, 0, +1
        val s = summarise(compareNodes(a, wifi(a, rssiA), b, wifi(b, rssiB), Metric.RSSI), Metric.RSSI)!!
        assertEquals(9, s.count)
        assertEquals(0.0, s.meanDelta, 1e-9)
        assertEquals(12.0 / 9, s.meanAbsDelta, 1e-9)
        assertEquals("A1", s.bestImprovement!!.label)
        assertEquals("B2", s.worstDegradation!!.label)
        assertEquals(1, s.improved)
        assertEquals(1, s.degraded)
        assertEquals(2, s.changed)
        assertEquals(3.0, s.threshold, 0.0)
    }

    @Test
    fun noChangeMeansNoImprovementOrDegradation() {
        val s = summarise(compareNodes(a, wifi(a, rssiA), b, wifi(b, rssiA), Metric.RSSI), Metric.RSSI)!!
        assertNull(s.bestImprovement)
        assertNull(s.worstDegradation)
        assertEquals(0.0, s.sdDelta, 0.0)
        assertNull(summarise(emptyList(), Metric.RSSI))
    }

    @Test
    fun repeatabilityMaths() {
        // Two passes of the same room: deltas -2, 2, -2, 2: mean 0, mean |Δ| 2, sample SD 2.31.
        val passA = listOf(-55, -58, -55, -54)
        val passB = listOf(-57, -56, -57, -52)
        val four = a.take(4)
        val fourB = b.take(4)
        val s = summarise(compareNodes(four, wifi(four, passA), fourB, wifi(fourB, passB), Metric.RSSI), Metric.RSSI)!!
        assertEquals(2.0, s.meanAbsDelta, 1e-9)
        assertEquals(sampleSd(listOf(-2.0, 2.0, -2.0, 2.0)), s.sdDelta, 1e-12)
        assertEquals(2.3094, s.sdDelta, 1e-4)
        assertEquals(2 * s.sdDelta, s.repeatabilityBand, 1e-12)
        assertEquals(0.0, sampleSd(listOf(5.0)), 0.0)
    }

    @Test
    fun incompatibleGridsAreRejected() {
        assertNull(incompatibility(room(1), room(2)))
        assertTrue(incompatibility(room(1), room(2, rows = 4))!!.startsWith("Different grids (3 x 3 and 4 x 3)"))
        assertNotNull(incompatibility(room(1), room(2, cols = 2)))
        assertEquals("That is the same survey.", incompatibility(room(1), room(1)))
    }

    @Test
    fun thresholdDefaults() {
        assertEquals(3.0, changeThreshold(Metric.RSSI), 0.0)
        assertEquals(3.0, changeThreshold(Metric.CELL_RSRP), 0.0)
        assertEquals(2.0, changeThreshold(Metric.CELL_RSRQ), 0.0)
        assertEquals(5.0, changeThreshold(Metric.LATENCY), 0.0)
        assertEquals(10.0, changeThreshold(Metric.LOSS), 0.0)
        for (m in Metric.entries) assertTrue(changeThreshold(m) > 0)
    }

    @Test
    fun sameConditionNeedsAMatchingNonBlankCondition() {
        assertTrue(sameCondition(room(1, condition = "Door closed"), room(2, condition = "door closed ")))
        assertFalse(sameCondition(room(1), room(2)))
        assertFalse(sameCondition(room(1, condition = "door open"), room(2, condition = "door closed")))
    }

    @Test
    fun csvHasHeaderAndRows() {
        val d = compareNodes(a, wifi(a, rssiA), b, wifi(b, rssiA.map { it + 1 }), Metric.RSSI)
        val csv = compareCsv(room(1, condition = "door, open"), room(2), Metric.RSSI, d).lines()
        assertEquals("a,S1,\"door, open\"", csv[1])
        assertEquals("node,row,col,a,b,delta_b_minus_a,better_in_b", csv[3])
        assertEquals("A1,0,0,-55.0,-54.0,1.0,true", csv[4])
        assertEquals(4 + 9 + 1, csv.size) // trailing newline
    }
}
