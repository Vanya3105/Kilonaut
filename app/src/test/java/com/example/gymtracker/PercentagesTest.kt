package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Test

class PercentagesTest {
    @Test
    fun reps_followTheEstimateFormula() {
        assertEquals(1, repsAtPercent(100))
        assertEquals(2, repsAtPercent(95))
        assertEquals(8, repsAtPercent(80))
        assertEquals(20, repsAtPercent(60))
    }

    @Test
    fun weights_roundDownToThePlates() {
        assertEquals(90f, weightAtPercent(100f, 90, WeightUnit.KG), 0.001f)
        assertEquals("82.5 rounds down to 82.5, 83.3 to 82.5", 82.5f, weightAtPercent(98f, 85, WeightUnit.KG), 0.001f)
        // 100 kg = 220.46 lb; 80 % = 176.4 lb, down to 175 lb.
        assertEquals(175f, WeightUnit.LB.fromKg(weightAtPercent(100f, 80, WeightUnit.LB)), 0.05f)
    }
}
