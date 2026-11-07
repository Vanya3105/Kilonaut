package com.example.gymtracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Undo puts back exactly what was deleted, also after a restart. */
@RunWith(RobolectricTestRunner::class)
class UndoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LocalStorage.resetForTests()
        context.deleteDatabase("gymtracker.db")
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() = LocalStorage.resetForTests()

    private val bench = ExerciseEntry("Bench Press", sets = listOf(SetEntry(1, "80", "8", isCompleted = true), SetEntry(2, "80", "7", isCompleted = true)))

    private fun workout(id: Long) = CompletedWorkout(id, "Push", "2026-11-0$id", "Nov $id", 3600, 80f, null, emptyList(), listOf(bench))

    @Test
    fun deletedWorkout_comesBackInPlace() {
        LocalStorage.saveCompletedWorkout(context, workout(1))
        LocalStorage.saveCompletedWorkout(context, workout(3))
        LocalStorage.saveCompletedWorkout(context, workout(2))
        val before = LocalStorage.loadHistory(context)

        LocalStorage.deleteWorkoutFromHistory(context, 2)
        LocalStorage.restoreWorkout(context, before.first { it.id == 2L })
        assertEquals(before, LocalStorage.loadHistory(context))

        LocalStorage.resetForTests()
        assertEquals(before.map { it.id }, LocalStorage.loadHistory(context).map { it.id })
    }

    @Test
    fun restoringAWorkout_leavesTheRunningOneAlone() {
        LocalStorage.saveCompletedWorkout(context, workout(1))
        LocalStorage.saveActiveSession(context, "Pull", listOf(bench))
        LocalStorage.deleteWorkoutFromHistory(context, 1)
        LocalStorage.restoreWorkout(context, workout(1))
        assertEquals("Pull", LocalStorage.loadActiveSession(context)?.first)
    }

    @Test
    fun deletedNewestWeighIn_bringsBackTheProfileWeight() {
        LocalStorage.saveUserProfile(context, UserProfile(hasCompletedOnboarding = true, currentWeightKg = 80f))
        LocalStorage.addWeightLog(context, 80f)
        Thread.sleep(5)
        val newest = LocalStorage.addWeightLog(context, 82f).first()

        LocalStorage.deleteWeightLog(context, newest.id)
        assertEquals(80f, LocalStorage.loadUserProfile(context).currentWeightKg)

        LocalStorage.restoreWeightLog(context, newest)
        assertEquals(newest, LocalStorage.loadWeightHistory(context).first())
        assertEquals(82f, LocalStorage.loadUserProfile(context).currentWeightKg)
    }

    @Test
    fun removedExercise_comesBackWithItsPlaceAndTarget() {
        val day = listOf("Bench Press" to "Chest", "Overhead Press" to "Shoulders", "Dips" to "Triceps")
        LocalStorage.saveTemplateForSplit(context, "Push", day)
        LocalStorage.setTarget(context, "Push", "Overhead Press", ExerciseTarget(4, 6, 8))
        val snapshot = LocalStorage.daySnapshot(context, "Push")

        LocalStorage.removeExerciseFromSplitTemplate(context, "Push", "Overhead Press")
        LocalStorage.restoreDay(context, snapshot)

        assertEquals(day, LocalStorage.loadTemplateForSplit(context, "Push"))
        assertEquals(ExerciseTarget(4, 6, 8), LocalStorage.getTarget(context, "Push", "Overhead Press"))
    }

    @Test
    fun workoutNotes_areKeptAndTravelInBackups() {
        LocalStorage.saveCompletedWorkout(context, workout(1))
        LocalStorage.setWorkoutNote(context, 1, "  Felt strong  ")
        assertEquals("Felt strong", LocalStorage.getWorkoutNote(context, 1))
        val backup = LocalStorage.exportJsonBackupString(context)
        LocalStorage.setWorkoutNote(context, 1, "")
        assertEquals("", LocalStorage.getWorkoutNote(context, 1))
        LocalStorage.importJsonBackupString(context, backup)
        assertEquals("Felt strong", LocalStorage.getWorkoutNote(context, 1))
    }
}
