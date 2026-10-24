package com.gridsense.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gridsense.core.Metric
import com.gridsense.core.Pt
import com.gridsense.core.nodeLabel
import com.gridsense.core.qualityLevel
import com.gridsense.data.GridPoint
import com.gridsense.data.Router
import com.gridsense.data.STATUS_DONE
import com.gridsense.data.Sample
import com.gridsense.data.SurveyRoom
import com.gridsense.data.pointValue
import com.gridsense.ui.theme.GridSenseTheme
import com.gridsense.ui.theme.PlanColors
import com.gridsense.ui.theme.Spacing
import com.gridsense.ui.theme.qualityColor
import kotlin.math.hypot

private const val PLAN_PADDING = 32f

@Composable
fun GridPane(
    vm: SurveyViewModel,
    room: SurveyRoom,
    polygon: List<Pt>,
    primaryMetric: Metric,
    points: List<GridPoint>,
    samplesByPoint: Map<Long, List<Sample>>,
    routers: List<Router>,
    textMeasurer: TextMeasurer,
    onSelect: (GridPoint) -> Unit,
    onRetake: (GridPoint) -> Unit
) {
    val colors = GridSenseTheme.plan
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.7f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseValue"
    )
    val medians = points.associate {
        it.id to pointValue(samplesByPoint[it.id].orEmpty(), primaryMetric, null)
    }
    val done = points.count { it.enabled && it.status == STATUS_DONE }

    Column(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Room plan with ${points.size} nodes, $done logged. Tap a node to open it."
                }
                .pointerInput(polygon, points, vm.placingRouter) {
                    fun view() = fitPlan(polygon, Size(size.width.toFloat(), size.height.toFloat()), PLAN_PADDING)
                    detectTapGestures(
                        onTap = { tap ->
                            val v = view()
                            if (vm.placingRouter) vm.placeRouterAt(v.wx(tap.x), v.wy(tap.y))
                            else nearestPoint(points, v, tap)?.let(onSelect)
                        },
                        onLongPress = { tap -> nearestPoint(points, view(), tap)?.let(onRetake) }
                    )
                }
        ) {
            drawRect(colors.background)
            val view = fitPlan(polygon, size, padding = PLAN_PADDING)
            drawGridCells(view, room.gridRows, room.gridCols, room.cellSizeM, colors.gridLine)
            drawOutline(view, polygon, colors.outline, 3f)
            val labelStyle = TextStyle(fontSize = 11.sp, color = colors.label)

            for (p in points) {
                val centre = view.screen(p.x, p.y)
                val radius = when {
                    !p.enabled -> {
                        drawCircle(colors.disabled, radius = 7f, center = centre, style = Stroke(2.5f))
                        7f
                    }
                    vm.loggingPointId == p.id -> {
                        drawCircle(colors.loggingHalo, radius = 22f * pulse, center = centre)
                        drawCircle(colors.logging, radius = 11f, center = centre)
                        11f
                    }
                    p.status == STATUS_DONE -> {
                        val level = medians[p.id]?.let { qualityLevel(primaryMetric, it) }
                        drawCircle(qualityColor(level), radius = 13f, center = centre)
                        drawCircle(colors.doneRing, radius = 13f, center = centre, style = Stroke(2f))
                        13f
                    }
                    else -> {
                        drawCircle(colors.pending, radius = 10f, center = centre)
                        10f
                    }
                }
                val label = nodeLabel(p.row, p.col)
                val measured = textMeasurer.measure(label, labelStyle)
                drawText(
                    textMeasurer,
                    label,
                    Offset(centre.x - measured.size.width / 2f, centre.y + radius + 2f),
                    labelStyle
                )
            }

            for (r in routers) drawRouterMarker(view, r.x, r.y, 13f, colors.router, colors.routerEdge)
        }
        GridLegend(colors, primaryMetric)
    }
}

@Composable
private fun GridLegend(colors: PlanColors, metric: Metric) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            LegendDot(colors.pending, "Pending")
            LegendDot(colors.logging, "Logging")
            LegendDot(qualityColor(null), "Done", ring = colors.doneRing)
            LegendDot(colors.disabled, "Disabled", hollow = true)
        }
        Text(
            "Done nodes are coloured by " + metric.label + " quality. Long-press a node to retake it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String, ring: Color? = null, hollow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(modifier = Modifier.size(14.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                if (hollow) {
                    drawCircle(color, radius = r - 1.5f, style = Stroke(2.5f))
                } else {
                    drawCircle(color, radius = r)
                    if (ring != null) drawCircle(ring, radius = r - 1f, style = Stroke(1.5f))
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun nearestPoint(points: List<GridPoint>, view: PlanView, tap: Offset): GridPoint? {
    var best: GridPoint? = null
    var bestDistance = Float.MAX_VALUE
    for (p in points) {
        val d = hypot(view.sx(p.x) - tap.x, view.sy(p.y) - tap.y)
        if (d < bestDistance) {
            bestDistance = d
            best = p
        }
    }
    return if (bestDistance <= 48f) best else null
}
