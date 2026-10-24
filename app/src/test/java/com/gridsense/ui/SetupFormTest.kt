package com.gridsense.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFormTest {

    private val valid = SetupForm(name = "Bedroom", rows = "3", cols = "3", cell = "1.0", samples = "10")

    @Test
    fun aCompleteFormHasNoBlocker() {
        assertNull(valid.blocker)
        assertTrue(valid.gridValid)
    }

    @Test
    fun theDefaultFormOnlyLacksAName() {
        assertEquals("Give the survey a name", SetupForm().blocker)
    }

    @Test
    fun gridSidesMustBeOneToTwenty() {
        assertEquals("Use 1 to 20", valid.copy(rows = "0").rowsError)
        assertEquals("Use 1 to 20", valid.copy(cols = "21").colsError)
        assertEquals("Use 1 to 20", valid.copy(rows = "x").rowsError)
        assertNull(valid.copy(rows = "20").rowsError)
        assertFalse(valid.copy(cols = "").gridValid)
    }

    @Test
    fun cellSizeAndSamplesMustBePositive() {
        assertEquals("Cell size: Enter a size above 0 m", valid.copy(cell = "0").blocker)
        assertEquals("Samples: At least 1 sample", valid.copy(samples = "0").blocker)
        assertNull(valid.copy(cell = "0.5").cellError)
    }

    @Test
    fun theFirstProblemIsReported() {
        assertEquals("Give the survey a name", valid.copy(name = " ", rows = "0").blocker)
        assertEquals("Rows: Use 1 to 20", valid.copy(rows = "0", cell = "0").blocker)
    }

    @Test
    fun duplicateNamesCountUp() {
        assertEquals("Bedroom (2)", duplicateName("Bedroom", listOf("Bedroom")))
        assertEquals("Bedroom (3)", duplicateName("Bedroom", listOf("Bedroom", "Bedroom (2)")))
        assertEquals("Bedroom (3)", duplicateName("Bedroom (2)", listOf("Bedroom", "Bedroom (2)")))
    }

    @Test
    fun latencyTestDefaults() {
        val f = SetupForm()
        assertTrue(f.extendedTests)
        assertFalse(f.loadTest)
        assertEquals("example.com", f.dnsName)
        assertEquals("1.1.1.1", f.tcpHost)
        assertEquals("443", f.tcpPort)
        assertEquals("https://speed.cloudflare.com/__down?bytes=20000000", f.downloadUrl)
    }

    @Test
    fun latencyTestFieldsAreValidatedOnlyWhenTheirTestIsOn() {
        assertEquals("TCP port: Use 1 to 65535", valid.copy(tcpPort = "70000").blocker)
        assertNull(valid.copy(extendedTests = false, tcpPort = "70000", dnsName = "").blocker)
        assertEquals("DNS name: Enter a name to look up", valid.copy(dnsName = " ").blocker)
        assertNull(valid.copy(downloadUrl = "ftp://x").blocker)
        assertEquals("Download URL: Enter an http or https URL", valid.copy(loadTest = true, downloadUrl = "ftp://x").blocker)
    }
}
