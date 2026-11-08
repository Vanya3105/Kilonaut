package com.example.gymtracker

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The setup figure follows the answers. Sheets of ages (across) by goals (down) go to
 * app/build/figures/looks-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigureLookTest {
    private val ages = listOf(12, 15, 25, 45, 60, 80)
    private val goals = listOf<TrainingGoal?>(null, TrainingGoal.GENERAL, TrainingGoal.MUSCLE, TrainingGoal.STRENGTH)

    @Test
    fun ageAndGoalShape() {
        val adult = lookFor(25, null, null)
        assertEquals(1f, adult.height)
        assertEquals(0f, adult.grey)
        assertTrue(lookFor(12, null, null).height < 0.85f)
        assertEquals(1f, lookFor(80, null, null).grey)
        assertTrue(lookFor(25, TrainingGoal.MUSCLE, Experience.ADVANCED).muscle > lookFor(25, TrainingGoal.MUSCLE, Experience.BEGINNER).muscle)
        assertTrue(lookFor(25, TrainingGoal.STRENGTH, null).waist > 1f)
        assertTrue(lookFor(25, TrainingGoal.GENERAL, null).waist < 1f)
        assertTrue(lookFor(80, TrainingGoal.MUSCLE, null).muscle < lookFor(40, TrainingGoal.MUSCLE, null).muscle)
    }

    @Test fun sheets() {
        listOf(false, true).forEach { dark ->
            sheet("looks-squat${if (dark) "-dark" else ""}", "Back Squat", 0.6f, dark)
            sheet("looks-bench${if (dark) "-dark" else ""}", "Barbell Bench Press", 0f, dark)
        }
    }

    private fun sheet(file: String, name: String, t: Float, dark: Boolean) {
        val palette = if (dark) DarkPalette else LightPalette
        val cell = Size(200f, 200f)
        val bitmap = Bitmap.createBitmap((cell.width * ages.size).toInt(), (cell.height * goals.size).toInt(), Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(bitmap)), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            drawRect(palette.bgPrimary)
            goals.forEachIndexed { g, goal ->
                ages.forEachIndexed { a, age ->
                    val left = a * cell.width
                    val top = g * cell.height
                    drawRect(palette.bgControl, Offset(left + 2f, top + 2f), Size(cell.width - 4f, cell.height - 4f))
                    translate(left + 12f, top + 12f) {
                        drawExerciseFigure(name, t, palette, Size(cell.width - 24f, cell.height - 24f), look = lookFor(age, goal, Experience.INTERMEDIATE))
                    }
                }
            }
        }
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(230, 120, 30); textSize = 15f; isAntiAlias = true }
        val c = android.graphics.Canvas(bitmap)
        goals.forEachIndexed { g, goal -> ages.forEachIndexed { a, age ->
            c.drawText("$age ${goal?.name?.lowercase() ?: "-"}", a * cell.width + 8f, g * cell.height + 20f, paint)
        } }
        File("build/figures/$file.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
