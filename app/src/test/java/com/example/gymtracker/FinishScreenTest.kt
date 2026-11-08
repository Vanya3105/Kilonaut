package com.example.gymtracker

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar

/** The screen after Save, drawn to app/build/screenshots/finish-*.png (mid-hop and landed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinishScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() = LocalStorage.resetForTests()
    @After fun tearDown() = LocalStorage.resetForTests()

    private fun data(bests: List<ShareBest> = emptyList(), volume: Int = 6240, reps: Int = 0, held: Int = 0) = ShareData(
        title = "Upper A", whenMillis = System.currentTimeMillis(), durationSeconds = 3720, workSets = 18, volumeKg = volume,
        reps = reps, heldSeconds = held, lifts = listOf(ShareLift("Bench Press", "80 kg × 8", bests.isNotEmpty())), bests = bests,
        muscles = emptyList(), volumeChangePct = 6, workoutNumber = 33, workoutsThisMonth = 6, weekStreak = 9,
        daysThisWeek = setOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)), photoPath = null
    )

    private fun draw(name: String, d: ShareData, millis: Long, dark: Boolean = false) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
        LocalStorage.saveUserProfile(compose.activity, UserProfile(age = 28, hasCompletedOnboarding = true))
        val palette = if (dark) DarkPalette else LightPalette
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GymTheme(palette) {
                WorkoutCelebration(d, Haptics(enabled = false, view = LocalView.current), onShare = {}, onDone = {})
            }
        }
        compose.mainClock.advanceTimeBy(millis)
        val bitmap = compose.activity.window.decorView.drawToBitmap()
        File("build/screenshots/finish-$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun midHop() = draw("hop", data(), 700)
    @Test fun landed() = draw("landed", data(), 2500)
    @Test fun withBest() = draw("best", data(bests = listOf(ShareBest("Bench Press", "80 kg × 8", "77.5 kg × 8"))), 2500)
    @Test fun timedOnlyDark() = draw("timed-dark", data(volume = 0, held = 150), 2500, dark = true)
}
