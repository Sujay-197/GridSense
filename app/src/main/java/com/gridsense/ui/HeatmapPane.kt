package com.gridsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import com.gridsense.core.Band
import com.gridsense.core.Metric
import com.gridsense.core.Pt
import com.gridsense.data.GridPoint
import com.gridsense.data.KIND_LINK
import com.gridsense.data.Router
import com.gridsense.data.Sample
import com.gridsense.data.SurveyRoom
import com.gridsense.ui.components.EmptyState
import com.gridsense.ui.theme.GridSenseTheme
import com.gridsense.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun familyName(band: Band) = when (band) {
    Band.WIFI -> "Wi-Fi"
    Band.CELLULAR -> "Cellular"
    Band.IP -> "Network"
}

/** Chip text without the family prefix, since the chips sit under a family label. */
private fun shortLabel(metric: Metric) = when (metric) {
    Metric.RSSI -> "RSSI"
    Metric.CELL_DBM -> "Signal"
    Metric.CELL_RSRP -> "RSRP"
    Metric.CELL_RSRQ -> "RSRQ"
    Metric.CELL_SINR -> "SINR"
    else -> metric.label
}

@Composable
fun HeatmapPane(
    vm: SurveyViewModel,
    room: SurveyRoom,
    polygon: List<Pt>,
    points: List<GridPoint>,
    samples: List<Sample>,
    routers: List<Router>,
    textMeasurer: TextMeasurer
) {
    val colors = GridSenseTheme.plan
    val bssids = remember(samples) { samples.mapNotNull { it.bssid }.distinct().sorted() }
    val connected = remember(samples) {
        samples.filter { it.kind == KIND_LINK }.mapNotNull { it.bssid }.distinct()
    }

    val metric = vm.metric
    val filter = vm.bssidFilter
    val showRouters = vm.showRouters
    val fixed = vm.fixedScale

    val scene by produceState<HeatmapScene?>(
        initialValue = null,
        polygon, points, samples, routers, metric, filter, showRouters, fixed, room.name
    ) {
        value = withContext(Dispatchers.Default) {
            buildHeatmapScene(room.name, polygon, points, samples, routers, metric, filter, showRouters, fixed)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            MetricChips(vm.metric) { vm.metric = it }
            if (metric.band == Band.WIFI) BssidDropdown(vm, bssids, connected)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = !fixed,
                        onClick = { vm.fixedScale = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("Relative") }
                    SegmentedButton(
                        selected = fixed,
                        onClick = { vm.fixedScale = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("Fixed") }
                }
                FilterChip(
                    selected = showRouters,
                    onClick = { vm.showRouters = !showRouters },
                    label = { Text("Routers") },
                    leadingIcon = { Icon(Icons.Filled.Router, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                )
            }
            Text(
                if (fixed) "Colours follow fixed quality bands, so red always means poor."
                else "Colours stretch from the weakest to the strongest spot in this room.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val current = scene
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                current.raster == null -> EmptyState(
                    icon = Icons.Filled.Layers,
                    title = "Nothing to map yet",
                    body = "No logged nodes have " + metric.label + " readings" +
                        (if (filter != null) " for this access point" else "") + "."
                )
                else -> Canvas(modifier = Modifier.fillMaxSize()) {
                    drawHeatmapScene(current, textMeasurer, colors)
                }
            }
        }
    }
}

@Composable
fun MetricChips(selectedMetric: Metric, onSelect: (Metric) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        Band.entries.forEach { band ->
            Text(
                familyName(band),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = if (band == Band.WIFI) Spacing.xs else Spacing.m)
            )
            Metric.entries.filter { it.band == band }.forEach { m ->
                val selected = selectedMetric == m
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(m) },
                    label = { Text(shortLabel(m)) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BssidDropdown(vm: SurveyViewModel, bssids: List<String>, connected: List<String>) {
    var open by remember { mutableStateOf(false) }
    val current = vm.bssidFilter
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = current?.let { it + if (it in connected) " (associated)" else "" } ?: "Connected AP (no filter)",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Access point") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Connected AP (no filter)") },
                onClick = {
                    vm.bssidFilter = null
                    open = false
                }
            )
            bssids.forEach { bssid ->
                DropdownMenuItem(
                    text = { Text(bssid + if (bssid in connected) "  (associated)" else "") },
                    onClick = {
                        vm.bssidFilter = bssid
                        open = false
                    }
                )
            }
        }
    }
}
