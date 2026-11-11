package com.example.gymtracker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date

/** One week's lifting: [startMillis] is its Sunday at midnight, as History's weeks start. */
@Immutable
internal data class WeekVolume(val startMillis: Long, val volumeKg: Int, val workouts: Int)

internal const val VOLUME_WEEKS = 12

/**
 * The last [weeks] weeks (oldest first, this week last) with the weight moved in each: work sets
 * of weighted exercises, counted as History's week totals count them, so the two agree.
 */
internal fun weeklyVolumes(history: List<CompletedWorkout>, nowMillis: Long = System.currentTimeMillis(), weeks: Int = VOLUME_WEEKS): List<WeekVolume> {
    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        firstDayOfWeek = Calendar.SUNDAY
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        if (timeInMillis > nowMillis) add(Calendar.WEEK_OF_YEAR, -1)
        add(Calendar.WEEK_OF_YEAR, -(weeks - 1))
    }
    // Calendar steps, not 7 × 24 h: a week with a clock change is an hour shorter or longer.
    val starts = LongArray(weeks + 1) { i -> if (i > 0) cal.add(Calendar.WEEK_OF_YEAR, 1); cal.timeInMillis }
    val volume = IntArray(weeks)
    val count = IntArray(weeks)
    for (w in history) {
        if (w.id < starts[0] || w.id >= starts[weeks]) continue
        val i = (0 until weeks).first { w.id < starts[it + 1] }
        volume[i] += computeWorkoutStats(w.exercises, emptyList()).volumeKg
        count[i]++
    }
    return List(weeks) { WeekVolume(starts[it], volume[it], count[it]) }
}

/**
 * The average of the finished weeks that had any lifting, for the dashed line: weeks off would
 * drag it down to a number no real week looks like. Null with fewer than two such weeks.
 */
internal fun typicalWeekVolume(weeks: List<WeekVolume>): Int? {
    val done = weeks.dropLast(1).filter { it.volumeKg > 0 }
    return if (done.size < 2) null else done.sumOf { it.volumeKg } / done.size
}

/**
 * Progress's weekly volume: twelve bars, this week's in the accent. Tap a bar for that week's
 * number. Hidden until something weighted has been logged in those weeks.
 */
@Composable
fun WeeklyVolumeCard(history: List<CompletedWorkout>, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val weeks = remember(history) { weeklyVolumes(history) }
    if (weeks.none { it.volumeKg > 0 }) return
    val typical = remember(weeks) { typicalWeekVolume(weeks) }
    var selected by remember(weeks) { mutableIntStateOf(weeks.lastIndex) }
    val week = weeks[selected]
    val rtl = isRtl()
    // Once, when the card first shows: the bars rise.
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700, delayMillis = 100, easing = FastOutSlowInEasing)) }

    AppCard(modifier.fillMaxWidth()) {
        Text(tr("Weekly volume"), style = AppType.headline, color = P.textPrimary)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(if (week.volumeKg > 0) formatVolume(week.volumeKg) else "—", style = AppType.bigNumber, color = P.textPrimary)
                Text(weekCaption(weeks, selected), style = AppType.caption, color = P.textSecondary)
            }
            if (typical != null) Column(horizontalAlignment = Alignment.End) {
                Text(formatVolume(typical), style = AppType.number, color = P.textPrimary)
                Text(tr("Usual week"), style = AppType.caption, color = P.textTertiary)
            }
        }
        Spacer(Modifier.height(16.dp))
        val max = (weeks.maxOf { it.volumeKg }.coerceAtLeast(typical ?: 0)).coerceAtLeast(1).toFloat()
        val muted = P.accentPrimary.copy(alpha = if (P.isDark) 0.32f else 0.22f)
        val empty = P.bgControl
        val line = P.textTertiary
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(weeks, rtl) {
                    detectTapGestures { pos ->
                        val slot = (pos.x / (size.width / weeks.size.toFloat())).toInt().coerceIn(0, weeks.lastIndex)
                        selected = if (rtl) weeks.lastIndex - slot else slot
                    }
                }
        ) {
            val slot = size.width / weeks.size
            val barW = slot * 0.62f
            val radius = CornerRadius(barW / 3f)
            val floor = 3.dp.toPx()
            weeks.forEachIndexed { i, w ->
                val x = (if (rtl) weeks.lastIndex - i else i) * slot + (slot - barW) / 2f
                // A week off still gets a stub, so the gap reads as a week, not a missing bar.
                val h = if (w.volumeKg > 0) (size.height * w.volumeKg / max * grow.value).coerceAtLeast(floor) else floor
                drawRoundRect(
                    color = when {
                        w.volumeKg == 0 -> empty
                        i == selected -> P.accentPrimary
                        else -> muted
                    },
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = radius
                )
            }
            if (typical != null) {
                val y = size.height - size.height * typical / max * grow.value
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(DateUtils.formatShortDay(Date(weeks.first().startMillis)), style = AppType.caption, color = P.textTertiary, modifier = Modifier.weight(1f))
            Text(tr("This week"), style = AppType.caption, color = P.textTertiary)
        }
    }
}

/** "This week · 2 workouts", "Oct 12 – Oct 18 · 3 workouts", "Last week · Rest week". */
private fun weekCaption(weeks: List<WeekVolume>, i: Int): String {
    val w = weeks[i]
    val name = when (i) {
        weeks.lastIndex -> tr("This week")
        weeks.lastIndex - 1 -> tr("Last week")
        else -> {
            val end = Calendar.getInstance().apply { timeInMillis = w.startMillis; add(Calendar.DAY_OF_YEAR, 6) }
            "${DateUtils.formatShortDay(Date(w.startMillis))} – ${DateUtils.formatShortDay(end.time)}"
        }
    }
    val what = when (w.workouts) {
        0 -> tr("Rest week")
        1 -> tr("1 workout")
        else -> tr("{0} workouts", w.workouts)
    }
    return "$name · $what"
}
