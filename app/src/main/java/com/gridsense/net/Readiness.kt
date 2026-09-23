package com.gridsense.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Everything that has to be true before a point can be logged or AR tracking can run. */
data class Readiness(
    val locationPermissionGranted: Boolean,
    val nearbyWifiPermissionGranted: Boolean,
    val cameraPermissionGranted: Boolean,
    val locationServicesOn: Boolean,
    val wifiIdentifiersReadable: Boolean,
    val cellularReadable: Boolean
) {
    /** Logging needs readable identifiers on at least one of the two radios. */
    val canLog: Boolean
        get() = locationPermissionGranted && locationServicesOn &&
            (wifiIdentifiersReadable || cellularReadable)
}

fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    return permissions.toTypedArray()
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun readReadiness(context: Context): Readiness {
    val nearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        true
    }
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    return Readiness(
        locationPermissionGranted = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
        nearbyWifiPermissionGranted = nearby,
        cameraPermissionGranted = granted(context, Manifest.permission.CAMERA),
        locationServicesOn = locationManager.isLocationEnabled,
        wifiIdentifiersReadable = identifiersReadable(readLink(context)),
        cellularReadable = readCell(context) != null
    )
}
