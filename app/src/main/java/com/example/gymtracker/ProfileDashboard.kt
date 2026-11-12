package com.example.gymtracker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date

/*
 * Profile's dashboard, after Hevy's: one chart of your training, switched between workouts, time
 * and volume, by week or by month. The number above is the picked bar's; tap a bar to pick it.
 */

/** One week or month of training; [startMillis] is its first day at midnight. */
@Immutable
internal data class ActivityBucket(val startMillis: Long, val workouts: Int, val seconds: Long, val volumeKg: Int)

internal enum class ActivityMetric { WORKOUTS, DURATION, VOLUME }

internal const val ACTIVITY_BARS = 12

/**
 * The last [count] weeks (Sunday-first, as History's) or calendar months, oldest first, the
 * current one last. Volume counts work sets of weighted exercises, as History's totals do.
 */
internal fun activityBuckets(
    history: List<CompletedWorkout>,
    byMonth: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
    count: Int = ACTIVITY_BARS
): List<ActivityBucket> {
    val field = if (byMonth) Calendar.MONTH else Calendar.WEEK_OF_YEAR
    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        firstDayOfWeek = Calendar.SUNDAY
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (byMonth) set(Calendar.DAY_OF_MONTH, 1) else {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            if (timeInMillis > nowMillis) add(Calendar.WEEK_OF_YEAR, -1)
        }
        add(field, -(count - 1))
    }
    // Calendar steps, not fixed lengths: months differ and clock changes move weeks by an hour.
    val starts = LongArray(count + 1) { i -> if (i > 0) cal.add(field, 1); cal.timeInMillis }
    val workouts = IntArray(count)
    val seconds = LongArray(count)
    val volume = IntArray(count)
    for (w in history) {
        if (w.id < starts[0] || w.id >= starts[count]) continue
        val i = (0 until count).first { w.id < starts[it + 1] }
        workouts[i]++
        seconds[i] += w.durationSeconds
        volume[i] += computeWorkoutStats(w.exercises, emptyList()).volumeKg
    }
    return List(count) { ActivityBucket(starts[it], workouts[it], seconds[it], volume[it]) }
}

private fun ActivityBucket.value(m: ActivityMetric): Float = when (m) {
    ActivityMetric.WORKOUTS -> workouts.toFloat()
    ActivityMetric.DURATION -> seconds.toFloat()
    ActivityMetric.VOLUME -> volumeKg.toFloat()
}

private fun ActivityBucket.shownValue(m: ActivityMetric): String = when (m) {
    ActivityMetric.WORKOUTS -> if (workouts == 1) tr("1 workout") else tr("{0} workouts", workouts)
    ActivityMetric.DURATION -> if (seconds > 0) formatDuration(seconds) else "—"
    ActivityMetric.VOLUME -> if (volumeKg > 0) formatVolume(volumeKg) else "—"
}

/** "This week", "Last month", "Oct 12 – Oct 18", "September 2026". */
private fun bucketName(buckets: List<ActivityBucket>, i: Int, byMonth: Boolean): String {
    val b = buckets[i]
    return when {
        i == buckets.lastIndex -> if (byMonth) tr("This month") else tr("This week")
        i == buckets.lastIndex - 1 -> if (byMonth) tr("Last month") else tr("Last week")
        byMonth -> DateUtils.formatMonthTitle(Date(b.startMillis))
        else -> {
            val end = Calendar.getInstance().apply { timeInMillis = b.startMillis; add(Calendar.DAY_OF_YEAR, 6) }
            "${DateUtils.formatShortDay(Date(b.startMillis))} – ${DateUtils.formatShortDay(end.time)}"
        }
    }
}

/** Shown once there's a workout to chart; the card would be twelve empty stubs before that. */
@Composable
fun ProfileDashboard(history: List<CompletedWorkout>, modifier: Modifier = Modifier) {
    if (history.isEmpty()) return
    val P = LocalPalette.current
    var byMonth by rememberSaveable { mutableStateOf(false) }
    var metricName by rememberSaveable { mutableStateOf(ActivityMetric.DURATION.name) }
    val buckets = remember(history, byMonth) { activityBuckets(history, byMonth) }
    // Volume only when something weighted was logged: a bodyweight trainee would see an empty chart.
    val liftsWeight = remember(history) { history.any { it.totalVolumeKg > 0 } }
    val metric = ActivityMetric.valueOf(metricName).let { if (it == ActivityMetric.VOLUME && !liftsWeight) ActivityMetric.DURATION else it }
    var selected by remember(buckets) { mutableIntStateOf(buckets.lastIndex) }
    val rtl = isRtl()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700, delayMillis = 100, easing = FastOutSlowInEasing)) }

    AppCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(buckets[selected].shownValue(metric), style = AppType.bigNumber, color = P.textPrimary, maxLines = 1)
                Text(bucketName(buckets, selected, byMonth), style = AppType.caption, color = P.textSecondary)
            }
            PeriodToggle(byMonth) { byMonth = it }
        }
        Spacer(Modifier.height(16.dp))
        val max = buckets.maxOf { it.value(metric) }.coerceAtLeast(1f)
        // On dark, see-through orange turns brown: the other bars go a quiet grey and only the picked one is orange.
        val muted = if (P.isDark) P.textTertiary.copy(alpha = 0.45f) else P.accentPrimary.copy(alpha = 0.22f)
        val empty = P.bgControl
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(buckets, rtl) {
                    detectTapGestures { pos ->
                        val slot = (pos.x / (size.width / buckets.size.toFloat())).toInt().coerceIn(0, buckets.lastIndex)
                        selected = if (rtl) buckets.lastIndex - slot else slot
                    }
                }
        ) {
            val slot = size.width / buckets.size
            val barW = slot * 0.62f
            val radius = CornerRadius(barW / 3f)
            val floor = 3.dp.toPx()
            buckets.forEachIndexed { i, b ->
                val v = b.value(metric)
                val x = (if (rtl) buckets.lastIndex - i else i) * slot + (slot - barW) / 2f
                // A stub for a quiet week, so the gap reads as time off, not a missing bar.
                val h = if (v > 0f) (size.height * v / max * grow.value).coerceAtLeast(floor) else floor
                drawRoundRect(
                    color = when {
                        v == 0f -> empty
                        i == selected -> P.accentPrimary
                        else -> muted
                    },
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = radius
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            val first = Date(buckets.first().startMillis)
            Text(
                if (byMonth) DateUtils.formatMonthTitle(first) else DateUtils.formatShortDay(first),
                style = AppType.caption, color = P.textTertiary, modifier = Modifier.weight(1f)
            )
            Text(if (byMonth) tr("This month") else tr("This week"), style = AppType.caption, color = P.textTertiary)
        }
        Spacer(Modifier.height(14.dp))
        ChoiceChips(
            options = buildList {
                add(ActivityMetric.DURATION to tr("Duration"))
                if (liftsWeight) add(ActivityMetric.VOLUME to tr("Volume"))
                add(ActivityMetric.WORKOUTS to tr("Workouts"))
            },
            selected = metric,
            onSelect = { metricName = it.name }
        )
    }
}

/** Weeks | Months, small enough to sit beside the number. */
@Composable
private fun PeriodToggle(byMonth: Boolean, onChange: (Boolean) -> Unit) {
    val P = LocalPalette.current
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(P.bgControl).padding(3.dp)) {
        listOf(false to tr("Weeks"), true to tr("Months")).forEach { (months, label) ->
            val on = months == byMonth
            Text(
                label,
                style = AppType.label,
                color = if (on) P.textPrimary else P.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) P.bgCard else androidx.compose.ui.graphics.Color.Transparent)
                    .pressable(pressedScale = 0.96f) { onChange(months) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}
