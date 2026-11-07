package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Test

class WarmupTest {
    private fun work(kg: String) = ExerciseEntry("Back Squat", sets = List(3) { SetEntry(it + 1, kg, "5") })

    private fun ramp(ex: ExerciseEntry) = ex.sets.filter { it.isWarmupSet }.map { formatKgClean(it.weightKg) + "x" + it.reps }

    @Test
    fun barLift_startsWithTheEmptyBar_thenRampsToTheWorkWeight() {
        Units.set(WeightUnit.KG)
        val ex = withWarmups(work("100"), 2.5f, 20f)
        assertEquals(listOf("20x10", "50x5", "70x3", "85x1"), ramp(ex))
        assertEquals(7, ex.sets.size)
        assertEquals((1..7).toList(), ex.sets.map { it.setNumber })
    }

    @Test
    fun dumbbells_noBar_roundDownToTheStep() {
        Units.set(WeightUnit.KG)
        assertEquals(listOf("10x5", "12.5x3", "15x1"), ramp(withWarmups(work("20"), 2.5f, null)))
    }

    @Test
    fun lightWork_skipsStepsAtTheBar_andReplacesOldWarmups() {
        Units.set(WeightUnit.KG)
        val withOld = work("40").let { it.copy(sets = listOf(SetEntry(1, "20", "8", isWarmup = true, intensityType = "WARMUP")) + it.sets) }
        assertEquals(listOf("20x10", "27.5x3", "32.5x1"), ramp(withWarmups(withOld, 2.5f, 20f)))
    }
}
