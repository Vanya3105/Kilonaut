package com.example.gymtracker.wear

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import com.example.gymtracker.watchlink.SetKind
import com.example.gymtracker.watchlink.WatchExercise
import com.example.gymtracker.watchlink.WatchFinished
import com.example.gymtracker.watchlink.WatchState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the watch screens on round displays and saves PNGs to wear/build/screenshots, to check
 * the layout without an emulator. Small = 40 mm Galaxy Watch, large = 44–47 mm.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_800_000_000_000L
    private val set = WatchState(
        active = true, workoutName = "Push", exerciseName = "Incline Dumbbell Bench Press", setLabel = "Set 2",
        primary = "102.5 kg", primaryLabel = "weight", secondary = "10", secondaryLabel = "reps",
        summary = "102.5 kg × 10", setsLeft = 11, startedAtMillis = now - 1_712_000,
        exercises = listOf(
            WatchExercise("Barbell Bench Press", 4, 4), WatchExercise("Incline Dumbbell Bench Press", 1, 3),
            WatchExercise("Cable Fly", 0, 3), WatchExercise("Overhead Press", 0, 3), WatchExercise("Triceps Pushdown", 0, 3)
        ),
        currentExercise = 1, setNumber = 2, setCount = 3, canUndo = true, workSetNumber = 2, workSetCount = 3,
        addSetTo = "Incline Dumbbell Bench Press"
    )
    private val resting = set.copy(restEndsAtMillis = now + 75_000, restTotalMillis = 120_000)

    private val idle = WatchState(active = false, days = listOf("Push", "Pull", "Legs", "Upper", "Lower"))
    private val saved = idle.copy(lastWorkout = WatchFinished("Push", now - 60_000, 3_130, 18, "4,250 kg", 2))

    private fun shot(
        name: String,
        state: WatchState?,
        large: Boolean = false,
        connected: Boolean = true,
        waiting: Boolean = false,
        overview: Boolean = false,
        confirm: Boolean = false,
        heartRate: Int? = null
    ) {
        RuntimeEnvironment.setQualifiers(if (large) "w227dp-h227dp-round-xhdpi" else "w192dp-h192dp-round-xhdpi")
        compose.setContent {
            WatchScreen(state, now, connected, waiting, onAction = {}, onStart = {}, onOpenOnPhone = {}, initialOverview = overview, initialConfirmFinish = confirm, heartRate = heartRate)
        }
        compose.waitForIdle()
        // Drawn directly: captureToImage waits for a window frame Robolectric never reports.
        val bitmap = compose.activity.window.decorView.drawToBitmap()
        File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun set() = shot("set-small", set)
    @Test fun setLarge() = shot("set-large", set, large = true)
    @Test fun bodyweight() = shot("bodyweight-small", set.copy(exerciseName = "Pull-Up", primary = "12 reps", primaryLabel = "reps", secondary = null, secondaryLabel = null))
    @Test fun waiting() = shot("waiting-small", set, waiting = true)
    @Test fun rest() = shot("rest-small", resting, heartRate = 128)
    @Test fun restLarge() = shot("rest-large", resting, large = true, heartRate = 128)
    @Test fun noWorkout() = shot("no-workout-small", WatchState(active = false))
    @Test fun allDone() = shot("all-done-small", set.copy(exerciseName = null, currentExercise = -1))
    @Test fun noPhone() = shot("no-phone-small", null, connected = false)
    @Test fun warmUp() = shot("warm-up-small", set.copy(setLabel = "Warm-up", setNumber = 1, setKind = SetKind.WARMUP))
    @Test fun start() = shot("start-small", idle)
    @Test fun startLarge() = shot("start-large", idle, large = true)
    @Test fun overview() = shot("overview-small", set, overview = true)
    @Test fun overviewLarge() = shot("overview-large", set, large = true, overview = true)
    @Test fun confirmFinish() = shot("confirm-finish-small", set, confirm = true)
    @Test fun saved() = shot("saved-small", saved)
    @Test fun savedLarge() = shot("saved-large", saved, large = true)
    @Test fun restLastSet() = shot("rest-last-set-small", resting.copy(exerciseName = null))

    // The phone app in Hebrew: names come translated, the watch's own words follow, right to left.
    private val hebrewSet = set.copy(
        language = "he", workoutName = "דחיפה", exerciseName = "לחיצת חזה בשיפוע עם משקולות יד",
        primary = "102.5 ק״ג", primaryLabel = "משקל", secondaryLabel = "חזרות", summary = "102.5 ק״ג × 10",
        exercises = listOf(WatchExercise("לחיצת חזה במוט", 4, 4), WatchExercise("לחיצת חזה בשיפוע עם משקולות יד", 1, 3), WatchExercise("פרפר בכבל", 0, 3))
    )
    @Test fun setHebrew() = shot("hebrew-set-small", hebrewSet)
    @Test fun restHebrew() = shot("hebrew-rest-small", hebrewSet.copy(restEndsAtMillis = now + 75_000, restTotalMillis = 120_000), heartRate = 128)
    @Test fun startHebrew() = shot("hebrew-start-small", idle.copy(language = "he", dayLabels = listOf("דחיפה", "משיכה", "רגליים", "עליון", "תחתון")))
    @Test fun overviewHebrew() = shot("hebrew-overview-small", hebrewSet, overview = true)
    @Test fun savedHebrew() = shot("hebrew-saved-small", saved.copy(language = "he", lastWorkout = saved.lastWorkout!!.copy(workoutName = "דחיפה")))
}
