package com.gridsense.ar

/**
 * The latest camera position from ARCore, flattened to what the survey needs. World x and z are
 * the horizontal floor plane; look x and z are the horizontal part of the camera's view
 * direction. [sessionId] changes whenever a new ARCore session starts, because a new session
 * has a new world origin and every position from the old one becomes meaningless.
 */
data class ArPose(
    val tracking: Boolean,
    val worldX: Double,
    val worldZ: Double,
    val lookX: Double,
    val lookZ: Double,
    val sessionId: Int,
    val problem: String?
) {
    companion object {
        val NONE = ArPose(false, 0.0, 0.0, 0.0, 0.0, -1, "AR is not running")
    }
}
