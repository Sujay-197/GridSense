package com.gridsense.ar

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.UnavailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Owns one ARCore session and runs it from a GLSurfaceView. Every rendered frame updates the
 * session and publishes the camera position as [pose]. The session is touched from both the
 * main thread (lifecycle) and the GL thread (updates), so every use goes through [lock].
 */
class ArTracker(private val activity: Activity) : GLSurfaceView.Renderer {

    private val lock = Any()
    private var session: Session? = null
    private var sessionId = -1
    private val background = CameraBackground()

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var geometryPending = false

    /** A crosshair measurement waiting for the next frame, with who to tell about it. */
    private var pendingHit: ((ArHit?, String?) -> Unit)? = null

    var pose by mutableStateOf(ArPose.NONE)
        private set

    /** Why AR could not start at all, if it could not. The manual tools still work. */
    var startupProblem by mutableStateOf<String?>(null)
        private set

    /** Whether the depth API is on. Without it, aiming only works on mapped planes and features. */
    var depthEnabled by mutableStateOf(false)
        private set

    /**
     * Measures where the crosshair at the centre of the preview meets a surface on the next
     * frame. [onResult] runs on the main thread with either a hit or the reason there was none.
     */
    fun hitAtCentre(onResult: (ArHit?, String?) -> Unit) {
        synchronized(lock) { pendingHit = onResult }
    }

    fun resume() {
        synchronized(lock) { resumeLocked() }
    }

    private fun resumeLocked() {
        if (session == null) {
            val created = try {
                Session(activity)
            } catch (unavailable: UnavailableException) {
                startupProblem = "ARCore could not start on this phone: " +
                    (unavailable.message ?: unavailable.javaClass.simpleName)
                return
            }
            val depth = created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            created.configure(
                Config(created).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    // Walls are what we aim at, so vertical planes matter as much as the floor.
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    depthMode = if (depth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                }
            )
            depthEnabled = depth
            session = created
            sessionId = nextSessionId++
            geometryPending = true
        }
        try {
            session?.resume()
            startupProblem = null
        } catch (busy: CameraNotAvailableException) {
            startupProblem = "The camera is in use by another app."
        }
    }

    fun pause() {
        synchronized(lock) { session?.pause() }
    }

    fun close() {
        synchronized(lock) {
            session?.close()
            session = null
            pose = ArPose.NONE
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        synchronized(lock) {
            surfaceWidth = width
            surfaceHeight = height
            geometryPending = true
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        synchronized(lock) {
            val active = session ?: return
            if (geometryPending && surfaceWidth > 0) {
                active.setDisplayGeometry(displayRotation(), surfaceWidth, surfaceHeight)
                geometryPending = false
            }
            active.setCameraTextureName(background.textureId)
            val frame = try {
                active.update()
            } catch (paused: SessionPausedException) {
                return
            } catch (lost: CameraNotAvailableException) {
                pose = ArPose.NONE.copy(sessionId = sessionId, problem = "Camera unavailable")
                return
            }
            background.draw(frame)

            val camera = frame.camera
            pose = if (camera.trackingState == TrackingState.TRACKING) {
                val p = camera.pose
                // The camera looks down its own -z axis.
                val z = p.zAxis
                ArPose(
                    tracking = true,
                    worldX = p.tx().toDouble(),
                    worldZ = p.tz().toDouble(),
                    lookX = -z[0].toDouble(),
                    lookZ = -z[2].toDouble(),
                    sessionId = sessionId,
                    problem = null
                )
            } else {
                ArPose.NONE.copy(
                    sessionId = sessionId,
                    problem = describe(camera.trackingFailureReason)
                )
            }

            val request = pendingHit ?: return
            pendingHit = null
            val tracking = camera.trackingState == TrackingState.TRACKING
            val hit = if (tracking) hitCentre(frame) else null
            val problem = when {
                hit != null -> null
                !tracking -> "ARCore is not tracking. " + describe(camera.trackingFailureReason)
                else -> "Nothing to measure against at the crosshair. Aim at a spot with some " +
                    "texture, or sweep the camera slowly over the wall first so ARCore can map it."
            }
            activity.runOnUiThread { request(hit, problem) }
        }
    }

    /** The nearest usable surface along the ray through the centre of the preview. */
    private fun hitCentre(frame: Frame): ArHit? {
        val chosen = frame.hitTest(surfaceWidth / 2f, surfaceHeight / 2f).firstOrNull { result ->
            when (val trackable = result.trackable) {
                is Plane -> trackable.isPoseInPolygon(result.hitPose)
                is DepthPoint -> true
                is Point -> true
                else -> false
            }
        } ?: return null
        val surface = when (chosen.trackable) {
            is DepthPoint -> "depth"
            is Plane -> "a mapped surface"
            else -> "a feature point"
        }
        val pose = chosen.hitPose
        return ArHit(
            worldX = pose.tx().toDouble(),
            worldZ = pose.tz().toDouble(),
            distanceM = chosen.distance.toDouble(),
            surface = surface,
            sessionId = sessionId
        )
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = activity.windowManager.defaultDisplay.rotation

    private fun describe(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark for tracking"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast"
        TrackingFailureReason.INSUFFICIENT_FEATURES ->
            "Point the camera at something with texture, not a blank wall"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        TrackingFailureReason.BAD_STATE -> "ARCore is in a bad state, try leaving and returning"
        else -> "Starting up, move the phone slowly"
    }

    private companion object {
        var nextSessionId = 1
    }
}
