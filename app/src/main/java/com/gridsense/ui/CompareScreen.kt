package com.gridsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gridsense.core.Metric
import com.gridsense.data.NodeDelta
import com.gridsense.data.SurveyRoom
import com.gridsense.data.compareCsv
import com.gridsense.data.compareNodes
import com.gridsense.data.decodePolygon
import com.gridsense.data.incompatibility
import com.gridsense.data.summarise
import com.gridsense.export.baseName
import com.gridsense.export.share
import com.gridsense.export.writePng
import com.gridsense.export.writeText
import com.gridsense.ui.components.EmptyState
import com.gridsense.ui.components.MetricRow
import com.gridsense.ui.components.ScreenScaffold
import com.gridsense.ui.components.SectionCard
import com.gridsense.ui.theme.GridSenseTheme
import com.gridsense.ui.theme.LightPlanColors
import com.gridsense.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun fmt(v: Double, metric: Metric): String =
    if (metric == Metric.LATENCY || metric == Metric.JITTER) String.format("%.1f", v) else String.format("%.1f", v).removeSuffix(".0")

private fun signedFmt(v: Double, metric: Metric) = (if (v > 0) "+" else "") + fmt(v, metric)

@Composable
fun CompareScreen(vm: CompareViewModel, onBack: () -> Unit) {
    val a by vm.roomA.collectAsStateWithLifecycle()
    val b by vm.roomB.collectAsStateWithLifecycle()
    val pointsA by vm.pointsA.collectAsStateWithLifecycle()
    val pointsB by vm.pointsB.collectAsStateWithLifecycle()
    val samplesA by vm.samplesA.collectAsStateWithLifecycle()
    val samplesB by vm.samplesB.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val colors = GridSenseTheme.plan
    var menu by remember { mutableStateOf(false) }

    val metric = vm.metric
    val deltas = remember(pointsA, samplesA, pointsB, samplesB, metric) {
        compareNodes(pointsA, samplesA.groupBy { it.pointId }, pointsB, samplesB.groupBy { it.pointId }, metric)
    }
    val summary = remember(deltas, metric) { summarise(deltas, metric) }
    val roomA = a
    val roomB = b
    val polygon = remember(roomA?.polygon) { decodePolygon(roomA?.polygon.orEmpty()) }
    val title = if (roomA != null && roomB != null) roomA.name + " vs " + roomB.name else "Compare"
    val scene by produceState<DiffScene?>(null, polygon, deltas, metric, title) {
        value = withContext(Dispatchers.Default) { buildDiffScene(title, polygon, deltas, metric) }
    }
    val reference by produceState<com.gridsense.data.Repeatability?>(null, roomA, metric, vm.sameConditions) {
        value = if (roomA == null || vm.sameConditions) null else vm.referenceRepeatability(roomA, metric)
    }

    ScreenScaffold(
        title = "Compare",
        subtitle = title,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            Box {
                IconButton(onClick = { menu = true }, enabled = deltas.isNotEmpty()) {
                    Icon(Icons.Filled.Share, contentDescription = "Share or export")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Difference map PNG", Icons.Filled.Image) {
                        menu = false
                        val s = scene ?: return@MenuItem
                        val r = roomA ?: return@MenuItem
                        scope.launch {
                            val file = withContext(Dispatchers.Default) {
                                writePng(context, renderDiffScene(s, textMeasurer, density, LightPlanColors), baseName(r), "_vs_" + roomB?.id + "_diff")
                            }
                            share(context, listOf(file), "image/png")
                        }
                    }
                    MenuItem("Node table CSV", Icons.Filled.TableChart) {
                        menu = false
                        val r1 = roomA ?: return@MenuItem
                        val r2 = roomB ?: return@MenuItem
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                writeText(context, baseName(r1) + "_vs_" + r2.id + "_compare.csv", compareCsv(r1, r2, metric, deltas))
                            }
                            share(context, listOf(file), "text/csv")
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (roomA == null || roomB == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        val problem = incompatibility(roomA, roomB)
        if (problem != null) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.CompareArrows,
                title = "These surveys can't be compared",
                body = problem,
                modifier = Modifier.padding(padding)
            )
            return@ScreenScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            item(key = "pair") {
                SectionCard(title = "Surveys", icon = Icons.AutoMirrored.Filled.CompareArrows) {
                    MetricRow("A", roomA.name + roomA.condition.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                    MetricRow("B", roomB.name + roomB.condition.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Same conditions", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Nothing changed between the passes, so the differences measure repeatability.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = vm.sameConditions, onCheckedChange = { vm.sameConditions = it })
                    }
                }
            }
            item(key = "metric") { MetricChips(metric) { vm.metric = it } }

            if (deltas.isEmpty() || summary == null) {
                item(key = "none") {
                    SectionCard(title = "No matching nodes") {
                        Text(
                            "No node is logged in both surveys with a " + metric.label + " reading.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                return@LazyColumn
            }

            item(key = "map") {
                SectionCard(title = "Difference map") {
                    val s = scene
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .semantics { contentDescription = "Difference map of ${deltas.size} nodes; blue is better in B, orange worse." }
                    ) {
                        if (s == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else Canvas(Modifier.fillMaxSize()) { drawDiffScene(s, textMeasurer, colors) }
                    }
                }
            }

            item(key = "summary") {
                SectionCard(title = "Summary") {
                    val unit = metric.unit
                    MetricRow("Nodes compared", summary.count.toString())
                    MetricRow("Mean change (B − A)", signedFmt(summary.meanDelta, metric), unit = unit)
                    MetricRow("Mean |change|", fmt(summary.meanAbsDelta, metric), unit = unit)
                    MetricRow("SD of change", fmt(summary.sdDelta, metric), unit = unit)
                    MetricRow(
                        "Largest improvement",
                        summary.bestImprovement?.let { it.label + " " + signedFmt(it.delta, metric) + " " + unit } ?: "none"
                    )
                    MetricRow(
                        "Largest degradation",
                        summary.worstDegradation?.let { it.label + " " + signedFmt(it.delta, metric) + " " + unit } ?: "none"
                    )
                    MetricRow(
                        "Changed by ≥ " + fmt(summary.threshold, metric) + " " + unit,
                        "${summary.changed} (${summary.improved} better, ${summary.degraded} worse)"
                    )
                }
            }

            item(key = "repeat") {
                SectionCard(title = "Repeatability", icon = Icons.Filled.Verified) {
                    val unit = metric.unit
                    if (vm.sameConditions) {
                        Text(
                            "Measurement repeatability: mean |Δ| ${fmt(summary.meanAbsDelta, metric)} $unit, " +
                                "SD of Δ ${fmt(summary.sdDelta, metric)} $unit. Changes under ±" +
                                "${fmt(summary.repeatabilityBand, metric)} $unit (2 SD) are within repeatability.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Give both surveys the same condition text and later comparisons on this grid will quote this figure.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val ref = reference
                        Text(
                            if (ref == null) {
                                "No same-condition pair has been surveyed on this grid, so there is no " +
                                    "repeatability figure yet. Survey the same setup twice with the same condition to get one."
                            } else {
                                "From ${ref.pairName} (${ref.count} nodes, same condition): changes under ±" +
                                    "${fmt(ref.band, metric)} $unit are within repeatability. " +
                                    "${deltas.count { kotlin.math.abs(it.delta) >= ref.band }} of ${deltas.size} nodes here changed by more."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item(key = "table-head") {
                Row(modifier = Modifier.padding(horizontal = Spacing.s)) {
                    listOf("Node", "A", "B", "Δ").forEachIndexed { i, h ->
                        Text(h, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(if (i == 0) 1.2f else 1f))
                    }
                }
            }
            items(deltas, key = { "row-${it.row}-${it.col}" }) { d -> DeltaRow(d, metric) }
        }
    }
}

@Composable
private fun DeltaRow(d: NodeDelta, metric: Metric) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(d.label, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
            Text(fmt(d.a, metric), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(fmt(d.b, metric), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                signedFmt(d.delta, metric) + if (d.gain > 0) " ▲" else if (d.gain < 0) " ▼" else "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (d.gain < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
        HorizontalDivider()
    }
}

/**
 * Picks the two surveys to compare. With [fixedA] the first is already chosen; otherwise the
 * user picks A, then B. Surveys that can't be compared are greyed out with the reason.
 */
@Composable
fun ComparePicker(
    rooms: List<SurveyRoom>,
    fixedA: SurveyRoom?,
    onPicked: (Long, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var a by remember { mutableStateOf(fixedA) }
    val first = a
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (first == null) "Compare: pick survey A" else "Compare ${first.name} with…") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(rooms, key = { it.id }) { r ->
                    val reason = if (first == null) null else incompatibility(first, r)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = reason == null) {
                                if (first == null) a = r else onPicked(first.id, r.id)
                            }
                            .padding(vertical = Spacing.s)
                    ) {
                        val alpha = if (reason == null) 1f else 0.45f
                        Text(
                            r.name + if (r.condition.isNotBlank()) " · " + r.condition else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                        )
                        Text(
                            reason ?: "${r.gridRows} x ${r.gridCols} grid",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (fixedA == null && first != null) a = null else onDismiss() }) {
                Text(if (fixedA == null && first != null) "Back" else "Cancel")
            }
        }
    )
}
