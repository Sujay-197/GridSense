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
}
