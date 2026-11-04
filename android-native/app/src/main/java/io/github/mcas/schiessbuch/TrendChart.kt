package io.github.mcas.schiessbuch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One line per calendar year (session averages, converted to 10 shots on A10) on a shared
 * January–December axis, with a grey bar and dots for the single series of sessions that have more than one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendChart(sessions: List<Session>, tx: Tx) {
    val primary = MaterialTheme.colorScheme.primary
    val palette = listOf(primary, Color(0xFFD64545), Color(0xFF3B78D8), Color(0xFFE08A1E), Color(0xFF8A56C2), Color(0xFF1E9C9C))
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = Color.Gray.copy(alpha = 0.55f)

    val years = sessions.map { it.year }.distinct().sortedDescending().take(6)
    val shown = sessions.filter { it.year in years }
    fun axis(d: LocalDate) = (LocalDate.of(2000, d.monthValue, d.dayOfMonth).dayOfYear - 1) / 365f
    data class P(val x: Float, val v: Double)
    val lines = years.associateWith { y ->
        shown.filter { it.year == y }.mapNotNull { s -> s.normalizedAverage?.let { P(axis(s.day), it) } }.sortedBy { it.x }
    }
    val bars = shown.mapNotNull { s ->
        val v = s.series.mapNotNull { it.normalized }
        if (v.size > 1) axis(s.day) to v else null
    }
    val all = lines.values.flatten().map { it.v } + bars.flatMap { it.second }
    val lo = maxOf(0.0, floor((all.minOrNull() ?: 0.0) - 3))
    val hi = maxOf(lo + 1, minOf(100.0, ceil((all.maxOrNull() ?: 100.0) + 2)))
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = label)
    val months = (1..12).map { java.time.Month.of(it).getDisplayName(DateTextStyle.NARROW, tx.locale) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tx.trendYears.uppercase(tx.locale), style = MaterialTheme.typography.labelSmall, color = label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            years.forEachIndexed { i, y ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(palette[i], CircleShape))
                    Text(y, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val left = 30.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx(); val right = 6.dp.toPx()
            val w = size.width - left - right; val h = size.height - top - bottom
            fun px(x: Float) = left + x * w
            fun py(v: Double) = top + ((hi - v) / (hi - lo)).toFloat() * h

            // horizontal grid with values
            val step = listOf(1.0, 2.0, 5.0, 10.0, 20.0).first { (hi - lo) / it <= 6 }
            var g = ceil(lo / step) * step
            while (g <= hi) {
                val y = py(g)
                drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
                val t = measurer.measure(tx.score(g), labelStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y - t.size.height / 2))
                g += step
            }
            // months
            for (m in 0 until 12) {
                val x0 = px((LocalDate.of(2000, m + 1, 1).dayOfYear - 1) / 365f)
                drawLine(grid, Offset(x0, top), Offset(x0, top + h), 1f)
                val t = measurer.measure(months[m], labelStyle)
                drawText(t, topLeft = Offset(x0 + w / 24 - t.size.width / 2, top + h + 3.dp.toPx()))
            }
            // single series of sessions with more than one
            bars.forEach { (x, v) ->
                drawLine(barColor, Offset(px(x), py(v.min())), Offset(px(x), py(v.max())), 3.dp.toPx(), StrokeCap.Round)
                v.forEach { drawCircle(Color.Gray, 2.5.dp.toPx(), Offset(px(x), py(it))) }
            }
            // one line per year, newest drawn last and thicker
            years.indices.reversed().forEach { i ->
                val pts = lines[years[i]] ?: return@forEach
                val c = palette[i]
                val newest = i == 0
                if (pts.size > 1) {
                    val path = Path().apply { pts.forEachIndexed { j, p -> if (j == 0) moveTo(px(p.x), py(p.v)) else lineTo(px(p.x), py(p.v)) } }
                    drawPath(path, c, style = Stroke(width = (if (newest) 3 else 2).dp.toPx(), cap = StrokeCap.Round))
                }
                pts.forEach { drawCircle(c, (if (newest) 4 else 3).dp.toPx(), Offset(px(it.x), py(it.v))) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            years.forEach { y ->
                val v = shown.filter { it.year == y }.mapNotNull { it.normalizedAverage }
                if (v.isNotEmpty()) Text("$y · ${tx.yearAvg(tx.score(v.average()), v.size)}", style = MaterialTheme.typography.labelSmall, color = label)
            }
            if (bars.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(3.dp).height(14.dp).background(barColor, RoundedCornerShape(2.dp)))
                Text(tx.keySeries, style = MaterialTheme.typography.labelSmall, color = label)
            }
        }
    }
}
