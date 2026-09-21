package com.gridsense.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gridsense.core.Metric
import com.gridsense.core.Pt
import com.gridsense.data.GridPoint
import com.gridsense.data.KIND_CELL
import com.gridsense.data.KIND_PING
import com.gridsense.data.STATUS_DONE
import com.gridsense.data.Sample
import com.gridsense.data.decodePolygon
import com.gridsense.data.medianCellDbm
import com.gridsense.data.medianLinkRssi
import com.gridsense.data.pointValue
import com.gridsense.data.quality
import com.gridsense.data.rampArgb
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(vm: SurveyViewModel, roomId: Long) {
    val room by vm.room.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val samples by vm.samples.collectAsStateWithLifecycle()
    val routers by vm.routers.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var selectedPointId by remember { mutableStateOf<Long?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val currentRoom = room
    if (currentRoom == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading room")
        }
        return
    }

    val polygon = decodePolygon(currentRoom.polygon)
    val primaryMetric = modeOf(currentRoom).defaultMetric
    val samplesByPoint = samples.groupBy { it.pointId }
    val active = points.filter { it.enabled }
    val done = active.count { it.status == STATUS_DONE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(currentRoom.name + "   " + done + " / " + active.size + " done")
                },
                navigationIcon = { TextButton(onClick = { vm.goHome() }) { Text("Rooms") } }
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { vm.beginRouterPlacement() },
                    text = { Text(if (vm.placingRouter) "Tap the plan" else "Router") },
                    icon = {}
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Grid") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Heatmap") })
            }

            val note = vm.note
            if (note != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF8E1))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.note = null }) { Text("Hide") }
                }
            }

            if (!vm.readiness.canLog) {
                Text(
                    "Logging is blocked: grant the location permission, switch location " +
                        "services on, and be on the Wi-Fi network or have a serving cell.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB71C1C),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (tab == 0) {
                GridPane(
                    vm = vm,
                    polygon = polygon,
                    primaryMetric = primaryMetric,
                    points = points,
                    samplesByPoint = samplesByPoint,
                    routers = routers,
                    onSelect = { selectedPointId = it.id },
                    onRetake = {
                        selectedPointId = it.id
                        vm.startLogging(it)
                    }
                )
            } else {
                HeatmapPane(
                    vm = vm,
                    room = currentRoom,
                    polygon = polygon,
                    points = points,
                    samples = samples,
                    routers = routers
                )
            }
        }
    }

    val selected = points.firstOrNull { it.id == selectedPointId }
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedPointId = null },
            sheetState = sheetState
        ) {
            PointSheet(vm, selected, samplesByPoint[selected.id].orEmpty())
        }
    }

    if (vm.routerDialogOpen) {
        RouterDialog(vm)
    }
}

@Composable
private fun GridPane(
    vm: SurveyViewModel,
    polygon: List<Pt>,
    primaryMetric: Metric,
    points: List<GridPoint>,
    samplesByPoint: Map<Long, List<Sample>>,
    routers: List<com.gridsense.data.Router>,
    onSelect: (GridPoint) -> Unit,
    onRetake: (GridPoint) -> Unit
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.7f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseValue"
    )

    val medians = points.associate {
        it.id to pointValue(samplesByPoint[it.id].orEmpty(), primaryMetric, null)
    }
    val measured = medians.values.filterNotNull()
    val low = measured.minOrNull() ?: 0.0
    val high = measured.maxOrNull() ?: 0.0

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F6F7))
            .pointerInput(polygon, points, vm.placingRouter) {
                detectTapGestures(
                    onTap = { tap ->
                        val view = fitPlan(
                            polygon,
                            androidx.compose.ui.geometry.Size(
                                size.width.toFloat(),
                                size.height.toFloat()
                            ),
                            padding = 28f
                        )
                        if (vm.placingRouter) {
                            vm.placeRouterAt(view.wx(tap.x), view.wy(tap.y))
                        } else {
                            nearestPoint(points, view, tap)?.let(onSelect)
                        }
                    },
                    onLongPress = { tap ->
                        val view = fitPlan(
                            polygon,
                            androidx.compose.ui.geometry.Size(
                                size.width.toFloat(),
                                size.height.toFloat()
                            ),
                            padding = 28f
                        )
                        nearestPoint(points, view, tap)?.let(onRetake)
                    }
                )
            }
    ) {
        val view = fitPlan(polygon, size, padding = 28f)
        drawOutline(view, polygon, Color(0xFF263238), 3f)

        for (p in points) {
            val centre = view.screen(p.x, p.y)
            if (!p.enabled) {
                drawCircle(Color(0xFFB0BEC5), radius = 6f, center = centre, style = Stroke(2f))
                continue
            }
            when {
                vm.loggingPointId == p.id -> {
                    drawCircle(Color(0x442196F3), radius = 22f * pulse, center = centre)
                    drawCircle(Color(0xFF1976D2), radius = 11f, center = centre)
                }
                p.status == STATUS_DONE -> {
                    val median = medians[p.id]
                    val color = if (median == null) {
                        Color(0xFF9E9E9E)
                    } else {
                        Color(rampArgb(quality(median, low, high, primaryMetric.higherIsBetter)))
                    }
                    drawCircle(color, radius = 13f, center = centre)
                    drawCircle(Color(0xFF263238), radius = 13f, center = centre, style = Stroke(2f))
                }
                else -> drawCircle(Color(0xFF9E9E9E), radius = 10f, center = centre)
            }
        }

        for (r in routers) {
            drawRouterMarker(view, r.x, r.y, 13f, Color(0xFF1565C0))
        }
    }
}

private fun nearestPoint(
    points: List<GridPoint>,
    view: PlanView,
    tap: Offset
): GridPoint? {
    var best: GridPoint? = null
    var bestDistance = Float.MAX_VALUE
    for (p in points) {
        if (!p.enabled) continue
        val d = hypot(view.sx(p.x) - tap.x, view.sy(p.y) - tap.y)
        if (d < bestDistance) {
            bestDistance = d
            best = p
        }
    }
    return if (bestDistance <= 40f) best else null
}

@Composable
private fun PointSheet(vm: SurveyViewModel, point: GridPoint, samples: List<Sample>) {
    val logging = vm.loggingPointId == point.id
    val ping = samples.lastOrNull { it.kind == KIND_PING }
    val median = medianLinkRssi(samples)
    val cellMedian = medianCellDbm(samples)
    val cellTech = samples.lastOrNull { it.kind == KIND_CELL }?.cellTech ?: "no cell"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Point " + (point.seq + 1),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            String.format("Position %.2f m, %.2f m", point.x, point.y),
            style = MaterialTheme.typography.bodySmall
        )
        Text("Status: " + point.status, style = MaterialTheme.typography.bodySmall)

        if (median != null) {
            Text(String.format("Median Wi-Fi RSSI %.1f dBm", median))
        }
        if (cellMedian != null) {
            Text(String.format("Median cellular signal %.1f dBm (%s)", cellMedian, cellTech))
        }
        if (ping != null) {
            Text(
                "Pinged " + (ping.pingHost ?: "nothing") + ": latency " +
                    formatOrDash(ping.rttAvgMs) +
                    ", jitter " + formatOrDash(ping.rttJitterMs) +
                    ", loss " + formatOrDash(ping.rttLossPct)
            )
        }
        Text(
            "Samples stored: " + samples.size,
            style = MaterialTheme.typography.bodySmall
        )

        if (logging) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    progress = { vm.loggingProgress },
                    modifier = Modifier.size(48.dp)
                )
                Text("Logging this point")
            }
        } else {
            Button(
                onClick = { vm.startLogging(point) },
                enabled = vm.readiness.canLog,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (point.status == STATUS_DONE) "Retake this point" else "Start logging")
            }
            TextButton(
                onClick = { vm.setPointEnabled(point, !point.enabled) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (point.enabled) "Disable this point" else "Enable this point")
            }
        }
    }
}

private fun formatOrDash(value: Double?): String =
    if (value == null) "-" else String.format("%.1f", value)

@Composable
private fun RouterDialog(vm: SurveyViewModel) {
    var label by remember { mutableStateOf("") }
    var bssid by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { vm.cancelRouterPlacement() },
        title = { Text("Router marker") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    String.format(
                        "Placed at %.2f m, %.2f m",
                        vm.pendingRouterX,
                        vm.pendingRouterY
                    ),
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
                    TextButton(
                        onClick = { vm.refreshScanChoices() },
                        enabled = !vm.scanChoicesLoading
                    ) { Text("Rescan") }
                }
                if (vm.scanChoicesLoading) {
                    Text("Scanning", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(vm.scanChoices, key = { it.bssid }) { neighbour ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = bssid == neighbour.bssid,
                                    onClick = { bssid = neighbour.bssid }
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = bssid == neighbour.bssid,
                                onClick = { bssid = neighbour.bssid }
                            )
                            Column {
                                Text(
                                    neighbour.ssid.ifBlank { "(hidden)" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    neighbour.bssid + "   " + neighbour.rssiDbm + " dBm   " +
                                        neighbour.freqMhz + " MHz",
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
        dismissButton = {
            TextButton(onClick = { vm.cancelRouterPlacement() }) { Text("Cancel") }
        }
    )
}
