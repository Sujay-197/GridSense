package com.gridsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gridsense.core.Metric
import com.gridsense.core.median
import com.gridsense.core.nodeLabel
import com.gridsense.core.qualityLevel
import com.gridsense.data.GridPoint
import com.gridsense.data.KIND_CELL
import com.gridsense.data.KIND_LINK
import com.gridsense.data.KIND_PING
import com.gridsense.data.STATUS_DONE
import com.gridsense.data.STATUS_LOGGING
import com.gridsense.data.Sample
import com.gridsense.data.pointValue
import com.gridsense.ui.components.MetricRow
import com.gridsense.ui.components.SectionCard
import com.gridsense.ui.components.StageProgress
import com.gridsense.ui.theme.Spacing

private fun fmt(v: Double?): String? = v?.let { if (it == Math.rint(it)) it.toLong().toString() else String.format("%.1f", it) }

private fun statusWord(point: GridPoint, logging: Boolean) = when {
    !point.enabled -> "Disabled"
    logging || point.status == STATUS_LOGGING -> "Logging"
    point.status == STATUS_DONE -> "Done"
    else -> "Pending"
}

@Composable
fun NodeSheet(vm: SurveyViewModel, point: GridPoint, samples: List<Sample>) {
    val logging = vm.loggingPointId == point.id
    val links = samples.filter { it.kind == KIND_LINK }
    val lastLink = links.lastOrNull()
    val lastCell = samples.lastOrNull { it.kind == KIND_CELL }
    val ping = samples.lastOrNull { it.kind == KIND_PING }
    fun value(m: Metric) = pointValue(samples, m, null)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(
                "Node " + nodeLabel(point.row, point.col),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            AssistChip(onClick = {}, label = { Text(statusWord(point, logging)) })
        }
        Text(
            String.format("Row %d, column %d · %.2f m, %.2f m", point.row + 1, point.col + 1, point.x, point.y),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.s))

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            if (samples.isEmpty() && !logging) {
                Text(
                    "Stand at the centre of this cell, hold the phone as you will for every node, and start logging.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (!logging) {
                SectionCard("Wi-Fi", icon = Icons.Filled.Wifi) {
                    Rated("RSSI", Metric.RSSI, value(Metric.RSSI))
                    val speeds = links.mapNotNull { it.linkSpeedMbps?.toDouble() }
                    MetricRow("Link speed", fmt(if (speeds.isEmpty()) null else median(speeds)), unit = "Mbps")
                    MetricRow("Network", lastLink?.let { (it.ssid ?: "(hidden)") + (it.freqMhz?.let { f -> " · $f MHz" } ?: "") })
                }
                SectionCard("Cellular", icon = Icons.Filled.SignalCellularAlt) {
                    Rated("Signal" + (lastCell?.cellTech?.let { " ($it)" } ?: ""), Metric.CELL_DBM, value(Metric.CELL_DBM))
                    Rated("RSRP", Metric.CELL_RSRP, value(Metric.CELL_RSRP))
                    Rated("RSRQ", Metric.CELL_RSRQ, value(Metric.CELL_RSRQ))
                    Rated("SINR", Metric.CELL_SINR, value(Metric.CELL_SINR))
                }
                SectionCard("Network", icon = Icons.Filled.Lan) {
                    Rated("Latency", Metric.LATENCY, ping?.rttAvgMs)
                    Rated("Jitter", Metric.JITTER, ping?.rttJitterMs)
                    Rated("Packet loss", Metric.LOSS, ping?.rttLossPct)
                    MetricRow("Ping target", ping?.pingHost)
                }
                Text(
                    "Samples stored: " + samples.size,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.m))
        if (logging) {
            StageProgress(vm.stages)
        } else {
            Button(
                onClick = { vm.startLogging(point) },
                enabled = vm.readiness.canLog && point.enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                if (point.status == STATUS_DONE) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("Retake this node", modifier = Modifier.padding(start = Spacing.s))
                } else {
                    Text("Start logging")
                }
            }
            TextButton(
                onClick = { vm.setPointEnabled(point, !point.enabled) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text(if (point.enabled) "Disable this node" else "Enable this node") }
        }
    }
}

@Composable
private fun Rated(label: String, metric: Metric, value: Double?) {
    MetricRow(label, fmt(value), unit = metric.unit, level = value?.let { qualityLevel(metric, it) }, rated = true)
}

@Composable
fun RouterDialog(vm: SurveyViewModel) {
    var label by remember { mutableStateOf("") }
    var bssid by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { vm.cancelRouterPlacement() },
        title = { Text("Router marker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    String.format("Placed at %.2f m, %.2f m", vm.pendingRouterX, vm.pendingRouterY),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Nearby access points", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { vm.refreshScanChoices() }, enabled = !vm.scanChoicesLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                    }
                }
                if (vm.scanChoicesLoading) {
                    Text("Scanning", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp).selectableGroup()) {
                    items(vm.scanChoices, key = { it.bssid }) { neighbour ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = bssid == neighbour.bssid,
                                    onClick = { bssid = neighbour.bssid },
                                    role = Role.RadioButton
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = bssid == neighbour.bssid, onClick = null)
                            Column(modifier = Modifier.padding(start = Spacing.s)) {
                                Text(neighbour.ssid.ifBlank { "(hidden)" }, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    neighbour.bssid + " · " + neighbour.rssiDbm + " dBm · " + neighbour.freqMhz + " MHz",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.confirmRouter(bssid!!, label.trim()) },
                enabled = bssid != null && label.isNotBlank()
            ) { Text("Place") }
        },
        dismissButton = { TextButton(onClick = { vm.cancelRouterPlacement() }) { Text("Cancel") } }
    )
}
