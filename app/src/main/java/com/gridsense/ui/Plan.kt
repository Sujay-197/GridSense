package com.gridsense.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gridsense.core.Pt
import com.gridsense.core.boundsOf

/**
 * Maps room metres to canvas pixels with a uniform scale, keeping the plan centred. World y
 * grows away from the origin corner in the direction you first faced, and is drawn upwards.
 */
class PlanView(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val minX: Double,
    val maxY: Double
) {
    fun sx(x: Double): Float = offsetX + ((x - minX) * scale).toFloat()
    fun sy(y: Double): Float = offsetY + ((maxY - y) * scale).toFloat()
    fun screen(x: Double, y: Double): Offset = Offset(sx(x), sy(y))
    fun screen(p: Pt): Offset = screen(p.x, p.y)
    fun wx(px: Float): Double = minX + (px - offsetX) / scale
    fun wy(py: Float): Double = maxY - (py - offsetY) / scale
    fun world(offset: Offset): Pt = Pt(wx(offset.x), wy(offset.y))
}

/**
 * Fits every position in [world] into [size]. During a walk the outline does not exist yet, so
 * the caller passes whatever is on screen: the breadcrumb trail, the corners and the points.
 */
fun fitPlan(world: List<Pt>, size: Size, padding: Float): PlanView {
    if (world.isEmpty()) {
        return PlanView(50f, size.width / 2f, size.height / 2f, 0.0, 0.0)
    }
    val b = boundsOf(world)
    val width = b.width.coerceAtLeast(2.0)
    val height = b.height.coerceAtLeast(2.0)
    val usableW = (size.width - 2 * padding).coerceAtLeast(1f)
    val usableH = (size.height - 2 * padding).coerceAtLeast(1f)
    val scale = minOf(usableW / width.toFloat(), usableH / height.toFloat())
    val drawnW = width.toFloat() * scale
    val drawnH = height.toFloat() * scale
    val centreX = (b.minX + b.maxX) / 2.0
    val centreY = (b.minY + b.maxY) / 2.0
    return PlanView(
        scale = scale,
        offsetX = padding + (usableW - drawnW) / 2f,
        offsetY = padding + (usableH - drawnH) / 2f,
        minX = centreX - width / 2.0,
        maxY = centreY + height / 2.0
    )
}

fun DrawScope.drawOutline(view: PlanView, polygonM: List<Pt>, color: Color, strokeWidth: Float) {
    if (polygonM.size < 2) return
    val path = Path()
    path.moveTo(view.sx(polygonM[0].x), view.sy(polygonM[0].y))
    for (i in 1 until polygonM.size) path.lineTo(view.sx(polygonM[i].x), view.sy(polygonM[i].y))
    path.close()
    drawPath(path, color, style = Stroke(width = strokeWidth))
}

/** Faint lines between the grid cells, so each node can be matched to a spot on the floor. */
fun DrawScope.drawGridCells(view: PlanView, rows: Int, cols: Int, cellM: Double, line: Color) {
    for (c in 1 until cols) {
        val x = c * cellM
        drawLine(line, view.screen(x, 0.0), view.screen(x, rows * cellM), strokeWidth = 1.5f)
    }
    for (r in 1 until rows) {
        val y = r * cellM
        drawLine(line, view.screen(0.0, y), view.screen(cols * cellM, y), strokeWidth = 1.5f)
    }
}

/** Router markers: a diamond with a contrasting edge. */
fun DrawScope.drawRouterMarker(
    view: PlanView,
    x: Double,
    y: Double,
    radius: Float,
    color: Color,
    edge: Color
) {
    val c = view.screen(x, y)
    val path = Path()
    path.moveTo(c.x, c.y - radius)
    path.lineTo(c.x + radius, c.y)
    path.lineTo(c.x, c.y + radius)
    path.lineTo(c.x - radius, c.y)
    path.close()
    drawPath(path, color)
    drawPath(path, edge, style = Stroke(width = radius * 0.28f))
}
