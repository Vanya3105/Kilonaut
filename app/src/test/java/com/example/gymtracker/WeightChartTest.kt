package com.example.gymtracker

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The body-weight card's ranges and axis, plus pictures of it in build/screenshots to check by eye. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeightChartTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    /** Five months of weigh-ins every few days, drifting down with day-to-day noise. */
    private val history = (0 until 50).map { i ->
        val millis = now - (150 - i * 3) * day
        val kg = 86f - i * 0.09f + listOf(0.4f, -0.3f, 0.1f, 0.5f, -0.2f)[i % 5]
        WeightLogEntry(millis, "", "", kg)
    }.reversed()

    @Test
    fun ranges_onlyThoseThatShowSomethingNew() {
        assertEquals(listOf(WeightRange.MONTH, WeightRange.QUARTER, WeightRange.HALF), usefulWeightRanges(history, now))
        val week = listOf(WeightLogEntry(now - day, "", "", 80f), WeightLogEntry(now, "", "", 80.5f))
        assertEquals("a week of data: one range, no picker", listOf(WeightRange.MONTH), usefulWeightRanges(week, now))
    }

    @Test
    fun sameDay_keepsTheLastWeighIn() {
        val morning = WeightLogEntry(now, "2027-01-15", "", 80f)
        val retry = WeightLogEntry(now + 60_000, "2027-01-15", "", 80.4f)
        val before = WeightLogEntry(now - day, "2027-01-14", "", 81f)
        assertEquals(listOf(81f, 80.4f), dailyWeighIns(listOf(retry, morning, before)).sortedBy { it.id }.map { it.weightKg })
    }

    @Test
    fun axis_roundStepsAroundTheData() {
        val (lo, hi, step) = niceAxis(81.3f, 86.6f)
        assertEquals(2f, step)
        assertEquals(80f, lo)
        assertEquals(88f, hi)
        val (flatLo, flatHi, _) = niceAxis(80f, 80f)
        assertEquals("a flat line still gets a scale", true, flatHi > flatLo)
    }

    private fun shot(name: String, palette: GymPalette, entries: List<WeightLogEntry>) {
        RuntimeEnvironment.setQualifiers("w400dp-h560dp-xhdpi")
        compose.setContent {
            GymTheme(palette) {
                Box(Modifier.background(palette.bgPrimary).padding(16.dp)) {
                    BodyWeightCard(entries.first().weightKg, entries, Haptics(false, compose.activity.window.decorView), onLog = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val bitmap = compose.activity.window.decorView.drawToBitmap()
        File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // The card ranges up to today: these use the real clock, so the weigh-ins are moved to end now.
    private fun recent(entries: List<WeightLogEntry>): List<WeightLogEntry> {
        val shift = System.currentTimeMillis() - entries.first().id
        return entries.map { it.copy(id = it.id + shift) }
    }

    @Test fun light() = shot("weight-light", LightPalette, recent(history))
    @Test fun dark() = shot("weight-dark", DarkPalette, recent(history))
    @Test fun two() = shot("weight-two", LightPalette, recent(history.take(2)))
}
