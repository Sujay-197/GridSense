package com.gridsense.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Everything that has to be true before a survey can be walked or a point logged. */
data class Readiness(
    val locationPermissionGranted: Boolean,
    val nearbyWifiPermissionGranted: Boolean,
    val activityRecognitionGranted: Boolean,
    val locationServicesOn: Boolean,
    val wifiIdentifiersReadable: Boolean,
    val cellularReadable: Boolean,
    val stepSensorPresent: Boolean,
    val headingSensorPresent: Boolean
) {
    /** Dead reckoning needs the step detector, a heading and the activity recognition grant. */
    val canWalk: Boolean
        get() = activityRecognitionGranted && stepSensorPresent && headingSensorPresent

    /** Logging needs readable identifiers on at least one of the two radios. */
    val canLog: Boolean
        get() = locationPermissionGranted && locationServicesOn &&
            (wifiIdentifiersReadable || cellularReadable)
}

fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    return permissions.toTypedArray()
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun readReadiness(context: Context): Readiness {
    val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val activity = granted(context, Manifest.permission.ACTIVITY_RECOGNITION)
    val nearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        true
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    return Readiness(
        locationPermissionGranted = fine,
        nearbyWifiPermissionGranted = nearby,
        activityRecognitionGranted = activity,
        locationServicesOn = locationManager.isLocationEnabled,
        wifiIdentifiersReadable = identifiersReadable(readLink(context)),
        cellularReadable = readCell(context) != null,
        stepSensorPresent = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null,
        headingSensorPresent = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
    )
}
