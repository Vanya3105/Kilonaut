package com.example.gymtracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** Training days over the last weeks, and how steady it's been. */
data class Consistency(
    /** Weeks in a row with a workout, counting this week only once it has one (a week isn't lost on Monday). */
    val weekStreak: Int,
    val trainedThisWeek: Boolean,
    val thisMonth: Int,
    val total: Int,
    /** [weeks] columns of 7 days, oldest first, Sunday-or-locale-first; workouts that day, -1 for days to come. */
    val days: List<List<Int>>
)

internal const val CONSISTENCY_WEEKS = 18

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
    val days = List(weeks) {
        List(7) {
            val d = start.timeInMillis
            start.add(Calendar.DAY_OF_YEAR, 1)
            if (d > today) -1 else perDay[d] ?: 0
        }
    }
    return Consistency(streak, trainedThisWeek, thisMonth, history.size, days)
}

/** Progress's opening card: the streak, this month, all time, and a calendar of training days. */
@Composable
fun ConsistencyCard(history: List<CompletedWorkout>, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val c = remember(history) { computeConsistency(history) }
    AppCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
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
        }
        Spacer(Modifier.height(14.dp))
        TrainingCalendar(c.days)
        Spacer(Modifier.height(14.dp))
        Row {
            Stat(tr("This month"), "${c.thisMonth}", Modifier.weight(1f))
            Stat(tr("All time"), "${c.total}", Modifier.weight(1f))
            Stat(tr("Last {0} weeks", CONSISTENCY_WEEKS), "${c.days.sumOf { w -> w.sumOf { it.coerceAtLeast(0) } }}", Modifier.weight(1f))
        }
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

/** One square per day, a column per week, newest on the end side; days with a workout in the accent. */
@Composable
private fun TrainingCalendar(days: List<List<Int>>) {
    val P = LocalPalette.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val trained = days.sumOf { w -> w.count { it > 0 } }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(days.size / 7f)
            .semantics { contentDescription = tr("{0} training days in the last {1} weeks", trained, days.size) }
    ) {
        val gap = size.width * 0.012f
        val cell = (size.width - gap * (days.size - 1)) / days.size
        val radius = CornerRadius(cell * 0.28f)
        days.forEachIndexed { col, week ->
            val x = (if (rtl) days.size - 1 - col else col) * (cell + gap)
            week.forEachIndexed { row, count ->
                if (count < 0) return@forEachIndexed
                val color = when {
                    count == 0 -> P.bgControl
                    count == 1 -> P.accentPrimary.copy(alpha = 0.75f)
                    else -> P.accentPrimary
                }
                drawRoundRect(color, Offset(x, row * (cell + gap)), Size(cell, cell), radius)
            }
        }
    }
}
