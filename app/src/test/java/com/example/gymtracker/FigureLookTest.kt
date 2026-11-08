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

/** The setup figure follows the user's age. Sheets of ages go to app/build/figures/looks-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigureLookTest {
    private val ages = listOf(12, 15, 25, 45, 60, 80)

    @Test
    fun ageShapesTheFigure() {
        assertEquals(FigureLook(height = 1f, grey = 0f, hair = true), lookFor(25))
        assertEquals(lookFor(25), lookFor(null))
        assertTrue(lookFor(12).height < 0.85f)
        assertTrue(lookFor(50).grey in 0.1f..0.9f)
        assertEquals(1f, lookFor(80).grey)
    }

    @Test fun sheets() {
        listOf(false, true).forEach { dark ->
            sheet("looks${if (dark) "-dark" else ""}", dark)
        }
    }

    private fun sheet(file: String, dark: Boolean) {
        val palette = if (dark) DarkPalette else LightPalette
        val cell = Size(200f, 200f)
        val rows = listOf("Back Squat" to 0.6f, "Barbell Bench Press" to 0f)
        val bitmap = Bitmap.createBitmap((cell.width * ages.size).toInt(), (cell.height * rows.size).toInt(), Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(bitmap)), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            drawRect(palette.bgPrimary)
            rows.forEachIndexed { r, (name, t) ->
                ages.forEachIndexed { a, age ->
                    val left = a * cell.width
                    val top = r * cell.height
                    drawRect(palette.bgControl, Offset(left + 2f, top + 2f), Size(cell.width - 4f, cell.height - 4f))
                    translate(left + 12f, top + 12f) {
                        drawExerciseFigure(name, t, palette, Size(cell.width - 24f, cell.height - 24f), look = lookFor(age))
                    }
                }
            }
        }
        File("build/figures/$file.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
