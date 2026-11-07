package com.example.gymtracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every workout as a CSV in Strong's export layout, one row per set: it opens in any spreadsheet,
 * Strong-compatible apps can bring it in, and Kilonaut's own import reads it back. Weights in kg,
 * distances in meters, so the file means the same whatever unit the phone shows.
 */
internal fun workoutsCsv(history: List<CompletedWorkout>, noteOf: (Long) -> String = { "" }): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    fun cell(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    fun number(v: Float) = if (v == 0f) "" else formatKgClean(v)
    fun duration(seconds: Long) = when {
        seconds <= 0 -> ""
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 60}m"
    }
    val out = StringBuilder()
    out.append(listOf("Date", "Workout Name", "Duration", "Exercise Name", "Set Order", "Weight (kg)", "Reps", "Distance (meters)", "Seconds", "Notes", "Workout Notes").joinToString(",")).append('\n')
    // Oldest first, as the exports from other apps.
    history.sortedBy { it.id }.forEach { w ->
        val note = noteOf(w.id)
        w.exercises.filter { !it.isSkipped }.forEach { ex ->
            var workNumber = 0
            ex.sets.forEach { s ->
                val order = when {
                    s.isWarmupSet -> "W"
                    s.intensityType == "DROP" -> "D"
                    else -> "${++workNumber}"
                }
                out.append(
                    listOf(
                        date.format(Date(w.id)),
                        cell(w.splitTitle),
                        duration(w.durationSeconds),
                        cell(ex.name),
                        order,
                        number(s.weightKg),
                        if (s.repCount > 0) "${s.repCount}" else "",
                        number(s.distanceM.toFloatOrNull() ?: 0f),
                        if (s.secondCount > 0) "${s.secondCount}" else "",
                        "",
                        cell(note)
                    ).joinToString(",")
                ).append('\n')
            }
        }
    }
    return out.toString()
}
