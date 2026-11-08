package com.example.gymtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The kind of movement, read from the name: a busy leg press is best swapped for another squat
 * pattern, not a leg extension. Muscle groups alone ("Chest") would offer a fly for a bench press.
 */
internal fun movementOf(name: String): String {
    val n = name.lowercase()
    fun has(vararg words: String) = words.any { it in n }
    return when {
        has("calf") -> "calf"
        has("shrug") -> "shrug"
        has("fly", "pec deck", "crossover") -> "fly"
        has("pulldown", "pull-up", "pullup", "chin-up", "chinup", "pullover") -> "vertical pull"
        has("row") -> "row"
        has("deadlift", "romanian", "rdl", "good morning", "hip thrust", "glute bridge", "back extension", "hyperextension") -> "hinge"
        has("squat", "leg press", "lunge", "step-up", "hack") -> "squat"
        has("curl") -> "curl"
        has("extension", "pushdown", "skull", "kickback") -> "extension"
        has("raise", "face pull", "reverse fly") -> "raise"
        has("crunch", "plank", "rollout", "sit-up", "twist", "dead bug", "hollow") -> "core"
        has("press", "bench", "push-up", "pushup", "dip") -> "press"
        else -> "other"
    }
}

/** Isolation movements: the ones a short session trims first. */
internal fun isIsolation(name: String) = movementOf(name) in setOf("fly", "curl", "extension", "raise", "calf", "shrug", "core")

/**
 * Up to [limit] exercises to do instead of [current]: the same muscle, the same kind of movement
 * first, what the equipment allows, what you've done before, and preferably not the same
 * equipment (that's what's taken). Leaves out what's already in the workout.
 */
internal fun swapCandidates(
    current: ExerciseEntry,
    inWorkout: Collection<String>,
    history: List<CompletedWorkout>,
    equipment: Equipment?,
    limit: Int = 3
): List<ExerciseInfo> {
    val info = ExerciseDatabase.find(current.name)
    val muscle = info?.muscle ?: current.targetMuscle
    val pattern = movementOf(current.name)
    val taken = inWorkout.mapTo(HashSet()) { exerciseKey(it) } + exerciseKey(current.name)
    val doneCount = HashMap<String, Int>()
    history.forEach { w -> w.exercises.forEach { ex -> if (!ex.isSkipped) doneCount.merge(exerciseKey(ex.name), 1, Int::plus) } }
    return ExerciseDatabase.all
        .asSequence()
        .filter { it.muscle.equals(muscle, ignoreCase = true) && exerciseKey(it.name) !in taken && fitsEquipment(it.name, equipment) }
        .map { c ->
            var score = 0.0
            if (movementOf(c.name) == pattern) score += 4
            val done = doneCount[exerciseKey(c.name)] ?: 0
            if (done > 0) score += 2 + minOf(done, 5) * 0.2
            if (info != null && c.equipment != info.equipment) score += 1
            if (c.common) score += 1
            if (c.type == current.type) score += 0.5
            score += swapPreference(c.name, history)
            c to score
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
        .toList()
}

/** [entry] with [workSets] work sets (copies of its last, or trimmed), its warm-ups kept. */
internal fun withWorkSets(entry: ExerciseEntry, workSets: Int): ExerciseEntry {
    val warmups = entry.sets.filter { it.isWarmupSet }
    val work = entry.sets.filter { !it.isWarmupSet }
    val template = work.lastOrNull() ?: return entry
    val resized = List(workSets.coerceAtLeast(1)) { i -> (work.getOrNull(i) ?: template).copy(isCompleted = false, rir = -1) }
    return entry.copy(sets = (warmups + resized).mapIndexed { i, s -> s.copy(setNumber = i + 1) })
}

/**
 * "Machine taken?": three alternatives for [exercise], each with the first set it would start
 * at. [canKeep]: the exercise is on a program day, so the swap can stay for next time.
 */
@Composable
fun SwapExerciseSheet(
    exercise: ExerciseEntry,
    splitTitle: String,
    inWorkout: List<String>,
    history: List<CompletedWorkout>,
    settings: AppSettings,
    equipment: Equipment?,
    canKeep: Boolean,
    onDismiss: () -> Unit,
    onSwap: (ExerciseEntry, keep: Boolean) -> Unit
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val candidates = remember(exercise.name) {
        swapCandidates(exercise, inWorkout, history, equipment).map { info ->
            val fresh = newExerciseEntry(context, splitTitle, info.name, info.muscle, history, settings)
            info to withWorkSets(fresh, exercise.sets.count { !it.isWarmupSet }).copy(isSuperset = exercise.isSuperset)
        }
    }
    val doneBefore = remember(history) { history.flatMap { w -> w.exercises.map { exerciseKey(it.name) } }.toSet() }
    var keep by remember { mutableStateOf(false) }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(tr("Machine taken?"), style = AppType.title, color = P.textPrimary)
            Text(tr("Swap {0} for something that trains the same muscle.", shown(exercise.name)), style = AppType.caption, color = P.textSecondary)
            Spacer(Modifier.height(16.dp))
            if (candidates.isEmpty()) {
                Text(tr("Nothing similar fits your equipment. Add any exercise from the workout's Add exercise."), style = AppType.body, color = P.textSecondary)
            }
            candidates.forEachIndexed { i, (info, entry) ->
                val first = entry.sets.firstOrNull { !it.isWarmupSet }
                AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).enterAnimation(i), onClick = { onSwap(entry, keep) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExerciseThumb(info.name, size = 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(shown(info.name), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    shown(info.equipment),
                                    if (exerciseKey(info.name) in doneBefore) tr("You've done it") else tr("New to you")
                                ).joinToString(" · "),
                                style = AppType.caption, color = P.textSecondary
                            )
                        }
                        if (first != null && entry.type.hasWeightSuggestions && first.weightKg > 0f) {
                            Spacer(Modifier.width(8.dp))
                            Text(formatSet(first, entry.type), style = AppType.label.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                        }
                    }
                }
            }
            if (canKeep && candidates.isNotEmpty()) {
                ToggleRow(tr("Keep it in {0}", shown(splitTitle)), tr("Off: just for today"), keep) { keep = it }
            }
        }
    }
}
