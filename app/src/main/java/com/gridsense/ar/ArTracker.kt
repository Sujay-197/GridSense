package com.gridsense.ar

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Config
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

    var pose by mutableStateOf(ArPose.NONE)
        private set

    /** Why AR could not start at all, if it could not. The manual tools still work. */
    var startupProblem by mutableStateOf<String?>(null)
        private set

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
            created.configure(
                Config(created).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                }
            )
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
        }
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
