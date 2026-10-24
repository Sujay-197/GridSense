package com.gridsense.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.gridsense.core.Metric
import com.gridsense.core.Obs
import com.gridsense.core.Pt
import com.gridsense.core.boundsOf
import com.gridsense.data.NodeDelta
import com.gridsense.data.Raster
import com.gridsense.data.changeThreshold
import com.gridsense.data.rasterize
import com.gridsense.ui.theme.PlanColors
import com.gridsense.ui.theme.diffArgb
import com.gridsense.ui.theme.diffColor
import kotlin.math.abs
import kotlin.math.roundToInt

/** The difference map: gain (positive is better in B) interpolated over the room. */
class DiffScene(
    val title: String,
    val polygonM: List<Pt>,
    val raster: Raster?,
    val image: ImageBitmap?,
    val deltas: List<NodeDelta>,
    val metric: Metric,
    /** The colour scale runs from -range to +range, centred on zero. */
    val range: Double
)

fun buildDiffScene(title: String, polygon: List<Pt>, deltas: List<NodeDelta>, metric: Metric): DiffScene {
    val range = maxOf(deltas.maxOfOrNull { abs(it.gain) } ?: 0.0, changeThreshold(metric))
    val raster = rasterize(polygon, deltas.map { Obs(it.x, it.y, it.gain) })
    val image = raster?.let { r ->
        val pixels = IntArray(r.width * r.height)
        for (i in pixels.indices) if (r.inside[i]) pixels[i] = diffArgb(r.values[i] / range, alpha = 230)
        Bitmap.createBitmap(pixels, r.width, r.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    return DiffScene(title, polygon, raster, image, deltas, metric, range)
}

private const val LEGEND_HEIGHT = 64f

private fun signed(v: Double, metric: Metric): String {
    val s = metric.format(abs(v))
    return (if (v > 0) "+" else if (v < 0) "−" else "±") + s
}

fun DrawScope.drawDiffScene(scene: DiffScene, textMeasurer: TextMeasurer, colors: PlanColors) {
    drawRect(colors.background, size = size)
    val view = fitPlan(scene.polygonM, Size(size.width, (size.height - LEGEND_HEIGHT).coerceAtLeast(1f)), padding = 24f)
    val raster = scene.raster
    val image = scene.image
    if (raster != null && image != null) {
        drawImage(
            image = image,
            dstOffset = IntOffset(view.sx(raster.originX).roundToInt(), view.sy(raster.originY).roundToInt()),
            dstSize = IntSize(
                (raster.width * raster.cell * view.scale).roundToInt().coerceAtLeast(1),
                (raster.height * raster.cell * view.scale).roundToInt().coerceAtLeast(1)
            )
        )
    }
    drawOutline(view, scene.polygonM, colors.outline, 3f)

    // Every node carries its label and change, so colour is never the only signal.
    val style = TextStyle(fontSize = 10.sp, color = colors.label)
    for (d in scene.deltas) {
        val c = view.screen(d.x, d.y)
        drawCircle(colors.pointDot, radius = 4f, center = c)
        drawCircle(colors.pointRing, radius = 4f, center = c, style = Stroke(1.5f))
        val text = d.label + " " + signed(d.delta, scene.metric)
        val m = textMeasurer.measure(text, style)
        drawText(textMeasurer, text, Offset(c.x - m.size.width / 2f, c.y + 6f), style)
    }

    val top = size.height - LEGEND_HEIGHT + 10f
    val left = 24f
    val right = size.width - 24f
    val steps = 64
    val w = (right - left) / steps
    for (i in 0 until steps) {
        drawRect(diffColor(-1.0 + 2.0 * i / (steps - 1)), Offset(left + i * w, top), Size(w + 1f, 14f))
    }
    drawRect(colors.outline, Offset(left, top), Size(right - left, 14f), style = Stroke(1.5f))
    val legend = TextStyle(fontSize = 11.sp, color = colors.label)
    val unit = " " + scene.metric.unit
    drawText(textMeasurer, "Worse in B " + signed(-scene.range, scene.metric) + unit, Offset(left, top + 17f), legend)
    val better = "Better in B " + signed(scene.range, scene.metric) + unit
    val bm = textMeasurer.measure(better, legend)
    drawText(textMeasurer, better, Offset(right - bm.size.width, top + 17f), legend)
    val caption = scene.title + " · " + scene.metric.label + ", labels show B − A"
    val cm = textMeasurer.measure(caption, legend)
    drawText(textMeasurer, caption, Offset((size.width - cm.size.width) / 2f, top + 33f), legend)
}

fun renderDiffScene(scene: DiffScene, textMeasurer: TextMeasurer, density: Density, colors: PlanColors): ImageBitmap {
    val b = boundsOf(scene.polygonM)
    val width = 1400
    val height = (width * (b.height / b.width.coerceAtLeast(1e-6))).roundToInt().coerceIn(300, 2400) + 80
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        drawDiffScene(scene, textMeasurer, colors)
    }
    return image
}
