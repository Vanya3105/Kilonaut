package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoachingTest {

    @Before
    fun kg() = Units.set(WeightUnit.KG)

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun set(weight: String, reps: String, done: Boolean = true, type: String = "NORMAL") =
        SetEntry(setNumber = 1, weight = weight, reps = reps, isCompleted = done, intensityType = type)

    private fun sets(weight: String, vararg reps: Int) = reps.map { set(weight, "$it") }

    /** A workout [daysAgo] days before [now]. */
    private fun workout(daysAgo: Int, split: String, vararg exercises: ExerciseEntry) = CompletedWorkout(
        id = now - daysAgo * day, splitTitle = split, dateKey = "", displayDate = "", durationSeconds = 0,
        bodyWeightKg = 80f, photoPath = null, skippedExercises = emptyList(), exercises = exercises.toList()
    )

    private fun bench(vararg s: SetEntry, target: ExerciseTarget? = null) = ExerciseEntry("Bench Press", sets = s.toList(), target = target)

    /** Sessions of [name] on Push, the first one [gap] days ago and each earlier one [gap] days before that. */
    private fun history(name: String, vararg sessions: List<SetEntry>, gap: Int = 3) =
        sessions.mapIndexed { i, s -> workout((i + 1) * gap, "Push", ExerciseEntry(name, sets = s)) }

    private fun next(history: List<CompletedWorkout>, ex: ExerciseEntry = bench(), step: Float = 2.5f, split: String = "Push") =
        suggestFromSplitHistory(split, ex, history, step, now)!!

    // --- Double progression ---

    @Test
    fun noHistory_noPlan() = assertNull(suggestFromSplitHistory("Push", bench(), emptyList(), 2.5f, now))

    @Test
    fun everySetAtTheTop_movesUpOneStep() {
        val advice = next(history("Bench Press", sets("60", 10, 10, 10)))
        assertEquals(SuggestionTone.GOOD, advice.tone)
        assertEquals("62.5", advice.suggestedWeight)
    }

    @Test
    fun onlyTheFirstSetAtTheTop_holds() {
        val advice = next(history("Bench Press", sets("60", 10, 9, 8)))
        assertEquals(SuggestionTone.NEUTRAL, advice.tone)
        assertEquals("60", advice.suggestedWeight)
    }

    @Test
    fun inRange_holdsAndAsksForARep() {
        val advice = next(history("Bench Press", sets("60", 8, 7, 7)))
        assertEquals("60", advice.suggestedWeight)
        assertTrue(advice.message, advice.message.contains("add a rep"))
        assertTrue(advice.message, advice.message.contains("8, 7, 7"))
    }

    @Test
    fun beatingTheTopByTwo_jumpsFurtherButAtMostTenPercent() {
        val advice = next(history("Back Squat", sets("100", 13, 13, 13)), ExerciseEntry("Back Squat"))
        assertEquals("110", advice.suggestedWeight)
        assertTrue(advice.message, advice.message.contains("Jump"))
    }

    @Test
    fun backOffAndDropSets_dontCountAsMisses() {
        val advice = next(history("Bench Press", sets("60", 10, 10) + set("40", "12", type = "DROP")))
        assertEquals("62.5", advice.suggestedWeight)
    }

    @Test
    fun lightDumbbell_earnsTheBigJumpWithExtraReps() {
        val raise = ExerciseEntry("Lateral Raise")
        // 2.5 kg on 10 kg is 25%: hold until the top is beaten by two.
        val hold = next(history("Lateral Raise", sets("10", 12, 12, 12)), raise)
        assertEquals(SuggestionTone.NEUTRAL, hold.tone)
        assertEquals("10", hold.suggestedWeight)
        assertTrue(hold.message, hold.message.contains("14 reps"))
        assertEquals("12.5", next(history("Lateral Raise", sets("10", 14, 14, 14)), raise).suggestedWeight)
    }

    @Test
    fun weightedBodyweight_countsBodyWeightInTheJump() {
        val pullUp = ExerciseEntry("Weighted Pull-Up", type = ExerciseType.WEIGHTED_BODYWEIGHT)
        // +2.5 kg is 25% of the added 10 kg but under 3% of the 90 kg actually lifted.
        val advice = next(history("Weighted Pull-Up", sets("10", 10, 10, 10)), pullUp)
        assertEquals("12.5", advice.suggestedWeight)
    }

    @Test
    fun targetRange_setsTheThresholds() {
        val target = ExerciseTarget(3, 8, 12)
        assertEquals(SuggestionTone.NEUTRAL, next(history("Bench Press", sets("60", 10, 10, 10)), bench(target = target)).tone)
        val up = next(history("Bench Press", sets("60", 12, 12, 12)), bench(target = target))
        assertEquals("62.5", up.suggestedWeight)
        assertTrue(up.message, up.message.contains("12+"))
        assertEquals("3 × 8–12", target.label)
        assertEquals("3 × 5", ExerciseTarget(3, 5, 5).label)
    }

    @Test
    fun habitualSetsOfFive_areStrengthWorkNotMisses() {
        val squat = ExerciseEntry("Back Squat")
        val advice = next(history("Back Squat", sets("100", 5, 5, 5), sets("97.5", 5, 5, 5)), squat)
        assertEquals(SuggestionTone.GOOD, advice.tone)
        assertEquals("102.5", advice.suggestedWeight)
    }

    // --- Going down ---

    @Test
    fun justUnderTheRangeOnce_holds() {
        val advice = next(history("Bench Press", sets("60", 5, 5, 4), sets("57.5", 8, 8, 7)))
        assertEquals(SuggestionTone.CAUTION, advice.tone)
        assertEquals("60", advice.suggestedWeight)
    }

    @Test
    fun underTheRangeTwiceAtTheSameWeight_drops() {
        val advice = next(history("Bench Press", sets("60", 5, 5, 4), sets("60", 5, 4, 4)))
        assertEquals(SuggestionTone.CAUTION, advice.tone)
        assertEquals("55", advice.suggestedWeight) // e1RM 70 → about 8 reps at 55
        assertTrue(advice.message, advice.message.contains("two sessions running"))
    }

    @Test
    fun farUnderTheRangeOnce_dropsRightAway() {
        val advice = next(history("Bench Press", sets("60", 3, 3, 2)))
        assertEquals("50", advice.suggestedWeight) // about 8 reps at 52.1, rounded down
    }

    @Test
    fun threeSessionsWithoutProgress_resetsTenPercent() {
        val advice = next(history("Bench Press", sets("60", 8, 7, 7), sets("60", 8, 7, 7), sets("60", 8, 8, 6)))
        assertEquals(SuggestionTone.CAUTION, advice.tone)
        assertEquals("52.5", advice.suggestedWeight)
        assertTrue(advice.message, advice.message.contains("No progress"))
    }

    @Test
    fun slowProgressAtTheSameWeight_isNotAStall() {
        val advice = next(history("Bench Press", sets("60", 8, 8, 8), sets("60", 8, 7, 7), sets("60", 7, 7, 6)))
        assertEquals(SuggestionTone.NEUTRAL, advice.tone)
        assertEquals("60", advice.suggestedWeight)
    }

    @Test
    fun fallingEstimatedMax_backsOff() {
        val advice = next(history("Bench Press", sets("60", 6, 6), sets("62.5", 7, 6), sets("62.5", 9, 8)))
        assertEquals(SuggestionTone.CAUTION, advice.tone)
        assertTrue(advice.message, advice.message.contains("dipped"))
        assertEquals("52.5", advice.suggestedWeight)
    }

    // --- Breaks ---

    @Test
    fun aMonthOff_easesBackIn() {
        assertEquals("90", next(history("Bench Press", sets("100", 8, 8), gap = 35)).suggestedWeight)
        assertEquals("80", next(history("Bench Press", sets("100", 8, 8), gap = 70)).suggestedWeight)
    }

    @Test
    fun twoWeeksOff_repeatsBeforeMovingUp() {
        val advice = next(history("Bench Press", sets("60", 10, 10, 10), gap = 16))
        assertEquals("60", advice.suggestedWeight)
        assertTrue(advice.message, advice.message.contains("Repeat"))
    }

    // --- Which history ---

    @Test
    fun sameDayFirst_otherDaysWhenItHasNone() {
        val h = listOf(
            workout(2, "Upper", bench(*sets("80", 10, 10).toTypedArray())),
            workout(5, "Push", bench(*sets("60", 8, 8).toTypedArray()))
        )
        assertEquals("60", next(h, split = "Push").suggestedWeight)
        assertEquals("82.5", next(h, split = "Upper").suggestedWeight)
        assertEquals("82.5", next(h, split = "Full Body").suggestedWeight)
    }

    // --- During the workout ---

    @Test
    fun noHistory_givesTheRange_butNoMadeUpWeight() {
        val advice = suggestNextWeight("Push", bench(set("60", "", done = false), target = ExerciseTarget(3, 8, 12)), emptyList(), 2.5f, now)!!
        assertTrue(advice.message, advice.message.startsWith("First time") && advice.message.contains("8–12"))
        assertEquals("", advice.suggestedWeight)
    }

    @Test
    fun firstSetTooHeavy_lightensTheRest() {
        val advice = suggestNextWeight("Push", bench(set("60", "3"), set("60", "", done = false)), emptyList(), 2.5f, now)!!
        assertEquals(SuggestionTone.CAUTION, advice.tone)
        assertEquals("55", advice.suggestedWeight)
        assertFalse(advice.forNextTime)
    }

    @Test
    fun firstSetFarTooLight_goesUpForTheRest() {
        val advice = suggestNextWeight("Push", bench(set("40", "15"), set("40", "", done = false)), emptyList(), 2.5f, now)!!
        assertEquals(SuggestionTone.GOOD, advice.tone)
        assertEquals("45", advice.suggestedWeight) // capped at about +15%
    }

    @Test
    fun aRepOrTwoLostPerSet_isNormalFatigue() {
        val ex = bench(set("60", "9"), set("60", "7"), set("60", "", done = false))
        val advice = suggestNextWeight("Push", ex, history("Bench Press", sets("60", 9, 8, 7)), 2.5f, now)!!
        assertEquals(SuggestionTone.NEUTRAL, advice.tone)
        assertEquals("60", advice.suggestedWeight)
    }

    @Test
    fun repsCrashingMidWorkout_suggestsRestOrLighter() {
        val ex = bench(set("60", "9"), set("60", "4"), set("60", "", done = false))
        val advice = suggestNextWeight("Push", ex, emptyList(), 2.5f, now)!!
        assertEquals(SuggestionTone.CAUTION, advice.tone)
        assertTrue(advice.message, advice.message.contains("Reps fell to 4"))
        assertEquals("55", advice.suggestedWeight)
    }

    @Test
    fun everySetDone_plansNextTime() {
        val ex = bench(*sets("60", 10, 10, 10).toTypedArray())
        val advice = suggestNextWeight("Push", ex, history("Bench Press", sets("60", 9, 8, 8)), 2.5f, now)!!
        assertTrue(advice.forNextTime)
        assertEquals("62.5", advice.suggestedWeight)
        assertTrue(advice.message, advice.message.contains("next time"))
    }

    @Test
    fun goLighter_roundsDownToTheStep() {
        assertEquals("55", lighterWeight("62.5", 2.5f))
        assertEquals("7.5", lighterWeight("10", 2.5f))
        assertEquals("0", lighterWeight("0", 2.5f))
        assertNotNull(lighterWeight("", 2.5f))
    }

    // --- Effort (reps left) ---

    private fun rated(weight: String, vararg repsAndRir: Pair<Int, Int>) =
        repsAndRir.map { (reps, rir) -> SetEntry(1, weight, "$reps", isCompleted = true, rir = rir) }

    @Test
    fun topOfRange_withRoomToSpare_jumpsMoreThanAStep() {
        // 3×10 at 60 with 3 left each: the lifter had ~13 in them, so the next weight is for the middle of the range, 2 short.
        val advice = next(history("Bench Press", rated("60", 10 to 3, 10 to 3, 10 to 3)))
        assertEquals(SuggestionTone.GOOD, advice.tone)
        assertTrue("more than one step: ${advice.suggestedWeight}", advice.suggestedWeight.toFloat() > 62.5f)
        assertTrue("never more than ~10%", advice.suggestedWeight.toFloat() <= 66f)
    }

    @Test
    fun topOfRange_onlyByFailing_isOneStep() {
        val advice = next(history("Bench Press", rated("60", 10 to 1, 10 to 0, 10 to 0)))
        assertEquals("62.5", advice.suggestedWeight)
        assertTrue(advice.message.contains("failure"))
    }

    @Test
    fun midRange_butWellShortOfFailure_goesUpAnyway() {
        // Without effort this would hold at 60 ("add a rep").
        val advice = next(history("Bench Press", rated("60", 8 to 3, 8 to 4, 8 to 3)))
        assertEquals(SuggestionTone.GOOD, advice.tone)
        assertTrue(advice.suggestedWeight.toFloat() > 60f)
    }

    @Test
    fun midRange_closeToFailure_holds() {
        val advice = next(history("Bench Press", rated("60", 8 to 1, 7 to 1, 7 to 0)))
        assertEquals("60", advice.suggestedWeight)
    }

    @Test
    fun noRatings_behavesAsBefore() {
        assertEquals("62.5", next(history("Bench Press", sets("60", 10, 10, 10))).suggestedWeight)
    }

    @Test
    fun duringTheWorkout_aLightRatedSet_raisesTheRest() {
        val today = bench(SetEntry(1, "60", "8", isCompleted = true, rir = 4), set("60", "8", done = false), set("60", "8", done = false))
        val advice = suggestNextWeight("Push", today, emptyList(), 2.5f, now)!!
        assertEquals(SuggestionTone.GOOD, advice.tone)
        assertTrue(advice.suggestedWeight.toFloat() > 60f)
    }

    @Test
    fun duringTheWorkout_firstSetToFailure_holdsWithAWarning() {
        val today = bench(SetEntry(1, "60", "7", isCompleted = true, rir = 0), set("60", "8", done = false), set("60", "8", done = false))
        val advice = suggestNextWeight("Push", today, emptyList(), 2.5f, now)!!
        assertEquals(SuggestionTone.CAUTION, advice.tone)
        assertEquals("60", advice.suggestedWeight)
    }
}
