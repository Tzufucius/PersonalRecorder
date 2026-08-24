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
import androidx.compose.ui.text.style.TextOverflow
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.stacked
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.common.Defaults
import io.github.tuzfucius.personalrecorder.statistics.AppCount
import io.github.tuzfucius.personalrecorder.statistics.DailyCount
import io.github.tuzfucius.personalrecorder.statistics.HourlyBreakdown
import io.github.tuzfucius.personalrecorder.statistics.HourlyCount
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

private val appPalette = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFEF6C00), Color(0xFF6A1B9A),
    Color(0xFF00838F), Color(0xFFC62828), Color(0xFF5D4037), Color(0xFFAD1457),
    Color(0xFF0277BD), Color(0xFF558B2F), Color(0xFFD84315), Color(0xFF4527A0),
)

internal fun appColorIndex(packageName: String): Int =
    Math.floorMod(packageName.hashCode(), appPalette.size)

/** Assigns palette slots deterministically while resolving collisions for the current result. */
internal fun packageColorIndices(packageNames: Collection<String>): Map<String, Int> {
    val used = BooleanArray(appPalette.size)
    return packageNames
        .distinct()
        .sorted()
        .associateWith { packageName ->
            val base = appColorIndex(packageName)
            val slot = (0 until appPalette.size)
                .map { offset -> (base + offset) % appPalette.size }
                .firstOrNull { !used[it] }
                ?: base
            used[slot] = true
            slot
        }
}

private fun appColor(packageName: String, colorIndices: Map<String, Int>): Color =
    appPalette[colorIndices[packageName] ?: appColorIndex(packageName)]

@Composable
fun HourlyChart(
    values: List<HourlyCount>,
    breakdowns: List<HourlyBreakdown>,
    apps: List<AppCount>,
    selectedHour: Int?,
    onHourClick: (Int) -> Unit,
    labelFor: (String) -> String,
) {
    if (values.isEmpty()) {
        Text("暂无小时数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val packageNames = remember(apps) { apps.map { it.packageName } }
    val colorIndices = remember(packageNames) { packageColorIndices(packageNames) }
    val chartSeries = remember(values, breakdowns, packageNames) {
        val seriesValues = packageNames.map { packageName ->
            values.map { value ->
                breakdowns.firstOrNull { it.hour == value.hour }
                    ?.appCounts
                    ?.firstOrNull { it.packageName == packageName }
                    ?.count
                    ?: 0
            }
        }
        seriesValues.ifEmpty { listOf(List(values.size) { 0 }) }
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values, breakdowns, packageNames) {
        producer.runTransaction {
            columnSeries {
                chartSeries.forEach { series(values.map { it.hour }, it) }
            }
        }
    }
    val lineComponents = (if (packageNames.isEmpty()) listOf(Color.Transparent) else packageNames.map { appColor(it, colorIndices) })
        .map { color -> rememberLineComponent(fill(color), Defaults.COLUMN_WIDTH.dp) }
    val columnLayer = rememberColumnCartesianLayer(
        columnProvider = ColumnCartesianLayer.ColumnProvider.series(lineComponents),
        mergeMode = { ColumnCartesianLayer.MergeMode.stacked() },
    )
    val itemPlacer = remember {
        HorizontalAxis.ItemPlacer.aligned(spacing = { 2 })
    }
    val chart = rememberCartesianChart(
        columnLayer,
        startAxis = VerticalAxis.rememberStart(),
        bottomAxis = HorizontalAxis.rememberBottom(
            itemPlacer = itemPlacer,
            valueFormatter = CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() },
        ),
    )
    val scrollState = rememberVicoScrollState(scrollEnabled = false)
    val zoomState = rememberVicoZoomState(
        zoomEnabled = false,
        initialZoom = Zoom.Content,
        minZoom = Zoom.Content,
        maxZoom = Zoom.Content,
    )
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val axisInsetPx = with(LocalDensity.current) { 48.dp.toPx() }
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
                            plotLeftPx(size.width.toFloat(), axisInsetPx) + plotWidthPx(size.width.toFloat(), values.size, axisInsetPx) * index.toFloat() / values.size,
                            0f,
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            plotWidthPx(size.width.toFloat(), values.size, axisInsetPx) / values.size,
                            size.height.toFloat(),
                        ),
                    )
                }
            }
            .pointerInput(values, axisInsetPx) {
                detectTapGestures { offset ->
                    mapPlotXToIndex(offset.x, size.width.toFloat(), values.size, axisInsetPx)?.let { onHourClick(values[it].hour) }
                }
            }
            .semantics {
                contentDescription = values.joinToString("、") { "${it.hour.toString().padStart(2, '0')}:00 ${it.count} 条" }
                role = Role.Button
            },
    ) {
        CartesianChartHost(
            chart = chart,
            modelProducer = producer,
            modifier = Modifier.fillMaxSize(),
            scrollState = scrollState,
            zoomState = zoomState,
        )
    }
    if (packageNames.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            packageNames.chunked(2).forEach { rowPackages ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowPackages.forEach { packageName ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(appColor(packageName, colorIndices), CircleShape),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = labelFor(packageName),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
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
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val axisInsetPx = with(LocalDensity.current) { 48.dp.toPx() }
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
                            plotLeftPx(size.width.toFloat(), axisInsetPx) + plotWidthPx(size.width.toFloat(), values.size, axisInsetPx) * index.toFloat() / values.size,
                            0f,
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            plotWidthPx(size.width.toFloat(), values.size, axisInsetPx) / values.size,
                            size.height.toFloat(),
                        ),
                    )
                }
            }
            .pointerInput(values, axisInsetPx) {
                detectTapGestures { offset ->
                    mapPlotXToIndex(offset.x, size.width.toFloat(), values.size, axisInsetPx)?.let { onDateClick(values[it].date) }
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

private fun plotLeftPx(width: Float, insetPx: Float = 48f): Float = insetPx.coerceAtMost(width / 3f)

private fun plotWidthPx(width: Float, itemCount: Int, insetPx: Float = 48f): Float =
    (width - plotLeftPx(width, insetPx) - 8f).coerceAtLeast(itemCount.toFloat())

/** Maps a touch to a chart item using the axis plot bounds, not a full-width overlay. */
internal fun mapPlotXToIndex(x: Float, width: Float, itemCount: Int, insetPx: Float = 48f): Int? {
    if (itemCount <= 0) return null
    val left = plotLeftPx(width, insetPx)
    val plotWidth = plotWidthPx(width, itemCount, insetPx)
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
    val colorIndices = remember(display.map { it.packageName }) {
        packageColorIndices(display.mapNotNull { it.packageName.takeUnless { name -> name == OTHER_KEY } })
    }
    val outlineColor = MaterialTheme.colorScheme.outline
    val colorFor = { packageName: String ->
        if (packageName == OTHER_KEY) outlineColor else appColor(packageName, colorIndices)
    }
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
                        color = colorFor(item.packageName).copy(alpha = if (selected) 1f else 0.82f),
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
                    Spacer(modifier = Modifier.size(10.dp).background(colorFor(item.packageName), CircleShape))
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
