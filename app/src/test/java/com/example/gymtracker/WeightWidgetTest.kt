package com.example.gymtracker

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Date

/** Draws the body-weight widget to app/build/screenshots, empty and with a month of weigh-ins. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeightWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        LocalStorage.resetForTests()
        context.deleteDatabase("gymtracker.db")
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() = LocalStorage.resetForTests()

    private fun save(name: String, bmp: Bitmap) {
        File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shots(prefix: String) {
        val settings = defaultWidgetSettings(WIDGET_KIND_WEIGHT)
        save("$prefix-wide-light", BodyWeightWidgetProvider.render(context, settings, 300, 130, night = false))
        save("$prefix-wide-dark", BodyWeightWidgetProvider.render(context, settings, 300, 130, night = true))
        save("$prefix-square-light", BodyWeightWidgetProvider.render(context, settings, 170, 170, night = false))
    }

    @Test
    fun empty() = shots("widget-weight-empty")

    @Test
    fun aMonthOfWeighIns() {
        val now = System.currentTimeMillis()
        (0 until 24).forEach { i ->
            val millis = now - (28 - i) * day - 3_600_000
            val kg = 84f - i * 0.07f + listOf(0.3f, -0.2f, 0.1f, 0.4f)[i % 4]
            LocalStorage.restoreWeightLog(context, WeightLogEntry(millis, DateUtils.formatDayKey(Date(millis)), DateUtils.formatDisplayDate(Date(millis)), kg))
        }
        shots("widget-weight")
        assertTrue(File("build/screenshots/widget-weight-wide-light.png").length() > 0)
    }
}
