package com.gridsense.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {

    private val myBssid = "60:98:49:cf:c8:2a"
    private val mySsid = "Airtel_SayMyName_BB"

    /** A 3 x 3 grid of logged nodes, ids 1..9 in row-major order. */
    private val grid = (0 until 9).map { i ->
        GridPoint(
            id = i + 1L, roomId = 1, seq = i, row = i / 3, col = i % 3,
            x = (i % 3) + 0.5, y = (i / 3) + 0.5, enabled = true, status = STATUS_DONE
        )
    }

    private fun link(point: Long, rssi: Int, speed: Int = 130) = Sample(
        pointId = point, ts = 0, kind = KIND_LINK, bssid = myBssid, ssid = mySsid,
        freqMhz = 2412, rssiDbm = rssi, linkSpeedMbps = speed
    )

    private fun cell(point: Long, rsrp: Int, pci: Int, rsrq: Int = -11, sinr: Int? = 2) = Sample(
        pointId = point, ts = 0, kind = KIND_CELL, cellTech = "LTE", cellDbm = rsrp,
        rsrp = rsrp, rsrq = rsrq, sinr = sinr, pci = pci, operator = "airtel"
    )

    private fun scan(point: Long, ssid: String, bssid: String, freq: Int, rssi: Int, fresh: Boolean = true) =
        Sample(
            pointId = point, ts = 0, kind = if (fresh) KIND_SCAN else KIND_SCAN_CACHED,
            bssid = bssid, ssid = ssid, freqMhz = freq, rssiDbm = rssi
        )

    private fun ping(point: Long, host: String, avg: Double?, jitter: Double?, loss: Double) = Sample(
        pointId = point, ts = 0, kind = KIND_PING, pingHost = host,
        rttAvgMs = avg, rttJitterMs = jitter, rttLossPct = loss
    )

    /** Ten Wi-Fi readings per node, scattered a couple of dB around [medians]. */
    private fun wifi(medians: List<Int>): List<Sample> = medians.flatMapIndexed { i, m ->
        listOf(m, m, m - 1, m + 1, m, m - 2, m + 2, m, m, m).map { link(i + 1L, it) }
    }

    private fun section(sections: List<InsightSection>, title: String) =
        sections.firstOrNull { it.title == title }

    private fun text(sections: List<InsightSection>, title: String) =
        section(sections, title)!!.lines.joinToString(" ")

    @Test
    fun aSmallSpreadIsCalledUniformNotAPattern() {
        // Room1: -50..-62 per reading, but node medians within about 6 dB.
        val s = buildInsights(grid, wifi(listOf(-55, -58, -55, -54, -54, -54, -56, -52, -53)))
        val wifi = text(s, "Wi-Fi coverage")
        assertTrue(wifi.contains("uniformly covered"))
        assertFalse(wifi.contains("router is probably"))
        assertTrue(wifi.contains("No node is below -67 dBm"))
        assertTrue(wifi.contains("Weakest node B1"))
        assertTrue(wifi.contains("strongest B3"))
    }

    @Test
    fun aRealGradientPointsAtTheRouter() {
        val s = buildInsights(grid, wifi(listOf(-50, -50, -50, -60, -60, -60, -70, -70, -70)))
        val wifi = text(s, "Wi-Fi coverage")
        assertTrue(wifi.contains("confirm the pattern with a second pass"))
        assertTrue(wifi.contains("stronger along row 1 than row 3"))
        assertTrue(wifi.contains("router is probably on the row 1 side"))
        assertTrue(wifi.contains("3 nodes are below -67 dBm"))
        assertTrue(wifi.contains("A3, B3, C3"))
    }

    @Test
    fun aColumnGradientNamesTheColumn() {
        val s = buildInsights(grid, wifi(listOf(-72, -62, -52, -72, -62, -52, -72, -62, -52)))
        assertTrue(text(s, "Wi-Fi coverage").contains("stronger along column C than column A"))
    }

    @Test
    fun theBandAndChannelAreNamed() {
        val s = buildInsights(grid, wifi(List(9) { -55 }))
        assertTrue(text(s, "Wi-Fi coverage").contains("on 2.4 GHz, channel 1"))
    }

    @Test
    fun fiveGigahertzFromTheSameRouterIsPointedOut() {
        val samples = wifi(List(9) { -55 }) +
            scan(1, mySsid, "60:98:49:cf:c8:2b", 5220, -71) +
            scan(1, "Airtel_padm_6652", "a4:08:53:4d:6b:8a", 2462, -80)
        val air = text(buildInsights(grid, samples), "Bands and neighbours")
        assertTrue(air.contains("also broadcasts $mySsid on 5 GHz (channel 44)"))
        assertTrue(air.contains("about -71 dBm"))
    }

    @Test
    fun aDistantChannelIsNotInterference() {
        // Channel 11 does not overlap channel 1.
        val samples = wifi(List(9) { -55 }) + scan(1, "Airtel_padm_6652", "a4:08", 2462, -60)
        val air = text(buildInsights(grid, samples), "Bands and neighbours")
        assertTrue(air.contains("Interference looks low"))
        assertTrue(air.contains("none overlap your channel"))
    }

    @Test
    fun aLoudOverlappingNeighbourIsFlagged() {
        // Channel 3 overlaps channel 1, and -60 dBm is loud.
        val samples = wifi(List(9) { -55 }) + scan(1, "Karthik's home", "4c:ae", 2422, -60)
        val air = text(buildInsights(grid, samples), "Bands and neighbours")
        assertTrue(air.contains("Possible interference: Karthik's home (-60 dBm)"))
    }

    @Test
    fun oneWeakOverlappingNeighbourReadsInTheSingular() {
        val samples = wifi(List(9) { -55 }) + scan(1, "Karthik's home", "4c:ae", 2422, -86)
        val air = text(buildInsights(grid, samples), "Bands and neighbours")
        assertTrue(air.contains("the one that overlaps your channel is weak"))
    }

    @Test
    fun hiddenNetworksAreCounted() {
        val samples = wifi(List(9) { -55 }) +
            scan(1, "", "72:98:49:cf:c8:2b", 5220, -72) +
            scan(1, "", "76:98:49:cf:c8:2b", 5220, -73)
        assertTrue(text(buildInsights(grid, samples), "Bands and neighbours").contains("2 hidden networks"))
    }

    @Test
    fun cellularIsBandedAndTowerSwitchingIsNoticed() {
        val samples = wifi(List(9) { -55 }) + (1L..9L).flatMap { p ->
            if (p % 2 == 0L) listOf(cell(p, -96, 211), cell(p, -100, 1)) else listOf(cell(p, -104, 211))
        }
        val c = text(buildInsights(grid, samples), "Cellular")
        assertTrue(c.contains("airtel LTE"))
        assertTrue(c.contains("poor overall") || c.contains("fair overall"))
        assertTrue(c.contains("switched between towers (PCI 1, 211) at 4 of 9 nodes"))
        assertTrue(c.contains("RSRQ -11 dB (fair)"))
    }

    @Test
    fun theLinkLocalPingBugIsExplained() {
        val samples = wifi(List(9) { -55 }) + (1L..9L).map { ping(it, "fe80::6298:49ff:fecf:c821", null, null, 100.0) }
        val l = text(buildInsights(grid, samples), "Latency, jitter and loss")
        assertTrue(l.contains("IPv6 link-local address"))
        assertTrue(l.contains("update the app and re-log"))
    }

    @Test
    fun aBlockedPingSuggestsAnotherTarget() {
        val samples = wifi(List(9) { -55 }) + (1L..9L).map { ping(it, "192.168.1.1", null, null, 100.0) }
        assertTrue(text(buildInsights(grid, samples), "Latency, jitter and loss").contains("block ping"))
    }

    @Test
    fun goodPingResultsAreSummarised() {
        val samples = wifi(List(9) { -55 }) + (1L..9L).map {
            ping(it, "192.168.1.1", 3.0 + it, 1.0, if (it == 9L) 10.0 else 0.0)
        }
        val l = text(buildInsights(grid, samples), "Latency, jitter and loss")
        assertTrue(l.contains("Latency median 8 ms, worst 12 ms at C3: low"))
        assertTrue(l.contains("Jitter median 1 ms: steady"))
        assertTrue(l.contains("Packet loss at 1 node: C3 10%"))
    }

    @Test
    fun cachedScansAreReportedAsAQualityNote() {
        val samples = wifi(List(9) { -55 }) + (1L..9L).map {
            scan(it, "Airtel_padm_6652", "a4:08", 2462, -80, fresh = it <= 4)
        }
        val q = text(buildInsights(grid, samples), "Data quality")
        assertTrue(q.contains("Only 4 of 9 nodes have a fresh neighbour scan (A1, B1, C1, A2)"))
    }

    @Test
    fun allFreshScansNeedNoQualityNote() {
        val samples = wifi(List(9) { -55 }) + (1L..9L).map { scan(it, "Airtel_padm_6652", "a4:08", 2462, -80) }
        assertNull(section(buildInsights(grid, samples), "Data quality"))
    }

    @Test
    fun pendingNodesAreListedAndOnlyLoggedOnesAnalysed() {
        val partial = grid.mapIndexed { i, p -> if (i >= 7) p.copy(status = STATUS_PENDING) else p }
        val s = buildInsights(partial, wifi(List(7) { -55 }))
        val overview = text(s, "Overview")
        assertTrue(overview.contains("7 of 9 nodes logged"))
        assertTrue(overview.contains("Still to log: B3, C3"))
        assertNotNull(section(s, "Wi-Fi coverage"))
    }

    @Test
    fun nothingLoggedGivesOnlyTheOverview() {
        val s = buildInsights(grid.map { it.copy(status = STATUS_PENDING) }, emptyList())
        assertEquals(listOf("Overview"), s.map { it.title })
    }

    @Test
    fun channelsAndBandsConvert() {
        assertEquals(1, wifiChannel(2412))
        assertEquals(11, wifiChannel(2462))
        assertEquals(44, wifiChannel(5220))
        assertEquals("very good", wifiBand(-54.0))
        assertEquals("good", wifiBand(-67.0))
        assertEquals("poor", rsrpBand(-104.0))
        assertEquals("fair", sinrBand(2.0))
    }

    @Test
    fun theSharedTextKeepsEverySection() {
        val s = buildInsights(grid, wifi(List(9) { -55 }))
        val shared = insightsAsText("GridSense report: room1", s)
        assertTrue(shared.startsWith("GridSense report: room1"))
        s.forEach { assertTrue(shared.contains("\n" + it.title + "\n")) }
    }

    @Test
    fun sectionsCarryRatingsOnTheSharedScale() {
        val samples = wifi(listOf(-55, -58, -55, -54, -54, -54, -56, -52, -62)) +
            grid.map { cell(it.id, -96, 1, rsrq = -11, sinr = 2) } +
            grid.map { ping(it.id, "192.168.1.1", 4.0, 1.0, 0.0) }
        val s = buildInsights(grid, samples)
        val wifi = section(s, "Wi-Fi coverage")!!.ratings
        assertEquals("Median RSSI", wifi[0].label)
        assertEquals(com.gridsense.core.QualityLevel.VERY_GOOD, wifi[0].level)
        assertEquals(com.gridsense.core.QualityLevel.GOOD, wifi[1].level)
        val cellular = section(s, "Cellular")!!.ratings.associate { it.label to it.level }
        assertEquals(com.gridsense.core.QualityLevel.FAIR, cellular["Median RSRP"])
        assertEquals(com.gridsense.core.QualityLevel.FAIR, cellular["RSRQ"])
        assertEquals(com.gridsense.core.QualityLevel.FAIR, cellular["SINR"])
        val latency = section(s, "Latency, jitter and loss")!!.ratings.associate { it.label to it.level }
        assertEquals(com.gridsense.core.QualityLevel.GOOD, latency["Median latency"])
        assertEquals(com.gridsense.core.QualityLevel.GOOD, latency["Worst loss"])
        assertTrue(section(s, "Overview")!!.ratings.isEmpty())
    }
}
