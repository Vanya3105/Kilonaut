package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapAndTimeTest {
    @Test
    fun movement_fromTheName() {
        assertEquals("squat", movementOf("Leg Press"))
        assertEquals("squat", movementOf("Hack Squat"))
        assertEquals("press", movementOf("Barbell Bench Press"))
        assertEquals("fly", movementOf("Pec Deck"))
        assertEquals("vertical pull", movementOf("Lat Pulldown"))
        assertEquals("hinge", movementOf("Romanian Deadlift"))
        assertTrue(isIsolation("Lateral Raise"))
        assertFalse(isIsolation("Back Squat"))
    }

    @Test
    fun swap_sameMuscleAndMovement_notWhatsInTheWorkout() {
        val legPress = ExerciseEntry("Leg Press", targetMuscle = "Quads", sets = listOf(SetEntry(1, "100", "10")))
        val picks = swapCandidates(legPress, listOf("Leg Press", "Hack Squat"), emptyList(), Equipment.GYM)
        assertEquals(3, picks.size)
        assertTrue(picks.all { it.muscle == "Quads" })
        assertTrue("already in the workout", picks.none { it.name == "Hack Squat" })
        assertEquals("a squat pattern first", "squat", movementOf(picks.first().name))
    }

    @Test
    fun swap_followsTheEquipment() {
        val bench = ExerciseEntry("Barbell Bench Press", targetMuscle = "Chest", sets = listOf(SetEntry(1, "80", "8")))
        val home = swapCandidates(bench, listOf("Barbell Bench Press"), emptyList(), Equipment.DUMBBELLS)
        assertTrue(home.isNotEmpty())
        assertTrue(home.all { it.equipment in setOf("Dumbbell", "Bodyweight", "None") })
    }

    @Test
    fun swap_prefersWhatYouveDone() {
        val bench = ExerciseEntry("Barbell Bench Press", targetMuscle = "Chest", sets = listOf(SetEntry(1, "80", "8")))
        val done = CompletedWorkout(1L, "Push", "2026-11-01", "", 3000, 0f, null, emptyList(), exercises = listOf(ExerciseEntry("Smith Machine Bench Press", sets = listOf(SetEntry(1, "70", "8", isCompleted = true)))))
        assertEquals("Smith Machine Bench Press", swapCandidates(bench, listOf("Barbell Bench Press"), listOf(done), Equipment.GYM).first().name)
    }

    private fun ex(name: String, work: Int, warmups: Int = 0, rest: Int = 120) =
        ExerciseEntry(name, restSeconds = rest, sets = List(warmups) { SetEntry(it + 1, "40", "8", isWarmup = true, intensityType = "WARMUP", restSeconds = 60) } +
            List(work) { SetEntry(warmups + it + 1, "80", "8", restSeconds = rest) })

    private val push = listOf(
        ex("Barbell Bench Press", 4, warmups = 3), ex("Overhead Press", 3, warmups = 2),
        ex("Incline Dumbbell Press", 3), ex("Cable Fly", 3), ex("Lateral Raise", 3), ex("Triceps Pushdown", 3)
    )

    @Test
    fun estimate_isAboutRight() {
        val m = estimateMinutes(push)
        assertTrue("a six-exercise push day is about an hour: $m", m in 50..80)
    }

    @Test
    fun trim_fitsTheTime_keepsTheMainLifts_andSaysWhatChanged() {
        val (short, cuts) = trimToMinutes(push, 30)
        assertTrue(estimateSeconds(short) <= 30 * 60)
        assertEquals("Barbell Bench Press", short[0].name)
        assertEquals("Overhead Press", short[1].name)
        assertTrue(cuts.isNotEmpty())
        // Isolation goes before the incline press.
        val dropped = cuts.filterIsInstance<Cut.Dropped>().map { it.name }
        if ("Incline Dumbbell Press" in dropped) assertTrue("Lateral Raise" in dropped && "Cable Fly" in dropped)
    }

    @Test
    fun trim_leavesAShortDayAlone() {
        val (same, cuts) = trimToMinutes(push, 200)
        assertEquals(push, same)
        assertTrue(cuts.isEmpty())
    }
}
