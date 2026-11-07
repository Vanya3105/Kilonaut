package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class CsvExportTest {
    private val start = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-11-07 18:00")!!.time

    private val workout = CompletedWorkout(
        start, "Push, Day 1", "", "", 3720, 80f, null, emptyList(),
        listOf(
            ExerciseEntry("Barbell Bench Press", sets = listOf(
                SetEntry(1, "40", "10", isWarmup = true, intensityType = "WARMUP", isCompleted = true),
                SetEntry(2, "80", "8", isCompleted = true),
                SetEntry(3, "82.5", "6", isCompleted = true)
            )),
            ExerciseEntry("Plank", type = ExerciseType.DURATION, sets = listOf(SetEntry(1, "", "", seconds = "60", isCompleted = true)))
        )
    )

    @Test
    fun writesStrongsLayout_withWarmupsQuotesAndNotes() {
        val csv = workoutsCsv(listOf(workout)) { "Felt \"great\", shoulder ok" }.lines()
        assertEquals("Date,Workout Name,Duration,Exercise Name,Set Order,Weight (kg),Reps,Distance (meters),Seconds,Notes,Workout Notes", csv[0])
        assertEquals("2026-11-07 18:00:00,\"Push, Day 1\",1h 2m,Barbell Bench Press,W,40,10,,,,\"Felt \"\"great\"\", shoulder ok\"", csv[1])
        assertTrue(csv[3].contains(",2,82.5,6,"))
        assertTrue(csv[4].contains("Plank,1,,,,60,"))
    }

    @Test
    fun kilonautsOwnImport_readsItBack() {
        val back = parseWorkoutExport(workoutsCsv(listOf(workout)), WeightUnit.KG, listOf("Barbell Bench Press", "Plank"), 80f)!!
        val w = back.workouts.single()
        assertEquals(start, w.id)
        assertEquals(3720L, w.durationSeconds)
        val bench = w.exercises.first { it.name == "Barbell Bench Press" }
        assertEquals(listOf(40f, 80f, 82.5f), bench.sets.map { it.weightKg })
        assertTrue(bench.sets.first().isWarmupSet)
    }
}
