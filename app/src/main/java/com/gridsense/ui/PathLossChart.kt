package com.gridsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gridsense.data.PathLossFit
import com.gridsense.data.PathLossPoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10

private val DISTANCE_TICKS = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0, 50.0)

/** Maps distance (log scale) and RSSI to the chart area. */
private class PathLossAxes(fit: PathLossFit, private val size: Size) {
    val left = 40f
    val right = size.width - 8f
    val top = 8f
    val bottom = size.height - 24f
    val minD = fit.minDistanceM / 1.3
    val maxD = fit.maxDistanceM * 1.3
    val lowDbm: Double
    val highDbm: Double

    init {
        val values = fit.points.map { it.rssiDbm } + listOf(fit.predict(minD), fit.predict(maxD), fit.p0Dbm - 20 * log10(maxD))
        lowDbm = floor((values.min() - 3) / 5) * 5
        highDbm = ceil((values.max() + 3) / 5) * 5
    }

    fun x(d: Double) = left + ((log10(d) - log10(minD)) / (log10(maxD) - log10(minD))).toFloat() * (right - left)
    fun y(dbm: Double) = top + ((highDbm - dbm) / (highDbm - lowDbm)).toFloat() * (bottom - top)
}

/**
 * RSSI against distance on a log axis: the measured nodes, the fitted line and the free-space
 * reference (n = 2 through the same P0). Tapping a dot reports its node through [onPick].
 */
@Composable
fun PathLossChart(
    fit: PathLossFit,
    selected: Long?,
    textMeasurer: TextMeasurer,
    onPick: (PathLossPoint?) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val axisStyle = TextStyle(fontSize = 10.sp, color = scheme.onSurfaceVariant)
    val labelStyle = TextStyle(fontSize = 10.sp, color = scheme.onSurface)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics {
                contentDescription = "Path loss chart for ${fit.router.label}: ${fit.count} nodes, " +
                    String.format("n = %.1f", fit.n)
            }
            .pointerInput(fit) {
                detectTapGestures { tap ->
                    val axes = PathLossAxes(fit, Size(size.width.toFloat(), size.height.toFloat()))
                    val hit = fit.points.minByOrNull { hypot(axes.x(it.distanceM) - tap.x, axes.y(it.rssiDbm) - tap.y) }
                    onPick(hit?.takeIf { hypot(axes.x(it.distanceM) - tap.x, axes.y(it.rssiDbm) - tap.y) < 48f })
                }
            }
    ) {
        val axes = PathLossAxes(fit, size)

        var dbm = axes.highDbm
        while (dbm >= axes.lowDbm) {
            val y = axes.y(dbm)
            drawLine(scheme.outlineVariant, Offset(axes.left, y), Offset(axes.right, y), strokeWidth = 1f)
            drawText(textMeasurer, dbm.toInt().toString(), Offset(0f, y - 7f), axisStyle)
            dbm -= if (axes.highDbm - axes.lowDbm > 30) 10.0 else 5.0
        }
        for (d in DISTANCE_TICKS.filter { it >= axes.minD && it <= axes.maxD }) {
            val x = axes.x(d)
            drawLine(scheme.outline, Offset(x, axes.bottom), Offset(x, axes.bottom + 4f), strokeWidth = 1.5f)
            val text = (if (d < 1) d.toString() else d.toInt().toString()) + " m"
            val m = textMeasurer.measure(text, axisStyle)
            drawText(textMeasurer, text, Offset(x - m.size.width / 2f, axes.bottom + 6f), axisStyle)
        }
        drawLine(scheme.outline, Offset(axes.left, axes.bottom), Offset(axes.right, axes.bottom), strokeWidth = 1.5f)

        // Free-space reference, dashed, then the fit.
        drawLine(
            scheme.tertiary,
            Offset(axes.x(axes.minD), axes.y(fit.p0Dbm - 20 * log10(axes.minD))),
            Offset(axes.x(axes.maxD), axes.y(fit.p0Dbm - 20 * log10(axes.maxD))),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        )
        drawLine(
            scheme.primary,
            Offset(axes.x(axes.minD), axes.y(fit.predict(axes.minD))),
            Offset(axes.x(axes.maxD), axes.y(fit.predict(axes.maxD))),
            strokeWidth = 3f
        )

        for (p in fit.points) {
            val c = Offset(axes.x(p.distanceM), axes.y(p.rssiDbm))
            val isSelected = p.pointId == selected
            drawCircle(scheme.onSurface, radius = if (isSelected) 8f else 6f, center = c)
            if (!p.fromLink) drawCircle(scheme.surface, radius = 3f, center = c)
            if (isSelected) {
                drawCircle(scheme.primary, radius = 12f, center = c, style = Stroke(2.5f))
                drawText(textMeasurer, p.label, Offset(c.x + 10f, c.y - 18f), labelStyle)
            }
        }
    }
}
