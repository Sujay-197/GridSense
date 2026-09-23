package com.gridsense.ar

import android.app.Activity
import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException

/** Whether this phone can run ARCore right now. */
enum class ArStatus { CHECKING, READY, NEEDS_INSTALL, UNSUPPORTED }

/** One availability check. CHECKING means ARCore has not decided yet and should be asked again. */
fun checkArStatus(context: Context): ArStatus {
    val availability = ArCoreApk.getInstance().checkAvailability(context)
    return when {
        availability.isTransient -> ArStatus.CHECKING
        availability == ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArStatus.READY
        availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArStatus.NEEDS_INSTALL
        else -> ArStatus.UNSUPPORTED
    }
}

/**
 * Asks Play Store to install or update Google Play Services for AR. The activity pauses while
 * that happens, and the availability is checked again when it resumes.
 */
fun requestArInstall(activity: Activity): String? = try {
    ArCoreApk.getInstance().requestInstall(activity, true)
    null
} catch (unavailable: UnavailableException) {
    "Google Play Services for AR could not be installed: " +
        (unavailable.message ?: unavailable.javaClass.simpleName)
}

/**
 * An ARCore tracker that lives while it is in composition and follows the activity lifecycle,
 * or null when [active] is false. Leaving composition closes the session, which also discards
 * its world origin.
 */
@Composable
fun rememberArTracker(active: Boolean): ArTracker? {
    if (!active) return null
    val activity = LocalContext.current as Activity
    val tracker = remember { ArTracker(activity) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, tracker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> tracker.resume()
                Lifecycle.Event.ON_PAUSE -> tracker.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            tracker.close()
        }
    }
    return tracker
}

/** The live camera feed. ARCore only updates while this view is rendering. */
@Composable
fun ArPreview(tracker: ArTracker, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val holder = remember { arrayOfNulls<GLSurfaceView>(1) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(tracker)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                holder[0] = this
            }
        }
    )
}
