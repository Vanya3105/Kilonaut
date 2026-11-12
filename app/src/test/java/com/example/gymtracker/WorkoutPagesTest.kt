package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Progress's weekly volume and a logged workout's page. */
class WorkoutPagesTest {

    private fun set(w: String, r: String, warmup: Boolean = false) =
        SetEntry(setNumber = 1, weight = w, reps = r, isWarmup = warmup, intensityType = if (warmup) "WARMUP" else "NORMAL", isCompleted = true)

    private fun workout(id: Long, title: String, vararg exercises: ExerciseEntry) = CompletedWorkout(
        id = id, splitTitle = title, dateKey = "", displayDate = "", durationSeconds = 3600,
        bodyWeightKg = 80f, photoPath = null, skippedExercises = emptyList(), exercises = exercises.toList()
    )

    private fun bench(vararg sets: SetEntry) = ExerciseEntry("Barbell Bench Press", targetMuscle = "Chest", sets = sets.toList())

    /** Wednesday 11 Nov 2026, 12:00, local time. */
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 11, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun daysAgo(d: Int) = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -d) }.timeInMillis

    @Test
    fun weeks_startOnSunday_oldestFirst_andCountOnlyWorkSets() {
        val history = listOf(
            workout(daysAgo(0), "Push", bench(set("40", "10", warmup = true), set("100", "5"))), // this week: 500
            workout(daysAgo(3), "Push", bench(set("100", "5"))), // Sunday 8 Nov: still this week
            workout(daysAgo(4), "Push", bench(set("50", "10"))), // Saturday 7 Nov: last week
            workout(daysAgo(200), "Push", bench(set("100", "10"))) // long before the chart
        )
        val weeks = weeklyVolumes(history, now)
        assertEquals(VOLUME_WEEKS, weeks.size)
        assertEquals(1000, weeks.last().volumeKg)
        assertEquals(2, weeks.last().workouts)
        assertEquals(500, weeks[weeks.lastIndex - 1].volumeKg)
        assertEquals(1500, weeks.sumOf { it.volumeKg })
        val sunday = Calendar.getInstance().apply { timeInMillis = weeks.last().startMillis }
        assertEquals(Calendar.SUNDAY, sunday.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, sunday.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun usualWeek_leavesOutThisWeekAndWeeksOff() {
        fun w(kg: Int) = WeekVolume(0L, kg, if (kg > 0) 1 else 0)
        assertEquals(1500, typicalWeekVolume(listOf(w(1000), w(0), w(2000), w(9000))))
        assertNull(typicalWeekVolume(listOf(w(1000), w(0), w(9000))))
    }

    @Test
    fun summary_marksOneNewBestPerExercise_andComparesWithTheSameDay() {
        val older = workout(1, "Push", bench(set("90", "5"), set("90", "5")))
        val other = workout(2, "Pull", ExerciseEntry("Barbell Row", sets = listOf(set("60", "10"))))
        val today = workout(3, "Push", bench(set("60", "10", warmup = true), set("95", "5"), set("100", "3"), set("80", "5")))
        val s = summarizeWorkout(today, listOf(today, other, older))
        assertEquals(1, s.bests)
        assertEquals(setOf(2), s.exercises.single().second) // 100 × 3, the top of the sets that beat 90 × 5
        assertEquals(older.id, s.previous?.id)
        assertEquals(900, s.previousStats?.volumeKg)
        assertEquals(95 * 5 + 300 + 400, s.stats.volumeKg)
        assertTrue(s.loads[RECOVERY_GROUPS.indexOf("Chest")] >= 1f)
    }

    @Test
    fun activity_byWeekAndByMonth_countsWorkoutsTimeAndVolume() {
        val history = listOf(
            workout(daysAgo(0), "Push", bench(set("100", "5"))),  // this week, this month
            workout(daysAgo(4), "Push", bench(set("50", "10"))),  // Sat 7 Nov: last week, this month
            workout(daysAgo(15), "Push", bench(set("60", "10"))), // 27 Oct: last month
            workout(daysAgo(800), "Push", bench(set("60", "10"))) // before both charts
        )
        val weeks = activityBuckets(history, byMonth = false, nowMillis = now)
        assertEquals(ACTIVITY_BARS, weeks.size)
        assertEquals(1, weeks.last().workouts)
        assertEquals(500, weeks.last().volumeKg)
        assertEquals(3600L, weeks.last().seconds)
        assertEquals(1, weeks[weeks.lastIndex - 1].workouts)
        assertEquals(3, weeks.sumOf { it.workouts })

        val months = activityBuckets(history, byMonth = true, nowMillis = now)
        assertEquals(2, months.last().workouts)
        assertEquals(1000, months.last().volumeKg)
        assertEquals(1, months[months.lastIndex - 1].workouts)
        val first = Calendar.getInstance().apply { timeInMillis = months.first().startMillis }
        assertEquals(1, first.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.DECEMBER, first.get(Calendar.MONTH))
    }

    @Test
    fun summary_firstTimeHasNothingToCompare() {
        val first = workout(1, "Push", bench(set("90", "5")))
        val s = summarizeWorkout(first, listOf(first))
        assertNull(s.previous)
    }
}
