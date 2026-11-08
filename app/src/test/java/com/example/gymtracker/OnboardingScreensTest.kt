package com.example.gymtracker

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Each setup step with its picture at the top, drawn to app/build/screenshots/setup-N.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val answered = UserProfile(
        age = 28, currentWeightKg = 80f, experience = Experience.INTERMEDIATE, goal = TrainingGoal.MUSCLE,
        equipment = Equipment.GYM, daysPerWeek = 4, sessionMinutes = 60, focus = Focus.UPPER, skipMuscles = setOf("Calves")
    )

    private fun draw(step: Int, dark: Boolean = false) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
        val palette = if (dark) DarkPalette else LightPalette
        compose.setContent {
            GymTheme(palette) {
                Box(Modifier.fillMaxSize().background(palette.bgPrimary)) {
                    OnboardingScreen(
                        initialProfile = answered, unit = WeightUnit.KG, onSelectUnit = {}, canGoBack = true,
                        haptics = Haptics(enabled = false, view = androidx.compose.ui.platform.LocalView.current), onBack = {}, onBuildSplit = {}, onSaveProfile = {},
                        startStep = step
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val bitmap = compose.activity.window.decorView.drawToBitmap()
        File("build/screenshots/setup-$step${if (dark) "-dark" else ""}.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun aboutYou() = draw(0)
    @Test fun training() = draw(1)
    @Test fun week() = draw(2)
    @Test fun body() = draw(3)
    @Test fun plan() = draw(4)
    @Test fun weekDark() = draw(2, dark = true)
}
