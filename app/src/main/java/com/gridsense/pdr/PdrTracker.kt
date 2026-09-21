package com.gridsense.pdr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gridsense.core.Pt
import com.gridsense.core.stepDisplacement

const val DEFAULT_STEP_LENGTH_M = 0.72
private const val MAX_TRACE = 4000

/**
 * Pedestrian dead reckoning. Each detected step advances the position by one stride in the
 * direction the phone is currently facing, measured against the heading recorded when the
 * origin corner was set. Hold the phone flat with its top edge pointing the way you walk.
 */
class PdrTracker(context: Context) : SensorEventListener {

    private val sensors =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Current compass azimuth in radians, clockwise from magnetic north. */
    var azimuth by mutableStateOf(0.0)
        private set

    /** The azimuth that defines the room's +y axis. */
    var reference by mutableStateOf(0.0)
        private set

    var x by mutableStateOf(0.0)
        private set

    var y by mutableStateOf(0.0)
        private set

    var steps by mutableStateOf(0)
        private set

    var running by mutableStateOf(false)
        private set

    var stepLengthM by mutableStateOf(DEFAULT_STEP_LENGTH_M)

    /** Breadcrumb of where the walk has been, drawn faintly behind the outline. */
    var trace by mutableStateOf<List<Pt>>(emptyList())
        private set

    var calibrating by mutableStateOf(false)
        private set

    var calibrationSteps by mutableStateOf(0)
        private set

    fun start(): Boolean {
        if (running) return true
        if (stepDetector == null || rotationVector == null) return false
        val ok = try {
            sensors.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME) &&
                sensors.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_FASTEST)
        } catch (denied: SecurityException) {
            false
        }
        running = ok
        return ok
    }

    fun stop() {
        if (!running) return
        sensors.unregisterListener(this)
        running = false
    }

    /** Anchors the room frame: you are standing on the origin corner, facing into the room. */
    fun setOrigin() {
        reference = azimuth
        x = 0.0
        y = 0.0
        steps = 0
        trace = listOf(Pt(0.0, 0.0))
    }

    /** Puts the tracker back onto a position you know, clearing the drift accumulated so far. */
    fun anchorTo(position: Pt) {
        x = position.x
        y = position.y
        trace = trace + position
    }

    fun position(): Pt = Pt(x, y)

    fun startCalibration() {
        calibrationSteps = 0
        calibrating = true
    }

    /** Ends a calibration walk of [distanceM] metres and returns the stride it implies. */
    fun finishCalibration(distanceM: Double): Double? {
        calibrating = false
        val walked = calibrationSteps
        if (walked <= 0 || distanceM <= 0.0) return null
        val stride = distanceM / walked
        stepLengthM = stride
        return stride
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                azimuth = orientation[0].toDouble()
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                if (calibrating) calibrationSteps += 1
                val move = stepDisplacement(stepLengthM, azimuth, reference)
                x += move.x
                y += move.y
                steps += 1
                val updated = trace + Pt(x, y)
                trace = if (updated.size > MAX_TRACE) updated.takeLast(MAX_TRACE) else updated
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
