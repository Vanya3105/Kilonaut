package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a workout post carries, and post times. */
@RunWith(RobolectricTestRunner::class)
class FeedTest {
    private val day = 24 * 60 * 60 * 1000L

    private fun workout(id: Long, kg: String, note: String = "") = CompletedWorkout(
        id, "Push", "", "", 3720, 81.3f, "/photos/private.jpg", emptyList(),
        listOf(
            ExerciseEntry("Bench Press", "Chest", sets = listOf(
                SetEntry(1, "20", "10", isWarmup = true, isCompleted = true),
                SetEntry(2, kg, "8", isCompleted = true), SetEntry(3, kg, "7", isCompleted = true)
            )),
            ExerciseEntry("Plank", "Core", type = ExerciseType.DURATION, sets = listOf(SetEntry(1, "", "", isCompleted = true, seconds = "75"))),
            ExerciseEntry("Cable Fly", "Chest", sets = listOf(SetEntry(1, "15", "12", isCompleted = false)))
        )
    )

    @Test
    fun workoutPostRoundTrips() {
        val before = listOf(workout(1_000_000L, "80"))
        val today = workout(1_000_000L + 7 * day, "85")
        val json = workoutPostJson(today, before).toString()
        assertFalse("body weight stays private", json.contains("81.3"))
        assertFalse("photo path stays private", json.contains("private.jpg"))

        val w = parseWorkoutPost(json)!!
        assertEquals("Push", w.title)
        assertEquals(3720L, w.seconds)
        assertEquals(1, w.bests)
        // Only exercises with done sets; warm-ups don't count.
        assertEquals(listOf("Bench Press", "Plank"), w.exercises.map { it.name })
        val bench = w.exercises.first()
        assertEquals(2, bench.sets)
        assertTrue(bench.isBest)
        assertTrue(formatSet(bench.best!!, bench.type), formatSet(bench.best!!, bench.type).contains("85"))
        assertEquals(ExerciseType.DURATION, w.exercises[1].type)
    }

    @Test
    fun postAges() {
        val now = System.currentTimeMillis()
        assertEquals("now", postAge(now - 20_000, now))
        assertEquals("5m", postAge(now - 5 * 60_000, now))
    }
}
