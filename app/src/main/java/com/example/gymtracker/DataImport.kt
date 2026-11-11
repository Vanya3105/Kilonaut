package com.example.gymtracker

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// COMBINING DATA (a backup, or another app's export, added to what's here)
// ============================================================================

/** What adding a file to the current data did, for the message afterwards. */
data class CombineResult(val addedWorkouts: Int, val alreadyHere: Int, val addedWeighIns: Int, val addedPrograms: Int)

/**
 * [current] plus whatever [incoming] has that [current] doesn't. Workouts and weigh-ins are the
 * same entry when their ids (start times) match; the copy already here is kept as it is, so
 * adding the same file twice changes nothing. For everything else (notes, rest, types, day lists,
 * programs, targets, training maxes) [current] wins and [incoming] only fills gaps.
 */
fun combineData(current: StoreData, incoming: StoreData): Pair<StoreData, CombineResult> {
    val haveWorkouts = current.history.mapTo(HashSet()) { it.id }
    val newWorkouts = incoming.history.filter { it.id !in haveWorkouts }.distinctBy { it.id }
    val haveWeights = current.weights.mapTo(HashSet()) { it.id }
    val newWeights = incoming.weights.filter { it.id !in haveWeights }.distinctBy { it.id }
    val havePrograms = current.programs.mapTo(HashSet()) { it.id }
    val newPrograms = incoming.programs.filter { it.id !in havePrograms }

    val types = if (current.types == null && incoming.types == null) null else incoming.types.orEmpty() + current.types.orEmpty()
    val combined = current.copy(
        history = (current.history + newWorkouts).sortedByDescending { it.id },
        weights = (current.weights + newWeights).sortedByDescending { it.id },
        templates = incoming.templates + current.templates,
        notes = incoming.notes + current.notes,
        restDefaults = incoming.restDefaults + current.restDefaults,
        types = types,
        programs = current.programs + newPrograms,
        targets = incoming.targets + current.targets,
        trainingMaxes = incoming.trainingMaxes + current.trainingMaxes
    )
    return combined to CombineResult(
        addedWorkouts = newWorkouts.size,
        alreadyHere = incoming.history.size - newWorkouts.size,
        addedWeighIns = newWeights.size,
        addedPrograms = newPrograms.size
    )
}

// ============================================================================
// OTHER APPS' EXPORTS (Strong, Hevy)
// ============================================================================

enum class ImportSource(val label: String) { STRONG("Strong"), HEVY("Hevy") }

/** A parsed export, before it's added: workouts plus how each exercise is logged. */
data class ImportedHistory(
    val source: ImportSource,
    val workouts: List<CompletedWorkout>,
    /** [exerciseKey] → type, worked out from the numbers each exercise has. */
    val types: Map<String, ExerciseType>,
    /** Names in the file that were given another name here: file name → name used. */
    val renamed: Map<String, String>,
    /** Strong files don't say whether weights are kg or lb (unless an old export says per row). */
    val unitInFile: Boolean,
    val setCount: Int
) {
    fun asStoreData() = StoreData(history = workouts, types = types)
}

/**
 * Reads a Strong or Hevy CSV export. [assumedUnit] is used where the file doesn't say (Strong);
 * distances go with it (km for kg, miles for lb). Names are matched to [knownNames] (what's
 * logged here) and then to the library, so "Squat (Barbell)" joins an existing "Back Squat".
 * Returns null when the file isn't one of these exports.
 */
fun parseWorkoutExport(text: String, assumedUnit: WeightUnit, knownNames: Collection<String>, bodyWeightKg: Float): ImportedHistory? {
    val rows = parseCsv(text)
    if (rows.size < 2) return null
    val header = rows.first().map { it.trim().lowercase(Locale.US) }
    fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1
    val source = when {
        "exercise_title" in header -> ImportSource.HEVY
        "exercise name" in header && "set order" in header -> ImportSource.STRONG
        else -> return null
    }
    val namer = ExerciseNamer(knownNames)
    val body = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }

    val sets = ArrayList<RawSet>()
    var unitInFile = false
    when (source) {
        ImportSource.STRONG -> {
            val date = col("date"); val title = col("workout name"); val duration = col("duration")
            val name = col("exercise name"); val order = col("set order"); val reps = col("reps")
            val seconds = col("seconds"); val rpe = col("rpe")
            val weight = col("weight", "weight (kg)", "weight (lbs)", "weight (lb)")
            val weightUnitCol = col("weight unit")
            val distance = col("distance", "distance (km)", "distance (meters)", "distance (mi)", "distance (miles)")
            val distanceUnitCol = col("distance unit")
            val headerUnit = when {
                "weight (kg)" in header -> WeightUnit.KG
                "weight (lbs)" in header || "weight (lb)" in header -> WeightUnit.LB
                else -> null
            }
            unitInFile = headerUnit != null || weightUnitCol >= 0
            for (r in body) {
                val orderText = r.at(order).trim()
                if (orderText.equals("Rest Timer", ignoreCase = true)) continue
                val start = parseExportDate(r.at(date)) ?: continue
                val rowUnit = r.at(weightUnitCol).lowercase(Locale.US).let { u ->
                    when { u.startsWith("kg") -> WeightUnit.KG; u.startsWith("lb") -> WeightUnit.LB; else -> null }
                } ?: headerUnit ?: assumedUnit
                val meters = r.at(distance).toFloatOrNull()?.let { d ->
                    val unit = r.at(distanceUnitCol).lowercase(Locale.US)
                    when {
                        "(meters)" in header.getOrElse(distance) { "" } || unit == "m" -> d
                        "(km)" in header.getOrElse(distance) { "" } || unit == "km" -> d * 1000f
                        "(mi" in header.getOrElse(distance) { "" } || unit.startsWith("mi") -> d * 1609.344f
                        else -> d * rowUnit.metersPerDistanceUnit
                    }
                } ?: 0f
                sets += RawSet(
                    start = start,
                    end = null,
                    durationSeconds = parseStrongDuration(r.at(duration)),
                    title = r.at(title).ifBlank { "Workout" },
                    exercise = r.at(name).trim(),
                    kind = when (orderText.uppercase(Locale.US)) { "W" -> "WARMUP"; "D" -> "DROP"; else -> "NORMAL" },
                    weight = r.at(weight).toFloatOrNull() ?: 0f,
                    unit = rowUnit,
                    reps = r.at(reps).toFloatOrNull()?.roundToInt() ?: 0,
                    seconds = r.at(seconds).toFloatOrNull()?.roundToInt() ?: 0,
                    meters = meters,
                    rpe = r.at(rpe)
                )
            }
        }
        ImportSource.HEVY -> {
            unitInFile = true
            val title = col("title"); val start = col("start_time"); val end = col("end_time")
            val name = col("exercise_title"); val type = col("set_type"); val reps = col("reps")
            val kg = col("weight_kg"); val lbs = col("weight_lbs"); val km = col("distance_km"); val miles = col("distance_miles")
            val seconds = col("duration_seconds"); val rpe = col("rpe")
            for (r in body) {
                val begin = parseExportDate(r.at(start)) ?: continue
                val kgValue = r.at(kg).toFloatOrNull()
                val lbValue = r.at(lbs).toFloatOrNull()
                sets += RawSet(
                    start = begin,
                    end = parseExportDate(r.at(end)),
                    durationSeconds = null,
                    title = r.at(title).ifBlank { "Workout" },
                    exercise = r.at(name).trim(),
                    kind = when (r.at(type).trim().lowercase(Locale.US)) { "warmup", "2" -> "WARMUP"; "dropset", "3" -> "DROP"; else -> "NORMAL" },
                    weight = kgValue ?: lbValue ?: 0f,
                    unit = if (kgValue != null || lbs < 0) WeightUnit.KG else WeightUnit.LB,
                    reps = r.at(reps).toFloatOrNull()?.roundToInt() ?: 0,
                    seconds = r.at(seconds).toFloatOrNull()?.roundToInt() ?: 0,
                    meters = r.at(km).toFloatOrNull()?.times(1000f) ?: r.at(miles).toFloatOrNull()?.times(1609.344f) ?: 0f,
                    rpe = r.at(rpe)
                )
            }
        }
    }
    if (sets.isEmpty()) return null

    // How each exercise is logged, from all its sets in the file.
    val byName = sets.filter { it.exercise.isNotBlank() }.groupBy { it.exercise }
    val types = HashMap<String, ExerciseType>()
    val renamed = LinkedHashMap<String, String>()
    val names = HashMap<String, String>()
    for ((fileName, its) in byName) {
        val used = namer.nameFor(fileName)
        names[fileName] = used
        if (used != fileName) renamed[fileName] = used
        types[exerciseKey(used)] = inferType(its, ExerciseDatabase.find(used)?.type)
    }

    val workouts = sets.filter { it.exercise.isNotBlank() }
        .groupBy { it.start to it.title }
        .entries.sortedBy { it.key.first }
        .map { (key, its) ->
            val (start, title) = key
            val exercises = LinkedHashMap<String, MutableList<SetEntry>>()
            for (s in its) {
                val used = names.getValue(s.exercise)
                val type = types.getValue(exerciseKey(used))
                exercises.getOrPut(used) { ArrayList() } += s.toSetEntry(type)
            }
            val entries = exercises.map { (n, list) ->
                ExerciseEntry(
                    name = n,
                    targetMuscle = ExerciseDatabase.find(n)?.muscle.orEmpty(),
                    sets = list.mapIndexed { i, s -> s.copy(setNumber = i + 1) },
                    type = types.getValue(exerciseKey(n))
                )
            }
            val endMillis = its.firstNotNullOfOrNull { it.end }
            val seconds = its.firstNotNullOfOrNull { it.durationSeconds }
                ?: endMillis?.let { ((it - start) / 1000L).coerceAtLeast(0L) } ?: 0L
            val day = Date(start)
            CompletedWorkout(
                id = start,
                splitTitle = title.trim(),
                dateKey = DateUtils.formatDayKey(day),
                displayDate = DateUtils.formatDisplayDate(day),
                durationSeconds = seconds,
                bodyWeightKg = bodyWeightKg,
                photoPath = null,
                skippedExercises = emptyList(),
                exercises = entries
            )
        }
        // Two workouts starting the same second under different names still need their own ids.
        .let { list ->
            val used = HashSet<Long>()
            list.map { w ->
                var id = w.id
                while (!used.add(id)) id++
                if (id == w.id) w else w.copy(id = id)
            }
        }

    // Volume and new bests, judged like the app's own workouts (against what came before).
    val finished = ArrayList<CompletedWorkout>()
    for (w in workouts) finished += buildCompletedWorkout(w, w.exercises, finished.asReversed())
    return ImportedHistory(source, finished.sortedByDescending { it.id }, types, renamed, unitInFile, sets.size)
}

private data class RawSet(
    val start: Long,
    val end: Long?,
    val durationSeconds: Long?,
    val title: String,
    val exercise: String,
    val kind: String,
    val weight: Float,
    val unit: WeightUnit,
    val reps: Int,
    val seconds: Int,
    val meters: Float,
    val rpe: String
) {
    fun toSetEntry(type: ExerciseType) = SetEntry(
        setNumber = 0,
        weight = if (type.usesWeight || weight > 0f) unit.stored(weight) else "",
        reps = if (type.usesReps || reps > 0) "$reps" else "",
        isWarmup = kind == "WARMUP",
        intensityType = kind,
        isCompleted = true,
        seconds = if (seconds > 0) "$seconds" else "",
        distanceM = if (meters > 0f) String.format(Locale.US, "%.1f", meters).trimEnd('0').trimEnd('.') else ""
    )
}

/**
 * How an exercise is logged, from its numbers: distance → distance & time; time without reps →
 * time; reps with no weight ever → bodyweight (or the library's own bodyweight kind); else
 * weight & reps (the library's kind when it's a weighted bodyweight one).
 */
private fun inferType(sets: List<RawSet>, library: ExerciseType?): ExerciseType = when {
    sets.any { it.meters > 0f } -> ExerciseType.DISTANCE_TIME
    sets.any { it.seconds > 0 } && sets.none { it.reps > 0 } -> ExerciseType.DURATION
    sets.none { it.weight > 0f } && sets.any { it.reps > 0 } ->
        if (library == ExerciseType.BODYWEIGHT || library == ExerciseType.ASSISTED || library == ExerciseType.WEIGHTED_BODYWEIGHT) library else ExerciseType.BODYWEIGHT
    library == ExerciseType.WEIGHTED_BODYWEIGHT || library == ExerciseType.ASSISTED -> library
    else -> ExerciseType.WEIGHT_REPS
}

/**
 * Turns another app's exercise names into this app's: "Bench Press (Barbell)" → "Barbell Bench
 * Press", "Squat (Barbell)" → whatever squat is already logged here, else the library's. Names
 * with no match stay as the file spells them (they can be merged later).
 */
private class ExerciseNamer(known: Collection<String>) {
    private val knownByKey = known.associateBy { sameExerciseKey(it) }
    private val cache = HashMap<String, String>()

    fun nameFor(fileName: String): String = cache.getOrPut(fileName) {
        val m = Regex("""^(.*?)\s*\(([^)]+)\)\s*$""").find(fileName)
        val base = m?.groupValues?.get(1)?.trim() ?: fileName.trim()
        val equipment = m?.groupValues?.get(2)?.trim().orEmpty()
        val candidates = buildList {
            add(fileName.trim())
            if (equipment.isNotEmpty()) {
                when (equipment.lowercase(Locale.US)) {
                    "bodyweight" -> add(base)
                    "assisted" -> add("Assisted $base")
                    "weighted" -> add("Weighted $base")
                    else -> { add("$equipment $base"); add("$base $equipment") }
                }
            }
        }
        for (c in candidates) knownByKey[sameExerciseKey(c)]?.let { return@getOrPut it }
        // The name without its equipment only counts when the equipment agrees: "Incline Bench Press
        // (Dumbbell)" isn't the library's Incline Barbell Bench Press ("barbell" is left out of keys).
        knownByKey[sameExerciseKey(base)]?.takeIf { fits(ExerciseDatabase.find(it), equipment) }?.let { return@getOrPut it }
        for (c in candidates) library(c)?.let { return@getOrPut knownByKey[sameExerciseKey(it.name)] ?: it.name }
        library(base)?.takeIf { fits(it, equipment) }?.let { return@getOrPut knownByKey[sameExerciseKey(it.name)] ?: it.name }
        // Same words in another order, the equipment among them: "Incline Bench Press (Dumbbell)" →
        // Incline Dumbbell Press, "Bicep Curl (Dumbbell)" → Dumbbell Curl. The fullest match wins.
        val words = sameExerciseKey("$base $equipment").split(' ').toSet()
        val first = sameExerciseKey(base).substringBefore(' ')
        ExerciseDatabase.all
            .filter { fits(it, equipment) }
            .map { it to sameExerciseKey(it.name).split(' ') }
            .filter { (_, w) -> w.size >= 2 && words.containsAll(w) }
            // On a tie, the one keeping the name's first word: "Incline …" is Incline Dumbbell Press, not Dumbbell Bench Press.
            .maxWithOrNull(compareBy({ (_, w) -> w.size }, { (_, w) -> first in w }))
            ?.let { (lib, _) -> return@getOrPut knownByKey[sameExerciseKey(lib.name)] ?: lib.name }
        fileName.trim()
    }

    private fun library(name: String): ExerciseInfo? {
        val key = sameExerciseKey(name)
        return ExerciseDatabase.all.firstOrNull { e -> sameExerciseKey(e.name) == key || e.aliases.any { sameExerciseKey(it) == key } }
    }

    /** Whether a library exercise can be the one done with the file's [equipment] ("" when it didn't say). */
    private fun fits(lib: ExerciseInfo?, equipment: String): Boolean {
        if (lib == null || equipment.isEmpty()) return true
        val e = equipment.lowercase(Locale.US)
        val l = lib.equipment.lowercase(Locale.US)
        if (e in setOf("bodyweight", "assisted", "weighted", "other", "none") || "other" in e) return true
        // Strong's "Cable - Straight Bar" or "Smith Machine" still name the library's Cable / Machine.
        return e.contains(l) || l.contains(e) || (l == "ez bar" && "ez" in e)
    }
}

private fun List<String>.at(i: Int): String = if (i in indices) this[i] else ""

/** "2h 38m", "45m", "3h", "1h 8m 20s" → seconds; null if there's nothing usable. */
internal fun parseStrongDuration(text: String): Long? {
    val parts = Regex("""(\d+)\s*([hms])""").findAll(text.lowercase(Locale.US)).toList()
    if (parts.isEmpty()) return text.trim().toLongOrNull()
    return parts.sumOf { p ->
        val n = p.groupValues[1].toLong()
        when (p.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
    }
}

/**
 * The date formats these exports use: Strong "2020-12-30 18:51:52", Hevy "15 Jul 2026, 09:52"
 * (month in Hevy's language, so the phone's language is tried too) and ISO timestamps.
 */
internal fun parseExportDate(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss" to Locale.US, "yyyy-MM-dd HH:mm" to Locale.US,
        "yyyy-MM-dd'T'HH:mm:ssXXX" to Locale.US, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" to Locale.US, "yyyy-MM-dd'T'HH:mm:ss" to Locale.US,
        "d MMM yyyy, HH:mm" to Locale.US, "d MMM yyyy, HH:mm" to Locale.getDefault(),
        "d MMM yyyy HH:mm" to Locale.US, "MMM d, yyyy, h:mm a" to Locale.US, "yyyy-MM-dd" to Locale.US
    )
    for ((pattern, locale) in patterns) {
        try {
            val fmt = SimpleDateFormat(pattern, locale).apply { isLenient = false }
            val pos = java.text.ParsePosition(0)
            val d = fmt.parse(t, pos) ?: continue
            if (pos.index == t.length) return d.time
        } catch (_: ParseException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    return null
}

/** RFC 4180 CSV (also ';'-separated, as older Strong exports are): rows of fields, quotes undone. */
internal fun parseCsv(text: String): List<List<String>> {
    val clean = text.removePrefix("\uFEFF")
    val firstLine = clean.substringBefore('\n')
    val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < clean.length) {
        val c = clean[i]
        when {
            quoted && c == '"' && i + 1 < clean.length && clean[i + 1] == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            !quoted && c == delimiter -> { row += field.toString(); field.setLength(0) }
            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < clean.length && clean[i + 1] == '\n') i++
                row += field.toString(); field.setLength(0)
                rows += row; row = ArrayList()
            }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
    return rows
}
