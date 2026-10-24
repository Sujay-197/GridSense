package com.gridsense.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTest {

    @Test
    fun overlapFallsLinearlyOverFourChannels() {
        val expected = listOf(1.0, 0.75, 0.5, 0.25, 0.0, 0.0)
        for (delta in 0..5) {
            assertEquals("delta $delta", expected[delta], channelOverlap(delta), 1e-9)
            assertEquals("delta -$delta", expected[delta], channelOverlap(-delta), 1e-9)
        }
    }

    @Test
    fun aNarrowChannelInsideAWideOneOverlapsFully() {
        // A 20 MHz channel 36 (5180) sits inside an 80 MHz block centred on 5210.
        assertEquals(1.0, spectralOverlap(5180.0, 20.0, 5210.0, 80.0), 1e-9)
        // Channel 52 (5260) is outside that block.
        assertEquals(0.0, spectralOverlap(5260.0, 20.0, 5210.0, 80.0), 1e-9)
        // A 40 MHz neighbour centred on channel 3 (2402 to 2442 MHz) covers all of channel 1.
        assertEquals(1.0, spectralOverlap(2412.0, 20.0, 2422.0, 40.0), 1e-9)
    }

    @Test
    fun dbmToMilliwatts() {
        assertEquals(1.0, dbmToMw(0.0), 1e-12)
        assertEquals(1e-7, dbmToMw(-70.0), 1e-18)
        assertEquals(2.0, dbmToMw(-60.0) / dbmToMw(-63.0), 0.01)
    }

    @Test
    fun widthCodes() {
        assertEquals(20, widthMhzFromCode(0))
        assertEquals(40, widthMhzFromCode(1))
        assertEquals(80, widthMhzFromCode(2))
        assertEquals(160, widthMhzFromCode(3))
        assertEquals(160, widthMhzFromCode(4))
        assertEquals(320, widthMhzFromCode(5))
        assertNull(widthMhzFromCode(-1))
    }

    @Test
    fun dfsChannels() {
        assertFalse(isDfs(48))
        assertTrue(isDfs(52))
        assertTrue(isDfs(100))
        assertTrue(isDfs(144))
        assertFalse(isDfs(149))
    }

    @Test
    fun channelCentres() {
        assertEquals(2412, channelCenterMhz(1, band5 = false))
        assertEquals(2472, channelCenterMhz(13, band5 = false))
        assertEquals(2484, channelCenterMhz(14, band5 = false))
        assertEquals(5180, channelCenterMhz(36, band5 = true))
    }
}
