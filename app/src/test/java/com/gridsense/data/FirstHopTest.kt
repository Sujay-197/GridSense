package com.gridsense.data

import com.gridsense.core.QualityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report wording checked against a real 2 x 2 survey (r1): every number here comes from it. */
class FirstHopTest {

    private val grid = (0 until 4).map { i ->
        GridPoint(
            id = i + 1L, roomId = 1, seq = i, row = i / 2, col = i % 2,
            x = (i % 2) + 0.5, y = (i / 2) + 0.5, enabled = true, status = STATUS_DONE
        )
    }

    private val myBssid = "60:98:49:cf:c8:2a"
    private val mySsid = "Airtel_SayMyName_BB"

    private fun link(p: Long, rssi: Int) = Sample(
        pointId = p, ts = 0, kind = KIND_LINK, bssid = myBssid, ssid = mySsid,
        freqMhz = 2412, rssiDbm = rssi, linkSpeedMbps = 130
    )

    private fun ping(p: Long, host: String, avg: Double, jitter: Double) = Sample(
        pointId = p, ts = 0, kind = KIND_PING, pingHost = host,
        rttAvgMs = avg, rttJitterMs = jitter, rttLossPct = 0.0
    )

    private fun tcp(p: Long, ms: Double) =
        Sample(pointId = p, ts = 0, kind = KIND_TCP, probeMs = ms, probeOk = true, target = "1.1.1.1:443")

    private fun scan(p: Long, ssid: String, bssid: String, freq: Int, rssi: Int, width: Int? = null, centre: Int? = null) =
        Sample(
            pointId = p, ts = 0, kind = KIND_SCAN, bssid = bssid, ssid = ssid,
            freqMhz = freq, rssiDbm = rssi, channelWidthMhz = width, centerFreq0Mhz = centre
        )

    private val wifi = (1L..4L).flatMap { p -> List(10) { link(p, -57) } }

    /** r1: the router answered pings in 26 to 33 ms with 13 to 47 ms of jitter. */
    private val r1Pings = listOf(
        ping(1, "192.168.1.1", 25.9, 20.9), ping(2, "192.168.1.1", 33.3, 35.5),
        ping(3, "192.168.1.1", 30.5, 13.0), ping(4, "192.168.1.1", 33.2, 46.8)
    )

    private fun section(samples: List<Sample>, title: String, loadTest: Boolean? = null) =
        buildInsights(grid, samples, loadTestEnabled = loadTest).single { it.title == title }

    private fun text(samples: List<Sample>, title: String, loadTest: Boolean? = null) =
        section(samples, title, loadTest).lines.joinToString(" ")

    @Test
    fun privateAndPublicAddressesAreTold_apart() {
        assertTrue(isLanAddress("192.168.1.1"))
        assertTrue(isLanAddress("10.0.0.1"))
        assertTrue(isLanAddress("172.20.4.1"))
        assertTrue(isLanAddress("fe80::6298:49ff:fecf:c821"))
        assertFalse(isLanAddress("172.32.0.1"))
        assertFalse(isLanAddress("8.8.8.8"))
        assertFalse(isLanAddress("1.1.1.1"))
        assertFalse(isLanAddress("example.com"))
    }

    @Test
    fun thirtyMillisecondsToYourOwnRouterIsHighNotFine() {
        val s = section(wifi + r1Pings, "Latency, jitter and loss")
        val t = s.lines.joinToString(" ")
        assertTrue(t.contains("your own router"))
        assertTrue(t.contains("high for the hop to your own router"))
        assertTrue(t.contains("Wi-Fi power saving"))
        assertFalse(t.contains("moderate, fine"))
        assertEquals(QualityLevel.POOR, s.ratings.single { it.label == "Median latency to router" }.level)
        assertTrue(t.contains("unsteady for a single Wi-Fi hop"))
        assertEquals(QualityLevel.POOR, s.ratings.single { it.label == "Median jitter" }.level)
    }

    @Test
    fun aFastRouterReadsAsNormal() {
        val pings = (1L..4L).map { ping(it, "192.168.1.1", 3.0, 1.0) }
        val t = text(wifi + pings, "Latency, jitter and loss")
        assertTrue(t.contains("normal for the hop to your own router"))
        assertTrue(t.contains("Jitter median 1 ms: steady"))
    }

    @Test
    fun internetTargetsKeepTheInternetBands() {
        val pings = (1L..4L).map { ping(it, "8.8.8.8", 32.0, 3.0) }
        val t = text(wifi + pings, "Latency, jitter and loss")
        assertTrue(t.contains("moderate, fine for browsing and video"))
        assertFalse(t.contains("own router"))
    }

    @Test
    fun aTcpGapInsideThePingJitterIsNotBlamedOnTheInternet() {
        // r1: TCP 45.8 ms against ping 31.9 ms, but the ping jitter alone was about 28 ms.
        val tcps = (1L..4L).flatMap { p -> listOf(45.0, 46.0, 47.0, 44.0, 46.5).map { tcp(p, it) } }
        val t = text(wifi + r1Pings + tcps, "Latency, jitter and loss")
        assertTrue(t.contains("within the ping's own jitter"))
        assertFalse(t.contains("is the path beyond the ping target"))
        assertTrue(t.contains("Most of the round trip to 1.1.1.1 is already spent on the first hop"))
    }

    @Test
    fun aClearTcpGapIsStillAttributed() {
        val pings = (1L..4L).map { ping(it, "192.168.1.1", 3.0, 1.0) }
        val tcps = (1L..4L).flatMap { p -> List(5) { tcp(p, 40.0) } }
        val t = text(wifi + pings + tcps, "Latency, jitter and loss")
        assertTrue(t.contains("so the extra 37 ms is the path beyond the ping target"))
        assertFalse(t.contains("already spent on the first hop"))
    }

    @Test
    fun loadTestSwitchedOnWithoutResultsSaysSo() {
        val tcps = (1L..4L).map { tcp(it, 45.0) }
        val on = text(wifi + r1Pings + tcps, "Latency, jitter and loss", loadTest = true)
        assertTrue(on.contains("switched on for this survey, but none of the logged nodes has a result"))
        assertFalse(on.contains("off by default"))
        val off = text(wifi + r1Pings + tcps, "Latency, jitter and loss", loadTest = false)
        assertTrue(off.contains("The load test is off for this survey"))
    }

    @Test
    fun aClearFiveGigahertzBandIsCalledClear() {
        // r1: connected on 2.4 GHz; the router's own 5 GHz radio is 160 MHz wide on channel 44,
        // centred on 5210 MHz, and no other 5 GHz network was heard.
        val scans = (1L..4L).flatMap { p ->
            listOf(
                scan(p, mySsid, "60:98:49:cf:c8:2b", 5220, -65, width = 160, centre = 5210),
                scan(p, "Airtel_padm_6652", "a4:08:53:4d:6b:8a", 2462, -86)
            )
        }
        val t = text(wifi + scans, "Channels")
        assertTrue(t.contains("Your router's 5 GHz radio is on channel 44 (160 MHz)"))
        assertTrue(t.contains("so that band is clear"))
        assertFalse(t.contains("least occupied 5 GHz channel here is 36"))
        assertTrue(t.contains("reaches into the DFS channels"))
    }

    @Test
    fun aNarrowRadioOutsideDfsGetsNoDfsWarning() {
        val scans = (1L..4L).map { p -> scan(p, mySsid, "60:98:49:cf:c8:2b", 5180, -65, width = 20, centre = 5180) }
        assertFalse(text(wifi + scans, "Channels").contains("DFS"))
    }

    @Test
    fun aUniformRoomNoLongerArguesFromSessionNoise() {
        val t = text(wifi, "Wi-Fi coverage")
        assertTrue(t.contains("within how much a repeat survey of the same spot typically moves"))
        assertTrue(t.contains("uniformly covered"))
        assertFalse(t.contains("±"))
    }
}
