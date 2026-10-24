package com.gridsense.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    private val square = listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 4.0), Pt(0.0, 4.0))

    private val lShape = listOf(
        Pt(0.0, 0.0), Pt(6.0, 0.0), Pt(6.0, 3.0),
        Pt(3.0, 3.0), Pt(3.0, 6.0), Pt(0.0, 6.0)
    )

    // --- outline geometry --------------------------------------------------

    @Test
    fun insidePointsAreDetected() {
        assertTrue(pointInPolygon(square, Pt(2.0, 2.0)))
        assertTrue(pointInPolygon(square, Pt(0.1, 3.9)))
    }

    @Test
    fun outsidePointsAreRejected() {
        assertFalse(pointInPolygon(square, Pt(-0.5, 2.0)))
        assertFalse(pointInPolygon(square, Pt(4.5, 2.0)))
        assertFalse(pointInPolygon(square, Pt(2.0, 9.0)))
    }

    @Test
    fun concaveCornerIsRespected() {
        // The notch in the top right of the L is outside the room.
        assertFalse(pointInPolygon(lShape, Pt(4.5, 4.5)))
        assertTrue(pointInPolygon(lShape, Pt(4.5, 1.5)))
        assertTrue(pointInPolygon(lShape, Pt(1.5, 4.5)))
    }

    @Test
    fun boundsCoverEveryCorner() {
        val b = boundsOf(lShape)
        assertEquals(0.0, b.minX, 1e-9)
        assertEquals(6.0, b.maxX, 1e-9)
        assertEquals(6.0, b.height, 1e-9)
    }

    // --- grid -------------------------------------------------------------

    @Test
    fun everyCellGetsOneNodeAtItsCentre() {
        val nodes = gridNodes(rows = 3, cols = 4, cellM = 1.0)
        assertEquals(12, nodes.size)
        assertEquals(GridNode(0, 0, 0.5, 0.5), nodes.first())
        assertEquals(GridNode(2, 3, 3.5, 2.5), nodes.last())
    }

    @Test
    fun cellSizeScalesPositions() {
        val node = gridNodes(rows = 2, cols = 2, cellM = 1.2)[3]
        assertEquals(1.8, node.x, 1e-9)
        assertEquals(1.8, node.y, 1e-9)
    }

    @Test
    fun nodesAreRowMajorFromA1() {
        val nodes = gridNodes(rows = 2, cols = 3, cellM = 1.0)
        assertEquals(listOf("A1", "B1", "C1", "A2", "B2", "C2"), nodes.map { nodeLabel(it.row, it.col) })
    }

    @Test
    fun columnLabelsCarryPastZ() {
        assertEquals("Z1", nodeLabel(0, 25))
        assertEquals("AA1", nodeLabel(0, 26))
    }

    @Test
    fun theOutlineContainsEveryNode() {
        val outline = gridOutline(rows = 3, cols = 5, cellM = 0.6)
        assertTrue(gridNodes(3, 5, 0.6).all { pointInPolygon(outline, Pt(it.x, it.y)) })
        assertEquals(3.0, boundsOf(outline).width, 1e-9)
        assertEquals(1.8, boundsOf(outline).height, 1e-9)
    }

    // --- statistics --------------------------------------------------------

    @Test
    fun medianHandlesOddAndEvenCounts() {
        assertEquals(-60.0, median(listOf(-70.0, -60.0, -50.0)), 1e-9)
        assertEquals(-65.0, median(listOf(-70.0, -60.0, -50.0, -80.0)), 1e-9)
    }

    @Test
    fun jitterIsMeanAbsoluteDifferenceOfConsecutiveRtts() {
        // Differences are 5, 5, 10 so the mean absolute difference is 20 / 3.
        assertEquals(20.0 / 3.0, meanAbsDiff(listOf(10.0, 15.0, 20.0, 10.0)), 1e-9)
        assertEquals(0.0, meanAbsDiff(listOf(12.0)), 1e-9)
        assertEquals(0.0, meanAbsDiff(emptyList()), 1e-9)
    }

    // --- interpolation -----------------------------------------------------

    @Test
    fun idwReturnsTheExactValueAtAnObservation() {
        val obs = listOf(Obs(0.0, 0.0, -40.0), Obs(4.0, 0.0, -80.0))
        assertEquals(-40.0, idw(obs, 0.0, 0.0), 1e-9)
        assertEquals(-80.0, idw(obs, 4.0, 0.0), 1e-9)
    }

    @Test
    fun idwMidpointOfTwoEqualDistanceObservationsIsTheirMean() {
        val obs = listOf(Obs(0.0, 0.0, -40.0), Obs(4.0, 0.0, -80.0))
        assertEquals(-60.0, idw(obs, 2.0, 0.0), 1e-9)
    }

    @Test
    fun idwFavoursTheNearerObservation() {
        val obs = listOf(Obs(0.0, 0.0, -40.0), Obs(4.0, 0.0, -80.0))
        // One metre from the strong point, three from the weak one.
        // Weights are 1 and 1/9, so the result sits close to -44.
        assertEquals(-44.0, idw(obs, 1.0, 0.0), 1e-9)
    }

    // --- ping --------------------------------------------------------------

    @Test
    fun pingRttsAreParsedFromRealOutput() {
        val output = """
            PING 192.168.1.1 (192.168.1.1) 56(84) bytes of data.
            64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=3.21 ms
            64 bytes from 192.168.1.1: icmp_seq=2 ttl=64 time=5.00 ms
            64 bytes from 192.168.1.1: icmp_seq=3 ttl=64 time<1.0 ms

            --- 192.168.1.1 ping statistics ---
            3 packets transmitted, 3 received, 0% packet loss, time 2003ms
            rtt min/avg/max/mdev = 1.000/3.070/5.000/1.640 ms
        """.trimIndent()
        assertEquals(listOf(3.21, 5.0, 1.0), parsePingRtts(output))
    }

    @Test
    fun lossIsDerivedFromMissingReplies() {
        val result = PingResult(sent = 10, rtts = listOf(4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0))
        assertEquals(20.0, result.lossPct, 1e-9)
        assertEquals(11.0, result.avgMs!!, 1e-9)
        assertEquals(2.0, result.jitterMs!!, 1e-9)
    }

    @Test
    fun totalLossGivesNullTimings() {
        val result = PingResult(sent = 10, rtts = emptyList())
        assertEquals(100.0, result.lossPct, 1e-9)
        assertEquals(null, result.avgMs)
        assertEquals(null, result.jitterMs)
    }
}
