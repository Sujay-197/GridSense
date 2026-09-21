package com.gridsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gridsense.core.Band
import com.gridsense.core.Metric
import com.gridsense.core.Pt
import com.gridsense.core.boundsOf
import com.gridsense.data.GridPoint
import com.gridsense.data.KIND_LINK
import com.gridsense.data.Router
import com.gridsense.data.Sample
import com.gridsense.data.SurveyRoom
import com.gridsense.data.observations
import com.gridsense.data.rasterize
import com.gridsense.export.baseName
import com.gridsense.export.share
import com.gridsense.export.writeCsvs
import com.gridsense.export.writeJson
import com.gridsense.export.writePng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt

private const val RASTER_CELL_M = 0.1

@Composable
fun HeatmapPane(
    vm: SurveyViewModel,
    room: SurveyRoom,
    polygon: List<Pt>,
    points: List<GridPoint>,
    samples: List<Sample>,
    routers: List<Router>
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    val bssids = remember(samples) {
        samples.mapNotNull { it.bssid }.distinct().sorted()
    }
    val connectedBssids = remember(samples) {
        samples.filter { it.kind == KIND_LINK }.mapNotNull { it.bssid }.distinct()
    }

    val metric = vm.metric
    val filter = vm.bssidFilter
    val showRouters = vm.showRouters

    val scene by produceState<HeatmapScene?>(
        initialValue = null,
        polygon, points, samples, routers, metric, filter, showRouters, room.name
    ) {
        value = withContext(Dispatchers.Default) {
            val samplesByPoint = samples.groupBy { it.pointId }
            val obs = observations(points, samplesByPoint, metric, filter)
            val raster = rasterize(polygon, obs, RASTER_CELL_M)
            HeatmapScene(
                title = room.name,
                polygonM = polygon,
                raster = raster,
                rasterImage = if (raster == null) null else rasterImage(raster, metric),
                points = points,
                routers = routers,
                metric = metric,
                showRouters = showRouters
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricPicker(vm, modifier = Modifier.weight(1f))
            BssidPicker(vm, bssids, connectedBssids, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Switch(checked = vm.showRouters, onCheckedChange = { vm.showRouters = it })
            Text("Show routers", style = MaterialTheme.typography.bodyMedium)
        }

        val current = scene
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
        ) {
            if (current == null) {
                Text(
                    "Building the surface",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (current.raster == null) {
                Text(
                    "No logged points match this metric and filter yet.",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawHeatmapScene(current, textMeasurer)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val ready = scene
                    if (ready?.raster != null) {
                        scope.launch {
                            val file = withContext(Dispatchers.Default) {
                                val image = renderScene(ready, textMeasurer, density, polygon)
                                writePng(context, image, baseName(room))
                            }
                            share(context, listOf(file), "image/png")
                        }
                    }
                },
                enabled = scene?.raster != null,
                modifier = Modifier.weight(1f)
            ) { Text("PNG") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val files = withContext(Dispatchers.IO) {
                            writeCsvs(context, room, points, samples, routers, baseName(room))
                        }
                        share(context, files, "text/csv")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("CSV") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            writeJson(context, room, points, samples, routers, baseName(room))
                        }
                        share(context, listOf(file), "application/json")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("JSON") }
        }
    }
}

/** Renders the same scene off screen so the exported PNG matches what the app shows. */
private fun renderScene(
    scene: HeatmapScene,
    textMeasurer: TextMeasurer,
    density: Density,
    polygon: List<Pt>
): ImageBitmap {
    val bounds = boundsOf(polygon)
    val width = 1400
    val planHeight = (width * (bounds.height / bounds.width.coerceAtLeast(1e-6)))
        .roundToInt()
        .coerceIn(300, 2400)
    val height = planHeight + 90
    val image = ImageBitmap(width, height)
    val canvas = GraphicsCanvas(image)
    CanvasDrawScope().draw(
        density,
        LayoutDirection.Ltr,
        canvas,
        Size(width.toFloat(), height.toFloat())
    ) {
        drawHeatmapScene(scene, textMeasurer)
    }
    return image
}

@Composable
private fun MetricPicker(vm: SurveyViewModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(vm.metric.label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Metric.entries.forEach { metric ->
                DropdownMenuItem(
                    text = { Text(metric.label + " (" + metric.unit + ")") },
                    onClick = {
                        vm.metric = metric
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BssidPicker(
    vm: SurveyViewModel,
    bssids: List<String>,
    connectedBssids: List<String>,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = vm.metric.band == Band.WIFI,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (vm.metric.band == Band.WIFI) (vm.bssidFilter ?: "Connected AP") else "No BSSID filter")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Connected AP (no filter)") },
                onClick = {
                    vm.bssidFilter = null
                    open = false
                }
            )
            bssids.forEach { bssid ->
                val suffix = if (bssid in connectedBssids) "  (associated)" else ""
                DropdownMenuItem(
                    text = { Text(bssid + suffix) },
                    onClick = {
                        vm.bssidFilter = bssid
                        open = false
                    }
                )
            }
        }
    }
}
