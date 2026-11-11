package com.example.gymtracker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** What a logged workout's page shows, worked out once from the workouts before it. */
internal class WorkoutSummary(
    val stats: WorkoutStats,
    val reps: Int,
    /** Each exercise done, with its new best set (the top one that beat the best before this workout). */
    val exercises: List<Pair<ExerciseEntry, Set<Int>>>,
    /** Exercises with a new best, as History's cards and the finish screen count them. */
    val bests: Int,
    /** [RECOVERY_GROUPS] loads: the most any exercise put on each muscle. */
    val loads: FloatArray,
    /** The same day done before this one, for "Since last time". */
    val previous: CompletedWorkout?,
    val previousStats: WorkoutStats?
)

internal fun summarizeWorkout(workout: CompletedWorkout, history: List<CompletedWorkout>): WorkoutSummary {
    val before = history.filter { it.id < workout.id }
    val done = workout.exercises.filter { !it.isSkipped && it.sets.any { s -> s.hasNumbers(it.type) } }
    val exercises = done.map { ex ->
        val records = computeExerciseRecords(ex.name, before)
        // Three sets past the old best are one new best: the trophy goes on the top one, not on all three.
        val beating = ex.sets.filter { !it.isWarmupSet && isSetPersonalRecord(it, records, ex.type) }
        val top = bestSetOf(ex.type, beating)
        ex to (if (top == null) emptySet() else setOf(ex.sets.indexOfFirst { it === top }))
    }
    val loads = FloatArray(RECOVERY_GROUPS.size)
    for (ex in done) {
        if (ex.workSets.isEmpty()) continue
        val l = muscleLoads(workout.splitTitle, ex.name, ex.targetMuscle)
        for (i in loads.indices) if (l[i] > loads[i]) loads[i] = l[i]
    }
    val previous = before.filter { it.splitTitle == workout.splitTitle }.maxByOrNull { it.id }
    return WorkoutSummary(
        stats = computeWorkoutStats(workout.exercises, emptyList()),
        reps = totalWorkReps(workout.exercises),
        exercises = exercises,
        bests = exercises.count { it.second.isNotEmpty() },
        loads = loads,
        previous = previous,
        previousStats = previous?.let { computeWorkoutStats(it.exercises, emptyList()) }
    )
}

/**
 * A logged workout, to look at: its numbers, what it worked, how it compares with the last time
 * that day was done, and every set. Editing is one tap away (the pencil) instead of being the
 * page itself, so looking back can't change anything by accident.
 */
@Composable
fun WorkoutSummaryScreen(
    workout: CompletedWorkout,
    history: List<CompletedWorkout>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRepeat: () -> Unit,
    onDelete: () -> Unit,
    onOpenExercise: (String) -> Unit
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val unit = Units.current
    val s = remember(workout, history, unit) { summarizeWorkout(workout, history) }
    val note = remember(workout) { LocalStorage.getWorkoutNote(context, workout.id) }
    val heart by produceState<HeartRateSummary?>(null, workout.id, HealthSync.granted) { value = HealthSync.heartRate(context, workout) }
    val photo = rememberPhoto(workout.photoPath)
    var menuOpen by remember { mutableStateOf(false) }
    val backModifier = rememberPredictiveBack(onBack = onBack)

    Box(backModifier.fillMaxSize().background(P.bgPrimary)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.statusBarsPadding().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), onBack)
                    Spacer(Modifier.weight(1f))
                    CircleIconButton(Icons.Rounded.Edit, tr("Edit"), onEdit)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        CircleIconButton(Icons.Rounded.MoreVert, tr("More"), { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = P.bgElevated) {
                            DropdownMenuItem(
                                text = { Text(tr("Share"), style = AppType.body) },
                                leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                                onClick = {
                                    menuOpen = false
                                    shareWorkoutCardImage(
                                        workout.splitTitle, workout.durationSeconds, workout.exercises,
                                        history.filter { it.id < workout.id }, workout.photoPath, whenMillis = workout.id
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Delete"), style = AppType.body, color = P.dangerText) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = P.dangerText) },
                                onClick = { menuOpen = false; onDelete() }
                            )
                        }
                    }
                }
            }

            item {
                Column {
                    Text(shown(workout.splitTitle), style = AppType.display, color = P.textPrimary)
                    Text(DateUtils.dateTimeOf(workout.displayDate, workout.id), style = AppType.body, color = P.textSecondary)
                    val hr = heart
                    if (s.bests > 0 || hr != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.bests > 0) Pill(if (s.bests == 1) tr("1 PR") else tr("{0} PRs", s.bests), icon = Icons.Rounded.EmojiEvents, color = P.warningText, container = P.warningSurface)
                            if (hr != null) Pill(tr("{0} bpm avg · {1} max", hr.average, hr.max), icon = Icons.Rounded.Favorite, color = P.dangerText, container = P.dangerSurface)
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(formatDuration(workout.durationSeconds), tr("Time"), Modifier.weight(1f))
                    StatTile("${s.stats.workSets}", tr("Sets"), Modifier.weight(1f))
                    // Bodyweight days lift no weight: "0 kg" says nothing, the reps do.
                    if (s.stats.volumeKg > 0) StatTile(formatVolume(s.stats.volumeKg), tr("Volume"), Modifier.weight(1f))
                    else StatTile("${s.reps}", tr("Reps"), Modifier.weight(1f))
                }
            }

            val prev = s.previous
            val prevStats = s.previousStats
            if (prev != null && prevStats != null) item { SinceLastTime(workout, s, prev, prevStats) }

            if (s.loads.any { it > 0f }) item { MusclesWorkedCard(s.loads) }

            if (note.isNotBlank()) item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(tr("Note"), style = AppType.overline, color = P.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(note, style = AppType.body, color = P.textPrimary)
                }
            }

            val bitmap = photo.bitmap
            if (bitmap != null) item {
                Image(
                    bitmap = bitmap,
                    contentDescription = tr("Workout photo"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp))
                )
            }

            item { SectionHeader(tr("Exercises")) }
            items(s.exercises) { (ex, bestSets) ->
                ExerciseSetsCard(ex, bestSets, onOpen = { onOpenExercise(ex.name) })
            }
            if (workout.skippedExercises.isNotEmpty()) item {
                Text(tr("Skipped: {0}", workout.skippedExercises.joinToString(", ") { shown(it) }), style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp))
            }

            item {
                SecondaryButton(tr("Do it again"), onRepeat, Modifier.fillMaxWidth().padding(top = 4.dp), icon = Icons.Rounded.Replay)
            }
        }
        StatusBarScrim()
    }
}

/** This workout against the last time the same day was done: the change in each number. */
@Composable
private fun SinceLastTime(workout: CompletedWorkout, s: WorkoutSummary, prev: CompletedWorkout, prevStats: WorkoutStats) {
    val P = LocalPalette.current
    AppCard(Modifier.fillMaxWidth()) {
        Text(tr("Since last time"), style = AppType.headline, color = P.textPrimary)
        Text(tr("{0} on {1}", shown(prev.splitTitle), DateUtils.dateTimeOf(prev.displayDate, prev.id)), style = AppType.caption, color = P.textSecondary)
        Spacer(Modifier.height(8.dp))
        if (s.stats.volumeKg > 0 || prevStats.volumeKg > 0) {
            val d = s.stats.volumeKg - prevStats.volumeKg
            ChangeRow(tr("Volume"), formatVolume(s.stats.volumeKg), if (d == 0) null else (if (d > 0) "+" else "−") + formatVolume(abs(d)), d > 0)
        }
        val sets = s.stats.workSets - prevStats.workSets
        ChangeRow(tr("Sets"), "${s.stats.workSets}", if (sets == 0) null else (if (sets > 0) "+$sets" else "−${-sets}"), sets > 0)
        // Quicker isn't better or worse, so the time's change stays grey.
        val secs = workout.durationSeconds - prev.durationSeconds
        ChangeRow(tr("Time"), formatDuration(workout.durationSeconds), if (abs(secs) < 60) null else (if (secs > 0) "+" else "−") + formatDuration(abs(secs)), null)
    }
}

/** A label, this workout's number, and its change: green when it went up, grey otherwise. */
@Composable
private fun ChangeRow(label: String, value: String, change: String?, up: Boolean?) {
    val P = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppType.body, color = P.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = AppType.number, color = P.textPrimary)
        Text(
            // A left-to-right mark keeps the sign with its number in Hebrew ("+115", not "115+").
            change?.let { "‎$it" } ?: tr("Same"),
            style = AppType.label,
            color = if (up == true && change != null) P.successText else P.textTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.widthIn(min = 84.dp)
        )
    }
}

@Composable
private fun MusclesWorkedCard(loads: FloatArray) {
    val P = LocalPalette.current
    val main = RECOVERY_GROUPS.filterIndexed { i, _ -> loads[i] >= 1f }
    val helping = RECOVERY_GROUPS.filterIndexed { i, _ -> loads[i] > 0f && loads[i] < 1f }
    AppCard(Modifier.fillMaxWidth()) {
        Text(tr("Muscles worked"), style = AppType.headline, color = P.textPrimary)
        if (main.isNotEmpty()) Text(main.joinToString(", ") { shown(it) }, style = AppType.caption, color = P.textSecondary)
        Spacer(Modifier.height(12.dp))
        MuscleMap(loads, Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(P.accentPrimary, tr("Main"))
            if (helping.isNotEmpty()) Legend(P.accentPrimary.copy(alpha = 0.5f), tr("Helping"))
        }
    }
}

/** One exercise and every set, numbered as in the workout ("W" for warm-ups), trophies on new bests. */
@Composable
private fun ExerciseSetsCard(ex: ExerciseEntry, bestSets: Set<Int>, onOpen: () -> Unit) {
    val P = LocalPalette.current
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().pressable(pressedScale = 0.99f, onClick = onOpen).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExerciseThumb(ex.name, Modifier.padding(end = 12.dp), size = 40.dp, showEmpty = false)
            Column(Modifier.weight(1f)) {
                Text(shown(ex.name), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val muscle = ExerciseDatabase.find(ex.name)?.muscle ?: ex.targetMuscle
                if (muscle.isNotBlank()) Text(shown(muscle), style = AppType.caption, color = P.textSecondary)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            var n = 0
            ex.sets.forEachIndexed { i, set ->
                if (!set.hasNumbers(ex.type)) return@forEachIndexed
                val warmup = set.isWarmupSet
                if (!warmup) n++
                val best = i in bestSets
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (best) P.warningSurface else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (warmup) "W" else "$n",
                        style = AppType.label.copy(fontFeatureSettings = "tnum"),
                        color = if (warmup) P.textTertiary else P.textSecondary,
                        modifier = Modifier.width(28.dp)
                    )
                    Text(
                        formatSet(set, ex.type),
                        style = AppType.body.copy(fontFeatureSettings = "tnum"),
                        color = if (warmup) P.textSecondary else P.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (best) {
                        Icon(Icons.Rounded.EmojiEvents, null, tint = P.warningText, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("New best"), style = AppType.caption, color = P.warningText)
                    }
                }
            }
        }
    }
}
