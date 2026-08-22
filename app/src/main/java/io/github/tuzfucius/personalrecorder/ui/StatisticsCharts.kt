package io.github.tuzfucius.personalrecorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import io.github.tuzfucius.personalrecorder.statistics.AppCount
import io.github.tuzfucius.personalrecorder.statistics.DailyCount
import io.github.tuzfucius.personalrecorder.statistics.HourlyCount

@Composable
fun HourlyChart(
    values: List<HourlyCount>,
    selectedHour: Int?,
    onHourClick: (Int) -> Unit
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        producer.runTransaction {
            columnSeries { series(values.map { it.count }) }
        }
    }
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(),
        startAxis = VerticalAxis.rememberStart(),
        bottomAxis = HorizontalAxis.rememberBottom()
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
                            } else {
                                Color.Transparent
                            }
                        )
                        .clickable { onHourClick(value.hour) }
                )
            }
        }
    }
}

@Composable
fun DailyTrendChart(values: List<DailyCount>) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        producer.runTransaction {
            lineSeries { series(values.map { it.count }) }
        }
    }
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(),
        startAxis = VerticalAxis.rememberStart(),
        bottomAxis = HorizontalAxis.rememberBottom()
    )
    CartesianChartHost(
        chart = chart,
        modelProducer = producer,
        modifier = Modifier.fillMaxWidth().height(220.dp)
    )
}

@Composable
fun AppDonutChart(values: List<AppCount>, labelFor: (String) -> String) {
    if (values.isEmpty()) {
        Text("暂无应用来源数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val top = values.take(6)
    val other = values.drop(6).sumOf { it.count }
    val display = buildList {
        addAll(top)
        if (other > 0) add(AppCount("__other__", other))
    }
    val colors = listOf(
        Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFEF6C00),
        Color(0xFF6A1B9A), Color(0xFF00838F), Color(0xFFC62828),
        MaterialTheme.colorScheme.outline
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(156.dp)) {
            val total = display.sumOf { it.count }.coerceAtLeast(1).toFloat()
            var start = -90f
            display.forEachIndexed { index, item ->
                val sweep = item.count / total * 360f
                drawArc(colors[index % colors.size], start, sweep, false, style = Stroke(width = 28.dp.toPx()))
                start += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            display.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        modifier = Modifier.size(10.dp).background(colors[index % colors.size], CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${if (item.packageName == "__other__") "其他" else labelFor(item.packageName)} ${item.count}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
