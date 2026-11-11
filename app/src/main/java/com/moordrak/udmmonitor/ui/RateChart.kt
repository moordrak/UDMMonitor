package com.moordrak.udmmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.moordrak.udmmonitor.MonitorViewModel
import com.moordrak.udmmonitor.data.RatePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Linijski graf WAN download/upload s dodirom za očitanje vrijednosti. */
@Composable
fun RateChart(points: List<RatePoint>, modifier: Modifier = Modifier) {
    val down = TrafficColors.down
    val up = TrafficColors.up
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cursorColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxV = (points.maxOfOrNull { maxOf(it.down, it.up) } ?: 0.0).coerceAtLeast(1.0) * 1.1
    var touchX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }

    val n = MonitorViewModel.MAX_POINTS
    val offset = n - points.size
    val selected: RatePoint? = touchX?.let { x ->
        if (points.isEmpty()) null
        else {
            val idx = ((x / widthPx) * (n - 1)).roundToInt() - offset
            points.getOrNull(idx.coerceIn(0, points.lastIndex))
        }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(down); Text(" Download", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(12.dp))
            LegendDot(up); Text(" Upload", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                "skala do ${Fmt.rate(maxV)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = { o ->
                            touchX = o.x
                            tryAwaitRelease()
                            touchX = null
                        })
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { o -> touchX = o.x },
                            onDragEnd = { touchX = null },
                            onDragCancel = { touchX = null },
                        ) { change, _ -> touchX = change.position.x }
                    }
            ) {
                for (i in 0..3) {
                    val y = size.height * i / 3f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                if (points.size >= 2) {
                    fun path(sel: (RatePoint) -> Double): Path {
                        val p = Path()
                        points.forEachIndexed { i, pt ->
                            val x = size.width * (i + offset) / (n - 1).toFloat()
                            val y = size.height * (1f - (sel(pt) / maxV).toFloat())
                            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                        }
                        return p
                    }
                    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(path { it.down }, down, style = stroke)
                    drawPath(path { it.up }, up, style = stroke)
                }
                touchX?.let { x ->
                    drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                }
            }
        }
        val info = selected ?: points.lastOrNull()
        if (info != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(info.time)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("↓ ${Fmt.rate(info.down)}", style = MaterialTheme.typography.labelSmall)
                Text("↑ ${Fmt.rate(info.up)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun LegendDot(color: Color) {
    Box(
        Modifier
            .padding(start = 2.dp)
            .size(10.dp)
            .background(color, CircleShape)
    )
}
