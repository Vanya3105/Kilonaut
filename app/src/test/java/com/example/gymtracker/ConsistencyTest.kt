package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class ConsistencyTest {
    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 18, 0) }.timeInMillis
    private fun w(millis: Long) = CompletedWorkout(millis, "Push", "", "", 3600, 80f, null, emptyList(), emptyList())

    @Test
    fun streak_waitsForThisWeek_andCountsBack() {
        Locale.setDefault(Locale.US) // weeks start on Sunday
        // Wednesday Nov 11 2026; workouts in the three weeks before, none yet this week.
        val history = listOf(at(2026, 11, 3), at(2026, 10, 27), at(2026, 10, 20), at(2026, 10, 1)).map(::w)
        val c = computeConsistency(history, at(2026, 11, 11))
        assertEquals(3, c.weekStreak)
        assertFalse(c.trainedThisWeek)
        val withThisWeek = computeConsistency(history + w(at(2026, 11, 9)), at(2026, 11, 11))
        assertEquals(4, withThisWeek.weekStreak)
        assertTrue(withThisWeek.trainedThisWeek)
    }

    @Test
    fun calendar_marksTrainingDays_andLeavesFutureDaysOut() {
        Locale.setDefault(Locale.US)
        val c = computeConsistency(listOf(w(at(2026, 11, 9)), w(at(2026, 11, 9) + 3_600_000)), at(2026, 11, 11))
        assertEquals(CONSISTENCY_WEEKS, c.days.size)
        val thisWeek = c.days.last()
        assertEquals(2, thisWeek[1]) // Monday, two workouts
        assertEquals(-1, thisWeek[4]) // Thursday hasn't come
        assertEquals(2, c.thisMonth)
    }
}
