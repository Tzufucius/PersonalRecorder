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
import androidx.compose.ui.input.pointer.pointerInput
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
    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        CartesianChartHost(chart = chart, modelProducer = producer, modifier = Modifier.fillMaxSize())
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            values.forEach { value ->
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (selectedHour == value.hour) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            } else Color.Transparent,
                        )
                        .semantics {
                            contentDescription = "${value.hour.toString().padStart(2, '0')}:00 ${value.count} 条"
                            role = Role.Button
                        }
                        .clickable { onHourClick(value.hour) },
                )
            }
        }
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
    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        CartesianChartHost(chart = chart, modelProducer = producer, modifier = Modifier.fillMaxSize())
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            values.forEach { value ->
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (selectedDate == value.date) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            } else Color.Transparent,
                        )
                        .semantics {
                            contentDescription = "${value.date} ${value.count} 条"
                            role = Role.Button
                        }
                        .clickable { onDateClick(value.date) },
                )
            }
        }
    }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .size(156.dp)
                .semantics { contentDescription = "应用来源环图" }
                .pointerInput(display) {
                    detectTapGestures { offset ->
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val distance = hypot(offset.x - center.x, offset.y - center.y)
                        val outer = minOf(size.width, size.height) / 2f
                        val inner = outer - 28.dp.toPx()
                        if (distance !in inner..outer) return@detectTapGestures
                        val degrees = Math.toDegrees(
                            atan2((offset.y - center.y).toDouble(), (offset.x - center.x).toDouble())
                        ).toFloat()
                        val angle = (degrees + 90f + 360f) % 360f
                        var cursor = 0f
                        display.forEach { item ->
                            val sweep = item.count / total * 360f
                            if (angle >= cursor && angle < cursor + sweep) {
                                if (item.packageName == OTHER_KEY) onOtherClick() else onAppClick(item.packageName)
                                return@detectTapGestures
                            }
                            cursor += sweep
                        }
                    }
                },
        ) {
            var start = -90f
            display.forEachIndexed { index, item ->
                val sweep = item.count / total * 360f
                val selected = item.packageName == selectedPackage
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = (if (selected) 36 else 28).dp.toPx()),
                )
                start += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            display.forEachIndexed { index, item ->
                val label = if (item.packageName == OTHER_KEY) "其他" else labelFor(item.packageName)
                Row(
                    modifier = Modifier
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
