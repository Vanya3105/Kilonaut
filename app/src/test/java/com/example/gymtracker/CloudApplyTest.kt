package com.example.gymtracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What comes from the account lands here, and reads back the same, so it isn't sent again. */
@RunWith(RobolectricTestRunner::class)
class CloudApplyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LocalStorage.resetForTests()
        context.deleteDatabase("gymtracker.db")
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() = LocalStorage.resetForTests()

    private val bench = ExerciseEntry("Bench Press", "Chest", sets = listOf(SetEntry(1, "80", "8", isCompleted = true, rir = 2)))

    private fun workout(id: Long, photo: String? = null) =
        CompletedWorkout(id, "Push", "2026-11-0$id", "Nov $id", 3600, 80f, photo, emptyList(), listOf(bench))

    @Test
    fun anotherPhonesPlan_landsAndReadsBackTheSame() {
        // The other phone: a program, a day with a target, settings in pounds.
        LocalStorage.saveProgram(context, SplitProgramInfo("mine", "My plan", "", 3, listOf(WorkoutSplit("Push", "Chest", "01"))))
        LocalStorage.saveTemplateForSplit(context, "Push", listOf("Bench Press" to "Chest"))
        LocalStorage.setTarget(context, "Push", "Bench Press", ExerciseTarget(4, 6, 8))
        LocalStorage.saveSettings(context, AppSettings(weightUnit = WeightUnit.LB, weightStep = 5f))
        LocalStorage.saveUserProfile(context, UserProfile(hasCompletedOnboarding = true, splitProgram = "mine"))
        // As stored (exercise types are worked out when the days are read back).
        LocalStorage.resetForTests()
        val plan = LocalStorage.cloudPlan(context).toString()

        // This phone, fresh.
        setUp()
        LocalStorage.applyCloud(context, emptyList(), emptySet(), emptyList(), emptySet(), JSONObject(plan))
        assertEquals("mine", LocalStorage.loadUserProfile(context).splitProgram)
        assertEquals(WeightUnit.LB, LocalStorage.loadSettings(context).weightUnit)
        assertEquals(ExerciseTarget(4, 6, 8), LocalStorage.getTarget(context, "Push", "Bench Press"))
        assertEquals(syncHash(plan), syncHash(LocalStorage.cloudPlan(context).toString()))

        LocalStorage.resetForTests()
        assertEquals(syncHash(plan), syncHash(LocalStorage.cloudPlan(context).toString()))
    }

    @Test
    fun workoutsFromTheAccount_mergeByIdAndKeepThisPhonesPhoto() {
        LocalStorage.saveCompletedWorkout(context, workout(1, photo = "/photos/1.jpg"))
        LocalStorage.saveCompletedWorkout(context, workout(2))
        val edited = workout(1).copy(durationSeconds = 4000)
        LocalStorage.applyCloud(
            context,
            workouts = listOf(edited.copy(photoPath = "/photos/1.jpg") to "good one", workout(3) to ""),
            deletedWorkouts = setOf(2L),
            weights = listOf(WeightLogEntry(9L, "2026-11-08", "Nov 8", 80.5f)),
            deletedWeights = emptySet(),
            plan = null
        )
        val history = LocalStorage.loadHistory(context)
        assertEquals(listOf(3L, 1L), history.map { it.id })
        assertEquals(4000L, history.last().durationSeconds)
        assertEquals("/photos/1.jpg", history.last().photoPath)
        assertEquals("good one", LocalStorage.getWorkoutNote(context, 1))
        assertEquals(listOf(9L), LocalStorage.loadWeightHistory(context).map { it.id })

        // Read back as the account would get it: identical, so nothing goes out again.
        val note = LocalStorage.workoutNotes(context)[1L].orEmpty()
        assertEquals(workoutPayload(edited, "good one"), workoutPayload(history.last(), note))
        LocalStorage.resetForTests()
        assertTrue(LocalStorage.loadHistory(context).any { it.id == 3L })
    }
}
