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
import com.gridsense.core.Pt
import com.gridsense.core.bandLabels
import com.gridsense.core.boundsOf
import com.gridsense.core.qualityLevel
import com.gridsense.data.GridPoint
import com.gridsense.data.Raster
import com.gridsense.data.Router
import com.gridsense.data.Sample
import com.gridsense.data.observations
import com.gridsense.data.quality
import com.gridsense.data.rasterize
import com.gridsense.ui.theme.PlanColors
import com.gridsense.ui.theme.qualityArgb
import com.gridsense.ui.theme.qualityColor
import com.gridsense.ui.theme.qualityRampArgb
import com.gridsense.ui.theme.qualityRampColor
import kotlin.math.roundToInt

private const val RASTER_CELL_M = 0.1

/**
 * Turns the interpolated surface into an image, transparent outside the room outline. A fixed
 * scale colours each pixel by its quality band; a relative one stretches the ramp over the room.
 */
fun rasterImage(raster: Raster, metric: Metric, fixedScale: Boolean): ImageBitmap {
    val pixels = IntArray(raster.width * raster.height)
    for (i in pixels.indices) {
        if (!raster.inside[i]) continue
        val v = raster.values[i]
        pixels[i] = if (fixedScale) {
            qualityArgb(qualityLevel(metric, v), alpha = 225)
        } else {
            qualityRampArgb(quality(v, raster.min, raster.max, metric.higherIsBetter), alpha = 225)
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
    val showRouters: Boolean,
    val fixedScale: Boolean
)

/** Heavy: interpolates the surface. Call off the main thread. */
fun buildHeatmapScene(
    title: String,
    polygon: List<Pt>,
    points: List<GridPoint>,
    samples: List<Sample>,
    routers: List<Router>,
    metric: Metric,
    bssidFilter: String?,
    showRouters: Boolean,
    fixedScale: Boolean
): HeatmapScene {
    val obs = observations(points, samples.groupBy { it.pointId }, metric, bssidFilter)
    val raster = rasterize(polygon, obs, RASTER_CELL_M)
    return HeatmapScene(
        title = title,
        polygonM = polygon,
        raster = raster,
        rasterImage = raster?.let { rasterImage(it, metric, fixedScale) },
        points = points,
        routers = routers,
        metric = metric,
        showRouters = showRouters,
        fixedScale = fixedScale
    )
}

private const val LEGEND_HEIGHT = 74f

fun DrawScope.drawHeatmapScene(scene: HeatmapScene, textMeasurer: TextMeasurer, colors: PlanColors) {
    drawRect(colors.background, size = size)

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

    drawOutline(view, scene.polygonM, colors.outline, 3f)

    for (p in scene.points) {
        if (!p.enabled) continue
        drawCircle(colors.pointDot, radius = 4f, center = view.screen(p.x, p.y))
        drawCircle(colors.pointRing, radius = 4f, center = view.screen(p.x, p.y), style = Stroke(1.5f))
    }

    if (scene.showRouters) {
        val labelStyle = TextStyle(fontSize = 11.sp, color = colors.routerLabel)
        for (r in scene.routers) {
            drawRouterMarker(view, r.x, r.y, 11f, colors.router, colors.routerEdge)
            val measured = textMeasurer.measure(r.label, labelStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = r.label,
                topLeft = Offset(view.sx(r.x) - measured.size.width / 2f, view.sy(r.y) + 13f),
                style = labelStyle
            )
        }
    }

    if (scene.fixedScale) drawBandLegend(scene, textMeasurer, colors) else drawRampLegend(scene, textMeasurer, colors)
}

private fun DrawScope.drawRampLegend(scene: HeatmapScene, textMeasurer: TextMeasurer, colors: PlanColors) {
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
        drawRect(
            color = qualityRampColor(quality(value, raster.min, raster.max, scene.metric.higherIsBetter)),
            topLeft = Offset(barLeft + i * stepWidth, top),
            size = Size(stepWidth + 1f, barHeight)
        )
    }
    drawRect(
        color = colors.outline,
        topLeft = Offset(barLeft, top),
        size = Size(barRight - barLeft, barHeight),
        style = Stroke(1.5f)
    )

    val style = TextStyle(fontSize = 12.sp, color = colors.label)
    val low = formatValue(raster.min, scene.metric)
    val high = formatValue(raster.max, scene.metric)
    val caption = scene.title + "  -  " + scene.metric.label + " (" + scene.metric.unit + "), relative"

    drawText(textMeasurer, low, Offset(barLeft, top + barHeight + 4f), style)
    val highMeasured = textMeasurer.measure(high, style)
    drawText(textMeasurer, high, Offset(barRight - highMeasured.size.width, top + barHeight + 4f), style)
    val captionMeasured = textMeasurer.measure(caption, style)
    drawText(
        textMeasurer,
        caption,
        Offset((size.width - captionMeasured.size.width) / 2f, top + barHeight + 4f),
        style
    )
}

/** One labelled swatch per band, worst on the left, so red always reads as poor. */
private fun DrawScope.drawBandLegend(scene: HeatmapScene, textMeasurer: TextMeasurer, colors: PlanColors) {
    val top = size.height - LEGEND_HEIGHT + 8f
    val left = 24f
    val right = size.width - 24f
    val bands = bandLabels(scene.metric).reversed()
    val width = (right - left) / bands.size
    val style = TextStyle(fontSize = 10.sp, color = colors.label)
    bands.forEachIndexed { i, (level, range) ->
        val x = left + i * width
        drawRect(qualityColor(level), topLeft = Offset(x, top), size = Size(width - 2f, 14f))
        drawText(textMeasurer, level.title, Offset(x, top + 16f), style)
        drawText(textMeasurer, range, Offset(x, top + 30f), style)
    }
    val caption = scene.title + "  -  " + scene.metric.label + " (" + scene.metric.unit + "), fixed bands"
    val captionStyle = TextStyle(fontSize = 12.sp, color = colors.label)
    val measured = textMeasurer.measure(caption, captionStyle)
    drawText(
        textMeasurer,
        caption,
        Offset((size.width - measured.size.width) / 2f, top + 46f),
        captionStyle
    )
}

/** Renders the scene off screen, in the light palette, for the PNG export. */
fun renderScene(
    scene: HeatmapScene,
    textMeasurer: TextMeasurer,
    density: Density,
    colors: PlanColors
): ImageBitmap {
    val bounds = boundsOf(scene.polygonM)
    val width = 1400
    val planHeight = (width * (bounds.height / bounds.width.coerceAtLeast(1e-6)))
        .roundToInt()
        .coerceIn(300, 2400)
    val height = planHeight + 90
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        drawHeatmapScene(scene, textMeasurer, colors)
    }
    return image
}

fun formatValue(value: Double, metric: Metric): String = metric.format(value) + " " + metric.unit
