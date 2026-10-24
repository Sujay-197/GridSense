package com.gridsense.data

import com.gridsense.core.dbmToMw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsTest {

    private val myBssid = "60:98:49:cf:c8:2a"
    private val mySsid = "Airtel_SayMyName_BB"

    private val grid = (0 until 4).map { i ->
        GridPoint(
            id = i + 1L, roomId = 1, seq = i, row = i / 2, col = i % 2,
            x = (i % 2) + 0.5, y = (i / 2) + 0.5, enabled = true, status = STATUS_DONE
        )
    }

    private fun link(point: Long, freq: Int = 2412, rssi: Int = -55, bssid: String = myBssid) = Sample(
        pointId = point, ts = 0, kind = KIND_LINK, bssid = bssid, ssid = mySsid,
        freqMhz = freq, rssiDbm = rssi
    )

    private fun scan(
        point: Long, ssid: String, bssid: String, freq: Int, rssi: Int,
        width: Int? = null, centre: Int? = null
    ) = Sample(
        pointId = point, ts = 0, kind = KIND_SCAN, bssid = bssid, ssid = ssid,
        freqMhz = freq, rssiDbm = rssi, channelWidthMhz = width, centerFreq0Mhz = centre
    )

    private fun links(freq: Int = 2412) = grid.map { link(it.id, freq) }

    private fun byPoint(samples: List<Sample>) = samples.groupBy { it.pointId }

    private fun net(bssid: String, freq: Int, rssi: Double, width: Int = 20) = AirNetwork(
        bssid = bssid, ssid = "n", freqMhz = freq, channel = wifiChannel(freq), widthMhz = width,
        centerMhz = freq.toDouble(), rssiDbm = rssi, nodesSeen = 1, own = false
    )

    // --- deduplication and own-network detection ---------------------------

    @Test
    fun oneEntryPerBssidWithTheMedianAcrossNodes() {
        val samples = links() +
            scan(1, "Karthik", "4c:ae:00:00:00:01", 2437, -70) +
            scan(1, "Karthik", "4c:ae:00:00:00:01", 2437, -72) + // node 1 median -71
            scan(2, "Karthik", "4c:ae:00:00:00:01", 2437, -80) +
            scan(3, "Karthik", "4C:AE:00:00:00:01", 2437, -76)
        val karthik = airNetworks(grid, byPoint(samples)).single { it.ssid == "Karthik" }
        assertEquals(-76.0, karthik.rssiDbm, 1e-9)
        assertEquals(3, karthik.nodesSeen)
        assertEquals(6, karthik.channel)
        assertFalse(karthik.own)
    }

    @Test
    fun theSameRoutersOtherRadiosAndHiddenBssidsAreOwn() {
        val samples = links() +
            scan(1, mySsid, "60:98:49:cf:c8:2b", 5220, -71) + // same SSID on 5 GHz
            scan(1, "", "72:98:49:cf:c8:2b", 5220, -72) + // hidden, first and last octet differ
            scan(1, "", "76:98:49:cf:c8:2c", 5220, -73) +
            scan(1, "", "aa:bb:cc:dd:ee:ff", 5745, -80) // hidden, someone else's
        val nets = airNetworks(grid, byPoint(samples)).associateBy { it.bssid }
        assertTrue(nets.getValue("60:98:49:cf:c8:2b").own)
        assertTrue(nets.getValue("72:98:49:cf:c8:2b").own)
        assertTrue(nets.getValue("76:98:49:cf:c8:2c").own)
        assertFalse(nets.getValue("aa:bb:cc:dd:ee:ff").own)
        assertEquals("(hidden)", nets.getValue("aa:bb:cc:dd:ee:ff").label)
    }

    @Test
    fun sameDeviceComparesTheMiddleFourOctets() {
        assertTrue(sameDevice("60:98:49:cf:c8:2a", "62:98:49:cf:c8:2f"))
        assertFalse(sameDevice("60:98:49:cf:c8:2a", "60:98:49:cf:c9:2a"))
        assertFalse(sameDevice("60:98:49:cf:c8:2a", "a4:08"))
        assertTrue(sameDevice("a4:08", "A4:08"))
    }

    @Test
    fun theConnectedApIsAddedWhenScansMissIt() {
        val samples = links() + scan(1, "Karthik", "4c:ae:00:00:00:01", 2437, -70)
        val own = airNetworks(grid, byPoint(samples)).single { it.bssid == myBssid }
        assertTrue(own.own)
        assertEquals(-55.0, own.rssiDbm, 1e-9)
    }

    @Test
    fun missingWidthDefaultsToTwentyMegahertz() {
        val samples = links() +
            scan(1, "Old", "4c:ae:00:00:00:01", 2437, -70) +
            scan(1, "Wide", "4c:ae:00:00:00:02", 5180, -70, width = 80, centre = 5210)
        val nets = airNetworks(grid, byPoint(samples)).associateBy { it.ssid }
        assertEquals(20, nets.getValue("Old").widthMhz)
        assertEquals(2437.0, nets.getValue("Old").centerMhz, 1e-9)
        assertEquals(80, nets.getValue("Wide").widthMhz)
        assertEquals(5210.0, nets.getValue("Wide").centerMhz, 1e-9)
    }

    // --- scoring -----------------------------------------------------------

    @Test
    fun scoreWeighsPowerByOverlap() {
        val others = listOf(net("a", 2412, -60.0), net("b", 2422, -70.0)) // ch 1 and ch 3
        val expected = dbmToMw(-60.0) + 0.5 * dbmToMw(-70.0)
        assertEquals(expected, channelScore(1, band5 = false, others = others), 1e-15)
        assertEquals(0.0, channelScore(11, band5 = false, others = others), 1e-15)
    }

    @Test
    fun aClearlyQuieterChannelIsRecommended() {
        // You are on 6 next to a loud neighbour on 6; channel 1 only hears a faint one.
        val others = listOf(net("a", 2437, -55.0), net("b", 2462, -60.0), net("c", 2412, -85.0))
        val advice = adviseChannel(false, CANDIDATES_24, current = 6, others = others)
        assertEquals(1, advice.best.channel)
        assertTrue(advice.change)
        assertTrue(advice.gainDb!! > 20)
    }

    @Test
    fun theCurrentChannelIsKeptWhenItIsAlreadyBest() {
        val others = listOf(net("a", 2437, -60.0), net("b", 2462, -65.0))
        val advice = adviseChannel(false, CANDIDATES_24, current = 1, others = others)
        assertEquals(1, advice.best.channel)
        assertFalse(advice.change)
    }

    @Test
    fun aSmallImprovementIsNotWorthAChange() {
        // Channel 11 is quieter than 1, but only by about 1.8 dB.
        val others = listOf(net("a", 2412, -70.0), net("b", 2462, -71.8))
        val advice = adviseChannel(false, CANDIDATES_24, current = 1, others = others)
        assertEquals(6, advice.best.channel) // 6 is silent
        val onlyOneAndEleven = adviseChannel(false, listOf(1, 11), current = 1, others = others)
        assertEquals(11, onlyOneAndEleven.best.channel)
        assertFalse(onlyOneAndEleven.change)
        assertEquals(1.8, onlyOneAndEleven.gainDb!!, 1e-6)
    }

    @Test
    fun aSilentChannelGivesAnInfiniteGain() {
        assertEquals(Double.POSITIVE_INFINITY, gainDb(1e-7, 0.0), 0.0)
        assertEquals(0.0, gainDb(0.0, 0.0), 0.0)
        assertEquals(3.0103, gainDb(2.0, 1.0), 1e-4)
    }

    @Test
    fun fiveGigahertzPrefersNonDfsOnTies() {
        val advice = adviseChannel(true, CANDIDATES_5, current = null, others = listOf(net("a", 5180, -60.0)))
        assertEquals(40, advice.best.channel)
        val inDfs = adviseChannel(true, CANDIDATES_5, current = 100, others = emptyList())
        assertEquals(100, inDfs.best.channel) // the current channel wins a tie
        assertFalse(inDfs.change)
    }

    // --- the report --------------------------------------------------------

    @Test
    fun fiveGigahertzCoChannelAndOverlappingNetworks() {
        val samples = grid.map { link(it.id, freq = 5180) } +
            scan(1, "Same", "4c:ae:00:00:00:01", 5180, -70) +
            scan(1, "Wide", "4c:ae:00:00:00:02", 5200, -75, width = 80, centre = 5210) +
            scan(1, "Far", "4c:ae:00:00:00:03", 5745, -60)
        val r = channelReport(grid, byPoint(samples))!!
        assertEquals(listOf("Same"), r.coChannel5.map { it.ssid })
        assertEquals(listOf("Wide"), r.overlapping5.map { it.ssid })
        assertNotNull(r.advice5)
        assertNull(r.advice24)
    }

    @Test
    fun noScansAndNoLinkMeansNoReport() {
        assertNull(channelReport(grid, emptyMap()))
    }

    @Test
    fun reportRecommendsAndCarriesTheCaveat() {
        val samples = grid.map { link(it.id, freq = 2437) } +
            scan(1, "Loud", "4c:ae:00:00:00:01", 2437, -50) +
            scan(1, "Faint", "4c:ae:00:00:00:02", 2412, -88)
        val text = buildInsights(grid, samples).single { it.title == "Channels" }.lines.joinToString(" ")
        assertTrue(text.contains("Channel 11 would be quieter than your channel 6"))
        assertTrue(text.contains("Possible interference: Loud (-50 dBm)"))
        assertTrue(text.contains(CHANNEL_CAVEAT))
    }

    @Test
    fun reportSaysTheCurrentChannelIsFine() {
        val samples = links() + scan(1, "Far", "4c:ae:00:00:00:01", 2462, -60)
        val text = buildInsights(grid, samples).single { it.title == "Channels" }.lines.joinToString(" ")
        assertTrue(text.contains("Channel 1 is fine"))
    }

    @Test
    fun reportFlagsDfs() {
        val samples = grid.map { link(it.id, freq = 5500) } + scan(1, "N", "4c:ae:00:00:00:01", 5180, -70)
        val text = buildInsights(grid, samples).single { it.title == "Channels" }.lines.joinToString(" ")
        assertTrue(text.contains("Channel 100 is a DFS channel"))
    }

    @Test
    fun bandsAndNeighboursNoLongerRepeatsInterference() {
        val samples = links() + scan(1, "Karthik", "4c:ae:00:00:00:01", 2422, -60)
        val sections = buildInsights(grid, samples)
        assertNull(sections.firstOrNull { it.title == "Bands and neighbours" })
        assertTrue(sections.any { it.title == "Channels" })
    }
}
