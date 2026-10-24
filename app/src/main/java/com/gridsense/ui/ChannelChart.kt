package com.gridsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gridsense.core.channelCenterMhz
import com.gridsense.data.AirNetwork

private const val TOP_DBM = -30.0
private const val BOTTOM_DBM = -100.0
private const val MAX_LABELS = 8

/**
 * Channel occupancy for one band: each network is a shape spanning its real width, as tall as
 * its median RSSI. 2.4 GHz always shows channels 1 to 13; 5 GHz shows the channels seen.
 */
@Composable
fun ChannelChart(networks: List<AirNetwork>, band5: Boolean, textMeasurer: TextMeasurer) {
    val scheme = MaterialTheme.colorScheme
    val axisStyle = TextStyle(fontSize = 10.sp, color = scheme.onSurfaceVariant)
    val labelStyle = TextStyle(fontSize = 10.sp, color = scheme.onSurface)
    val ownStyle = TextStyle(fontSize = 10.sp, color = scheme.primary)

    val ticks = if (band5) networks.mapNotNull { it.channel }.distinct().sorted() else (1..13).toList()
    val lowMhz = if (band5) (networks.minOfOrNull { it.lowMhz } ?: 5170.0) - 10 else 2400.0
    val highMhz = if (band5) (networks.maxOfOrNull { it.highMhz } ?: 5330.0) + 10 else 2484.0
    val labelled = (networks.filter { it.own } + networks.filter { !it.own }.take(MAX_LABELS)).toSet()
    val own = networks.filter { it.own }.joinToString { it.label }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics {
                contentDescription = (if (band5) "5 GHz" else "2.4 GHz") + " channel chart, " +
                    networks.size + " networks" + if (own.isNotEmpty()) ", yours: $own" else ""
            }
    ) {
        val left = 36f
        val right = size.width - 8f
        val top = 16f
        val bottom = size.height - 22f
        fun x(mhz: Double) = left + ((mhz - lowMhz) / (highMhz - lowMhz)).toFloat() * (right - left)
        fun y(dbm: Double) = top + ((TOP_DBM - dbm.coerceIn(BOTTOM_DBM, TOP_DBM)) / (TOP_DBM - BOTTOM_DBM)).toFloat() * (bottom - top)

        for (dbm in listOf(-90.0, -70.0, -50.0)) {
            drawLine(scheme.outlineVariant, Offset(left, y(dbm)), Offset(right, y(dbm)), strokeWidth = 1f)
            drawText(textMeasurer, dbm.toInt().toString(), Offset(0f, y(dbm) - 7f), axisStyle)
        }
        drawLine(scheme.outline, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.5f)
        for (ch in ticks) {
            val cx = x(channelCenterMhz(ch, band5).toDouble())
            drawLine(scheme.outline, Offset(cx, bottom), Offset(cx, bottom + 4f), strokeWidth = 1.5f)
            val t = textMeasurer.measure(ch.toString(), axisStyle)
            drawText(textMeasurer, ch.toString(), Offset(cx - t.size.width / 2f, bottom + 6f), axisStyle)
        }

        // Others first, so the user's own networks are drawn on top.
        for (n in networks.sortedBy { it.own }) {
            val x0 = x(n.lowMhz)
            val x1 = x(n.highMhz)
            val peak = y(n.rssiDbm)
            val shoulder = (x1 - x0) * 0.15f
            val path = Path().apply {
                moveTo(x0, bottom)
                cubicTo(x0 + shoulder * 0.5f, bottom, x0 + shoulder * 0.5f, peak, x0 + shoulder, peak)
                lineTo(x1 - shoulder, peak)
                cubicTo(x1 - shoulder * 0.5f, peak, x1 - shoulder * 0.5f, bottom, x1, bottom)
                close()
            }
            val colour = if (n.own) scheme.primary else scheme.onSurfaceVariant
            drawPath(path, colour.copy(alpha = if (n.own) 0.28f else 0.10f))
            drawPath(path, colour, style = Stroke(width = if (n.own) 3f else 1.5f))
            if (n in labelled) {
                val text = n.label.take(14)
                val style = if (n.own) ownStyle else labelStyle
                val m = textMeasurer.measure(text, style)
                val lx = ((x0 + x1) / 2f - m.size.width / 2f).coerceIn(left, (right - m.size.width).coerceAtLeast(left))
                drawText(textMeasurer, text, Offset(lx, (peak - m.size.height - 1f).coerceAtLeast(0f)), style)
            }
        }
    }
}
