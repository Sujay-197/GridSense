package com.gridsense.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Width assumed for scan rows that predate stored widths, and for unknown width codes. */
const val DEFAULT_WIDTH_MHZ = 20

/** 5 GHz channels where the router must vacate on radar detection (DFS). */
val DFS_CHANNELS = 52..144

fun isDfs(channel: Int): Boolean = channel in DFS_CHANNELS

/**
 * ScanResult.channelWidth codes: 0 = 20, 1 = 40, 2 = 80, 3 = 160, 4 = 80+80, 5 = 320 MHz.
 * 80+80 is counted as 160 MHz of occupied spectrum.
 */
fun widthMhzFromCode(code: Int): Int? = when (code) {
    0 -> 20
    1 -> 40
    2 -> 80
    3, 4 -> 160
    5 -> 320
    else -> null
}

/** Power in milliwatts. Sums of power, not of dBm, are what interference adds up to. */
fun dbmToMw(dbm: Double): Double = 10.0.pow(dbm / 10.0)

/**
 * Fraction of the narrower block that the two blocks share, from 0 to 1. Two 20 MHz channels
 * one channel number apart are 5 MHz apart and share 15 MHz, so 0.75.
 */
fun spectralOverlap(centerA: Double, widthA: Double, centerB: Double, widthB: Double): Double {
    val shared = min(centerA + widthA / 2, centerB + widthB / 2) - max(centerA - widthA / 2, centerB - widthB / 2)
    return (shared / min(widthA, widthB)).coerceIn(0.0, 1.0)
}

/** Overlap of two 20 MHz channels [delta] channel numbers apart: 1 at 0, 0 at 4 or more. */
fun channelOverlap(delta: Int): Double = spectralOverlap(0.0, 20.0, 5.0 * delta, 20.0)

/** Centre frequency of a 20 MHz channel number on 2.4 GHz (1 to 14) or 5 GHz. */
fun channelCenterMhz(channel: Int, band5: Boolean): Int = when {
    band5 -> 5000 + 5 * channel
    channel == 14 -> 2484
    else -> 2407 + 5 * channel
}
