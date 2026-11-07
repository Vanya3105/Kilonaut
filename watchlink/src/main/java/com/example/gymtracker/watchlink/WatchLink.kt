package com.example.gymtracker.watchlink

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone → watch: the live workout as one Data Layer item at [STATE_PATH], already formatted by
 * the phone (units, exercise types), so the watch only displays it.
 * Watch → phone: one message per button press at [ACTION_PREFIX] + [WatchAction.name], a
 * [START_WORKOUT] message with the day's title as its data, and a [REQUEST_STATE] message when
 * the watch app opens. The phone answers every action and start with [ACK], so the watch can tell
 * a slow phone from a tap that simply changed nothing.
 */
object WatchLink {
    const val STATE_PATH = "/gymtracker/session"
    const val ACTION_PREFIX = "/gymtracker/action/"
    const val REQUEST_STATE = "/gymtracker/request-state"
    const val ACK = "/gymtracker/ack"
    const val START_WORKOUT = "/gymtracker/start"

    /** Opens the phone app (its MainActivity handles this link), e.g. from the watch's "Open on phone". */
    const val OPEN_PHONE_APP_URI = "gymtracker://open"

    fun actionPath(action: WatchAction) = ACTION_PREFIX + action.name

    fun actionFromPath(path: String): WatchAction? =
        if (path.startsWith(ACTION_PREFIX)) WatchAction.entries.firstOrNull { it.name == path.removePrefix(ACTION_PREFIX) } else null
}

/** The watch's buttons. "Primary" is the first box (weight, reps, time or distance), "secondary" the second. */
enum class WatchAction {
    PRIMARY_MINUS, PRIMARY_PLUS, SECONDARY_MINUS, SECONDARY_PLUS, COMPLETE_SET, ADD_REST, SKIP_REST,
    /** Takes 15 s off the rest (the bezel turned back). */
    REMOVE_REST,
    /** Un-ticks the last finished set, e.g. after "Set done" by mistake. */
    UNDO_SET,
    /** Saves the workout to history, like Finish → Save on the phone. */
    FINISH_WORKOUT,
    /** One more set for [WatchState.addSetTo], a copy of its last set. */
    ADD_SET
}

/** What kind of set is next, so the watch can name it in its own language. */
enum class SetKind { WORK, WARMUP, DROP }

/** One row of the watch's workout overview: [done] of [total] sets ticked. */
data class WatchExercise(val name: String, val done: Int, val total: Int)

/** The workout just saved, for the watch's "Saved" screen. [volume] is formatted; null for no volume. */
data class WatchFinished(
    val workoutName: String,
    val finishedAtMillis: Long,
    val durationSeconds: Long,
    val sets: Int,
    val volume: String?,
    val newBests: Int
)

data class WatchState(
    /** False when no workout is running on the phone. */
    val active: Boolean,
    val workoutName: String = "",
    /** Null once every set is done. */
    val exerciseName: String? = null,
    /** "Set 2", "Warm-up", "Drop set". */
    val setLabel: String = "",
    val primary: String = "",
    val primaryLabel: String = "",
    val secondary: String? = null,
    val secondaryLabel: String? = null,
    /** The set as text, for the rest screen: "80 kg × 8". */
    val summary: String = "",
    val setsLeft: Int = 0,
    /** Wall-clock time the rest ends (watch and phone clocks are synced); 0 when not resting. */
    val restEndsAtMillis: Long = 0L,
    /** Rest length when it started, for the progress ring. */
    val restTotalMillis: Long = 0L,
    /** When the workout started (wall clock), for the elapsed time. */
    val startedAtMillis: Long = 0L,
    /** The workout's exercises (skipped ones left out), in order. */
    val exercises: List<WatchExercise> = emptyList(),
    /** Index in [exercises] of the exercise [exerciseName] belongs to; -1 once every set is done. */
    val currentExercise: Int = -1,
    /** "Set 2 of 4" within the current exercise; 0 when unknown. */
    val setNumber: Int = 0,
    val setCount: Int = 0,
    /** True when a finished set can be un-ticked. */
    val canUndo: Boolean = false,
    /** No workout: the active program's days, the one that's up next first. */
    val days: List<String> = emptyList(),
    /** No workout: the last workout, when it was saved recently. */
    val lastWorkout: WatchFinished? = null,
    /**
     * The phone app's language ("en", "he"); the watch follows it. Names (workout, exercises,
     * days) come already in it; [days] stay as they are stored, for starting one.
     */
    val language: String = "en",
    /** [days] as shown, in [language]; empty from an older phone app. */
    val dayLabels: List<String> = emptyList(),
    val setKind: SetKind = SetKind.WORK,
    /** "Set 2 of 3" counting work sets only (warm-ups have no number); 0 when unknown. */
    val workSetNumber: Int = 0,
    val workSetCount: Int = 0,
    /** The exercise "Add set" adds to (the one ticked last); null from an older phone app or with nothing to add to. */
    val addSetTo: String? = null
) {
    /** How [days] reads on screen. */
    fun dayLabel(i: Int): String = dayLabels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: days[i]

    fun toJson(): String = JSONObject().apply {
        put("v", 2)
        put("active", active)
        put("workout", workoutName)
        exerciseName?.let { put("exercise", it) }
        put("setLabel", setLabel)
        put("primary", primary)
        put("primaryLabel", primaryLabel)
        secondary?.let { put("secondary", it) }
        secondaryLabel?.let { put("secondaryLabel", it) }
        put("summary", summary)
        put("setsLeft", setsLeft)
        put("restEndsAt", restEndsAtMillis)
        put("restTotal", restTotalMillis)
        put("startedAt", startedAtMillis)
        put("exercises", JSONArray().apply {
            exercises.forEach { put(JSONObject().put("name", it.name).put("done", it.done).put("total", it.total)) }
        })
        put("currentExercise", currentExercise)
        put("setNumber", setNumber)
        put("setCount", setCount)
        put("canUndo", canUndo)
        put("days", JSONArray(days))
        put("lang", language)
        put("dayLabels", JSONArray(dayLabels))
        put("setKind", setKind.name)
        put("workSet", workSetNumber)
        put("workSets", workSetCount)
        addSetTo?.let { put("addSetTo", it) }
        lastWorkout?.let { w ->
            put("last", JSONObject().apply {
                put("workout", w.workoutName)
                put("finishedAt", w.finishedAtMillis)
                put("duration", w.durationSeconds)
                put("sets", w.sets)
                w.volume?.let { put("volume", it) }
                put("newBests", w.newBests)
            })
        }
    }.toString()

    companion object {
        /** Unknown fields are ignored and missing ones get defaults, so either app can be updated first. */
        fun fromJson(json: String): WatchState? = try {
            val o = JSONObject(json)
            WatchState(
                active = o.optBoolean("active", false),
                workoutName = o.optString("workout"),
                exerciseName = if (o.has("exercise")) o.getString("exercise") else null,
                setLabel = o.optString("setLabel"),
                primary = o.optString("primary"),
                primaryLabel = o.optString("primaryLabel"),
                secondary = if (o.has("secondary")) o.getString("secondary") else null,
                secondaryLabel = if (o.has("secondaryLabel")) o.getString("secondaryLabel") else null,
                summary = o.optString("summary"),
                setsLeft = o.optInt("setsLeft"),
                restEndsAtMillis = o.optLong("restEndsAt"),
                restTotalMillis = o.optLong("restTotal"),
                startedAtMillis = o.optLong("startedAt"),
                exercises = o.optJSONArray("exercises").objects().map {
                    WatchExercise(it.optString("name"), it.optInt("done"), it.optInt("total"))
                },
                currentExercise = o.optInt("currentExercise", -1),
                setNumber = o.optInt("setNumber"),
                setCount = o.optInt("setCount"),
                canUndo = o.optBoolean("canUndo"),
                days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                language = o.optString("lang", "en").ifBlank { "en" },
                dayLabels = o.optJSONArray("dayLabels")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                setKind = SetKind.entries.find { it.name == o.optString("setKind") } ?: SetKind.WORK,
                workSetNumber = o.optInt("workSet"),
                workSetCount = o.optInt("workSets"),
                addSetTo = if (o.has("addSetTo")) o.getString("addSetTo") else null,
                lastWorkout = o.optJSONObject("last")?.let { w ->
                    WatchFinished(
                        workoutName = w.optString("workout"),
                        finishedAtMillis = w.optLong("finishedAt"),
                        durationSeconds = w.optLong("duration"),
                        sets = w.optInt("sets"),
                        volume = if (w.has("volume")) w.getString("volume") else null,
                        newBests = w.optInt("newBests")
                    )
                }
            )
        } catch (e: Exception) {
            null
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
