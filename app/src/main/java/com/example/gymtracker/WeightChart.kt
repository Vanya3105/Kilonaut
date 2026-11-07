package com.example.gymtracker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How far back the body-weight chart looks. */
enum class WeightRange(val days: Int?) {
    MONTH(30), QUARTER(91), HALF(182), YEAR(365), ALL(null);

    val label: String
        get() = when (this) {
            MONTH -> tr("1M")
            QUARTER -> tr("3M")
            HALF -> tr("6M")
            YEAR -> tr("1Y")
            ALL -> tr("All")
        }

    fun startMillis(nowMillis: Long): Long? = days?.let { nowMillis - it * DAY_MS }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Weigh-ins in [range], oldest first. */
internal fun weighInsIn(range: WeightRange, history: List<WeightLogEntry>, nowMillis: Long): List<WeightLogEntry> {
    val start = range.startMillis(nowMillis)
    return history.filter { start == null || it.id >= start }.sortedBy { it.id }
}

/**
 * The ranges worth offering: each needs two weigh-ins and something the next shorter one doesn't
 * show; a month of data doesn't need five buttons that all draw the same line.
 */
internal fun usefulWeightRanges(history: List<WeightLogEntry>, nowMillis: Long): List<WeightRange> {
    var lastCount = -1
    return WeightRange.entries.filter { r ->
        val n = weighInsIn(r, history, nowMillis).size
        (n >= 2 && n != lastCount).also { if (n >= 2) lastCount = n }
    }
}

/** From this many weigh-ins the line is the trend and each weigh-in a dot. */
internal const val TREND_FROM = 8

/**
 * The trend under the day-to-day swings (water, salt, a late meal move the scale by a kilo):
 * an average that leans on recent weigh-ins, by time, so a gap counts as the days it was.
 */
internal fun weightTrend(entries: List<WeightLogEntry>, tauDays: Float = 10f): List<Float> {
    if (entries.isEmpty()) return emptyList()
    var trend = entries.first().weightKg
    var prev = entries.first().id
    return entries.map { e ->
        val days = (e.id - prev) / DAY_MS.toFloat()
        trend += (1f - kotlin.math.exp(-days / tauDays)) * (e.weightKg - trend)
        prev = e.id
        trend
    }
}

/** Gridline values: about four, on a round step, covering [lo]..[hi] (display units). */
internal fun niceAxis(lo: Float, hi: Float): Triple<Float, Float, Float> {
    val span = max(hi - lo, 1f)
    val raw = span / 3f
    val steps = listOf(0.1f, 0.2f, 0.25f, 0.5f, 1f, 2f, 2.5f, 5f, 10f, 20f, 25f, 50f, 100f)
    val step = steps.firstOrNull { it >= raw } ?: 100f
    val bottom = floor(lo / step) * step
    var top = ceil(hi / step) * step
    if (top - bottom < step * 2) top = bottom + step * 2
    return Triple(bottom, top, step)
}

/**
 * Monotone cubic through [pts] (Fritsch–Carlson): smooth, but never bulging above a high or below
 * a low that wasn't weighed, and straight between two points.
 */
private fun monotonePath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    val n = pts.size
    val d = FloatArray(n - 1) { i -> (pts[i + 1].y - pts[i].y) / max(pts[i + 1].x - pts[i].x, 0.001f) }
    val m = FloatArray(n) { i ->
        when (i) {
            0 -> d[0]
            n - 1 -> d[n - 2]
            else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
    }
    for (i in 0 until n - 1) {
        if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
        val a = m[i] / d[i]
        val b = m[i + 1] / d[i]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / kotlin.math.sqrt(s)
            m[i] = t * a * d[i]
            m[i + 1] = t * b * d[i]
        }
    }
    for (i in 0 until n - 1) {
        val p0 = pts[i]
        val p1 = pts[i + 1]
        val h = (p1.x - p0.x) / 3f
        path.cubicTo(p0.x + h, p0.y + m[i] * h, p1.x - h, p1.y - m[i + 1] * h, p1.x, p1.y)
    }
    return path
}

/**
 * Body weight over time: dates spaced as they really are, round gridlines with values on the end
 * side, the line drawing itself in along its length. Tap or drag to pick a weigh-in ([onSelect]
 * with its index, null when let go), so the card's big number can follow the finger.
 */
@Composable
fun WeightChart(
    entries: List<WeightLogEntry>,
    startMillis: Long,
    endMillis: Long,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val P = LocalPalette.current
    val unit = Units.current
    val rtl = isRtl()
    val measurer = rememberTextMeasurer()
    val axisStyle = AppType.caption.copy(color = P.textTertiary, fontFeatureSettings = "tnum")

    val values = entries.map { unit.fromKg(it.weightKg) }
    val smoothed = entries.size >= TREND_FROM
    val lineValues = if (smoothed) remember(entries) { weightTrend(entries) }.map { unit.fromKg(it) } else values
    val (axisLo, axisHi, step) = niceAxis(values.min(), values.max())
    // The scale glides to a new range instead of jumping; the line draws in again on top of it.
    val lo by animateFloatAsState(axisLo, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow), label = "axis_lo")
    val hi by animateFloatAsState(axisHi, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow), label = "axis_hi")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(entries.firstOrNull()?.id, entries.size, startMillis) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val selectedPoint by animateFloatAsState((selected ?: entries.lastIndex).toFloat(), spring(stiffness = Spring.StiffnessMedium), label = "pick")

    val currentSelect by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val span = max(endMillis - startMillis, 1L)
    /** The weigh-in nearest in time to a touch at [x], on a canvas [width] wide. */
    fun nearest(x: Float, width: Float, labelW: Float): Int {
        val left = if (rtl) labelW else 0f
        val t = ((x - left) / (width - labelW)).let { if (rtl) 1f - it else it }
        val at = startMillis + (t * span).toLong()
        return entries.indices.minBy { abs(entries[it].id - at) }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics {
                contentDescription = tr("Body weight from {0} to {1}", formatWeightKg(entries.first().weightKg), formatWeightKg(entries.last().weightKg))
            }
            .pointerInput(entries, startMillis, rtl) {
                detectTapGestures { pos ->
                    val i = nearest(pos.x, size.width.toFloat(), AXIS_LABEL_WIDTH.toPx())
                    haptics.tick()
                    currentSelect(if (current == i) null else i)
                }
            }
            .pointerInput(entries, startMillis, rtl) {
                var last = -1
                detectHorizontalDragGestures(
                    onDragStart = { last = nearest(it.x, size.width.toFloat(), AXIS_LABEL_WIDTH.toPx()); haptics.tick(); currentSelect(last) },
                    onDragEnd = { currentSelect(null) },
                    onDragCancel = { currentSelect(null) }
                ) { change, _ ->
                    val i = nearest(change.position.x, size.width.toFloat(), AXIS_LABEL_WIDTH.toPx())
                    if (i != last) { last = i; haptics.tick(); currentSelect(i) }
                }
            }
    ) {
        val labelW = AXIS_LABEL_WIDTH.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val plotLeft = if (rtl) labelW else 0f
        val plotRight = if (rtl) size.width else size.width - labelW
        val plotW = plotRight - plotLeft
        val range = max(hi - lo, 0.001f)
        fun xOf(millis: Long): Float {
            val t = ((millis - startMillis).toFloat() / span).coerceIn(0f, 1f)
            return if (rtl) plotRight - t * plotW else plotLeft + t * plotW
        }
        fun yOf(v: Float) = bottom - (v - lo) / range * (bottom - top)

        // Gridlines and their values.
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        var g = axisLo
        while (g <= axisHi + step / 2) {
            val y = yOf(g)
            if (y in top - 1f..bottom + 1f) {
                drawLine(P.borderSubtle, Offset(plotLeft, y), Offset(plotRight, y), 1.dp.toPx(), pathEffect = if (g == axisLo) null else dash)
                val text = measurer.measure(formatKgClean(unit.round(g)), axisStyle)
                val tx = if (rtl) plotLeft - text.size.width - 6.dp.toPx() else plotRight + 6.dp.toPx()
                drawText(text, topLeft = Offset(tx, y - text.size.height / 2f))
            }
            g += step
        }

        // Dates under the plot: start, middle, end.
        listOf(0f, 0.5f, 1f).forEach { f ->
            val millis = startMillis + (span * f).toLong()
            val text = measurer.measure(DateUtils.formatShortDay(Date(millis)), axisStyle)
            val x = xOf(millis)
            val tx = (x - text.size.width * if (rtl) 1f - f else f).coerceIn(0f, size.width - text.size.width)
            drawText(text, topLeft = Offset(tx, bottom + 6.dp.toPx()))
        }

        val pts = entries.mapIndexed { i, e -> Offset(xOf(e.id), yOf(values[i])) }
        val linePts = if (smoothed) entries.mapIndexed { i, e -> Offset(xOf(e.id), yOf(lineValues[i])) } else pts
        val line = monotonePath(linePts)
        val measure = PathMeasure().apply { setPath(line, false) }
        val drawn = Path().also { measure.getSegment(0f, measure.length * reveal.value, it, true) }
        if (pts.size >= 2) {
            val fill = Path().apply {
                addPath(line)
                lineTo(linePts.last().x, bottom)
                lineTo(linePts.first().x, bottom)
                close()
            }
            // The fill follows the line's front edge as it draws.
            val frontX = pts.first().x + (pts.last().x - pts.first().x) * reveal.value
            clipRect(left = min(pts.first().x, frontX), right = max(pts.first().x, frontX) + 1f) {
                drawPath(fill, Brush.verticalGradient(listOf(P.accentPrimary.copy(alpha = 0.22f), P.accentPrimary.copy(alpha = 0f)), startY = top, endY = bottom))
            }
            drawPath(drawn, P.accentPrimary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }

        // Each weigh-in as a dot, appearing as the line reaches it: around the trend, soft ones;
        // on the line itself, ringed, while they're few enough to tell apart.
        if (smoothed || pts.size <= 30) {
            pts.forEachIndexed { i, p ->
                val at = if (pts.size == 1) 1f else ((reveal.value * (pts.size - 1)) - i + 1f).coerceIn(0f, 1f)
                if (at <= 0f) return@forEachIndexed
                if (smoothed) {
                    drawCircle(P.accentPrimary.copy(alpha = 0.35f), 2.5.dp.toPx() * at, p)
                } else {
                    drawCircle(P.bgCard, 4.dp.toPx() * at, p)
                    drawCircle(P.accentPrimary, 2.5.dp.toPx() * at, p)
                }
            }
        }

        // The picked weigh-in (the latest when none): a guide and a ringed dot, gliding between points.
        if (reveal.value > 0.95f) {
            val f = selectedPoint.coerceIn(0f, pts.lastIndex.toFloat())
            val a = pts[floor(f).toInt()]
            val b = pts[ceil(f).toInt()]
            val t = f - floor(f)
            val p = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            if (selected != null) {
                drawLine(P.borderStrong, Offset(p.x, top), Offset(p.x, bottom), 1.dp.toPx(), pathEffect = dash)
            }
            drawSelectedDot(p, P.accentPrimary, P.bgCard)
        }
    }
}

private val AXIS_LABEL_WIDTH = 34.dp

private fun DrawScope.drawSelectedDot(p: Offset, accent: Color, card: Color) {
    drawCircle(accent.copy(alpha = 0.18f), 11.dp.toPx(), p)
    drawCircle(card, 6.dp.toPx(), p)
    drawCircle(accent, 4.dp.toPx(), p)
}
