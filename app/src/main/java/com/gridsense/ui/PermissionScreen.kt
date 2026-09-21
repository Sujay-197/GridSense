package com.gridsense.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gridsense.net.Readiness

/**
 * First-launch rationale. Android hides SSID and BSSID behind the location permission,
 * so the survey cannot read Wi-Fi identifiers without it, even though GridSense never
 * asks for a location fix.
 */
@Composable
fun PermissionScreen(
    readiness: Readiness,
    onGrant: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Before you start", style = MaterialTheme.typography.headlineSmall)

        Text(
            "GridSense needs the location permission because Android gates Wi-Fi and cellular " +
                "identifiers behind it. Without the permission the system reports the SSID as " +
                "\"<unknown ssid>\" and the BSSID as 02:00:00:00:00:00, and refuses to hand " +
                "over the serving cell at all, which makes a coverage survey meaningless.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            "GridSense never requests a location fix and never uses GPS. Your position is " +
                "worked out from your own steps, starting at a corner you choose.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            "The physical activity permission is what lets the phone report each step, which " +
                "is how the survey knows where you are.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusLine("Location permission granted", readiness.locationPermissionGranted)
                StatusLine("Nearby Wi-Fi permission granted", readiness.nearbyWifiPermissionGranted)
                StatusLine("Physical activity permission granted", readiness.activityRecognitionGranted)
                StatusLine("Location services switched on", readiness.locationServicesOn)
                StatusLine("Wi-Fi identifiers readable", readiness.wifiIdentifiersReadable)
                StatusLine("Serving cell readable", readiness.cellularReadable)
                StatusLine("Step detector present", readiness.stepSensorPresent)
                StatusLine("Rotation vector sensor present", readiness.headingSensorPresent)
            }
        }

        if (!readiness.locationPermissionGranted ||
            !readiness.nearbyWifiPermissionGranted ||
            !readiness.activityRecognitionGranted
        ) {
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text("Grant permission")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open app settings")
            }
        }

        if (!readiness.locationServicesOn) {
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Turn on location services")
            }
        }

        if (readiness.locationPermissionGranted &&
            readiness.locationServicesOn &&
            !readiness.wifiIdentifiersReadable &&
            !readiness.cellularReadable
        ) {
            Text(
                "Permissions look right but neither a Wi-Fi association nor a serving cell is " +
                    "readable yet. Connect to the network you want to survey, or make sure the " +
                    "phone has mobile service.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Continue without logging for now")
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text(
        text = (if (ok) "OK   " else "No   ") + label,
        style = MaterialTheme.typography.bodyMedium
    )
}
