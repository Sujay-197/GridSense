package com.gridsense.data

import com.gridsense.core.Metric
import com.gridsense.core.QualityLevel
import com.gridsense.core.bandLabels
import com.gridsense.core.qualityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTest {

    private val grid = (0 until 4).map { i ->
        GridPoint(
            id = i + 1L, roomId = 1, seq = i, row = i / 2, col = i % 2,
            x = (i % 2) + 0.5, y = (i / 2) + 0.5, enabled = true, status = STATUS_DONE
        )
    }

    private fun ping(p: Long, avg: Double? = 4.0) =
        Sample(pointId = p, ts = 0, kind = KIND_PING, pingHost = "192.168.1.1", rttAvgMs = avg, rttJitterMs = 1.0, rttLossPct = 0.0)

    private fun dns(p: Long, ms: Double?, ok: Boolean = ms != null) =
        Sample(pointId = p, ts = 0, kind = KIND_DNS, probeMs = ms, probeOk = ok, target = "example.com")

    private fun tcp(p: Long, ms: Double?, ok: Boolean = ms != null) =
        Sample(pointId = p, ts = 0, kind = KIND_TCP, probeMs = ms, probeOk = ok, target = "1.1.1.1:443")

    private fun loaded(p: Long, ms: Double) =
        Sample(pointId = p, ts = 0, kind = KIND_LOADED_PING, probeMs = ms, probeOk = true, target = "192.168.1.1")

    private fun download(p: Long, bytes: Long, ms: Long, ok: Boolean = true) =
        Sample(pointId = p, ts = 0, kind = KIND_DOWNLOAD, bytes = bytes, durationMs = ms, probeOk = ok, target = DEFAULT_DOWNLOAD_URL)

    private fun link(p: Long, speed: Int) =
        Sample(pointId = p, ts = 0, kind = KIND_LINK, bssid = "b", ssid = "s", freqMhz = 2412, rssiDbm = -55, linkSpeedMbps = speed)

    private fun latencyText(samples: List<Sample>) =
        buildInsights(grid, samples).single { it.title == "Latency, jitter and loss" }.lines.joinToString(" ")

    // --- aggregation -------------------------------------------------------

    @Test
    fun perAttemptRowsAggregateToMediansAndFailureCounts() {
        val rows = listOf(dns(1, 12.0), dns(1, 30.0), dns(1, 14.0), dns(1, null), dns(1, 900.0, ok = false)) +
            listOf(tcp(1, 20.0), tcp(1, 24.0)) +
            listOf(loaded(1, 40.0), loaded(1, 60.0), loaded(1, 50.0)) +
            ping(1, 4.0)
        val n = nodeLatency(rows)
        assertEquals(14.0, n.dns!!.medianMs!!, 1e-9) // a failed attempt's time never counts
        assertEquals(3, n.dns!!.ok)
        assertEquals(2, n.dns!!.failed)
        assertEquals(5, n.dns!!.attempts)
        assertEquals(22.0, n.tcp!!.medianMs!!, 1e-9)
        assertEquals(50.0, n.loadedMedianMs!!, 1e-9)
        assertEquals(46.0, n.bufferbloatMs!!, 1e-9)
        assertFalse(n.loadTested)
    }

    @Test
    fun aNodeWithoutProbesHasNoStats() {
        val n = nodeLatency(listOf(ping(1)))
        assertNull(n.dns)
        assertNull(n.tcp)
        assertNull(n.bufferbloatMs)
        assertNull(n.goodputMbps)
        assertNull(latencyValue(listOf(ping(1)), Metric.DNS))
    }

    @Test
    fun allFailedProbesHaveNoMedian() {
        val n = nodeLatency(listOf(dns(1, null), dns(1, null)))
        assertNull(n.dns!!.medianMs)
        assertEquals(2, n.dns!!.failed)
    }

    // --- formulas ----------------------------------------------------------

    @Test
    fun goodputFromBytesAndDuration() {
        assertEquals(20.0, goodputMbps(20_000_000, 8_000)!!, 1e-9)
        assertEquals(8.0, goodputMbps(1_000_000, 1_000)!!, 1e-9)
        assertNull(goodputMbps(1_000, 0))
        assertEquals(20.0, nodeLatency(listOf(download(1, 20_000_000, 8_000))).goodputMbps!!, 1e-9)
        assertNull(nodeLatency(listOf(download(1, 0, 8_000, ok = false))).goodputMbps)
    }

    @Test
    fun bufferbloatMathsAndGrades() {
        assertEquals(26.0, bufferbloatMs(30.0, 4.0), 1e-9)
        assertEquals(QualityLevel.EXCELLENT, bufferbloatGrade(4.9))
        assertEquals(QualityLevel.GOOD, bufferbloatGrade(5.0)) // "below 5" is strict
        assertEquals(QualityLevel.GOOD, bufferbloatGrade(29.9))
        assertEquals(QualityLevel.FAIR, bufferbloatGrade(30.0))
        assertEquals(QualityLevel.POOR, bufferbloatGrade(60.0))
        assertEquals(QualityLevel.EXCELLENT, bufferbloatGrade(-2.0)) // loaded below idle is noise
    }

    @Test
    fun strictBandsAreLabelledWithLessThan() {
        assertEquals(
            listOf(
                QualityLevel.EXCELLENT to "< 5",
                QualityLevel.GOOD to "5 to 30",
                QualityLevel.FAIR to "30 to 60",
                QualityLevel.POOR to "≥ 60"
            ),
            bandLabels(Metric.BUFFERBLOAT)
        )
        assertEquals(QualityLevel.GOOD, qualityLevel(Metric.DOWNLOAD, 25.0))
        assertEquals(QualityLevel.POOR, qualityLevel(Metric.DOWNLOAD, 4.0))
    }

    @Test
    fun newMetricsPointTheRightWay() {
        assertFalse(Metric.DNS.higherIsBetter)
        assertFalse(Metric.TCP_CONNECT.higherIsBetter)
        assertFalse(Metric.LOADED_LATENCY.higherIsBetter)
        assertFalse(Metric.BUFFERBLOAT.higherIsBetter)
        assertTrue(Metric.DOWNLOAD.higherIsBetter)
        assertEquals("12.3", Metric.DNS.format(12.34))
        assertEquals("-55", Metric.RSSI.format(-55.2))
    }

    @Test
    fun dataUseComesFromTheByteCap() {
        assertEquals(20_000_000L, downloadCapBytes(DEFAULT_DOWNLOAD_URL))
        assertEquals("about 20 MB per node", dataUsePerNode(DEFAULT_DOWNLOAD_URL))
        assertEquals("up to 8 s of download per node", dataUsePerNode("https://example.com/file.bin"))
    }

    @Test
    fun settingsDefaults() {
        val room = SurveyRoom(
            name = "r", mode = "WIFI", samplesPerPoint = 10, pingHost = null, polygon = "",
            gridRows = 3, gridCols = 3, cellSizeM = 1.0, createdAt = 0
        )
        assertTrue(room.extendedTests)
        assertFalse(room.loadTest)
        assertEquals("example.com", room.dnsName)
        assertEquals("1.1.1.1", room.tcpHost)
        assertEquals(443, room.tcpPort)
        assertEquals("https://speed.cloudflare.com/__down?bytes=20000000", room.downloadUrl)
    }

    // --- report ------------------------------------------------------------

    @Test
    fun reportOnFullSuccess() {
        val samples = grid.flatMap { p ->
            listOf(ping(p.id, 4.0), link(p.id, 130)) +
                (1..5).map { dns(p.id, 15.0 + it) } +
                (1..5).map { tcp(p.id, 22.0) } +
                listOf(loaded(p.id, 40.0), loaded(p.id, 44.0)) +
                download(p.id, 20_000_000, 8_000)
        }
        val text = latencyText(samples)
        assertTrue(text.contains("DNS lookups of example.com took a median 18 ms"))
        assertTrue(text.contains("All 20 lookups were answered"))
        assertTrue(text.contains("TCP connect to 1.1.1.1:443 took a median 22 ms, against 4 ms for ping"))
        assertTrue(text.contains("the extra 18 ms is the path beyond the ping target"))
        assertTrue(text.contains("rose from 4 ms idle to 42 ms: bufferbloat 38 ms, grade fair"))
        assertTrue(text.contains("Waveform-style grades"))
        assertTrue(text.contains("goodput was a median 20 Mbps against a reported Wi-Fi link speed of 130 Mbps"))
        assertTrue(text.contains("Link speed is the radio's signalling rate, not throughput"))
        val ratings = buildInsights(grid, samples).single { it.title == "Latency, jitter and loss" }.ratings
            .associate { it.label to it.level }
        assertEquals(QualityLevel.FAIR, ratings["Bufferbloat"])
        assertEquals(QualityLevel.FAIR, ratings["Download goodput"])
        assertEquals(QualityLevel.GOOD, ratings["Median DNS lookup"])
    }

    @Test
    fun reportOnPartialFailure() {
        val samples = grid.flatMap { p ->
            listOf(ping(p.id)) +
                listOf(dns(p.id, 20.0), dns(p.id, null)) +
                listOf(tcp(p.id, 5.0), tcp(p.id, null))
        } + download(1, 20_000_000, 8_000) + loaded(1, 10.0) + download(2, 0, 2_000, ok = false)
        val text = latencyText(samples)
        assertTrue(text.contains("4 of 8 lookups failed"))
        assertTrue(text.contains("and the two agree"))
        assertTrue(text.contains("4 of 8 attempts failed"))
        assertTrue(text.contains("The load test ran at 2 of 4 nodes"))
        assertTrue(text.contains("download failed at B1"))
        assertTrue(text.contains("grade excellent") || text.contains("grade good"))
    }

    @Test
    fun reportOnTotalFailure() {
        val samples = grid.flatMap { p ->
            listOf(ping(p.id, null), dns(p.id, null), tcp(p.id, null), download(p.id, 0, 8_000, ok = false))
        }
        val text = latencyText(samples)
        assertTrue(text.contains("Every DNS lookup of example.com failed (4 of 4)"))
        assertTrue(text.contains("Every TCP connection to 1.1.1.1:443 failed (4 attempts)"))
        assertTrue(text.contains("download failed at every node"))
    }

    @Test
    fun reportOnASkippedLoadTest() {
        val samples = grid.flatMap { p -> listOf(ping(p.id), dns(p.id, 20.0), tcp(p.id, 22.0)) }
        val text = latencyText(samples)
        assertTrue(text.contains("No load test was run"))
        assertFalse(text.contains("grade"))
    }

    @Test
    fun oldSurveysWithoutProbesReadAsBefore() {
        val text = latencyText(grid.map { ping(it.id) })
        assertFalse(text.contains("DNS"))
        assertFalse(text.contains("load test"))
    }
}
