package com.example.gymtracker

import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// ============================================================================
// WEIGHT UNITS
// ============================================================================
// Everything is stored in kilograms. The unit only changes how weights are shown and typed,
// so switching back and forth never alters logged data, records or charts.

enum class WeightUnit(
    private val englishLabel: String,
    private val hebrewLabel: String,
    private val kgPerUnit: Double,
    /** Display rounding: kg keeps 0.01 (1.25 kg plates), lb keeps 0.1. */
    private val decimals: Int,
    val defaultStep: Float,
    val stepPresets: List<Float>,
    /** Step for body-weight steppers. */
    val bodyStep: Float,
    /** Starting weight for an exercise with no history. */
    val defaultWorkWeight: Float,
    /** Distances go with the weight unit: kilometres for kg, miles for lb. */
    private val englishDistance: String,
    private val hebrewDistance: String,
    val metersPerDistanceUnit: Float
) {
    KG("kg", "ק״ג", 1.0, 2, 2.5f, listOf(0.5f, 1f, 1.25f, 2f, 2.5f, 5f), 0.5f, 40f, "km", "ק״מ", 1000f),
    LB("lb", "ליב׳", 0.45359237, 1, 5f, listOf(1f, 2.5f, 5f, 10f), 1f, 95f, "mi", "מייל", 1609.344f);

    // Hebrew units keep "2 ק״ג" in order in a right-to-left line, where "2 kg" would show as "kg 2".
    val label: String get() = if (hebrewOn()) hebrewLabel else englishLabel
    val distanceLabel: String get() = if (hebrewOn()) hebrewDistance else englishDistance

    /** Kilograms → this unit, rounded the way it is displayed. */
    fun fromKg(kg: Float): Float = round((kg / kgPerUnit).toFloat())

    /** Rounds a value in this unit to its display precision. */
    fun round(value: Float): Float {
        val f = if (decimals == 2) 100f else 10f
        return (value * f).roundToInt() / f
    }

    /** This unit → kilograms, unrounded. */
    fun toKg(value: Float): Float = (value * kgPerUnit).toFloat()

    /**
     * The kg string stored for a value in this unit. Pounds keep 4 decimals of kg so the value
     * reads back exactly (135 lb → "61.235" → 135 lb).
     */
    fun stored(value: Float): String =
        if (this == KG) formatKgClean(value)
        else String.format(Locale.US, "%.4f", toKg(value).coerceAtLeast(0f)).trimEnd('0').trimEnd('.')

    /** A kg value as a bare number in this unit: "62.5", "135". */
    fun number(kg: Float): String = formatKgClean(fromKg(kg))

    companion object {
        fun fromKey(key: String?): WeightUnit = entries.firstOrNull { it.name == key } ?: KG
    }
}

/**
 * The unit the app currently shows. Snapshot state, so any composable that formats a weight
 * redraws when it changes. Updated whenever settings are loaded or saved, which also covers
 * widgets and notifications.
 */
object Units {
    private val state = mutableStateOf(WeightUnit.KG)
    val current: WeightUnit get() = state.value

    fun set(unit: WeightUnit) {
        if (state.value != unit) state.value = unit
    }
}

/** "2 left", "4+ left", "to failure": a set's reps in reserve. */
fun effortText(rir: Int): String = when {
    rir <= 0 -> tr("to failure")
    rir >= 4 -> tr("4+ left")
    else -> tr("{0} left", rir)
}

/** "62.5 kg" / "135 lb". */
fun formatWeightKg(kg: Float): String = "${Units.current.number(kg)} ${Units.current.label}"

/** Bare number in the current unit, for places that show the unit once (column headers, charts). */
fun formatWeightNumber(kg: Float): String = Units.current.number(kg)

/** "5 km" / "3.1 mi", up to two decimals. */
fun formatDistance(meters: Float): String =
    "${formatKgClean(meters / Units.current.metersPerDistanceUnit)} ${Units.current.distanceLabel}"

/** A set as words for its type: "80 kg × 6", "12 reps", "+20 kg × 8", "1:30", "5 km · 25:00". */
fun formatSet(set: SetEntry, type: ExerciseType = ExerciseType.WEIGHT_REPS): String = when (type) {
    ExerciseType.WEIGHT_REPS -> "${formatWeightKg(set.weightKg)} × ${set.reps}"
    ExerciseType.BODYWEIGHT -> if (set.repCount == 1) tr("1 rep") else tr("{0} reps", set.repCount)
    // Nothing added / no assistance is simply a bodyweight set.
    ExerciseType.WEIGHTED_BODYWEIGHT -> if (set.weightKg > 0f) "+${formatWeightKg(set.weightKg)} × ${set.reps}" else formatSet(set, ExerciseType.BODYWEIGHT)
    ExerciseType.ASSISTED -> if (set.weightKg > 0f) "−${formatWeightKg(set.weightKg)} × ${set.reps}" else formatSet(set, ExerciseType.BODYWEIGHT)
    ExerciseType.DURATION -> formatClock(set.secondCount.toLong())
    ExerciseType.DISTANCE_TIME -> listOfNotNull(
        set.distanceMeters.takeIf { it > 0f }?.let(::formatDistance),
        set.secondCount.takeIf { it > 0 }?.let { formatClock(it.toLong()) }
    ).joinToString(" · ")
}

/** Compact form for tight spots ("last time" lines): "80×6", "12", "+20×8", "1:30". */
fun formatSetShort(set: SetEntry, type: ExerciseType): String = when (type) {
    ExerciseType.WEIGHT_REPS -> "${formatWeightNumber(set.weightKg)}×${set.reps}"
    ExerciseType.BODYWEIGHT -> "${set.repCount}"
    ExerciseType.WEIGHTED_BODYWEIGHT -> if (set.weightKg > 0f) "+${formatWeightNumber(set.weightKg)}×${set.reps}" else set.reps
    ExerciseType.ASSISTED -> if (set.weightKg > 0f) "−${formatWeightNumber(set.weightKg)}×${set.reps}" else set.reps
    ExerciseType.DURATION, ExerciseType.DISTANCE_TIME -> formatSet(set, type)
}

fun formatVolume(kg: Int): String {
    val v = Units.current.fromKg(kg.toFloat()).roundToInt()
    val unit = Units.current.label
    return if (v >= 10_000) String.format(Locale.US, "%.1fk %s", v / 1000f, unit) else String.format(Locale.US, "%,d %s", v, unit)
}

/**
 * A stored kg string moved one step (the +/− buttons), never below [minKg]. A value off the step
 * grid (e.g. 88.2 lb after switching units) snaps to the next multiple instead: 88.2 → 90 → 95.
 */
fun stepWeight(storedKg: String, delta: Float, fallbackKg: Float = 20f, minKg: Float = 0f): String {
    val u = Units.current
    val value = u.fromKg(parseWeightClean(storedKg, fallbackKg))
    val step = abs(delta)
    val steps = value / step
    val onGrid = abs(steps - steps.roundToInt()) < 0.01f
    val next = when {
        step == 0f -> value
        onGrid -> value + delta
        delta > 0 -> ceil(steps) * step
        else -> floor(steps) * step
    }
    return u.stored(u.round(next).coerceAtLeast(u.fromKg(minKg)))
}

/**
 * A stored kg string scaled by [factor] (e.g. 0.9 for "go lighter"), with a floor of one
 * default step so it never reaches zero.
 */
fun scaleWeight(storedKg: String, factor: Float, fallbackKg: Float = 20f): String {
    val u = Units.current
    val scaled = u.round(u.fromKg(parseWeightClean(storedKg, fallbackKg)) * factor)
    return u.stored(scaled.coerceAtLeast(u.defaultStep))
}
