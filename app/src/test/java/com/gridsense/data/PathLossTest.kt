package com.gridsense.data

import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathLossTest {

    private val bssid = "60:98:49:cf:c8:2a"
    private val router = Router(id = 1, roomId = 1, x = 0.0, y = 0.0, bssid = bssid, label = "Router")

    /** Nodes along the x axis at [distances], ids from 1. */
    private fun nodes(distances: List<Double>) = distances.mapIndexed { i, d ->
        GridPoint(
            id = i + 1L, roomId = 1, seq = i, row = 0, col = i,
            x = d, y = 0.0, enabled = true, status = STATUS_DONE
        )
    }

    private fun link(point: Long, rssi: Int, b: String = bssid) =
        Sample(pointId = point, ts = 0, kind = KIND_LINK, bssid = b, freqMhz = 2412, rssiDbm = rssi)

    private fun scan(point: Long, rssi: Int, b: String = bssid) =
        Sample(pointId = point, ts = 0, kind = KIND_SCAN, bssid = b, freqMhz = 2412, rssiDbm = rssi)

    @Test
    fun leastSquaresRecoversAKnownLine() {
        val xs = listOf(0.0, 1.0, 2.0, 3.0)
        val fit = leastSquares(xs, xs.map { 5 - 2 * it })!!
        assertEquals(5.0, fit.intercept, 1e-12)
        assertEquals(-2.0, fit.slope, 1e-12)
        assertEquals(1.0, fit.r2!!, 1e-12)
        assertEquals(0.0, fit.sigma, 1e-12)
    }

    @Test
    fun degenerateInputsGiveNoLine() {
        assertNull(leastSquares(listOf(1.0), listOf(1.0)))
        assertNull(leastSquares(listOf(2.0, 2.0, 2.0), listOf(1.0, 2.0, 3.0)))
        assertNull(leastSquares(emptyList(), emptyList()))
        // Flat data: a line, but no variance for R² to explain.
        assertNull(leastSquares(listOf(1.0, 2.0), listOf(-60.0, -60.0))!!.r2)
    }

    @Test
    fun syntheticDataRecoversNAndP0() {
        val ds = listOf(0.7, 1.0, 1.5, 2.2, 3.5, 5.0)
        val p0 = -40.0
        val n = 3.0
        // Exact model values; Sample stores whole dBm, so fit the points directly.
        val points = ds.mapIndexed { i, d -> PathLossPoint(i + 1L, "N$i", d, p0 - 10 * n * log10(d), true) }
        val line = leastSquares(points.map { 10 * log10(it.distanceM) }, points.map { it.rssiDbm })!!
        assertEquals(n, -line.slope, 0.01)
        assertEquals(p0, line.intercept, 0.01)
    }

    @Test
    fun syntheticSamplesThroughTheWholePipeline() {
        // Distances chosen so the model gives whole dBm: d = 10^(k/20) and n = 2 give -40 - k.
        val ks = listOf(0, 6, 10, 14, 20)
        val ds = ks.map { Math.pow(10.0, it / 20.0) }
        val logged = nodes(ds)
        val samples = ks.mapIndexed { i, k -> link(i + 1L, -40 - k) }.groupBy { it.pointId }
        val fit = pathLossFit(router, logged, samples)!!
        assertEquals(2.0, fit.n, 0.01)
        assertEquals(-40.0, fit.p0Dbm, 0.01)
        assertEquals(1.0, fit.r2!!, 1e-9)
        assertEquals(5, fit.count)
        assertTrue(fit.trustworthy)
        assertEquals(-40.0 + 20 * log10(2412.0) - 27.55, fit.eirpDbm!!, 1e-9)
    }

    @Test
    fun symmetricNoiseGivesTheExpectedSigma() {
        // Two readings per distance, model value +e and -e: the line is unchanged and every
        // residual is e, so sigma = e * sqrt(N / (N - 2)).
        val ds = listOf(1.0, 2.0, 4.0, 8.0)
        val e = 2.0
        val xs = ds.flatMap { listOf(10 * log10(it), 10 * log10(it)) }
        val ys = ds.flatMap { val m = -40 - 30 * log10(it); listOf(m + e, m - e) }
        val fit = leastSquares(xs, ys)!!
        assertEquals(3.0, -fit.slope, 1e-9)
        assertEquals(e * sqrt(8.0 / 6.0), fit.sigma, 1e-9)
    }

    @Test
    fun fewerThanTwoUsableNodesGiveNoFit() {
        val logged = nodes(listOf(1.0, 2.0))
        assertNull(pathLossFit(router, logged, listOf(link(1, -50)).groupBy { it.pointId }))
        assertNull(pathLossFit(router, logged, emptyMap()))
    }

    @Test
    fun nodesCloserThanHalfAMetreAreExcluded() {
        val logged = nodes(listOf(0.3, 1.0, 2.0, 4.0))
        val samples = listOf(link(1, -30), link(2, -40), link(3, -46), link(4, -52)).groupBy { it.pointId }
        val fit = pathLossFit(router, logged, samples)!!
        assertEquals(3, fit.count)
        assertEquals(listOf(1L), fit.tooClose.map { it.pointId })
        assertEquals(1.0, fit.minDistanceM, 1e-9)
    }

    @Test
    fun heightIsPartOfTheDistance() {
        val tall = router.copy(heightDiffM = 1.5)
        assertEquals(2.5, routerDistance(tall, 2.0, 0.0), 1e-9)
        assertEquals(2.0, routerDistance(router, 2.0, 0.0), 1e-9)
    }

    @Test
    fun validityFlagsTrip() {
        // A 3 x 3 room with 1 m cells: distances from a corner router span about 0.7 to 3.5 m.
        val few = nodes(listOf(1.0, 3.0, 4.0))
        val fewFit = pathLossFit(router, few, listOf(link(1, -50), link(2, -60), link(3, -62)).groupBy { it.pointId })!!
        assertTrue(fewFit.fewNodes)
        assertFalse(fewFit.narrowRange)
        assertEquals(1, pathLossWarnings(fewFit).size)

        val narrow = nodes(listOf(1.0, 1.2, 1.5, 1.9))
        val narrowFit = pathLossFit(
            router, narrow,
            listOf(link(1, -50), link(2, -52), link(3, -54), link(4, -57)).groupBy { it.pointId }
        )!!
        assertFalse(narrowFit.fewNodes)
        assertTrue(narrowFit.narrowRange)
        assertFalse(narrowFit.trustworthy)
        assertTrue(pathLossWarnings(narrowFit).single().contains("1.0 to 1.9 m"))
    }

    @Test
    fun linkReadingsTakePrecedenceOverScans() {
        val logged = nodes(listOf(1.0))
        val samples = listOf(link(1, -50), link(1, -52), scan(1, -70), scan(1, -71)).groupBy { it.pointId }
        val p = pathLossPoints(router, logged, samples).single()
        assertEquals(-51.0, p.rssiDbm, 1e-9)
        assertTrue(p.fromLink)

        val scanOnly = listOf(scan(1, -70), scan(1, -72), link(1, -40, b = "aa:bb:cc:dd:ee:ff")).groupBy { it.pointId }
        val q = pathLossPoints(router, logged, scanOnly).single()
        assertEquals(-71.0, q.rssiDbm, 1e-9)
        assertFalse(q.fromLink)
    }

    @Test
    fun bssidMatchingIgnoresCase() {
        val logged = nodes(listOf(1.0))
        val samples = listOf(link(1, -50, b = bssid.uppercase())).groupBy { it.pointId }
        assertEquals(1, pathLossPoints(router, logged, samples).size)
    }

    @Test
    fun exponentBands() {
        assertTrue(exponentBand(1.6).startsWith("below free space"))
        assertTrue(exponentBand(2.0).startsWith("close to free space"))
        assertEquals("typical for indoors", exponentBand(2.5))
        assertEquals("typical for indoors", exponentBand(3.4))
        assertEquals("obstructed", exponentBand(3.5))
        assertEquals("heavily obstructed", exponentBand(4.5))
    }

    @Test
    fun fsplAtOneMetreIsAboutFortyDbAt2_4GHz() {
        assertEquals(40.10, fsplAt1m(2412.0), 0.01)
    }

    // --- report ------------------------------------------------------------

    private fun reportText(points: List<GridPoint>, samples: List<Sample>, routers: List<Router>) =
        buildInsights(points, samples, routers).firstOrNull { it.title == "Path loss" }?.lines?.joinToString(" ")

    @Test
    fun noRouterMeansNoPathLossSection() {
        val logged = nodes(listOf(1.0, 2.0))
        assertNull(reportText(logged, listOf(link(1, -50), link(2, -56)), emptyList()))
    }

    @Test
    fun reportGivesNBandSigmaAndTheWarning() {
        // Real-room shape: a 3 x 3 room, distances about 0.7 to 3.5 m, three usable nodes.
        val logged = nodes(listOf(0.3, 1.0, 2.0, 3.5))
        val samples = listOf(link(1, -45), link(2, -50), link(3, -58), link(4, -62))
        val text = reportText(logged, samples, listOf(router))!!
        assertTrue(text.contains("Router: path-loss exponent n = 2.2,"))
        assertTrue(text.contains("close to free space"))
        assertTrue(text.contains("shadowing σ"))
        assertTrue(text.contains("Only 3 nodes could be used"))
        assertTrue(text.contains("Treat this n as indicative"))
        assertTrue(text.contains("Left out A1, closer than 0.5 m"))
        assertTrue(text.contains("Estimated EIRP about"))
    }

    @Test
    fun reportSaysWhenThereIsTooLittleToFit() {
        val logged = nodes(listOf(1.0, 2.0))
        val text = reportText(logged, listOf(link(1, -50)), listOf(router))!!
        assertTrue(text.contains("too few nodes heard $bssid"))
    }
}
