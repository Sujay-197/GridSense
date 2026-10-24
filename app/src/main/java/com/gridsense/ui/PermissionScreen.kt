package com.gridsense.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gridsense.net.Readiness
import com.gridsense.ui.components.ScreenScaffold
import com.gridsense.ui.components.SectionCard
import com.gridsense.ui.theme.Spacing

/**
 * The readiness checklist. Android hides SSID and BSSID behind the location permission,
 * so the survey cannot read Wi-Fi identifiers without it, even though GridSense never
 * asks for a location fix.
 */
@Composable
fun PermissionScreen(
    readiness: Readiness,
    onGrant: () -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?
) {
    val context = LocalContext.current
    val openAppSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    ScreenScaffold(title = "Before you start", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l)
        ) {
            Text(
                "Android gates Wi-Fi and cellular identifiers behind the location permission. " +
                    "Without it the SSID reads \"<unknown ssid>\", the BSSID 02:00:00:00:00:00, and " +
                    "the serving cell is withheld, which makes a coverage survey meaningless. " +
                    "GridSense never requests a location fix and never uses GPS: positions come " +
                    "from the grid you lay over the room.",
                style = MaterialTheme.typography.bodyMedium
            )

            SectionCard(title = "Checklist") {
                CheckRow(
                    Icons.Filled.LocationOn, "Location permission", readiness.locationPermissionGranted,
                    actionLabel = "Grant", onAction = onGrant
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    CheckRow(
                        Icons.Filled.WifiFind, "Nearby Wi-Fi devices permission", readiness.nearbyWifiPermissionGranted,
                        actionLabel = "Grant", onAction = onGrant
                    )
                }
                CheckRow(
                    Icons.Filled.MyLocation, "Location services on", readiness.locationServicesOn,
                    actionLabel = "Turn on",
                    onAction = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                )
                CheckRow(
                    Icons.Filled.Wifi, "Wi-Fi identifiers readable", readiness.wifiIdentifiersReadable,
                    hint = "Connect to the Wi-Fi network you want to survey."
                )
                CheckRow(
                    Icons.Filled.CellTower, "Serving cell readable", readiness.cellularReadable,
                    hint = "Needs a SIM with mobile service."
                )
            }

            if (!readiness.locationPermissionGranted || !readiness.nearbyWifiPermissionGranted) {
                OutlinedButton(onClick = openAppSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Open app settings")
                }
            }

            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (readiness.canLog) "Continue" else "Continue without logging for now")
            }
        }
    }
}

@Composable
private fun CheckRow(
    icon: ImageVector,
    label: String,
    ok: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    hint: String? = null
) {
    val state = if (ok) "Ready" else "Not ready"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $state" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.heightIn(max = 16.dp)
                )
                Text(
                    if (ok || hint == null) state else "$state. $hint",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
        }
        if (!ok && actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
