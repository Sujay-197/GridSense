package com.gridsense.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.gridsense.core.Metric
import com.gridsense.core.Pt
import com.gridsense.data.GridPoint
import com.gridsense.data.Raster
import com.gridsense.data.Router
import com.gridsense.data.rampArgb
import com.gridsense.data.quality
import kotlin.math.roundToInt

/** Turns the interpolated surface into an image, transparent outside the room outline. */
fun rasterImage(raster: Raster, metric: Metric): ImageBitmap {
    val pixels = IntArray(raster.width * raster.height)
    for (i in pixels.indices) {
        pixels[i] = if (raster.inside[i]) {
            rampArgb(
                quality(raster.values[i], raster.min, raster.max, metric.higherIsBetter),
                alpha = 225
            )
        } else {
            0
        }
    }
    return Bitmap.createBitmap(pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/** Everything the heatmap draws, so the screen and the exported PNG stay identical. */
class HeatmapScene(
    val title: String,
    val polygonM: List<Pt>,
    val raster: Raster?,
    val rasterImage: ImageBitmap?,
    val points: List<GridPoint>,
    val routers: List<Router>,
    val metric: Metric,
    val showRouters: Boolean
)

private const val LEGEND_HEIGHT = 74f

fun DrawScope.drawHeatmapScene(scene: HeatmapScene, textMeasurer: TextMeasurer) {
    drawRect(Color.White, size = size)

    val planSize = Size(size.width, (size.height - LEGEND_HEIGHT).coerceAtLeast(1f))
    val view = fitPlan(scene.polygonM, planSize, padding = 24f)

    val raster = scene.raster
    val image = scene.rasterImage
    if (raster != null && image != null) {
        drawImage(
            image = image,
            dstOffset = IntOffset(
                view.sx(raster.originX).roundToInt(),
                view.sy(raster.originY).roundToInt()
            ),
            dstSize = IntSize(
                (raster.width * raster.cell * view.scale).roundToInt().coerceAtLeast(1),
                (raster.height * raster.cell * view.scale).roundToInt().coerceAtLeast(1)
            )
        )
    }

    drawOutline(view, scene.polygonM, Color(0xFF263238), 3f)

    for (p in scene.points) {
        if (!p.enabled) continue
        drawCircle(Color(0xFF263238), radius = 4f, center = view.screen(p.x, p.y))
        drawCircle(Color.White, radius = 4f, center = view.screen(p.x, p.y), style = Stroke(1.5f))
    }

    if (scene.showRouters) {
        val labelStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF0D47A1))
        for (r in scene.routers) {
            drawRouterMarker(view, r.x, r.y, 11f, Color(0xFF1565C0))
            val measured = textMeasurer.measure(r.label, labelStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = r.label,
                topLeft = Offset(view.sx(r.x) - measured.size.width / 2f, view.sy(r.y) + 13f),
                style = labelStyle
            )
        }
    }

    drawLegend(scene, textMeasurer)
}

private fun DrawScope.drawLegend(scene: HeatmapScene, textMeasurer: TextMeasurer) {
    val raster = scene.raster ?: return
    val top = size.height - LEGEND_HEIGHT + 14f
    val barLeft = 24f
    val barRight = size.width - 24f
    val barHeight = 16f

    val steps = 64
    val stepWidth = (barRight - barLeft) / steps
    for (i in 0 until steps) {
        val t = i / (steps - 1.0)
        val value = raster.min + t * (raster.max - raster.min)
        val argb = rampArgb(quality(value, raster.min, raster.max, scene.metric.higherIsBetter))
        drawRect(
            color = Color(argb),
            topLeft = Offset(barLeft + i * stepWidth, top),
            size = Size(stepWidth + 1f, barHeight)
        )
    }
    drawRect(
        color = Color(0xFF263238),
        topLeft = Offset(barLeft, top),
        size = Size(barRight - barLeft, barHeight),
        style = Stroke(1.5f)
    )

    val style = TextStyle(fontSize = 12.sp, color = Color(0xFF263238))
    val low = formatValue(raster.min, scene.metric)
    val high = formatValue(raster.max, scene.metric)
    val caption = scene.title + "  -  " + scene.metric.label + " (" + scene.metric.unit + ")"

    drawText(textMeasurer, low, Offset(barLeft, top + barHeight + 4f), style)
    val highMeasured = textMeasurer.measure(high, style)
    drawText(
        textMeasurer,
        high,
        Offset(barRight - highMeasured.size.width, top + barHeight + 4f),
        style
    )
    val captionMeasured = textMeasurer.measure(caption, style)
    drawText(
        textMeasurer,
        caption,
        Offset((size.width - captionMeasured.size.width) / 2f, top + barHeight + 4f),
        style
    )
}

fun formatValue(value: Double, metric: Metric): String = when (metric) {
    Metric.LATENCY, Metric.JITTER -> String.format("%.1f %s", value, metric.unit)
    Metric.LOSS -> String.format("%.0f %s", value, metric.unit)
    else -> value.roundToInt().toString() + " " + metric.unit
}
