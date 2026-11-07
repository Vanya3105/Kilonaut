package com.example.gymtracker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.roundToInt

/** Training days over the last weeks, and how steady it's been. */
data class Consistency(
    /** Weeks in a row with a workout, counting this week only once it has one (a week isn't lost on Monday). */
    val weekStreak: Int,
    val trainedThisWeek: Boolean,
    val thisMonth: Int,
    val total: Int,
    /** [weeks] columns of 7 days, oldest first, Sunday-or-locale-first; workouts that day, -1 for days to come. */
    val days: List<List<Int>>,
    /** Midnight of the first day in [days]. */
    val firstDayMillis: Long = 0L
)

/** Six months: wide and short at the top of History. */
internal const val CONSISTENCY_WEEKS = 26

/** How many columns a day takes to land once the sweep reaches it. */
private const val SWEEP_TAIL = 4f
private val SettleEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal fun computeConsistency(history: List<CompletedWorkout>, nowMillis: Long = System.currentTimeMillis(), weeks: Int = CONSISTENCY_WEEKS): Consistency {
    fun dayStart(millis: Long) = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    fun weekStartOf(millis: Long) = dayStart(millis).apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.let {
        // set(DAY_OF_WEEK) can land in the next week when the day is before firstDayOfWeek.
        if (it.timeInMillis > millis) it.add(Calendar.WEEK_OF_YEAR, -1)
        it.timeInMillis
    }
    val perDay = HashMap<Long, Int>()
    history.forEach { w -> val d = dayStart(w.id).timeInMillis; perDay[d] = (perDay[d] ?: 0) + 1 }
    val trainedWeeks = history.mapTo(HashSet()) { weekStartOf(it.id) }

    val thisWeek = weekStartOf(nowMillis)
    val trainedThisWeek = thisWeek in trainedWeeks
    var streak = 0
    val cursor = Calendar.getInstance().apply { timeInMillis = thisWeek; if (!trainedThisWeek) add(Calendar.WEEK_OF_YEAR, -1) }
    while (weekStartOf(cursor.timeInMillis) in trainedWeeks) {
        streak++
        cursor.add(Calendar.WEEK_OF_YEAR, -1)
    }

    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val thisMonth = history.count { w ->
        Calendar.getInstance().apply { timeInMillis = w.id }.let { it.get(Calendar.YEAR) == now.get(Calendar.YEAR) && it.get(Calendar.MONTH) == now.get(Calendar.MONTH) }
    }

    val today = dayStart(nowMillis).timeInMillis
    val start = Calendar.getInstance().apply { timeInMillis = thisWeek; add(Calendar.WEEK_OF_YEAR, -(weeks - 1)) }
    val firstDay = start.timeInMillis
    val days = List(weeks) {
        List(7) {
            val d = start.timeInMillis
            start.add(Calendar.DAY_OF_YEAR, 1)
            if (d > today) -1 else perDay[d] ?: 0
        }
    }
    return Consistency(streak, trainedThisWeek, thisMonth, history.size, days, firstDay)
}

/** "4 weeks in a row" or "Training days", with the line under it. */
@Composable
private fun StreakHeadline(c: Consistency) {
    val P = LocalPalette.current
    Text(
        // One week isn't a streak yet.
        if (c.weekStreak >= 2) tr("{0} weeks in a row", c.weekStreak) else tr("Training days"),
        style = AppType.headline,
        color = if (c.weekStreak >= 2) P.accentPrimary else P.textPrimary
    )
    Text(
        when {
            c.total == 0 -> tr("Your first workout starts the streak.")
            c.weekStreak > 0 && !c.trainedThisWeek -> tr("Train this week to keep it going.")
            c.weekStreak == 0 -> tr("One workout this week starts a new streak.")
            c.weekStreak == 1 -> tr("Train next week too and it's a streak.")
            else -> tr("Every week with a workout counts.")
        },
        style = AppType.caption,
        color = P.textSecondary
    )
}

/**
 * Progress's opening card: the streak and the counts. The calendar itself lives in History (one
 * calendar, one place); a tap goes there.
 */
@Composable
fun ConsistencyCard(history: List<CompletedWorkout>, onOpenCalendar: () -> Unit, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val c = remember(history) { computeConsistency(history) }
    // Once per launch (the tab stays composed): the numbers count up.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(700, delayMillis = 120, easing = FastOutSlowInEasing)) }
    AppCard(modifier.fillMaxWidth(), onClick = onOpenCalendar) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { StreakHeadline(c) }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tr("Training calendar"), tint = P.textTertiary)
        }
        Spacer(Modifier.height(14.dp))
        Row {
            val f = reveal.value
            fun counted(n: Int) = "${(n * f).roundToInt()}"
            Stat(tr("This month"), counted(c.thisMonth), Modifier.weight(1f))
            Stat(tr("All time"), counted(c.total), Modifier.weight(1f))
            Stat(tr("Last 6 months"), counted(c.days.sumOf { w -> w.sumOf { it.coerceAtLeast(0) } }), Modifier.weight(1f))
        }
    }
}

/**
 * History's overview: the streak and six months of training days. Tap a day to open it in the
 * month calendar; [selectedDayKey] (the day the month calendar shows) is ringed.
 */
@Composable
fun TrainingDaysCard(history: List<CompletedWorkout>, selectedDayKey: String?, onPickDay: (Long) -> Unit, modifier: Modifier = Modifier) {
    val c = remember(history) { computeConsistency(history) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, delayMillis = 120, easing = FastOutSlowInEasing)) }
    val selected = remember(selectedDayKey, c.firstDayMillis) {
        selectedDayKey?.let { key ->
            val cal = Calendar.getInstance().apply { timeInMillis = c.firstDayMillis }
            (0 until c.days.size * 7).firstOrNull { i ->
                val match = DateUtils.formatDayKey(cal.time) == key
                cal.add(Calendar.DAY_OF_YEAR, 1)
                match
            }
        }
    }
    AppCard(modifier.fillMaxWidth()) {
        StreakHeadline(c)
        Spacer(Modifier.height(12.dp))
        TrainingCalendar(c.days, selected, onTap = { index ->
            val cal = Calendar.getInstance().apply { timeInMillis = c.firstDayMillis; add(Calendar.DAY_OF_YEAR, index) }
            onPickDay(cal.timeInMillis)
        }) { reveal.value }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val P = LocalPalette.current
    Column(modifier) {
        Text(value, style = AppType.number, color = P.textPrimary)
        Text(label, style = AppType.caption, color = P.textSecondary)
    }
}

/**
 * One square per day, a column per week, newest on the end side; days with a workout in the accent,
 * growing in as [reveal] (read while drawing, so the animation only redraws) sweeps across. Today
 * has a ring until it has a workout; [selected] (a day index from the first) a firm one. A tap on a
 * past day gives its index to [onTap].
 */
@Composable
private fun TrainingCalendar(days: List<List<Int>>, selected: Int?, onTap: (Int) -> Unit, reveal: () -> Float) {
    val P = LocalPalette.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val trained = days.sumOf { w -> w.count { it > 0 } }
    val currentTap by rememberUpdatedState(onTap)
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(days.size / 7f)
            .semantics { contentDescription = tr("{0} training days in the last {1} weeks", trained, days.size) }
            .pointerInput(days, rtl) {
                detectTapGestures { pos ->
                    val gap = size.width * 0.012f
                    val cell = (size.width - gap * (days.size - 1)) / days.size
                    val slot = (pos.x / (cell + gap)).toInt().coerceIn(0, days.lastIndex)
                    val col = if (rtl) days.lastIndex - slot else slot
                    val row = (pos.y / (cell + gap)).toInt().coerceIn(0, 6)
                    if (days[col][row] >= 0) currentTap(col * 7 + row)
                }
            }
    ) {
        val gap = size.width * 0.012f
        val cell = (size.width - gap * (days.size - 1)) / days.size
        val radius = CornerRadius(cell * 0.28f)
        val sweep = reveal() * (days.size + SWEEP_TAIL)
        val lastCol = days.indexOfLast { w -> w.any { it >= 0 } }
        val todayRow = days.getOrNull(lastCol)?.indexOfLast { it >= 0 } ?: -1
        days.forEachIndexed { col, week ->
            val x = (if (rtl) days.size - 1 - col else col) * (cell + gap)
            week.forEachIndexed { row, count ->
                if (count < 0) return@forEachIndexed
                val y = row * (cell + gap)
                drawRoundRect(P.bgControl, Offset(x, y), Size(cell, cell), radius)
                if (count > 0) {
                    val t = ((sweep - col) / SWEEP_TAIL).coerceIn(0f, 1f)
                    if (t > 0f) {
                        // Each day grows in and settles.
                        val grow = cell * (0.4f + 0.6f * SettleEase.transform(t))
                        val inset = (cell - grow) / 2
                        drawRoundRect(
                            P.accentPrimary.copy(alpha = (if (count == 1) 0.75f else 1f) * t),
                            Offset(x + inset, y + inset), Size(grow, grow), CornerRadius(grow * 0.28f)
                        )
                    }
                }
                val w = cell * 0.12f
                if (selected == col * 7 + row) {
                    drawRoundRect(P.textPrimary, Offset(x - w / 2, y - w / 2), Size(cell + w, cell + w), CornerRadius(cell * 0.32f), style = Stroke(w))
                } else if (count == 0 && col == lastCol && row == todayRow) {
                    drawRoundRect(
                        P.accentPrimary.copy(alpha = 0.6f * reveal()),
                        Offset(x + w / 2, y + w / 2), Size(cell - w, cell - w), CornerRadius(cell * 0.24f), style = Stroke(w)
                    )
                }
            }
        }
    }
}
