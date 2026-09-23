package com.gridsense.core

import kotlin.math.hypot

/** The camera has to be within about sixty degrees of level for its heading to mean anything. */
private const val MIN_HORIZONTAL = 0.5

/**
 * Maps ARCore's horizontal world plane onto the room's own 2-D frame. ARCore's world is
 * gravity aligned with y up, so the floor plane is (x, z). The frame is fixed by standing on
 * the origin corner with a wall on your left and pointing the camera along that wall: that
 * direction becomes the room's +y axis and +x points to your right, into the room.
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
         * A frame whose origin is the camera's current position, with +y along the camera's
         * horizontal look direction. Returns null when the camera points too steeply up or down.
         */
        fun at(worldX: Double, worldZ: Double, lookX: Double, lookZ: Double): RoomFrame? {
            val horizontal = hypot(lookX, lookZ)
            if (horizontal < MIN_HORIZONTAL) return null
            return RoomFrame(worldX, worldZ, lookX / horizontal, lookZ / horizontal)
        }
    }
}
