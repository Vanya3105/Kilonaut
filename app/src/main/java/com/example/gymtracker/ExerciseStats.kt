package com.example.gymtracker

import java.util.Locale

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

// ============================================================================
// PER-EXERCISE HISTORY & RECORDS (powers the exercise detail screen)
// ============================================================================

/** Rep counts shown in the "rep records" table (loaded lifts only). */
val REP_RECORD_TARGETS = listOf(1, 3, 5, 8, 10, 12)

/** One workout's worth of an exercise. Only valid work sets count toward the numbers. */
@Immutable
data class ExerciseSession(
    val workoutId: Long,
    val splitTitle: String,
    val displayDate: String,
    /** Every set with numbers, warm-ups included, for the history list. */
    val sets: List<SetEntry>,
    val topWeightKg: Float,
    val estimated1RmKg: Float,
    val volumeKg: Float,
    /** Beat every earlier session's best (never set on the first session). */
    val isRecord: Boolean,
    val type: ExerciseType = ExerciseType.WEIGHT_REPS,
    /** The type's headline number: see [sessionValue]. */
    val value: Float = 0f,
    val maxReps: Int = 0,
    val totalReps: Int = 0,
    val maxSeconds: Int = 0,
    val totalSeconds: Int = 0,
    val distanceM: Float = 0f
)

/** Heaviest weight lifted for at least [reps] reps, and the workout where that first happened. */
@Immutable
data class RepRecord(val reps: Int, val weightKg: Float, val workoutId: Long)

internal val PERCENTAGES = listOf(100, 95, 90, 85, 80, 75, 70, 65, 60)

/** Reps a percentage of the 1-rep max allows, by the same formula as the estimate (Epley). */
internal fun repsAtPercent(pct: Int): Int = if (pct >= 100) 1 else (30f * (100f / pct - 1f)).roundToInt().coerceAtLeast(1)

/** [pct] % of [oneRmKg], rounded down to the unit's plate step (stored in kg). */
internal fun weightAtPercent(oneRmKg: Float, pct: Int, unit: WeightUnit): Float {
    val shown = unit.fromKg(oneRmKg) * pct / 100f
    val step = unit.defaultStep
    return unit.toKg(kotlin.math.floor(shown / step) * step)
}

@Immutable
data class ExerciseHistory(
    val name: String,
    /** Newest first; sessions with no valid work sets are left out. */
    val sessions: List<ExerciseSession>,
    val repRecords: List<RepRecord>,
    val best1RmKg: Float,
    val heaviestKg: Float,
    val type: ExerciseType = ExerciseType.WEIGHT_REPS,
    /** Best [ExerciseSession.value] ever (lowest for assisted). */
    val bestValue: Float = 0f,
    val allTimeReps: Int = 0,
    val allTimeSeconds: Int = 0,
    val allTimeDistanceM: Float = 0f
)

enum class ChartMetric(val label: String) {
    EST_1RM(tr("Est. 1RM")),
    TOP_WEIGHT(tr("Top weight")),
    VOLUME(tr("Volume")),
    MOST_REPS(tr("Most reps")),
    TOTAL_REPS(tr("Total reps")),
    ASSISTANCE(tr("Assistance")),
    LONGEST(tr("Longest")),
    TOTAL_TIME(tr("Total time")),
    DISTANCE(tr("Distance")),
    TIME(tr("Time")),
    PACE(tr("Pace"));

    fun valueOf(session: ExerciseSession): Float = when (this) {
        EST_1RM -> session.estimated1RmKg
        TOP_WEIGHT -> session.topWeightKg
        VOLUME -> session.volumeKg
        MOST_REPS -> session.maxReps.toFloat()
        TOTAL_REPS -> session.totalReps.toFloat()
        ASSISTANCE -> session.value
        LONGEST -> session.maxSeconds.toFloat()
        TOTAL_TIME, TIME -> session.totalSeconds.toFloat()
        DISTANCE -> session.distanceM
        // Seconds per metre; shown per km or mile.
        PACE -> if (session.distanceM > 0f && session.totalSeconds > 0) session.totalSeconds / session.distanceM else 0f
    }

    /** "82.5 kg", "1,250 kg", "15 reps", "−20 kg", "1:30", "5 km", "5:12 /km". */
    fun format(v: Float): String = when (this) {
        EST_1RM, TOP_WEIGHT -> formatWeightKg(v)
        VOLUME -> formatVolume(v.roundToInt())
        MOST_REPS, TOTAL_REPS -> if (v.roundToInt() == 1) tr("1 rep") else tr("{0} reps", v.roundToInt())
        ASSISTANCE -> if (v > 0f) "−${formatWeightKg(v)}" else tr("No help")
        LONGEST, TOTAL_TIME, TIME -> formatClock(v.roundToInt().toLong())
        DISTANCE -> formatDistance(v)
        PACE -> "${formatClock((v * Units.current.metersPerDistanceUnit).roundToInt().toLong())} /${Units.current.distanceLabel}"
    }

    /** Axis label: the number without its unit. */
    fun short(v: Float): String = when (this) {
        EST_1RM, TOP_WEIGHT, ASSISTANCE, VOLUME -> compactNumber(Units.current.fromKg(v))
        MOST_REPS, TOTAL_REPS -> "${v.roundToInt()}"
        LONGEST, TOTAL_TIME, TIME -> formatClock(v.roundToInt().toLong())
        DISTANCE -> formatKgClean(((v / Units.current.metersPerDistanceUnit) * 10).roundToInt() / 10f)
        PACE -> formatClock((v * Units.current.metersPerDistanceUnit).roundToInt().toLong())
    }
}

/** The chart choices for each type; the first is the default. */
fun metricsFor(type: ExerciseType): List<ChartMetric> = when (type) {
    ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT -> listOf(ChartMetric.EST_1RM, ChartMetric.TOP_WEIGHT, ChartMetric.VOLUME)
    ExerciseType.BODYWEIGHT -> listOf(ChartMetric.MOST_REPS, ChartMetric.TOTAL_REPS)
    ExerciseType.ASSISTED -> listOf(ChartMetric.ASSISTANCE, ChartMetric.MOST_REPS)
    ExerciseType.DURATION -> listOf(ChartMetric.LONGEST, ChartMetric.TOTAL_TIME)
    ExerciseType.DISTANCE_TIME -> listOf(ChartMetric.DISTANCE, ChartMetric.TIME, ChartMetric.PACE)
}

enum class ChartRange(val label: String, val days: Int?) {
    THREE_MONTHS("3M", 91),
    YEAR("1Y", 365),
    ALL(tr("All"), null)
}

fun buildExerciseHistory(exerciseName: String, history: List<CompletedWorkout>): ExerciseHistory {
    val key = exerciseKey(exerciseName)
    val chronological = ArrayList<ExerciseSession>()
    val repBest = HashMap<Int, RepRecord>()
    var type = ExerciseType.WEIGHT_REPS
    var best1Rm = 0f
    var bestValue: Float? = null

    for (workout in history.sortedBy { it.id }) {
        // An exercise can appear twice in one workout (added again later); merge its sets.
        val entries = workout.exercises.filter { !it.isSkipped && exerciseKey(it.name) == key }
        if (entries.isEmpty()) continue
        type = entries.first().type
        val workSets = entries.flatMap { it.workSets }
        if (workSets.isEmpty()) continue

        // Rounded once here so records, tiles and the chart all agree with what's shown,
        // and float noise between equal lifts (92.5×8 vs 95×7) never counts as a record.
        val session1Rm = if (type.hasWeightSuggestions) roundToHalf(workSets.maxOf { calculateEstimated1Rm(it.weightKg, it.repCount) }) else 0f
        val value = sessionValue(type, workSets)
        val previousBest = bestValue
        val isRecord = previousBest != null && when {
            type.hasWeightSuggestions -> session1Rm > best1Rm
            type == ExerciseType.ASSISTED -> value < previousBest
            else -> value > previousBest
        }
        chronological += ExerciseSession(
            workoutId = workout.id,
            splitTitle = workout.splitTitle,
            displayDate = workout.displayDate,
            sets = entries.flatMap { e -> e.sets.filter { it.hasNumbers(type) } },
            topWeightKg = workSets.maxOf { it.weightKg },
            estimated1RmKg = session1Rm,
            volumeKg = if (type.countsVolume) workSets.sumOf { (it.weightKg * it.repCount).toDouble() }.toFloat() else 0f,
            isRecord = isRecord,
            type = type,
            value = value,
            maxReps = workSets.maxOf { it.repCount },
            totalReps = workSets.sumOf { it.repCount },
            maxSeconds = workSets.maxOf { it.secondCount },
            totalSeconds = workSets.sumOf { it.secondCount },
            distanceM = workSets.sumOf { it.distanceMeters.toDouble() }.toFloat()
        )
        best1Rm = maxOf(best1Rm, session1Rm)
        bestValue = when {
            previousBest == null -> value
            type == ExerciseType.ASSISTED -> minOf(previousBest, value)
            else -> maxOf(previousBest, value)
        }

        if (type.hasWeightSuggestions) {
            for (target in REP_RECORD_TARGETS) {
                val heaviest = workSets.filter { it.repCount >= target }.maxOfOrNull { it.weightKg } ?: continue
                if (heaviest > (repBest[target]?.weightKg ?: 0f)) repBest[target] = RepRecord(target, heaviest, workout.id)
            }
        }
    }

    return ExerciseHistory(
        name = exerciseName,
        sessions = chronological.asReversed().toList(),
        repRecords = REP_RECORD_TARGETS.mapNotNull { repBest[it] },
        best1RmKg = best1Rm,
        heaviestKg = chronological.maxOfOrNull { it.topWeightKg } ?: 0f,
        type = type,
        bestValue = bestValue ?: 0f,
        allTimeReps = chronological.sumOf { it.totalReps },
        allTimeSeconds = chronological.sumOf { it.totalSeconds },
        allTimeDistanceM = chronological.sumOf { it.distanceM.toDouble() }.toFloat()
    )
}

/**
 * Oldest-first (time, value) points for the chart, limited to [range] counted back from
 * [nowMillis]. Sessions without the numbers a metric needs (pace without a distance) are skipped.
 */
fun ExerciseHistory.chartPoints(metric: ChartMetric, range: ChartRange, nowMillis: Long): List<Pair<Long, Float>> {
    val since = range.days?.let { nowMillis - it * 86_400_000L } ?: Long.MIN_VALUE
    return sessions.asReversed()
        .filter { it.workoutId >= since }
        .map { it.workoutId to metric.valueOf(it) }
        .filter { (_, v) -> metric != ChartMetric.PACE || v > 0f }
}

fun roundToHalf(v: Float): Float = (v * 2f).roundToInt() / 2f

/** "1.2k" style numbers for chart axes. */
fun compactNumber(v: Float): String = when {
    v >= 10_000f -> "${(v / 1000f).roundToInt()}k"
    v >= 1_000f -> "${((v / 100f).roundToInt() / 10f)}k"
    else -> formatKgClean((v * 2f).roundToInt() / 2f)
}

/** Every exercise that has at least one valid work set, most recently trained first. */
fun loggedExerciseNames(history: List<CompletedWorkout>): List<String> {
    val seen = HashSet<String>()
    val names = ArrayList<String>()
    for (w in history.sortedByDescending { it.id }) {
        for (ex in w.exercises) {
            if (ex.isSkipped) continue
            val key = exerciseKey(ex.name)
            // Checking the sets is the costly part; skip it for names already found.
            if (key in seen || ex.workSets.isEmpty()) continue
            seen += key
            names += ex.name
        }
    }
    return names
}

/**
 * History split per exercise, keyed by [ignoreCaseKey] (order kept), so per-exercise scans like
 * [computeExerciseRecords] only walk the workouts that contain the exercise.
 */
/**
 * The sets logged for [name] last time, for the "Previous" column: from [sameDay] (this day's last
 * workout) when it has the exercise, as coaching reads it, else from the latest workout that does.
 * [withExercise] is newest first, as [historyByExercise] gives it. Null if it was never logged.
 */
fun lastSessionSets(name: String, type: ExerciseType, sameDay: CompletedWorkout?, withExercise: List<CompletedWorkout>): List<SetEntry>? {
    fun setsIn(w: CompletedWorkout) = w.exercises
        .find { !it.isSkipped && it.name.equals(name, ignoreCase = true) }
        ?.sets?.filter { it.hasNumbers(type) }?.takeIf { it.isNotEmpty() }
    return sameDay?.let(::setsIn) ?: withExercise.firstNotNullOfOrNull(::setsIn)
}

/**
 * The set from last time that lines up with `sets[index]`: same kind (warm-up, drop, rest-pause,
 * working) and the same place among sets of that kind, so a warm-up is never compared with a work set.
 */
fun previousSetFor(sets: List<SetEntry>, index: Int, previous: List<SetEntry>): SetEntry? {
    fun kind(s: SetEntry) = if (s.isWarmupSet) "WARMUP" else s.intensityType
    val set = sets.getOrNull(index) ?: return null
    val position = (0 until index).count { kind(sets[it]) == kind(set) }
    return previous.filter { kind(it) == kind(set) }.getOrNull(position)
}

fun historyByExercise(history: List<CompletedWorkout>): Map<String, List<CompletedWorkout>> {
    val out = HashMap<String, ArrayList<CompletedWorkout>>()
    for (w in history) {
        w.exercises.mapTo(HashSet()) { ignoreCaseKey(it.name) }.forEach { out.getOrPut(it) { ArrayList() } += w }
    }
    return out
}

/**
 * Two names have the same key exactly when `a.equals(b, ignoreCase = true)`, the match
 * [computeExerciseRecords] uses. Plain `lowercase()` differs for a few letters (Turkish İ).
 */
fun ignoreCaseKey(name: String): String = buildString(name.length) {
    name.forEach { append(Character.toLowerCase(Character.toUpperCase(it))) }
}

/** Words that don't make a different exercise: "Barbell Back Squat" is "Back Squat". */
private val NAME_NOISE = setOf("barbell", "bb", "the")

/** A name reduced to what identifies the exercise: case, hyphens, plurals and "barbell" don't count. */
internal fun sameExerciseKey(name: String): String =
    name.lowercase(Locale.US).replace('-', ' ').replace("'", "")
        .split(' ').filter { it.isNotBlank() && it !in NAME_NOISE }
        .joinToString(" ") { w -> if (w.length >= 3 && w.endsWith('s') && !w.endsWith("ss")) w.dropLast(1) else w }

/**
 * Logged names that look like one exercise under two names ("Back Squat" / "Barbell Back Squat",
 * "Tricep Pushdown" / "Triceps Pushdown", or a library nickname like "RDL"), as pairs. Only
 * suggestions: merging is the user's call, since "Comp Bench Press" may really be its own lift.
 */
fun likelyDuplicates(names: List<String>): List<Pair<String, String>> {
    fun canonical(name: String): String {
        val key = sameExerciseKey(name)
        val library = ExerciseDatabase.all.firstOrNull { e -> sameExerciseKey(e.name) == key || e.aliases.any { sameExerciseKey(it) == key } }
        return library?.let { sameExerciseKey(it.name) } ?: key
    }
    return names.distinctBy(::exerciseKey).groupBy(::canonical).values
        .filter { it.size > 1 }
        .flatMap { group -> group.drop(1).map { group.first() to it } }
}
