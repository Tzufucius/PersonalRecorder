package io.github.tuzfucius.personalrecorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import io.github.tuzfucius.personalrecorder.statistics.AppCount
import io.github.tuzfucius.personalrecorder.statistics.DailyCount
import io.github.tuzfucius.personalrecorder.statistics.HourlyCount
import kotlin.math.atan2
import kotlin.math.hypot

@Composable
fun HourlyChart(
    values: List<HourlyCount>,
    selectedHour: Int?,
    onHourClick: (Int) -> Unit,
) {
    if (values.isEmpty()) {
        Text("暂无小时数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        producer.runTransaction { columnSeries { series(values.map { it.count }) } }
    }
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(),
        startAxis = VerticalAxis.rememberStart(),
        bottomAxis = HorizontalAxis.rememberBottom(),
    )
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .drawBehind {
                selectedHour?.let { hour ->
                    val index = values.indexOfFirst { it.hour == hour }
                    if (index >= 0) drawRect(
                        color = selectionColor,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            plotLeftPx(size.width.toFloat()) + plotWidthPx(size.width.toFloat(), values.size) * index.toFloat() / values.size,
                            0f,
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            plotWidthPx(size.width.toFloat(), values.size) / values.size,
                            size.height.toFloat(),
                        ),
                    )
                }
            }
            .pointerInput(values) {
                detectTapGestures { offset ->
                    mapPlotXToIndex(offset.x, size.width.toFloat(), values.size)?.let { onHourClick(values[it].hour) }
                }
            }
            .semantics {
                contentDescription = values.joinToString("、") { "${it.hour.toString().padStart(2, '0')}:00 ${it.count} 条" }
                role = Role.Button
            },
    ) {
        CartesianChartHost(chart = chart, modelProducer = producer, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun DailyTrendChart(
    values: List<DailyCount>,
    selectedDate: java.time.LocalDate? = null,
    onDateClick: (java.time.LocalDate) -> Unit = {},
) {
    if (values.isEmpty()) {
        Text("暂无每日数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        producer.runTransaction { lineSeries { series(values.map { it.count }) } }
    }
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(),
        startAxis = VerticalAxis.rememberStart(),
        bottomAxis = HorizontalAxis.rememberBottom(),
    )
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .drawBehind {
                selectedDate?.let { date ->
                    val index = values.indexOfFirst { it.date == date }
                    if (index >= 0) drawRect(
                        color = selectionColor,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            plotLeftPx(size.width.toFloat()) + plotWidthPx(size.width.toFloat(), values.size) * index.toFloat() / values.size,
                            0f,
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            plotWidthPx(size.width.toFloat(), values.size) / values.size,
                            size.height.toFloat(),
                        ),
                    )
                }
            }
            .pointerInput(values) {
                detectTapGestures { offset ->
                    mapPlotXToIndex(offset.x, size.width.toFloat(), values.size)?.let { onDateClick(values[it].date) }
                }
            }
            .semantics {
                contentDescription = values.joinToString("、") { "${it.date} ${it.count} 条" }
                role = Role.Button
            },
    ) {
        CartesianChartHost(chart = chart, modelProducer = producer, modifier = Modifier.fillMaxSize())
    }
}

private fun plotLeftPx(width: Float): Float = 48f.coerceAtMost(width / 3f)

private fun plotWidthPx(width: Float, itemCount: Int): Float =
    (width - plotLeftPx(width) - 8f).coerceAtLeast(itemCount.toFloat())

/** Maps a touch to a chart item using the axis plot bounds, not a full-width overlay. */
internal fun mapPlotXToIndex(x: Float, width: Float, itemCount: Int): Int? {
    if (itemCount <= 0) return null
    val left = plotLeftPx(width)
    val plotWidth = plotWidthPx(width, itemCount)
    if (x < left || x >= left + plotWidth) return null
    return ((x - left) / plotWidth * itemCount).toInt().coerceIn(0, itemCount - 1)
}

/** A Canvas donut with angle and radius hit-testing, rather than a coarse bounding box. */
@Composable
fun AppDonutChart(
    values: List<AppCount>,
    labelFor: (String) -> String,
    selectedPackage: String? = null,
    otherExpanded: Boolean = false,
    onAppClick: (String) -> Unit = {},
    onOtherClick: () -> Unit = {},
) {
    if (values.isEmpty()) {
        Text("暂无应用来源数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val top = values.take(6)
    val other = values.drop(6).sumOf { it.count }
    val display = if (otherExpanded) {
        values
    } else {
        buildList {
            addAll(top)
            if (other > 0) add(AppCount(OTHER_KEY, other))
        }
    }
    val colors = listOf(
        Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFEF6C00),
        Color(0xFF6A1B9A), Color(0xFF00838F), Color(0xFFC62828),
        MaterialTheme.colorScheme.outline,
    )
    val total = display.sumOf { it.count }.coerceAtLeast(1).toFloat()
    val ringWidthPx = with(LocalDensity.current) { 28.dp.toPx() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(156.dp)
                .semantics { contentDescription = "应用来源环图，共 ${display.sumOf { it.count }} 条" }
                .pointerInput(display) {
                    detectTapGestures { offset ->
                        donutHitTest(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(), display.map { it.count }, ringWidthPx)
                            ?.let { index ->
                                val item = display[index]
                                if (item.packageName == OTHER_KEY) onOtherClick() else onAppClick(item.packageName)
                            }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                var start = -90f
                display.forEachIndexed { index, item ->
                    val sweep = item.count / total * 360f
                    val selected = item.packageName == selectedPackage
                    drawArc(
                        color = colors[index % colors.size].copy(alpha = if (selected) 1f else 0.82f),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = (if (selected) 36 else 28).dp.toPx()),
                    )
                    start += sweep
                }
            }
            Text("${display.sumOf { it.count }}", style = MaterialTheme.typography.titleMedium)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            display.forEachIndexed { index, item ->
                val label = if (item.packageName == OTHER_KEY) "其他" else labelFor(item.packageName)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .semantics {
                            contentDescription = "$label ${item.count} 条"
                            role = Role.Button
                        }
                        .clickable {
                            if (item.packageName == OTHER_KEY) onOtherClick() else onAppClick(item.packageName)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.size(10.dp).background(colors[index % colors.size], CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "$label ${item.count}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private const val OTHER_KEY = "__other__"

internal fun donutHitTest(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    counts: List<Int>,
    ringWidth: Float = 28f,
): Int? {
    if (counts.isEmpty()) return null
    val centerX = width / 2f
    val centerY = height / 2f
    val distance = hypot(x - centerX, y - centerY)
    val outer = minOf(width, height) / 2f
    if (distance !in (outer - ringWidth)..outer) return null
    val degrees = Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble())).toFloat()
    val angle = (degrees + 90f + 360f) % 360f
    val total = counts.sum().coerceAtLeast(1).toFloat()
    var cursor = 0f
    counts.forEachIndexed { index, count ->
        val sweep = count / total * 360f
        if (angle >= cursor && angle < cursor + sweep) return index
        cursor += sweep
    }
    return null
}
