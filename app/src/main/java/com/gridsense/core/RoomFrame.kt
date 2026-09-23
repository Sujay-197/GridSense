package com.gridsense.core

import kotlin.math.hypot

/** Two aimed corners closer than this cannot define a wall direction reliably. */
private const val MIN_WALL_LENGTH_M = 0.3

/**
 * Maps ARCore's horizontal world plane onto the room's own 2-D frame. ARCore's world is
 * gravity aligned with y up, so the floor plane is (x, z). The frame is fixed by two corners of
 * one wall: facing that wall, corner 1 is its left end and becomes the origin, the wall runs
 * along +y to corner 2, and +x points to the right of that direction, back into the room.
 *
 * [offsetX] and [offsetY] shift the result, which is how re-anchoring corrects accumulated
 * drift without redoing the whole frame.
 */
data class RoomFrame(
    val originX: Double,
    val originZ: Double,
    val forwardX: Double,
    val forwardZ: Double,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0
) {
    fun toRoom(worldX: Double, worldZ: Double): Pt {
        val dx = worldX - originX
        val dz = worldZ - originZ
        // Right is forward x up, which for a y-up right-handed world is (-forwardZ, forwardX).
        val x = dx * -forwardZ + dz * forwardX
        val y = dx * forwardX + dz * forwardZ
        return Pt(x + offsetX, y + offsetY)
    }

    /**
     * The room-frame heading of a horizontal world direction, in radians clockwise from +y,
     * which is what the plan uses to draw which way you are facing.
     */
    fun headingOf(lookX: Double, lookZ: Double): Double {
        val x = lookX * -forwardZ + lookZ * forwardX
        val y = lookX * forwardX + lookZ * forwardZ
        return kotlin.math.atan2(x, y)
    }

    /** A copy shifted so the given world position maps exactly onto [truePosition]. */
    fun anchoredAt(worldX: Double, worldZ: Double, truePosition: Pt): RoomFrame {
        val raw = copy(offsetX = 0.0, offsetY = 0.0).toRoom(worldX, worldZ)
        return copy(offsetX = truePosition.x - raw.x, offsetY = truePosition.y - raw.y)
    }

    companion object {
        /**
         * The frame defined by two corners of one wall, given as world floor positions.
         * Returns null when they are too close together to give a direction.
         */
        fun alongWall(x1: Double, z1: Double, x2: Double, z2: Double): RoomFrame? {
            val length = hypot(x2 - x1, z2 - z1)
            if (length < MIN_WALL_LENGTH_M) return null
            return RoomFrame(x1, z1, (x2 - x1) / length, (z2 - z1) / length)
        }
    }
}
