package com.example.gymtracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.gymtracker.watchlink.WatchAction
import com.example.gymtracker.watchlink.WatchExercise
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LocalStorage.resetForTests()
        context.deleteDatabase("gymtracker.db")
        context.filesDir.listFiles()?.forEach { it.delete() }
        LocalStorage.saveSettings(context, AppSettings(weightStep = 2.5f, restTimerSeconds = 90))
    }

    @After
    fun tearDown() = LocalStorage.resetForTests()

    private fun startWorkout() = LocalStorage.saveActiveSession(
        context, "Push", listOf(
            ExerciseEntry("Bench Press", sets = listOf(SetEntry(1, "80", "8", restSeconds = 120), SetEntry(2, "80", "8"))),
            ExerciseEntry("Plank", type = ExerciseType.DURATION, sets = listOf(SetEntry(1, "", "", seconds = "60")))
        )
    )

    @Test
    fun noWorkout_showsInactive() {
        assertFalse(watchStateOf(context).active)
    }

    @Test
    fun showsTheNextSet_formattedLikeTheWidget() {
        startWorkout()
        val s = watchStateOf(context)
        assertTrue(s.active)
        assertEquals("Push", s.workoutName)
        assertEquals("Bench Press", s.exerciseName)
        assertEquals("Set 1", s.setLabel)
        assertEquals("80 kg", s.primary)
        assertEquals("weight", s.primaryLabel)
        assertEquals("8", s.secondary)
        assertEquals("reps", s.secondaryLabel)
        assertEquals(3, s.setsLeft)
        assertEquals(0L, s.restEndsAtMillis)
    }

    @Test
    fun buttons_editTheSet_andCompletingStartsTheRest() {
        startWorkout()
        WatchSync.apply(context, WatchAction.PRIMARY_PLUS)
        WatchSync.apply(context, WatchAction.SECONDARY_MINUS)
        assertEquals("82.5 kg", watchStateOf(context).primary)
        assertEquals("7", watchStateOf(context).secondary)

        val before = System.currentTimeMillis()
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        val resting = watchStateOf(context)
        assertEquals("Set 2", resting.setLabel)
        assertEquals(120_000L, resting.restTotalMillis)
        assertTrue(resting.restEndsAtMillis >= before + 120_000L)
        // Taps on the set are ignored while resting (the watch shows the rest screen then).
        WatchSync.apply(context, WatchAction.PRIMARY_PLUS)
        assertEquals("80 kg", watchStateOf(context).primary)

        WatchSync.apply(context, WatchAction.ADD_REST)
        val longer = watchStateOf(context)
        assertEquals(resting.restEndsAtMillis + 15_000L, longer.restEndsAtMillis)
        assertEquals(135_000L, longer.restTotalMillis)

        WatchSync.apply(context, WatchAction.SKIP_REST)
        assertEquals(0L, watchStateOf(context).restEndsAtMillis)
    }

    @Test
    fun timeExercise_showsTimeOnly_andAllDoneHasNoExercise() {
        LocalStorage.saveActiveSession(context, "Core", listOf(ExerciseEntry("Plank", type = ExerciseType.DURATION, sets = listOf(SetEntry(1, "", "", seconds = "60", restSeconds = 0)))))
        val s = watchStateOf(context)
        assertEquals("1:00", s.primary)
        assertEquals("time", s.primaryLabel)
        assertNull(s.secondary)
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        assertNull(watchStateOf(context).exerciseName)
        assertTrue(watchStateOf(context).active)
    }

    @Test
    fun sendsProgressThroughTheWorkout() {
        startWorkout()
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        val s = watchStateOf(context)
        assertEquals(listOf(WatchExercise("Bench Press", 1, 2), WatchExercise("Plank", 0, 1)), s.exercises)
        assertEquals(0, s.currentExercise)
        assertEquals(2, s.setNumber)
        assertEquals(2, s.setCount)
        assertTrue(s.canUndo)
        assertTrue(s.startedAtMillis > 0L)
    }

    @Test
    fun removeRest_shortensIt_andEndsItWhenLittleIsLeft() {
        startWorkout()
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        val end = watchStateOf(context).restEndsAtMillis
        WatchSync.apply(context, WatchAction.REMOVE_REST)
        assertEquals(end - 15_000L, watchStateOf(context).restEndsAtMillis)
        LocalStorage.setRestEndTimeMillis(context, System.currentTimeMillis() + 10_000L)
        WatchSync.apply(context, WatchAction.REMOVE_REST)
        assertEquals(0L, watchStateOf(context).restEndsAtMillis)
    }

    @Test
    fun addSet_copiesTheExerciseTickedLast_evenWhileTheNextOneIsUp() {
        startWorkout()
        assertEquals("before anything is ticked: the current exercise", "Bench Press", watchStateOf(context).addSetTo)
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        WatchSync.apply(context, WatchAction.SKIP_REST)
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        assertEquals("Plank", watchStateOf(context).exerciseName)
        assertEquals("Bench Press", watchStateOf(context).addSetTo)

        WatchSync.apply(context, WatchAction.ADD_SET)
        val s = watchStateOf(context)
        assertEquals("the new set is next", "Bench Press", s.exerciseName)
        assertEquals("Set 3", s.setLabel)
        assertEquals("80 kg", s.primary)
        assertEquals(WatchExercise("Bench Press", 2, 3), s.exercises[0])
    }

    @Test
    fun undo_unticksTheLastSet_evenInThePreviousExercise() {
        startWorkout()
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        WatchSync.apply(context, WatchAction.SKIP_REST)
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        WatchSync.apply(context, WatchAction.SKIP_REST)
        assertEquals("Plank", watchStateOf(context).exerciseName)

        WatchSync.apply(context, WatchAction.UNDO_SET)
        val back = watchStateOf(context)
        assertEquals("Bench Press", back.exerciseName)
        assertEquals("Set 2", back.setLabel)
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        WatchSync.apply(context, WatchAction.UNDO_SET)
        assertEquals("rest it started ends too", 0L, watchStateOf(context).restEndsAtMillis)

        WatchSync.apply(context, WatchAction.UNDO_SET)
        WatchSync.apply(context, WatchAction.UNDO_SET)
        assertFalse(watchStateOf(context).canUndo)
        WatchSync.apply(context, WatchAction.UNDO_SET) // nothing left: no crash, no change
        assertEquals("Set 1", watchStateOf(context).setLabel)
    }

    @Test
    fun finish_savesToHistory_andTheWatchShowsIt() {
        startWorkout()
        WatchSync.apply(context, WatchAction.COMPLETE_SET)
        WatchSync.apply(context, WatchAction.FINISH_WORKOUT)
        assertNull(LocalStorage.loadActiveSession(context))
        val saved = LocalStorage.loadHistory(context).single()
        assertEquals("Push", saved.splitTitle)
        // Only the ticked set is kept; Plank, with nothing ticked, counts as skipped.
        assertEquals(listOf("Bench Press"), saved.exercises.map { it.name })
        assertEquals(1, saved.exercises.single().sets.size)
        assertEquals(listOf("Plank"), saved.skippedExercises)

        val s = watchStateOf(context)
        assertFalse(s.active)
        assertEquals("Push", s.lastWorkout?.workoutName)
        // Counted like the phone's finish sheet: ticked sets only.
        val stats = computeWorkoutStats(saved.exercises, emptyList())
        assertEquals(stats.workSets, s.lastWorkout?.sets)
        assertEquals(formatVolume(stats.volumeKg), s.lastWorkout?.volume)
    }

    @Test
    fun start_usesTheDaysTemplate_andIgnoresUnknownDaysOrARunningWorkout() {
        val days = watchStateOf(context).days
        assertTrue(days.isNotEmpty())
        WatchSync.startWorkout(context, "Not a day")
        assertNull(LocalStorage.loadActiveSession(context))

        WatchSync.startWorkout(context, days.first())
        val (title, exercises) = LocalStorage.loadActiveSession(context)!!
        assertEquals(days.first(), title)
        assertEquals(LocalStorage.loadTemplateForSplit(context, title).map { it.first }, exercises.map { it.name })

        WatchSync.startWorkout(context, days.last())
        assertEquals(days.first(), LocalStorage.loadActiveSession(context)!!.first)
    }
}
