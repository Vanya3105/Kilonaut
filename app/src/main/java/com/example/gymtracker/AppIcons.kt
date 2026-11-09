package com.example.gymtracker

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// ============================================================================
// APP ICONS
// The tab bar's own icons: one 24-unit grid, one rounded 1.8 stroke, so they read as a set
// rather than four stock glyphs. Selected versions fill the shape's main part.
// ============================================================================

private const val STROKE = 1.8f

private fun icon(name: String, strokes: List<String>, fills: List<String> = emptyList(), fillAlpha: Float = 1f): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        // Tinted by Icon(); the colour here only marks what's drawn.
        fills.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black), fillAlpha = fillAlpha) }
        strokes.forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()

private const val PLATES =
    "M4.5,8 h1.5 a1,1 0 0 1 1,1 v6 a1,1 0 0 1 -1,1 h-1.5 a1,1 0 0 1 -1,-1 v-6 a1,1 0 0 1 1,-1 z " +
        "M18,8 h1.5 a1,1 0 0 1 1,1 v6 a1,1 0 0 1 -1,1 h-1.5 a1,1 0 0 1 -1,-1 v-6 a1,1 0 0 1 1,-1 z"
private const val BAR = "M7,12 H17 M2,12 H3.5 M20.5,12 H22"

private const val CALENDAR = "M6,5 H18 a2.5,2.5 0 0 1 2.5,2.5 V18 a2.5,2.5 0 0 1 -2.5,2.5 H6 a2.5,2.5 0 0 1 -2.5,-2.5 V7.5 A2.5,2.5 0 0 1 6,5 z"
private const val CALENDAR_PINS = "M8,3 V6.5 M16,3 V6.5 M3.5,10 H20.5"
private const val CALENDAR_TICK = "M9,15 l2,2 l4,-4"
// The header band, filled when selected.
private const val CALENDAR_BAND = "M6,5 H18 a2.5,2.5 0 0 1 2.5,2.5 V10 H3.5 V7.5 A2.5,2.5 0 0 1 6,5 z"

private const val CHART_LINE = "M4,16 L9,11 L13,14 L20,7"
private const val CHART_ARROW = "M15.5,7 H20 V11.5"
private const val CHART_BASE = "M4,20 H20"
private const val CHART_AREA = "M4,16 L9,11 L13,14 L20,7 V20 H4 z"

private const val HEAD = "M12,4.5 a3.5,3.5 0 1 1 0,7 a3.5,3.5 0 1 1 0,-7 z"
private const val SHOULDERS = "M5,20 c0,-3.9 3.1,-6 7,-6 s7,2.1 7,6"
private const val BODY = "M5,20 c0,-3.9 3.1,-6 7,-6 s7,2.1 7,6 z"

// Two people, the one in front whole and the one behind showing a head and a shoulder.
private const val FRONT_HEAD = "M9,5.5 a3,3 0 1 1 0,6 a3,3 0 1 1 0,-6 z"
private const val FRONT_SHOULDERS = "M3,20 c0,-3.4 2.7,-5.6 6,-5.6 s6,2.2 6,5.6"
private const val FRONT_BODY = "M3,20 c0,-3.4 2.7,-5.6 6,-5.6 s6,2.2 6,5.6 z"
private const val BACK_HEAD = "M16.5,5 a2.6,2.6 0 1 1 0,5.2 a2.6,2.6 0 1 1 0,-5.2 z"
private const val BACK_SHOULDER = "M16.2,13.6 c3,0 4.8,2.1 4.8,5.2"

object AppIcons {
    val Today = icon("Today", listOf(PLATES, BAR))
    val TodayFilled = icon("TodayFilled", listOf(PLATES, BAR), fills = listOf(PLATES))

    val History = icon("History", listOf(CALENDAR, CALENDAR_PINS, CALENDAR_TICK))
    val HistoryFilled = icon("HistoryFilled", listOf(CALENDAR, CALENDAR_PINS, CALENDAR_TICK), fills = listOf(CALENDAR_BAND))

    val Progress = icon("Progress", listOf(CHART_LINE, CHART_ARROW, CHART_BASE))
    val ProgressFilled = icon("ProgressFilled", listOf(CHART_LINE, CHART_ARROW, CHART_BASE), fills = listOf(CHART_AREA), fillAlpha = 0.35f)

    val Feed = icon("Feed", listOf(FRONT_HEAD, FRONT_SHOULDERS, BACK_HEAD, BACK_SHOULDER))
    val FeedFilled = icon("FeedFilled", listOf(FRONT_HEAD, FRONT_SHOULDERS, BACK_HEAD, BACK_SHOULDER), fills = listOf(FRONT_HEAD, FRONT_BODY))

    val Profile = icon("Profile", listOf(HEAD, SHOULDERS))
    val ProfileFilled = icon("ProfileFilled", listOf(HEAD, SHOULDERS), fills = listOf(HEAD, BODY))
}
