package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a friend sees about you, and usernames. */
@RunWith(RobolectricTestRunner::class)
class FriendsTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_794_200_000_000L

    private fun workout(id: Long, title: String, kg: String, reps: String) = CompletedWorkout(
        id, title, "", "", 3600, 82.5f, "/photos/secret.jpg", emptyList(),
        listOf(
            ExerciseEntry("Bench Press", "Chest", sets = listOf(SetEntry(1, kg, reps, isCompleted = true), SetEntry(2, kg, reps, isCompleted = true))),
            ExerciseEntry("Plank", "Core", type = ExerciseType.DURATION, sets = listOf(SetEntry(1, "", "", isCompleted = true, seconds = "60")))
        )
    )

    @Test
    fun statsSummariseWithoutPrivateThings() {
        val history = listOf(workout(now - 1 * day, "Push", "80", "8"), workout(now - 8 * day, "Push", "77.5", "8"))
        val json = friendStatsJson(history, weeklyGoal = 4, now = now).toString()
        // Body weight, photos and notes stay private.
        assertFalse(json.contains("82.5"))
        assertFalse(json.contains("secret"))

        val s = parseFriendStats(json, now)!!
        assertEquals(2, s.total)
        assertEquals(4, s.goal)
        assertEquals("Push", s.lastTitle)
        assertEquals(now - day, s.lastAt)
        assertEquals(2 + 1, s.lastSets)
        val bench = s.bests.first { it.first == "Bench Press" }.second
        assertTrue(bench, bench.contains("80"))
        assertTrue(s.bests.any { it.first == "Plank" })
        assertEquals(1, s.days.size)

        // Read a week later, before they open the app again: nothing this week yet, the rest stays.
        val later = parseFriendStats(json, now + 7 * day)!!
        assertEquals(0, later.thisWeek)
        assertTrue(later.days.isEmpty())
        assertEquals(2, later.total)
    }

    @Test
    fun noWorkoutsYet() {
        val s = parseFriendStats(friendStatsJson(emptyList(), 3, now).toString(), now)!!
        assertEquals(0, s.total)
        assertEquals(null, s.lastTitle)
        assertTrue(s.bests.isEmpty())
    }

    @Test
    fun usernames() {
        assertEquals("vanya_e", cleanHandle(" @Vanya_E "))
        assertTrue(isValidHandle("vanya.e"))
        assertFalse(isValidHandle("va"))
        assertFalse(isValidHandle("vanya e"))
        assertFalse(isValidHandle("ваня"))
        assertFalse(isValidHandle("a".repeat(21)))
    }
}
