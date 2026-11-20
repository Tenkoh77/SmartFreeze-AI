package com.buse.smartfreeze.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buse.smartfreeze.ui.theme.LocalStatusColors

/**
 * Lightweight time-series line chart drawn with Canvas (no third-party chart library).
 * Points are (x = time, y = value). Drawing happens on the UI thread but only processes the
 * small, already-capped list held in state; data collection happens off the main thread.
 */
@Composable
fun LineChart(
    points: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    yMin: Double? = null,
    yMax: Double? = null,
    threshold: Double? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    description: String,
) {
    val grid = LocalStatusColors.current.chartGrid
    val thresholdColor = LocalStatusColors.current.critical
    val clean = remember(points) { points.filter { it.second.isFinite() } }
    Box(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (clean.size < 2) {
            Text("Collecting data…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val x0 = clean.first().first.toDouble()
            val x1 = clean.last().first.toDouble().let { if (it == x0) x0 + 1 else it }
            val lo = yMin ?: (clean.minOf { it.second } - 0.02)
            val hiRaw = yMax ?: (clean.maxOf { it.second } + 0.02)
            val hi = if (hiRaw <= lo) lo + 1e-6 else hiRaw
            fun px(t: Long) = ((t - x0) / (x1 - x0) * size.width).toFloat()
            fun py(v: Double) = (size.height - (v.coerceIn(lo, hi) - lo) / (hi - lo) * size.height).toFloat()

            for (i in 0..3) {
                val y = size.height * i / 3f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            threshold?.let {
                val y = py(it)
                drawLine(thresholdColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            }
            val path = Path()
            clean.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(px(t), py(v)) else path.lineTo(px(t), py(v)) }
            drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            val (lt, lv) = clean.last()
            drawCircle(color, radius = 5f, center = Offset(px(lt), py(lv)))
        }
    }
}

@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val clean = values.filter { it.isFinite() }
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        if (clean.size < 2) return@Canvas
        val lo = clean.min()
        val hi = clean.max().let { if (it - lo < 1e-9) lo + 1e-9 else it }
        val path = Path()
        clean.forEachIndexed { i, v ->
            val x = size.width * i / (clean.size - 1)
            val y = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}
