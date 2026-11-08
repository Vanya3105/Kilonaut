package com.example.gymtracker

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// ============================================================================
// MUSCLE RECOVERY
// ============================================================================

enum class RecoveryStatus(val label: String) { TIRED(tr("Tired")), RECOVERING(tr("Recovering")), READY(tr("Ready")) }

@Immutable
data class MuscleRecoveryItem(
    val muscleGroup: String,
    val recoveryPercent: Int,
    val status: RecoveryStatus,
    val hoursSinceTrained: Int?,
    val weeklyWorkingSets: Int
)

/** Weekly hard sets per muscle that tends to work well for building muscle. */
val WEEKLY_SET_TARGET = 6..16

/** The library's muscles, minus cardio. */
val RECOVERY_GROUPS = listOf(
    "Chest", "Back", "Shoulders", "Biceps", "Triceps", "Forearms",
    "Quads", "Hamstrings", "Glutes", "Calves", "Core"
)

/**
 * Hours each group needs after a hard session of about 6 sets, in [RECOVERY_GROUPS] order.
 * Big muscles under long, loaded stretches (quads, hamstrings, back) take ~72 h; chest and
 * shoulders ~48 h; arms, calves, forearms and abs recover fastest.
 */
private val BASE_RECOVERY_HOURS = floatArrayOf(48f, 72f, 48f, 36f, 42f, 30f, 72f, 72f, 60f, 36f, 30f)

/** Short names where the full one crowds a ring label. */
fun recoveryLabel(group: String): String = shown(if (group == "Hamstrings") "Hams" else group)

/**
 * How much one set of an exercise works each group, in [RECOVERY_GROUPS] order: 1 for the
 * target muscle, 0.5 for muscles that assist a compound lift (triceps on bench, biceps on rows),
 * 0.25 for smaller roles such as grip. Counting assisting muscles as half a set is what predicted
 * growth best in the Pelland et al. volume meta-regression.
 */
internal fun muscleLoads(splitTitle: String, exName: String, targetMuscle: String): FloatArray {
    val loads = FloatArray(RECOVERY_GROUPS.size)
    fun add(group: String, share: Float) {
        val i = RECOVERY_GROUPS.indexOf(group)
        if (i >= 0 && share > loads[i]) loads[i] = share
    }
    val name = exName.lowercase(Locale.US)
    fun has(vararg words: String) = words.any { name.contains(it) }
    val muscle = ExerciseDatabase.find(exName)?.muscle ?: targetMuscle.trim()
    if (muscle.equals("Cardio", ignoreCase = true)) return loads

    val primary = RECOVERY_GROUPS.firstOrNull { it.equals(muscle, ignoreCase = true) } ?: guessPrimary(name, muscle.lowercase(Locale.US), splitTitle.lowercase(Locale.US))
    if (primary == null) {
        // Older custom exercises could be tagged "Arms": split it by the movement.
        if (muscle.equals("Arms", ignoreCase = true)) { add("Biceps", 0.5f); add("Triceps", 0.5f) }
        return loads
    }
    add(primary, 1f)

    when (primary) {
        "Chest" -> if (!has("fly", "pec deck", "crossover", "pullover")) { add("Triceps", 0.5f); add("Shoulders", 0.5f) }
        "Triceps" -> if (has("bench", "press", "dip", "push-up")) { add("Chest", 0.5f); add("Shoulders", 0.25f) }
        "Shoulders" -> when {
            has("snatch") -> { add("Glutes", 0.5f); add("Hamstrings", 0.5f); add("Back", 0.25f) }
            has("press", "push-up", "handstand") -> add("Triceps", 0.5f)
            has("get-up") -> { add("Core", 0.5f); add("Glutes", 0.25f) }
            has("upright row") -> { add("Back", 0.25f); add("Biceps", 0.25f) }
            has("rear delt", "reverse pec", "face pull") -> add("Back", 0.25f)
        }
        "Back" -> when {
            has("deadlift", "rack pull") -> { add("Hamstrings", 0.5f); add("Glutes", 0.5f); add("Forearms", 0.5f); add("Quads", 0.25f) }
            has("back extension", "hyperextension") -> { add("Hamstrings", 0.5f); add("Glutes", 0.5f) }
            has("shrug") -> add("Forearms", 0.25f)
            has("straight-arm", "straight arm", "pullover") -> {}
            has("pull-up", "pullup", "chin", "pulldown", "muscle-up", "rope climb") -> { add("Biceps", 0.5f); add("Forearms", 0.25f) }
            has("high pull") -> { add("Shoulders", 0.5f); add("Glutes", 0.25f) }
            has("superman") -> { add("Glutes", 0.25f); add("Hamstrings", 0.25f) }
            has("row") -> { add("Biceps", 0.5f); add("Shoulders", 0.25f); add("Forearms", 0.25f) }
        }
        "Biceps" -> add("Forearms", if (has("hammer", "reverse")) 0.5f else 0.25f)
        "Forearms" -> when {
            has("reverse curl") -> add("Biceps", 0.5f)
            has("farmer", "carry") -> { add("Back", 0.25f); add("Core", 0.25f) }
            has("hang") -> add("Back", 0.25f)
        }
        "Quads" -> if (!has("extension", "sissy")) {
            add("Glutes", 0.5f)
            if (has("lunge", "split", "step-up", "step up")) add("Hamstrings", 0.25f)
            if (has("clean", "snatch", "jerk")) { add("Hamstrings", 0.5f); add("Back", 0.5f); add("Shoulders", 0.5f) }
            if (has("thruster")) { add("Shoulders", 0.5f); add("Triceps", 0.25f) }
        }
        "Hamstrings" -> if (has("clean")) {
            add("Glutes", 0.5f); add("Back", 0.25f); add("Shoulders", 0.25f)
        } else if (has("deadlift", "good morning")) {
            add("Glutes", 0.5f); add("Back", 0.25f)
            if (has("deadlift")) add("Forearms", 0.25f)
        }
        "Glutes" -> when {
            has("deadlift") -> { add("Quads", 0.5f); add("Hamstrings", 0.5f); add("Back", 0.5f); add("Forearms", 0.5f) }
            has("thrust", "bridge", "pull-through", "pull through") -> add("Hamstrings", 0.25f)
            has("swing") -> { add("Hamstrings", 0.5f); add("Back", 0.25f); add("Forearms", 0.25f) }
            has("lunge", "squat") -> { add("Quads", 0.5f); add("Hamstrings", 0.25f) }
        }
        "Core" -> when {
            has("hanging", "toes-to-bar") -> add("Forearms", 0.25f)
            has("slam") -> { add("Shoulders", 0.5f); add("Back", 0.25f) }
            has("windmill", "mountain", "bear crawl", "l-sit") -> add("Shoulders", 0.25f)
        }
    }
    return loads
}

/** Target group for an exercise that isn't in the library, from its name and free-text muscle. */
private fun guessPrimary(name: String, muscle: String, split: String): String? {
    val text = "$name $muscle"
    fun any(vararg words: String) = words.any { text.contains(it) }
    return when {
        any("calf", "calves") -> "Calves"
        any("wrist", "forearm", "grip") -> "Forearms"
        any("leg curl", "hamstring", "rdl", "romanian", "nordic") -> "Hamstrings"
        any("leg extension", "quad", "squat", "leg press", "lunge") -> "Quads"
        any("glute", "hip thrust") -> "Glutes"
        any("back extension", "hyperextension") -> "Back"
        any("abs", "abdominal", "core", "crunch", "plank", "sit-up", "leg raise", "knee raise") -> "Core"
        any("tricep", "pushdown", "skull", "kickback", "extension") -> "Triceps"
        any("bicep", "curl", "brachialis") -> "Biceps"
        any("lateral", "delt", "shoulder", "overhead press", "face pull", "arnold", "raise") -> "Shoulders"
        any("chest", "bench", "pec", "fly", "dip", "push-up", "pushup") -> "Chest"
        any("lat", "back", "row", "pull-up", "pullup", "chin", "deadlift", "shrug") -> "Back"
        any("leg") -> "Quads"
        // Untagged exercise on a push or pull day: assume the day's main muscle.
        muscle.isBlank() && split.contains("push") -> "Chest"
        muscle.isBlank() && split.contains("pull") -> "Back"
        else -> null
    }
}

/**
 * Recovery per muscle group. Each session leaves fatigue that fades linearly over the group's
 * recovery time, scaled by how many sets it got (assisting muscles count part sets), and
 * sessions close together stack. Also counts this week's sets per group.
 */
fun computeMuscleRecoveryStatus(history: List<CompletedWorkout>, now: Long = System.currentTimeMillis()): List<MuscleRecoveryItem> {
    val weekAgo = now - 7L * 24 * 60 * 60 * 1000
    val groups = RECOVERY_GROUPS.size
    val weekly = FloatArray(groups)
    val readiness = FloatArray(groups) { 1f }
    val lastTrained = LongArray(groups) { Long.MIN_VALUE }
    // The word matching is the slow part; the same exercise recurs in almost every week.
    val loadsOf = HashMap<Triple<String, String, String>, FloatArray>()
    // Newest first (as stored), the scan can stop once every group has a last session and the
    // week is behind it. This runs after every edit while a recovery widget is placed.
    val newestFirst = history.zipWithNext().all { (a, b) -> a.id >= b.id }
    val setsInWorkout = FloatArray(groups)

    for (workout in history) {
        if (newestFirst && workout.id < weekAgo && lastTrained.all { it != Long.MIN_VALUE }) break
        setsInWorkout.fill(0f)
        for (ex in workout.exercises) {
            if (ex.isSkipped) continue
            // The split title only matters for exercises without a target muscle.
            val key = Triple(if (ex.targetMuscle.isBlank()) workout.splitTitle else "", ex.name, ex.targetMuscle)
            val loads = loadsOf.getOrPut(key) { muscleLoads(workout.splitTitle, ex.name, ex.targetMuscle) }
            if (loads.all { it == 0f }) continue
            val count = ex.sets.count { !it.isWarmupSet && (it.repCount > 0 || it.isValidWorkSet(ex.type)) }
            for (g in 0 until groups) setsInWorkout[g] += count * loads[g]
        }
        val hours = ((now - workout.id) / 3_600_000f).coerceAtLeast(0f)
        for (g in 0 until groups) {
            val sets = setsInWorkout[g]
            if (sets <= 0f) continue
            // A quarter-set of grip work isn't "training" forearms.
            if (sets >= 1f && workout.id > lastTrained[g]) lastTrained[g] = workout.id
            if (workout.id < weekAgo) continue
            weekly[g] += sets
            readiness[g] *= 1f - recoveryLeft(BASE_RECOVERY_HOURS[g], sets, hours)
        }
    }

    return RECOVERY_GROUPS.mapIndexed { g, group ->
        val weeklySets = weekly[g].roundToInt()
        val pct = (readiness[g] * 100f).roundToInt().coerceIn(12, 100)
        val status = when {
            pct < 55 -> RecoveryStatus.TIRED
            pct < 85 -> RecoveryStatus.RECOVERING
            else -> RecoveryStatus.READY
        }
        val hoursAgo = if (lastTrained[g] == Long.MIN_VALUE) null else ((now - lastTrained[g]) / (1000L * 60 * 60)).toInt().coerceAtLeast(0)
        MuscleRecoveryItem(group, pct, status, hoursAgo, weeklySets)
    }
}

/**
 * Share of a session's fatigue still left [hours] later. Four or more sets tire a muscle fully;
 * more sets also stretch the recovery time (6 sets → the base time, 12 → 1.4×, 2 → 0.73×).
 */
internal fun recoveryLeft(baseHours: Float, sets: Float, hours: Float): Float {
    val needed = baseHours * (0.6f + 0.4f * sets.coerceAtMost(12f) / 6f)
    val severity = (sets / 4f).coerceAtMost(1f)
    return severity * (1f - hours / needed).coerceAtLeast(0f)
}

// ============================================================================
// WEIGHT SUGGESTIONS
// ============================================================================
// Double progression: keep a weight until every work set reaches the top of the rep range, then
// add weight. How much comes from the estimated 1RM, capped near 10% (ACSM: 2–10% once you beat
// the target by a rep or two). Misses below the range, stalls and long breaks bring it down.
// Everything here works in the display unit, so steps and rounding land on real plates.
//
// Effort ("reps left", RIR), when the lifter records it, refines this. RIR-based loading beats
// fixed loading for strength (Helms et al. 2018; Graham & Cleather 2020), and growth is best with
// work sets about 1-3 reps short of failure (Robinson et al. 2024), so new weights aim to leave
// TARGET_RIR in the tank. People underestimate their reserve by about a rep (Halperin et al. 2021):
// taken as said, it errs toward lighter. Ratings are only trusted on sets of up to 15 reps.

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Body weight assumed for weighted bodyweight lifts when no workout recorded one. */
private const val DEFAULT_BODY_KG = 75f

/** The rep range coaching aims for. */
/** Reps to leave in the tank on a work set: the middle of the 1-3 that grows muscle best. */
private const val TARGET_RIR = 2

/** Above this many reps, "reps left" guesses get unreliable. */
private const val MAX_RATED_REPS = 15

private class RepGoal(val min: Int, val max: Int) {
    val text: String get() = if (min == max) "$min" else "$min–$max"
    val mid: Int get() = (min + max + 1) / 2
}

/**
 * One session of an exercise, in the display unit. Only sets at the session's heaviest weight
 * count for reps: back-off and drop sets would otherwise look like misses.
 */
private class Session(val time: Long, sets: List<SetEntry>, unit: WeightUnit, bodyLoad: Float) {
    val weight = unit.fromKg(sets.maxOf { it.weightKg })
    val reps = sets.filter { unit.fromKg(it.weightKg) >= weight - 0.01f }.map { it.repCount }
    val firstReps get() = reps.first()
    val minReps get() = reps.min()
    val totalReps get() = reps.sum()
    // Epley gets unreliable past ~15 reps, where it overstates the max.
    val e1rm = sets.maxOf { calculateEstimated1Rm(unit.fromKg(it.weightKg) + bodyLoad, it.repCount.coerceAtMost(15)) }
    /** Reps left on the top-weight sets that have it recorded (4 = four or more). */
    val rirs = sets.filter { unit.fromKg(it.weightKg) >= weight - 0.01f && it.rir >= 0 && it.repCount in 1..MAX_RATED_REPS }.map { it.reserve() }
    /** The max from reps plus reps left (what the set would have been to failure); the plain one without ratings. */
    val effortE1rm = sets.filter { it.rir >= 0 && it.repCount in 1..MAX_RATED_REPS }
        .maxOfOrNull { calculateEstimated1Rm(unit.fromKg(it.weightKg) + bodyLoad, (it.repCount + it.reserve()).coerceAtMost(MAX_RATED_REPS)) } ?: e1rm
}

/**
 * The next session's weight from how hard the last one felt, or null to decide on reps alone.
 * Up more when every set reached the top with reps to spare, only a step when it took going to
 * failure, and up anyway when every set stopped well short of failure.
 */
private fun planFromEffort(last: Session, goal: RepGoal, step: Float, bodyLoad: Float, unit: WeightUnit, whenText: String, nextTime: Boolean): Suggestion? {
    val rirs = last.rirs.ifEmpty { return null }
    val w = last.weight
    if (w <= 0f) return null
    fun shown(v: Float) = "${formatKgClean(unit.round(v))} ${unit.label}"
    fun make(v: Float, tone: SuggestionTone, message: String) =
        Suggestion(message, tone, unit.stored(unit.round(v.coerceAtLeast(0f))), forNextTime = nextTime)
    // One step on a light dumbbell can be 20%+: never more than that one step there.
    val bigStep = (w + bodyLoad) > 0f && step / (w + bodyLoad) > 0.10f
    /** The weight for the middle of the range with TARGET_RIR left, at least a step up and at most ~10%. */
    fun upTo(): Float {
        if (bigStep) return w + step
        // To the nearest plate, not down: the reserve is shown, and is usually a rep more than said.
        val aim = nearestTo(weightFor(last.effortE1rm, goal.mid + TARGET_RIR, bodyLoad), step)
        val cap = maxOf(floorTo(w * 1.10f, step), w + step)
        return aim.coerceIn(w + step, cap)
    }
    val minRir = rirs.min()
    val spare = if (minRir >= 4) "4" else "$minRir"
    return when {
        last.minReps >= goal.max && minRir >= 2 -> {
            val up = upTo()
            make(up, SuggestionTone.GOOD, tr("Every set hit {0}+ with {1}+ reps to spare. Go up to {2} {3}.", goal.max, spare, shown(up), whenText))
        }
        last.minReps >= goal.max && rirs.any { it == 0 } -> {
            val up = w + step
            make(up, SuggestionTone.GOOD, tr("You reached {0} reps at {1}, but only by going to failure. Go up one step to {2} {3}.", goal.max, shown(w), shown(up), whenText))
        }
        last.firstReps >= goal.min && last.minReps < goal.max && minRir >= 3 && !bigStep -> {
            val up = upTo()
            make(up, SuggestionTone.GOOD, tr("You stopped with {0}+ reps left on every set. Go up to {1} {2} and finish 1–2 reps short of failure.", spare, shown(up), whenText))
        }
        else -> null
    }
}

/** Reps expected at [weight] from an estimated 1RM (Epley, inverted). */
private fun repsAt(e1rm: Float, weight: Float, bodyLoad: Float): Float = 30f * (e1rm / (weight + bodyLoad) - 1f)

/** The weight that should allow [reps] reps from an estimated 1RM. */
private fun weightFor(e1rm: Float, reps: Int, bodyLoad: Float): Float = e1rm / (1f + reps / 30f) - bodyLoad

private fun nearestTo(value: Float, step: Float): Float =
    if (step <= 0f) value else (kotlin.math.round(value / step) * step).coerceAtLeast(0f)

private fun floorTo(value: Float, step: Float): Float =
    if (step <= 0f) value else (floor(value / step + 0.001f) * step).coerceAtLeast(0f)

private fun isCompoundLift(exerciseName: String): Boolean {
    val lower = exerciseName.lowercase(Locale.US)
    return listOf("squat", "bench", "press", "deadlift", "rdl", "row", "pull-up", "pullup", "pulldown", "dip", "lunge").any {
        lower.contains(it)
    } && !lower.contains("pushdown") && !lower.contains("extension") && !lower.contains("fly")
}

/** The rep range coaching uses for an exercise without a target of its own. */
fun defaultRepRange(name: String): Pair<Int, Int> =
    prescriptionFor(TrainingProfile.current, name)?.let { it.repMin to it.repMax } ?: if (isCompoundLift(name)) 6 to 10 else 8 to 12

private fun repGoal(exercise: ExerciseEntry, recent: List<Session>): RepGoal {
    exercise.target?.let { return RepGoal(it.repMin, it.repMax) }
    // No plan, but even sets of 5 or fewer (5×5, 3×3) twice running: strength work, not a miss.
    // Misses look different: the reps tail off from set to set.
    if (recent.size >= 2 && recent.take(2).all { it.firstReps in 1..5 && it.minReps == it.firstReps }) return RepGoal(3, 5)
    // From the setup answers (experience and goal), when there are any.
    // Ranges 4–5 reps wide leave room to progress for a few weeks between jumps.
    return defaultRepRange(exercise.name).let { (min, max) -> RepGoal(min, max) }
}

private fun bodyLoadFor(type: ExerciseType, history: List<CompletedWorkout>, unit: WeightUnit): Float =
    if (type != ExerciseType.WEIGHTED_BODYWEIGHT) 0f
    else unit.fromKg(history.firstOrNull { it.bodyWeightKg > 0f }?.bodyWeightKg ?: DEFAULT_BODY_KG)

/** Past sessions of this exercise, newest first: the same day if it has any, else any day. */
private fun pastSessions(
    splitTitle: String,
    exercise: ExerciseEntry,
    history: List<CompletedWorkout>,
    unit: WeightUnit,
    bodyLoad: Float
): List<Session> {
    fun from(workouts: List<CompletedWorkout>) = workouts.mapNotNull { workout ->
        workout.exercises
            .find { !it.isSkipped && it.name.equals(exercise.name, ignoreCase = true) }
            ?.workSets
            ?.takeIf { it.isNotEmpty() }
            ?.let { Session(workout.id, it, unit, bodyLoad) }
    }
    // A day can be heavy or light on purpose, so its own history comes first. Lighter weeks are
    // lighter on purpose too, so the next plan builds on the weeks before them.
    val history = history.outsideLighterWeeks()
    return from(history.filter { it.splitTitle.equals(splitTitle, ignoreCase = true) }).ifEmpty { from(history) }
}

/**
 * What to lift next for this exercise. While sets remain, reacts to today's sets (too heavy,
 * too light, reps falling off faster than normal fatigue); once every set is done it plans the
 * next session. Null for exercise types where weight suggestions don't apply.
 */
fun suggestNextWeight(
    splitTitle: String,
    exercise: ExerciseEntry,
    history: List<CompletedWorkout>,
    step: Float,
    now: Long = System.currentTimeMillis()
): Suggestion? {
    if (!exercise.type.hasWeightSuggestions || exercise.programmed) return null
    val unit = Units.current
    val bodyLoad = bodyLoadFor(exercise.type, history, unit)
    val past = pastSessions(splitTitle, exercise, history, unit, bodyLoad)
    val goal = repGoal(exercise, past)

    val workSets = exercise.sets.filter { !it.isWarmupSet }
    val done = workSets.filter { it.isCompleted && it.repCount > 0 }
    // Weighted bodyweight with nothing added yet and no history: there's no load to build on.
    if (exercise.type == ExerciseType.WEIGHTED_BODYWEIGHT && past.isEmpty() && workSets.none { it.weightKg > 0f }) return null

    if (done.isNotEmpty() && workSets.all { it.isCompleted }) {
        return plan(listOf(Session(now, done, unit, bodyLoad)) + past, goal, step, unit, bodyLoad, now, nextTime = true)
    }
    if (done.isNotEmpty()) inSession(done, goal, step, unit, bodyLoad)?.let { return it }
    if (past.isNotEmpty()) return plan(past, goal, step, unit, bodyLoad, now, nextTime = false)

    // Never logged: the weight in the sets is only a placeholder, so don't present it as advice.
    return Suggestion(
        tr("First time: find a weight you can lift {0} times with good form. Hit {1} on every set, then add {2} {3}.", goal.text, goal.max, formatKgClean(step), unit.label),
        SuggestionTone.NEUTRAL, ""
    )
}

/** Advice for the rest of today's sets, or null when today's sets look as they should. */
private fun inSession(done: List<SetEntry>, goal: RepGoal, step: Float, unit: WeightUnit, bodyLoad: Float): Suggestion? {
    // Drop and rest-pause sets are meant to fall short; judge straight sets only.
    val straight = done.filter { it.intensityType == "NORMAL" }.ifEmpty { return null }
    val latest = straight.last()
    val w = unit.fromKg(latest.weightKg)
    val e1rm = calculateEstimated1Rm(w + bodyLoad, latest.repCount.coerceAtMost(15))
    fun make(v: Float, tone: SuggestionTone, message: String) =
        Suggestion(message, tone, unit.stored(unit.round(v.coerceAtLeast(0f))))
    fun shown(v: Float) = "${formatKgClean(unit.round(v))} ${unit.label}"

    // Losing a rep or two from set to set is normal fatigue; two under the range is not.
    if (latest.repCount <= goal.min - 2 && w > 0f) {
        val lighter = minOf(w - step, maxOf(floorTo(weightFor(e1rm, goal.min, bodyLoad), step), floorTo(w * 0.8f, step))).coerceAtLeast(0f)
        return if (straight.size == 1) {
            make(lighter, SuggestionTone.CAUTION, tr("{0} reps is under your {1} range, so this is too heavy today. Use {2} for the next sets.", latest.repCount, goal.text, shown(lighter)))
        } else {
            make(lighter, SuggestionTone.CAUTION, tr("Reps fell to {0}. Rest a little longer, or use {1} for the rest.", latest.repCount, shown(lighter)))
        }
    }
    if (straight.size == 1 && latest.repCount >= goal.max + 3) {
        val heavier = maxOf(w + step, minOf(floorTo(weightFor(e1rm, goal.mid, bodyLoad), step), floorTo(w * 1.15f, step)))
        return make(heavier, SuggestionTone.GOOD, tr("{0} reps is light for {1}. Go up to {2} for the next sets.", latest.repCount, goal.text, shown(heavier)))
    }
    // The set just rated: what it would have been to failure says what the next ones should weigh.
    val left = latest.reserve()
    if (left >= 0 && latest.repCount in 1..MAX_RATED_REPS && w > 0f) {
        val toFailure = calculateEstimated1Rm(w + bodyLoad, (latest.repCount + left).coerceAtMost(MAX_RATED_REPS))
        if (left >= 3 && latest.repCount >= goal.min) {
            val heavier = maxOf(w + step, minOf(nearestTo(weightFor(toFailure, goal.mid + TARGET_RIR, bodyLoad), step), floorTo(w * 1.15f, step)))
            val spare = if (latest.rir >= 4) "4" else "${latest.rir}"
            return make(heavier, SuggestionTone.GOOD, tr("{0} reps with {1}+ left is light for {2}. Use {3} for the next sets and finish 1–2 reps short of failure.", latest.repCount, spare, goal.text, shown(heavier)))
        }
        if (left == 0 && straight.size == 1 && latest.repCount < goal.max) {
            val lighter = minOf(w - step, floorTo(weightFor(toFailure, goal.min + TARGET_RIR, bodyLoad), step)).coerceAtLeast(0f)
            return make(w, SuggestionTone.CAUTION, tr("Set 1 went to failure. Stay at {0} and expect a rep or two fewer, or use {1} to stay in {2}.", shown(w), shown(lighter), goal.text))
        }
    }
    return null
}

/** The next session's weight from [sessions], newest first (never empty). */
private fun plan(
    sessions: List<Session>,
    goal: RepGoal,
    step: Float,
    unit: WeightUnit,
    bodyLoad: Float,
    now: Long,
    nextTime: Boolean
): Suggestion {
    val last = sessions[0]
    val prev = sessions.getOrNull(1)
    val w = last.weight
    val whenText = if (nextTime) tr("next time") else tr("today")
    val lastText = if (nextTime) tr("today") else tr("last time")
    fun shown(v: Float) = "${formatKgClean(unit.round(v))} ${unit.label}"
    fun make(v: Float, tone: SuggestionTone, message: String) =
        Suggestion(message, tone, unit.stored(unit.round(v.coerceAtLeast(0f))), forNextTime = nextTime)
    /** At least one step lighter, aiming at [target] but never more than [maxDrop] off. */
    fun lighter(target: Float, maxDrop: Float) =
        minOf(w - step, maxOf(floorTo(target, step), floorTo(w * (1f - maxDrop), step))).coerceAtLeast(0f)
    val sameWeight = { s: Session -> abs(s.weight - w) < 0.01f }

    // Back after a break. Strength barely drops in 2–3 weeks; after a month, ease back in.
    val daysOff = ((now - last.time) / DAY_MS).toInt()
    if (!nextTime && daysOff >= 28 && w > 0f) {
        val back = floorTo(w * (if (daysOff >= 56) 0.8f else 0.9f), step)
        return make(back, SuggestionTone.CAUTION, tr("It's been {0} weeks since you last did this. Ease back in at {1} and build up from there.", daysOff / 7, shown(back)))
    }

    // Well under the range once, or under it twice running at this weight: the weight is wrong.
    val missedTwice = last.firstReps < goal.min && prev != null && sameWeight(prev) && prev.firstReps < goal.min
    if (w > 0f && (last.firstReps <= goal.min - 3 || missedTwice)) {
        val down = lighter(weightFor(last.e1rm, goal.mid, bodyLoad), 0.15f)
        val why = if (missedTwice) tr("Under {0} reps at {1} two sessions running.", goal.min, shown(w))
        else tr("Only {0} reps at {1} {2}, well under {3}.", last.firstReps, shown(w), lastText, goal.text)
        return make(down, SuggestionTone.CAUTION, tr("{0} Drop to {1} {2} and build back up.", why, shown(down), whenText))
    }

    planFromEffort(last, goal, step, bodyLoad, unit, whenText, nextTime)?.let { return it }

    if (sessions.size >= 3 && w > 0f) {
        val three = sessions.take(3)
        // Same weight three times, never reaching the top and no more reps than before: a stall.
        if (three.all(sameWeight) && three.none { it.minReps >= goal.max } && last.totalReps <= three[2].totalReps) {
            val reset = lighter(w * 0.9f, 0.1f)
            return make(reset, SuggestionTone.CAUTION, tr("No progress at {0} in 3 sessions. Reset to {1} {2} and work back up; you'll usually pass it within a few weeks.", shown(w), shown(reset), whenText))
        }
        if (three[0].e1rm < three[1].e1rm * 0.99f && three[1].e1rm < three[2].e1rm * 0.99f) {
            val down = lighter(w * 0.9f, 0.1f)
            return make(down, SuggestionTone.CAUTION, tr("Your numbers have dipped three sessions in a row. Go lighter {0}: {1}, and check your sleep and recovery.", whenText, shown(down)))
        }
    }

    if (last.minReps >= goal.max) {
        val total = w + bodyLoad
        // One step on a light dumbbell can be 20%+. Earn it with extra reps first.
        val bigJump = total > 0f && step / total > 0.10f
        if (bigJump && last.minReps < goal.max + 2) {
            return make(w, SuggestionTone.NEUTRAL, tr("Every set hit {0}+ at {1}. The next step is a big jump for this weight, so get {2} reps on every set first.", goal.max, shown(w), goal.max + 2))
        }
        if (!nextTime && daysOff >= 14) {
            return make(w, SuggestionTone.NEUTRAL, tr("First session in {0} weeks. Repeat {1} before moving up.", daysOff / 7, shown(w)))
        }
        var steps = 1
        // Beat the top by 2+ on every set: a bigger jump, as long as the reps should stay in range.
        if (last.minReps >= goal.max + 2 && last.e1rm > 0f) {
            val cap = maxOf(w * 1.10f, w + step)
            while (steps < 4 && w + (steps + 1) * step <= cap + 0.001f && repsAt(last.e1rm, w + (steps + 1) * step, bodyLoad) >= goal.mid) steps++
        }
        val up = w + steps * step
        val message = if (steps == 1) tr("Every set hit {0}+ reps at {1}. Go up to {2} {3}.", goal.max, shown(w), shown(up), whenText)
        else tr("You beat {0} reps on every set at {1}. Jump to {2} {3}.", goal.max, shown(w), shown(up), whenText)
        return make(up, SuggestionTone.GOOD, message)
    }

    if (last.firstReps < goal.min) {
        return make(w, SuggestionTone.CAUTION, tr("{0} reps at {1} {2}, just under {3}. Stay there and aim for {4}.", last.firstReps, shown(w), lastText, goal.text, goal.min))
    }
    if (last.firstReps >= goal.max) {
        return make(w, SuggestionTone.NEUTRAL, tr("Your first set hit {0} at {1}. Get every set there, then move up.", goal.max, shown(w)))
    }
    return make(w, SuggestionTone.NEUTRAL, tr("{0} for {1} {2}. Stay there and add a rep; move up once every set hits {3}.", shown(w), last.reps.joinToString(", "), lastText, goal.max))
}

/**
 * The weight for this exercise's next session from history alone (no sets logged today), used to
 * lay out a new workout. Null when there is no history of it.
 */
fun suggestFromSplitHistory(
    splitTitle: String,
    exercise: ExerciseEntry,
    history: List<CompletedWorkout>,
    step: Float,
    now: Long = System.currentTimeMillis()
): Suggestion? {
    if (!exercise.type.hasWeightSuggestions) return null
    val unit = Units.current
    val bodyLoad = bodyLoadFor(exercise.type, history, unit)
    val past = pastSessions(splitTitle, exercise, history, unit, bodyLoad).ifEmpty { return null }
    return plan(past, repGoal(exercise, past), step, unit, bodyLoad, now, nextTime = false)
}

/** "Go lighter": about 10% off, rounded down to the step and at least one step lighter. */
fun lighterWeight(storedKg: String, step: Float): String {
    val u = Units.current
    val w = u.fromKg(parseWeightClean(storedKg, 0f))
    return u.stored(u.round(minOf(w - step, floorTo(w * 0.9f, step)).coerceAtLeast(0f)))
}
