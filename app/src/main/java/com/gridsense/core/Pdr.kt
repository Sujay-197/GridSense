package com.gridsense.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Wraps an angle in radians into the range (-pi, pi]. */
fun normaliseAngle(radians: Double): Double {
    var a = radians
    while (a <= -Math.PI) a += 2 * Math.PI
    while (a > Math.PI) a -= 2 * Math.PI
    return a
}

/**
 * The displacement of one detected step in the room frame. Both angles are compass azimuths
 * in radians measured clockwise from magnetic north. The direction you were facing when the
 * origin corner was set becomes the room's +y axis, and +x is ninety degrees to its right.
 */
fun stepDisplacement(stepLengthM: Double, azimuth: Double, reference: Double): Pt {
    val relative = normaliseAngle(azimuth - reference)
    return Pt(stepLengthM * sin(relative), stepLengthM * cos(relative))
}

/** Straight line distance between two positions, used to report accumulated drift. */
fun distance(a: Pt, b: Pt): Double = hypot(b.x - a.x, b.y - a.y)

/** Estimated stride from a calibration walk over a measured distance. */
fun calibrateStepLength(distanceM: Double, steps: Int): Double? =
    if (steps <= 0 || distanceM <= 0.0) null else distanceM / steps
