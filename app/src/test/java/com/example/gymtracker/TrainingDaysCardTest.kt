package com.example.gymtracker

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Date

/** History's training-days card and Progress's streak card, drawn to app/build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrainingDaysCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = 24 * 60 * 60 * 1000L

    /** Three workouts a week for four months, today included. */
    private val history = run {
        val now = System.currentTimeMillis()
        (0 until 120).filter { it % 7 in setOf(0, 2, 4) }.map { i ->
            val t = now - i * day
            CompletedWorkout(t, "Push", DateUtils.formatDayKey(Date(t)), "", 3600, 0f, null, emptyList(), emptyList())
        }
    }

    @Test
    fun cards() {
        RuntimeEnvironment.setQualifiers("w400dp-h560dp-xhdpi")
        compose.setContent {
            GymTheme(LightPalette) {
                Column(Modifier.background(LightPalette.bgPrimary).padding(16.dp)) {
                    TrainingDaysCard(history, selectedDayKey = DateUtils.formatDayKey(Date(System.currentTimeMillis() - 9 * day)), onPickDay = {})
                    Spacer(Modifier.height(16.dp))
                    ConsistencyCard(history, onOpenCalendar = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val bitmap = compose.activity.window.decorView.drawToBitmap()
        File("build/screenshots/training-days.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
