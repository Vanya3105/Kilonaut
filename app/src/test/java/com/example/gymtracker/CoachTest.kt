package com.example.gymtracker

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoachTest {

    @Before
    fun setUp() {
        Units.set(WeightUnit.KG)
        CoachState.set(CoachData())
    }

    @After
    fun tearDown() = CoachState.set(CoachData())

    private val day = 24L * 60 * 60 * 1000
    private val now = 400 * day

    private fun set(weight: String, reps: Int, rir: Int = -1) =
        SetEntry(setNumber = 1, weight = weight, reps = "$reps", isCompleted = true, rir = rir)

    private fun ex(name: String, vararg s: SetEntry, skipped: Boolean = false) = ExerciseEntry(name, "Chest", sets = s.toList(), isSkipped = skipped)

    private fun workout(daysAgo: Double, split: String, vararg exercises: ExerciseEntry, skipped: List<String> = emptyList()) = CompletedWorkout(
        id = now - (daysAgo * day).toLong(), splitTitle = split, dateKey = "", displayDate = "", durationSeconds = 0,
        bodyWeightKg = 80f, photoPath = null, skippedExercises = skipped, exercises = exercises.toList()
    )

    /** Two workouts a week for [weeks] weeks, newest first, bench at [weight] each time. */
    private fun twiceAWeek(weeks: Int, weight: (Int) -> String = { "60" }) = (0 until weeks * 2).map { i ->
        workout(1 + i * 3.5, "Push", ex("Bench Press", set(weight(i), 8), set(weight(i), 8), set(weight(i), 8)))
    }

    private fun day(title: String, vararg names: String, sets: Int = 3) =
        CoachDay(title, names.map { CoachExercise(it, "Chest", sets, null) })

    // --- Lighter weeks ---

    @Test
    fun sixHardWeeks_aLighterWeekIsDue() = assertEquals(6, deloadDue(twiceAWeek(6), now, CoachData()))

    @Test
    fun fiveHardWeeksAndProgressing_notYet() =
        assertNull(deloadDue(twiceAWeek(5) { i -> "${80 - i * 2}" }, now, CoachData()))

    @Test
    fun fourHardWeeksWithStalls_dueEarly() {
        // Bench and squat both flat for the last four sessions.
        val history = (0 until 8).map { i ->
            workout(1 + i * 3.5, "Full", ex("Bench Press", set("60", 8)), ex("Back Squat", set("100", 5)))
        }
        assertEquals(4, deloadDue(history, now, CoachData()))
    }

    @Test
    fun weeksCountFromTheLastLighterWeek() {
        val coach = CoachData(deloads = listOf((now - 16 * day)..(now - 9 * day)))
        assertEquals(1, hardWeeks(twiceAWeek(8), now, coach))
        assertNull(deloadDue(twiceAWeek(8), now, coach))
    }

    @Test
    fun notNow_waitsAWeek() = assertNull(deloadDue(twiceAWeek(7), now, CoachData(deloadSnoozedUntil = now + day)))

    @Test
    fun lighterWeek_halfTheSetsAndLighter() {
        val entry = ExerciseEntry(
            "Bench Press",
            sets = listOf(SetEntry(1, "50", "8", isWarmup = true, intensityType = "WARMUP")) + List(3) { SetEntry(it + 2, "100", "8") }
        )
        val light = lighterWeekVersion(entry, 2.5f)
        assertEquals(1, light.sets.count { it.isWarmupSet })
        assertEquals(listOf("90"), light.sets.filter { !it.isWarmupSet }.map { it.weight }.distinct())
        assertEquals(2, light.sets.count { !it.isWarmupSet })
        assertTrue(light.programmed)
        // A percentage program's lift has its own plan.
        assertEquals(entry.copy(programmed = true), lighterWeekVersion(entry.copy(programmed = true), 2.5f))
    }

    @Test
    fun suggestionsBuildOnTheWeeksBeforeTheLighterOne() {
        val history = listOf(
            workout(2.0, "Push", ex("Bench Press", set("90", 10), set("90", 10))),
            workout(9.0, "Push", ex("Bench Press", set("100", 8), set("100", 8)))
        )
        CoachState.set(CoachData(deloads = listOf((now - 4 * day)..(now - 1 * day))))
        assertEquals("100", suggestFromSplitHistory("Push", ExerciseEntry("Bench Press"), history, 2.5f, now)!!.suggestedWeight)
    }

    // --- Weekly sets ---

    @Test
    fun stalledMuscleUnderTenSets_getsASet() {
        val change = volumeChange(listOf(day("Push", "Bench Press")), twiceAWeek(5), now, CoachData())
        assertNotNull(change)
        assertEquals("Bench Press", change!!.exercise)
        assertEquals(3, change.fromSets)
        assertEquals(4, change.toSets)
        assertEquals(6, change.weeklySets)
    }

    @Test
    fun progressingMuscle_noChange() =
        assertNull(volumeChange(listOf(day("Push", "Bench Press")), twiceAWeek(5) { i -> "${80 - i * 2}" }, now, CoachData()))

    @Test
    fun onlyEveryFourWeeks() =
        assertNull(volumeChange(listOf(day("Push", "Bench Press")), twiceAWeek(5), now, CoachData(volumeCheckedAt = now - 10 * day)))

    @Test
    fun tooManySetsAndSlipping_losesASet() {
        // 6 sets twice a week of bench and 6 of incline: 24 chest sets a week, numbers going down,
        // over more than the 4 weeks a check needs.
        val history = (0 until 9).map { i ->
            val w = "${60 + i * 2}"
            workout(1 + i * 3.5, "Push", ex("Bench Press", *Array(6) { set(w, 8) }), ex("Incline Dumbbell Press", *Array(6) { set("24", 10) }))
        }
        val days = listOf(CoachDay("Push", listOf(CoachExercise("Bench Press", "Chest", 6, null), CoachExercise("Incline Dumbbell Press", "Chest", 6, null))))
        val change = volumeChange(days, history, now, CoachData())
        assertNotNull(change)
        assertEquals(5, change!!.toSets)
    }

    // --- Skips and swaps ---

    @Test
    fun skippedThreeOfFour_offersASwap() {
        val history = listOf(
            workout(1.0, "Push", ex("Bench Press", set("60", 8)), skipped = listOf("Cable Fly")),
            workout(4.0, "Push", ex("Bench Press", set("60", 8)), ex("Cable Fly", set("20", 12))),
            workout(8.0, "Push", ex("Bench Press", set("60", 8)), skipped = listOf("Cable Fly")),
            workout(11.0, "Push", ex("Bench Press", set("60", 8)), ex("Cable Fly", skipped = true))
        )
        val days = listOf(day("Push", "Bench Press", "Cable Fly"))
        val s = skippedOften(days, history, CoachData())
        assertEquals("Cable Fly", s!!.exercise)
        assertEquals(3, s.skipped)
        assertNull(skippedOften(days, history, CoachData(dismissed = setOf("skip|push|cable fly"))))
        // Done last time: maybe it's back in favour.
        assertNull(skippedOften(days, history.drop(1), CoachData()))
    }

    @Test
    fun sameSwapOnTwoDays_offersToKeepIt() {
        val days = listOf(day("Push", "Barbell Bench Press"))
        val once = SwapRecord("Push", "Barbell Bench Press", "Dumbbell Bench Press", now - 3 * day)
        val twice = once.copy(time = now - day)
        assertNotNull(repeatedSwap(days, CoachData(swaps = listOf(once, twice))))
        assertNull(repeatedSwap(days, CoachData(swaps = listOf(twice, twice.copy(time = twice.time + 1000)))))
        assertNull(repeatedSwap(days, CoachData(swaps = listOf(once, twice), dismissed = setOf("swap|push|barbell bench press|dumbbell bench press"))))
    }

    @Test
    fun swapsAndSkipsShapeTheAlternatives() {
        val coach = CoachData(swaps = listOf(SwapRecord("Push", "Barbell Bench Press", "Machine Chest Press", now)))
        assertTrue(swapPreference("Machine Chest Press", emptyList(), coach) > 0)
        val skips = listOf(workout(1.0, "Push", skipped = listOf("Cable Fly")), workout(4.0, "Push", skipped = listOf("Cable Fly")))
        assertTrue(swapPreference("Cable Fly", skips, coach) < 0)
    }

    // --- Reps-left check ---

    private fun rated(daysAgo: Double) = workout(daysAgo, "Push", ex("Bench Press", set("60", 8, rir = 2), set("60", 8, rir = 1)))

    @Test
    fun checkIn_firstMainLiftRatedThreeTimes() {
        val history = listOf(rated(2.0), rated(5.0), rated(9.0))
        val today = listOf(CoachExercise("Bench Press", "Chest", 3, null), CoachExercise("Overhead Press", "Shoulders", 3, null))
        assertTrue(checkInDue("Bench Press", today, history, CoachData(), true, now))
        assertFalse(checkInDue("Overhead Press", today, history, CoachData(), true, now))
        assertFalse(checkInDue("Bench Press", today, history, CoachData(), false, now))
        assertFalse(checkInDue("Bench Press", today, history.take(2), CoachData(), true, now))
        val recent = CoachData(checks = listOf(RirCheck("Squat", now - 5 * day, 8, 7)))
        assertFalse(checkInDue("Bench Press", today, history, recent, true, now))
    }

    @Test
    fun checkInResult_comparesWithTheSetBefore() {
        val r = checkInResult(ex("Bench Press", set("60", 8, rir = 2), set("60", 11, rir = 0)), now)
        assertTrue(r.done)
        assertEquals(9, r.expected) // 8 + 2 left, a rep less for one more set
        assertEquals(11, r.reps)
        // Not taken to failure: nothing to learn.
        assertFalse(checkInResult(ex("Bench Press", set("60", 8, rir = 2), set("60", 9, rir = 1)), now).done)
    }

    @Test
    fun checks_correctTheRatings() {
        val coach = CoachData(checks = listOf(RirCheck("Bench Press", now, 11, 9), RirCheck("Back Squat", now, 8, 6)))
        assertEquals(2, coach.rirBias)
        assertEquals(3, set("60", 8, rir = 1).reserve(coach.rirBias))
        assertEquals(4, set("60", 8, rir = 4).reserve(coach.rirBias))
        assertEquals(-1, set("60", 8).reserve(coach.rirBias))
        assertEquals(0, CoachData(checks = listOf(RirCheck("Bench Press", now, -1, -1))).rirBias)
    }

    @Test
    fun stateSurvivesSaving() {
        val d = CoachData(
            deloads = listOf(1L..2L), deloadSnoozedUntil = 3, volumeCheckedAt = 4, dismissed = setOf("a"),
            swaps = listOf(SwapRecord("Push", "A", "B", 5)), checks = listOf(RirCheck("Bench Press", 6, 10, 9)), unseenCheck = true
        )
        assertEquals(d, CoachState.decode(CoachState.encode(d)))
        assertEquals(CoachData(), CoachState.decode("not json"))
    }
}
