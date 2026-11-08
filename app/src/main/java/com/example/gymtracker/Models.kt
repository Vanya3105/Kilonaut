package com.example.gymtracker

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

@Immutable
data class UserProfile(
    val hasCompletedOnboarding: Boolean = false,
    val age: Int = 0,
    val currentWeightKg: Float = 0f,
    val splitProgram: String = "PPL_ARNOLD",
    // Setup answers for recommendations (Recommendations.kt); null / empty when never answered.
    val experience: Experience? = null,
    val goal: TrainingGoal? = null,
    val equipment: Equipment? = null,
    val daysPerWeek: Int = 0,
    val limits: Set<Limit> = emptySet(),
    /** Muscles to prioritise; null when not answered (balanced). */
    val focus: Focus? = null,
    /** How long a workout can be, in minutes; 0 when not answered. */
    val sessionMinutes: Int = 0,
    /** Muscle groups (as in [BUILDER_MUSCLES]) the user doesn't want to train. */
    val skipMuscles: Set<String> = emptySet(),
    /** The training questions were shown once to someone set up before they existed. */
    val trainingAsked: Boolean = false
)

@Immutable
data class WeightLogEntry(
    val id: Long,
    val dateKey: String,
    val displayDate: String,
    val weightKg: Float
)

@Immutable
data class AppSettings(
    val themeChoice: String = THEME_SYSTEM,
    val targetFps: Int = 120,
    val hapticsEnabled: Boolean = true,
    /** +/− step for set weights, in [weightUnit]. */
    val weightStep: Float = 2.5f,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val restTimerSeconds: Int = 90,
    val keepScreenAwake: Boolean = true,
    val restSoundEnabled: Boolean = true,
    /** Ask how many reps were left after each work set. */
    val trackEffort: Boolean = false,
    /** Workout reminder days (Calendar.SUNDAY…); none = reminders off. */
    val reminderDays: Set<Int> = emptySet(),
    /** Reminder time, minutes after midnight. */
    val reminderMinutes: Int = 18 * 60,
    /** A reminder every morning to weigh in. */
    val weighReminderOn: Boolean = false,
    val weighReminderMinutes: Int = 7 * 60 + 30
)

/**
 * A workout started empty from Today, outside the program. Its exercises are today's only: adding,
 * removing or reordering them doesn't change any day's exercise list.
 */
const val QUICK_WORKOUT_TITLE = "Quick workout"

/** Nothing changed in a running workout for this long: it was probably forgotten. */
const val FORGOTTEN_WORKOUT_MS = 60L * 60 * 1000

const val THEME_SYSTEM = "SYSTEM"
const val THEME_LIGHT = "LIGHT"
const val THEME_DARK = "DARK"

@Immutable
data class CompletedWorkout(
    val id: Long,
    val splitTitle: String,
    val dateKey: String,
    val displayDate: String,
    val durationSeconds: Long,
    val bodyWeightKg: Float,
    val photoPath: String?,
    val skippedExercises: List<String>,
    val exercises: List<ExerciseEntry>,
    val totalVolumeKg: Int = 0,
    val prsHit: Int = 0
)

@Immutable
data class ExerciseEntry(
    val name: String,
    val targetMuscle: String = "",
    val restSeconds: Int = 90,
    val isSkipped: Boolean = false,
    val sets: List<SetEntry> = emptyList(),
    val isSuperset: Boolean = false,
    /** Copied from the exercise's definition when loaded; not stored per workout. */
    val type: ExerciseType = ExerciseType.WEIGHT_REPS,
    /** The day's plan for this exercise, if one is set; guides set count, reps and coaching. */
    val target: ExerciseTarget? = null,
    /** A program's word on this exercise today, e.g. "5/3/1 · Week 2 of 4 (3s) · TM 100 kg". */
    val planNote: String? = null,
    /** Sets are laid out by a percentage program, so weight suggestions stay out of the way. */
    val programmed: Boolean = false,
    /** Today's reps-left check: the last set goes as far as good form allows ([checkInDue]). */
    val checkIn: Boolean = false
)

/** A planned "sets × rep range" for an exercise on one day of a program. */
@Immutable
data class ExerciseTarget(val sets: Int, val repMin: Int, val repMax: Int) {
    val repText: String get() = if (repMin == repMax) "$repMin" else "$repMin–$repMax"
    val label: String get() = "$sets × $repText"
}

/** What a set of this exercise records. Belongs to the exercise, not to each workout. */
enum class ExerciseType(val label: String, val example: String) {
    WEIGHT_REPS(tr("Weight & reps"), tr("Barbell, dumbbell, machine, cable")),
    BODYWEIGHT(tr("Bodyweight reps"), tr("Push-ups, pull-ups, sit-ups")),
    WEIGHTED_BODYWEIGHT(tr("Weighted bodyweight"), tr("Dips or pull-ups with a belt: log the added weight")),
    ASSISTED(tr("Assisted bodyweight"), tr("Band or machine help: log the assistance")),
    DURATION(tr("Time"), tr("Planks, hangs, holds")),
    DISTANCE_TIME(tr("Distance & time"), tr("Running, rowing, cycling"));

    val usesWeight: Boolean get() = this == WEIGHT_REPS || this == WEIGHTED_BODYWEIGHT || this == ASSISTED
    val usesReps: Boolean get() = usesWeight || this == BODYWEIGHT
    val usesTime: Boolean get() = this == DURATION || this == DISTANCE_TIME
    val usesDistance: Boolean get() = this == DISTANCE_TIME

    /** Weight suggestions ("go up to 62.5 kg") apply where more weight means harder. */
    val hasWeightSuggestions: Boolean get() = this == WEIGHT_REPS || this == WEIGHTED_BODYWEIGHT

    /** Volume is weight × reps; for weighted bodyweight only the added weight counts. */
    val countsVolume: Boolean get() = this == WEIGHT_REPS || this == WEIGHTED_BODYWEIGHT

    companion object {
        fun fromKey(key: String?): ExerciseType = entries.firstOrNull { it.name == key } ?: WEIGHT_REPS
    }
}

@Immutable
data class SetEntry(
    val setNumber: Int,
    val weight: String,
    val reps: String,
    val isWarmup: Boolean = false,
    val intensityType: String = "NORMAL", // NORMAL, WARMUP, DROP, REST_PAUSE
    val isCompleted: Boolean = false,
    val restSeconds: Int = 90,
    /** Time-based sets: seconds as typed digits ("" when unused). */
    val seconds: String = "",
    /** Distance sets: metres ("" when unused). */
    val distanceM: String = "",
    /** Reps left in the tank when it was done (0…4, 4 = four or more); -1 when not recorded. */
    val rir: Int = -1
) {
    // Parsed once per set rather than on every read: records, stats, suggestions and charts read
    // these for every set in history. Body properties, so they stay out of equals() and copy().
    val weightKg: Float = parseWeightClean(weight, 0f)
    val repCount: Int = parseRepsClean(reps, 0)
    val secondCount: Int = seconds.filter(Char::isDigit).toIntOrNull() ?: 0
    val distanceMeters: Float = distanceM.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
}

val SetEntry.isWarmupSet: Boolean get() = isWarmup || intensityType == "WARMUP"

/** Non-warmup set with real numbers for [type] — the unit every stat and suggestion counts. */
fun SetEntry.isValidWorkSet(type: ExerciseType): Boolean = !isWarmupSet && hasNumbers(type)

/** Has the numbers [type] needs, warm-up or not. */
fun SetEntry.hasNumbers(type: ExerciseType): Boolean = when (type) {
    ExerciseType.WEIGHT_REPS -> weightKg > 0f && repCount > 0
    // Weighted with nothing added is still a bodyweight set; assistance can be zero too.
    ExerciseType.BODYWEIGHT, ExerciseType.WEIGHTED_BODYWEIGHT, ExerciseType.ASSISTED -> repCount > 0
    ExerciseType.DURATION -> secondCount > 0
    ExerciseType.DISTANCE_TIME -> distanceMeters > 0f || secondCount > 0
}

val ExerciseEntry.workSets: List<SetEntry> get() = sets.filter { it.isValidWorkSet(type) }

/**
 * The set to do next: the first unfinished one after the last finished set, so a skipped
 * warm-up doesn't hold things back; otherwise the first unfinished one. -1 when all are done.
 * The workout screen, widget, watch and rest alert all use this, so they agree.
 */
fun ExerciseEntry.nextSetIndex(): Int {
    val after = sets.indexOfLast { it.isCompleted } + 1
    return (after until sets.size).firstOrNull { !sets[it].isCompleted } ?: sets.indexOfFirst { !it.isCompleted }
}

@Immutable
data class WorkoutStats(
    val workSets: Int,
    val volumeKg: Int,
    val prCount: Int
)

/**
 * What a finished workout keeps: the ticked sets of each exercise that wasn't skipped. Sets come
 * pre-filled with suggestions, so an unticked one wasn't done. With nothing ticked at all every set
 * is kept, so a workout logged without ticking still saves.
 */
fun performedExercises(exercises: List<ExerciseEntry>): List<ExerciseEntry> {
    val kept = exercises.filter { !it.isSkipped }
    if (kept.none { ex -> ex.sets.any { it.isCompleted } }) return kept
    fun ticked(warmUpsCount: Boolean) = kept.mapNotNull { ex ->
        val done = ex.sets.filter { it.isCompleted }
        if (done.none { warmUpsCount || !it.isWarmupSet }) null else ex.copy(sets = done.mapIndexed { i, set -> set.copy(setNumber = i + 1) })
    }
    // Only a warm-up ticked: the exercise wasn't really done (History would show "0 ×" for it),
    // unless warm-ups are all there is, which still makes a workout.
    return ticked(warmUpsCount = false).ifEmpty { ticked(warmUpsCount = true) }
}

/** Reps of all work sets: the total to show instead of volume when nothing was lifted with weight. */
fun totalWorkReps(exercises: List<ExerciseEntry>): Int =
    exercises.filter { !it.isSkipped }.sumOf { ex -> ex.sets.filter { it.isValidWorkSet(ex.type) }.sumOf { it.repCount } }

fun computeWorkoutStats(exercises: List<ExerciseEntry>, history: List<CompletedWorkout>): WorkoutStats {
    var workSets = 0
    var volume = 0f
    var prs = 0
    for (ex in exercises) {
        if (ex.isSkipped) continue
        val records = computeExerciseRecords(ex.name, history)
        for (s in ex.sets) {
            if (s.isValidWorkSet(ex.type) || (ex.type == ExerciseType.WEIGHT_REPS && !s.isWarmupSet && s.repCount > 0)) {
                workSets++
                if (ex.type.countsVolume) volume += s.weightKg * s.repCount
            }
        }
        // New bests count exercises, as the celebration and share images list them: three sets past
        // the old best are one new best, not three.
        if (ex.sets.any { isSetPersonalRecord(it, records, ex.type) }) prs++
    }
    return WorkoutStats(workSets, volume.roundToInt(), prs)
}

@Immutable
data class WorkoutSplit(
    val title: String,
    val subtitle: String,
    val code: String
)

@Immutable
data class SplitProgramInfo(
    val id: String,
    val name: String,
    val tagline: String,
    val weeklyGoal: Int,
    val splits: List<WorkoutSplit>
)

@Immutable
data class WeekSummaryData(
    val weekOffset: Int,
    val titleLabel: String,
    val dateRangeLabel: String,
    val workouts: List<CompletedWorkout>,
    val completedCoreSplits: Set<String>,
    val recommendedSplit: String,
    val weeklyGoal: Int
)

enum class SuggestionTone { GOOD, CAUTION, NEUTRAL }

/** A weight recommendation shown on an exercise card, in plain words. */
@Immutable
data class Suggestion(
    val message: String,
    val tone: SuggestionTone,
    val suggestedWeight: String,
    /** Advice for the next session (every set is done), so there's nothing to fill in today. */
    val forNextTime: Boolean = false
)
