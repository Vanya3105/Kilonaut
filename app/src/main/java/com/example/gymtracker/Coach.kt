package com.example.gymtracker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// THE COACH: advice across weeks, not one exercise's next session
// ============================================================================
// - Lighter weeks (deloads): coaches put one in every 4-8 weeks, at about half the sets and a
//   little lighter, so fatigue clears without losing anything (Bell et al. 2023, Delphi consensus).
//   Offered after 6 hard weeks in a row, or 4 when several main lifts have stopped going up.
// - Weekly sets per muscle, once every 4 weeks: more sets grow more muscle, with diminishing
//   returns, roughly 10-20 hard sets a week (Schoenfeld et al. 2017; Pelland et al. 2024). A muscle
//   under 10 that has stalled gets a set; one over 20 that is slipping loses one.
// - Skips and swaps: an exercise skipped in 3 of a day's last 4 workouts, or swapped for the same
//   one twice, is probably not the right one for that day. Swap suggestions learn from both.
// - Reps-left check: people misjudge reps in reserve, mostly underestimating it by about a rep
//   (Halperin et al. 2021). Every 6 weeks or so one main lift's last set goes as far as good form
//   allows, and the gap to what the set before was rated corrects the ratings from then on.

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_MS = 7 * DAY_MS

/** A one-off swap during a workout ("Machine taken?" without keeping it in the day). */
data class SwapRecord(val day: String, val from: String, val to: String, val time: Long)

/**
 * One reps-left check: [reps] on the all-out set, [expected] from the rating of the set before.
 * Both are -1 when the check was offered but not done, which still counts for when to ask again.
 */
data class RirCheck(val exercise: String, val time: Long, val reps: Int, val expected: Int) {
    val done: Boolean get() = reps >= 0 && expected > 0
}

/** What the coach remembers between workouts. Kept on this phone (SharedPreferences). */
data class CoachData(
    /** Lighter weeks, start..end in millis. */
    val deloads: List<LongRange> = emptyList(),
    val deloadSnoozedUntil: Long = 0L,
    /** When the last weekly-sets suggestion was answered; 0 = never (counts from the first workout). */
    val volumeCheckedAt: Long = 0L,
    /** Suggestions answered with "keep it" / "no", by key, so they don't come back. */
    val dismissed: Set<String> = emptySet(),
    val swaps: List<SwapRecord> = emptyList(),
    val checks: List<RirCheck> = emptyList(),
    /** A check finished since Today last showed its result. */
    val unseenCheck: Boolean = false
) {
    fun deloadAt(time: Long): LongRange? = deloads.firstOrNull { time in it }

    /**
     * How many reps more than rated the lifter really had, from the last three checks. Kept within
     * -1..3: a single odd set shouldn't turn every rating upside down.
     */
    val rirBias: Int
        get() = checks.filter { it.done }.takeLast(3).ifEmpty { return 0 }
            .map { it.reps - it.expected }.average().roundToInt().coerceIn(-1, 3)
}

object CoachState {
    private const val PREFS = "gym_tracker_coach"
    private const val KEY = "state"

    @Volatile var current: CoachData = CoachData()
        private set
    @Volatile private var loaded = false

    /** Bumped on every change, so Today redraws. */
    var version by mutableIntStateOf(0)
        private set

    fun load(context: Context): CoachData {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                current = decode(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
                loaded = true
            }
        }
        return current
    }

    fun update(context: Context, change: (CoachData) -> CoachData) {
        synchronized(this) {
            load(context)
            current = change(current)
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(current)).apply()
        }
        version++
    }

    /** For tests. */
    internal fun set(data: CoachData) {
        current = data
        loaded = true
    }

    internal fun encode(d: CoachData): String = JSONObject().apply {
        put("deloads", JSONArray().apply { d.deloads.forEach { put(JSONArray().put(it.first).put(it.last)) } })
        put("deloadSnoozedUntil", d.deloadSnoozedUntil)
        put("volumeCheckedAt", d.volumeCheckedAt)
        put("dismissed", JSONArray(d.dismissed.toList()))
        put("swaps", JSONArray().apply {
            d.swaps.forEach { put(JSONObject().put("day", it.day).put("from", it.from).put("to", it.to).put("time", it.time)) }
        })
        put("checks", JSONArray().apply {
            d.checks.forEach { put(JSONObject().put("exercise", it.exercise).put("time", it.time).put("reps", it.reps).put("expected", it.expected)) }
        })
        put("unseenCheck", d.unseenCheck)
    }.toString()

    internal fun decode(raw: String?): CoachData {
        val o = try { JSONObject(raw ?: return CoachData()) } catch (_: Exception) { return CoachData() }
        fun <T> list(key: String, item: (Int, JSONArray) -> T?): List<T> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> try { item(i, arr) } catch (_: Exception) { null } }
        }
        return CoachData(
            deloads = list("deloads") { i, a -> a.getJSONArray(i).let { it.getLong(0)..it.getLong(1) } },
            deloadSnoozedUntil = o.optLong("deloadSnoozedUntil", 0L),
            volumeCheckedAt = o.optLong("volumeCheckedAt", 0L),
            dismissed = list("dismissed") { i, a -> a.getString(i) }.toSet(),
            swaps = list("swaps") { i, a -> a.getJSONObject(i).let { SwapRecord(it.getString("day"), it.getString("from"), it.getString("to"), it.getLong("time")) } },
            checks = list("checks") { i, a -> a.getJSONObject(i).let { RirCheck(it.getString("exercise"), it.getLong("time"), it.getInt("reps"), it.getInt("expected")) } },
            unseenCheck = o.optBoolean("unseenCheck", false)
        )
    }
}

/** Workouts that count for progress: lighter weeks are lighter on purpose. */
internal fun List<CompletedWorkout>.outsideLighterWeeks(coach: CoachData = CoachState.current): List<CompletedWorkout> =
    if (coach.deloads.isEmpty()) this else filter { coach.deloadAt(it.id) == null }

/** Reps left as rated, corrected by the reps-left checks; -1 when not rated. */
internal fun SetEntry.reserve(bias: Int = CoachState.current.rirBias): Int =
    if (rir < 0) -1 else (rir + bias).coerceIn(0, 4)

// --- Lighter weeks ---

/** Weeks in a row, counting back from [now], with 2+ workouts each, since the last lighter week. */
internal fun hardWeeks(history: List<CompletedWorkout>, now: Long, coach: CoachData): Int {
    val afterDeload = coach.deloads.maxOfOrNull { it.last } ?: Long.MIN_VALUE
    var weeks = 0
    while (weeks < 52) {
        val end = now - weeks * WEEK_MS
        val start = end - WEEK_MS
        if (start < afterDeload) break
        if (history.count { it.id in start until end } < 2) break
        weeks++
    }
    return weeks
}

/** Best estimated max of each session of [name] in [history] (newest first), weight exercises only. */
private fun sessionMaxes(name: String, history: List<CompletedWorkout>): List<Pair<Long, Float>> =
    history.mapNotNull { w ->
        val ex = w.exercises.find { !it.isSkipped && exerciseKey(it.name) == exerciseKey(name) } ?: return@mapNotNull null
        if (ex.type != ExerciseType.WEIGHT_REPS) return@mapNotNull null
        val best = ex.workSets.maxOfOrNull { calculateEstimated1Rm(it.weightKg, it.repCount.coerceAtMost(15)) } ?: return@mapNotNull null
        w.id to best
    }

/** Main lifts done 4+ times in the last 6 weeks whose last two sessions beat neither of the two before. */
internal fun stalledLifts(history: List<CompletedWorkout>, now: Long): Int {
    val recent = history.filter { it.id >= now - 6 * WEEK_MS }.outsideLighterWeeks()
    val names = recent.flatMap { w -> w.exercises.filter { !it.isSkipped && isCompound(it.name) }.map { exerciseKey(it.name) } }.toSet()
    return names.count { name ->
        val maxes = sessionMaxes(name, recent).map { it.second }
        maxes.size >= 4 && maxes.take(2).max() <= maxes.drop(2).take(2).max() * 1.005f
    }
}

/** Hard weeks in a row when a lighter week is due, else null. */
internal fun deloadDue(history: List<CompletedWorkout>, now: Long, coach: CoachData): Int? {
    if (coach.deloadAt(now) != null || now < coach.deloadSnoozedUntil) return null
    val weeks = hardWeeks(history, now, coach)
    return weeks.takeIf { it >= 6 || (it >= 4 && stalledLifts(history, now) >= 2) }
}

/**
 * [entry] for a lighter week: half its work sets (rounded up) and, for weights, about 10% lighter.
 * Weight advice stays out of the way, as for a program's lifts; a program's own lifts are left alone.
 */
internal fun lighterWeekVersion(entry: ExerciseEntry, step: Float): ExerciseEntry {
    if (entry.programmed) return entry
    val warmups = entry.sets.filter { it.isWarmupSet }
    val work = entry.sets.filter { !it.isWarmupSet }
    if (work.isEmpty()) return entry
    val lighter = entry.type.hasWeightSuggestions
    val kept = work.take((work.size + 1) / 2).map { s ->
        if (lighter && s.weightKg > 0f) s.copy(weight = lighterWeight(s.weight, step)) else s
    }
    return entry.copy(
        sets = (warmups + kept).mapIndexed { i, s -> s.copy(setNumber = i + 1) },
        planNote = if (lighter) tr("Lighter week: half the sets, about 10% lighter") else tr("Lighter week: half the sets"),
        programmed = true
    )
}

// --- Weekly sets ---

/** A day of the current program as the coach sees it. */
data class CoachDay(val title: String, val exercises: List<CoachExercise>)

/** [sets]: work sets a new workout gives it; [programmed]: a percentage program's lift. */
data class CoachExercise(
    val name: String,
    val muscle: String,
    val sets: Int,
    val target: ExerciseTarget?,
    val type: ExerciseType = ExerciseType.WEIGHT_REPS,
    val programmed: Boolean = false
)

data class VolumeChange(val day: String, val exercise: String, val muscle: String, val fromSets: Int, val toSets: Int, val weeklySets: Int)

private fun muscleOf(name: String, muscle: String) = ExerciseDatabase.find(name)?.muscle ?: muscle

/** Every 4 weeks: one exercise to give a set more or less, or null when the sets look right. */
internal fun volumeChange(days: List<CoachDay>, history: List<CompletedWorkout>, now: Long, coach: CoachData): VolumeChange? {
    if (history.isEmpty() || coach.deloadAt(now) != null) return null
    val since = maxOf(coach.volumeCheckedAt, history.minOf { it.id })
    if (now - since < 4 * WEEK_MS) return null
    val window = history.filter { it.id >= now - 4 * WEEK_MS }.outsideLighterWeeks(coach)
    if (window.size < 8) return null

    val weekly = HashMap<String, Float>()
    window.forEach { w -> w.exercises.filter { !it.isSkipped }.forEach { ex -> weekly.merge(muscleOf(ex.name, ex.targetMuscle), ex.workSets.size / 4f, Float::plus) } }

    val planned = days.flatMap { d -> d.exercises.map { d.title to it } }
        .filter { (_, e) -> e.type == ExerciseType.WEIGHT_REPS && !e.programmed }
    val removes = ArrayList<VolumeChange>()
    val adds = ArrayList<VolumeChange>()
    for ((muscle, list) in planned.groupBy { (_, e) -> muscleOf(e.name, e.muscle) }) {
        val sets = (weekly[muscle] ?: 0f).roundToInt()
        // The muscle's main exercise: the one done most in the window.
        val (mainDay, main) = list.maxBy { (_, e) -> sessionMaxes(e.name, window).size }
        val maxes = sessionMaxes(main.name, window).map { it.second }
        if (maxes.size < 3) continue
        val change = maxes.take(2).max() / maxes.takeLast(2).max() - 1f
        if (sets > 20 && change < -0.02f) {
            val (day, e) = list.maxBy { (_, e) -> e.sets }
            if (e.sets >= 3) removes += VolumeChange(day, e.name, muscle, e.sets, e.sets - 1, sets)
        } else if (sets < 10 && change < 0.01f && change > -0.05f && main.sets < 5) {
            adds += VolumeChange(mainDay, main.name, muscle, main.sets, main.sets + 1, sets)
        }
    }
    return removes.maxByOrNull { it.weeklySets } ?: adds.minByOrNull { it.weeklySets }
}

// --- Skips and swaps ---

private fun skipKey(day: String, exercise: String) = "skip|${day.lowercase()}|${exerciseKey(exercise)}"
private fun swapKey(day: String, from: String, to: String) = "swap|${day.lowercase()}|${exerciseKey(from)}|${exerciseKey(to)}"

private fun CompletedWorkout.skipped(name: String) =
    skippedExercises.any { exerciseKey(it) == exerciseKey(name) } || exercises.any { it.isSkipped && exerciseKey(it.name) == exerciseKey(name) }

data class SkippedOften(val day: String, val exercise: String, val muscle: String, val skipped: Int, val of: Int)

/** An exercise skipped in 3 of its day's last 4 workouts, the last one included. */
internal fun skippedOften(days: List<CoachDay>, history: List<CompletedWorkout>, coach: CoachData): SkippedOften? {
    for (day in days) {
        val recent = history.filter { it.splitTitle.equals(day.title, ignoreCase = true) }.take(4)
        if (recent.size < 3) continue
        for (e in day.exercises) {
            if (skipKey(day.title, e.name) in coach.dismissed || !recent[0].skipped(e.name)) continue
            val n = recent.count { it.skipped(e.name) }
            if (n >= 3) return SkippedOften(day.title, e.name, e.muscle, n, recent.size)
        }
    }
    return null
}

data class RepeatedSwap(val day: String, val from: String, val to: String, val muscle: String, val times: Int)

/** The same one-off swap on two or more days, for an exercise still in the day. */
internal fun repeatedSwap(days: List<CoachDay>, coach: CoachData): RepeatedSwap? {
    val groups = coach.swaps.groupBy { Triple(it.day.lowercase(), exerciseKey(it.from), exerciseKey(it.to)) }
    for ((_, records) in groups.entries.sortedByDescending { e -> e.value.maxOf { it.time } }) {
        val r = records.maxBy { it.time }
        val times = records.map { it.time / DAY_MS }.distinct().size
        if (times < 2 || swapKey(r.day, r.from, r.to) in coach.dismissed) continue
        val day = days.find { it.title.equals(r.day, ignoreCase = true) } ?: continue
        val from = day.exercises.find { exerciseKey(it.name) == exerciseKey(r.from) } ?: continue
        if (day.exercises.any { exerciseKey(it.name) == exerciseKey(r.to) }) continue
        return RepeatedSwap(day.title, from.name, r.to, from.muscle, times)
    }
    return null
}

/** Extra points for swap candidates: ones picked in swaps before, minus ones often skipped. */
internal fun swapPreference(name: String, history: List<CompletedWorkout>, coach: CoachData = CoachState.current): Double {
    val key = exerciseKey(name)
    var score = 0.0
    if (coach.swaps.any { exerciseKey(it.to) == key }) score += 1.5
    if (history.take(12).count { it.skipped(name) } >= 2) score -= 2.0
    return score
}

// --- Reps-left check ---

/**
 * Whether [exerciseName] gets today's check-in set: the first main lift of the day that has been
 * rated in 3+ sessions and not checked for 6 weeks, with no check in the last 2 weeks at all.
 */
internal fun checkInDue(
    exerciseName: String,
    dayExercises: List<CoachExercise>,
    history: List<CompletedWorkout>,
    coach: CoachData,
    trackEffort: Boolean,
    now: Long
): Boolean {
    if (!trackEffort || coach.deloadAt(now) != null) return false
    if (coach.checks.any { it.time >= now - 2 * WEEK_MS }) return false
    val due = dayExercises.firstOrNull { e ->
        e.type == ExerciseType.WEIGHT_REPS && !e.programmed && isCompound(e.name) &&
            (e.target == null || e.target.repMax <= 12) &&
            coach.checks.none { exerciseKey(it.exercise) == exerciseKey(e.name) && it.time >= now - 6 * WEEK_MS } &&
            history.count { w -> w.exercises.any { ex -> exerciseKey(ex.name) == exerciseKey(e.name) && ex.workSets.any { it.rir >= 0 } } } >= 3
    }
    return due != null && exerciseKey(due.name) == exerciseKey(exerciseName)
}

/**
 * The check from a finished exercise: the last set (rated 0, so it went all out) against the set
 * before at the same weight, which predicted its reps plus reps left, less about a rep for the
 * fatigue of one more set. Null when it can't be read; then it counts as not done.
 */
internal fun checkInResult(ex: ExerciseEntry, time: Long): RirCheck {
    val notDone = RirCheck(ex.name, time, -1, -1)
    val work = ex.sets.filter { it.isCompleted && !it.isWarmupSet && it.intensityType == "NORMAL" && it.repCount > 0 }
    if (work.size < 2) return notDone
    val last = work.last()
    val before = work[work.size - 2]
    if (last.rir != 0 || before.rir !in 0..3 || abs(last.weightKg - before.weightKg) > 0.01f) return notDone
    val expected = before.repCount + before.rir - 1
    if (expected <= 0) return notDone
    return RirCheck(ex.name, time, last.repCount, expected)
}

object Coach {
    /** After a workout is saved: reads its check-in set, if it had one. */
    fun workoutSaved(context: Context, workout: CompletedWorkout) {
        val checked = workout.exercises.filter { it.checkIn }
        if (checked.isEmpty()) return
        CoachState.update(context) { d ->
            val results = checked.map { checkInResult(it, workout.id) }
            d.copy(checks = (d.checks + results).takeLast(12), unseenCheck = d.unseenCheck || results.any { it.done })
        }
    }

    fun recordSwap(context: Context, day: String, from: String, to: String) =
        CoachState.update(context) { it.copy(swaps = (it.swaps + SwapRecord(day, from, to, System.currentTimeMillis())).takeLast(40)) }

    fun undoSwap(context: Context, day: String, from: String, to: String) =
        CoachState.update(context) { d ->
            val last = d.swaps.lastOrNull { it.day == day && it.from == from && it.to == to } ?: return@update d
            d.copy(swaps = d.swaps - last)
        }

    fun startLighterWeek(context: Context, now: Long = System.currentTimeMillis()) =
        CoachState.update(context) { it.copy(deloads = (it.deloads + listOf(now..now + WEEK_MS)).takeLast(20)) }

    fun endLighterWeek(context: Context, now: Long = System.currentTimeMillis()) =
        CoachState.update(context) { d -> d.copy(deloads = d.deloads.map { if (now in it) it.first..now else it }) }

    fun snoozeLighterWeek(context: Context, now: Long = System.currentTimeMillis()) =
        CoachState.update(context) { it.copy(deloadSnoozedUntil = now + WEEK_MS) }

    fun volumeAnswered(context: Context, now: Long = System.currentTimeMillis()) =
        CoachState.update(context) { it.copy(volumeCheckedAt = now) }

    fun keepSkipped(context: Context, day: String, exercise: String) =
        CoachState.update(context) { it.copy(dismissed = it.dismissed + skipKey(day, exercise)) }

    fun declineSwap(context: Context, day: String, from: String, to: String) =
        CoachState.update(context) { it.copy(dismissed = it.dismissed + swapKey(day, from, to)) }

    fun checkSeen(context: Context) = CoachState.update(context) { it.copy(unseenCheck = false) }

    /** The current program's days with what a new workout would give each exercise. */
    fun days(context: Context, splits: List<WorkoutSplit>): List<CoachDay> = splits.filter { it.title != QUICK_WORKOUT_TITLE }.map { split ->
        CoachDay(split.title, LocalStorage.loadTemplateForSplit(context, split.title).map { (name, muscle) ->
            val target = LocalStorage.getTarget(context, split.title, name)
            CoachExercise(
                name = name,
                muscle = muscle,
                sets = target?.sets ?: prescriptionFor(TrainingProfile.current, name)?.sets ?: 2,
                target = target,
                type = LocalStorage.exerciseType(context, name),
                programmed = liftPlanFor(split.title, name) != null
            )
        })
    }
}

/** What Today's coach card shows: one thing at a time, the most useful first. */
sealed class CoachTip {
    data class CheckResult(val check: RirCheck, val bias: Int) : CoachTip()
    data class LighterWeek(val endsAt: Long) : CoachTip()
    data class DeloadDue(val weeks: Int) : CoachTip()
    data class Skipped(val s: SkippedOften) : CoachTip()
    data class Swapped(val s: RepeatedSwap) : CoachTip()
    data class Volume(val v: VolumeChange) : CoachTip()
}

internal fun coachTip(days: List<CoachDay>, history: List<CompletedWorkout>, coach: CoachData, now: Long): CoachTip? {
    if (coach.unseenCheck) coach.checks.lastOrNull { it.done }?.let { return CoachTip.CheckResult(it, coach.rirBias) }
    coach.deloadAt(now)?.let { return CoachTip.LighterWeek(it.last) }
    deloadDue(history, now, coach)?.let { return CoachTip.DeloadDue(it) }
    skippedOften(days, history, coach)?.let { return CoachTip.Skipped(it) }
    repeatedSwap(days, coach)?.let { return CoachTip.Swapped(it) }
    volumeChange(days, history, now, coach)?.let { return CoachTip.Volume(it) }
    return null
}
