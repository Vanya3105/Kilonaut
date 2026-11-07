package com.example.gymtracker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DAY_MINUTES = 24 * 60
private const val STEP_MINUTES = 5

/** "7:30 AM" / "07:30", as the phone shows times. */
internal fun clockText(minutes: Int, is24: Boolean): String {
    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60) }
    return SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault()).format(cal.time)
}

/** Night, morning, afternoon or evening, for the line under the time. */
internal fun partOfDay(minutes: Int): String = when (minutes / 60) {
    in 5..11 -> tr("Morning")
    in 12..16 -> tr("Afternoon")
    in 17..20 -> tr("Evening")
    else -> tr("Night")
}

/** The angle of [p] around the centre of a [w]×[h] box: 0 at the top, clockwise. */
private fun degreesFromTop(p: Offset, w: Int, h: Int): Float =
    (atan2((p.x - w / 2f).toDouble(), -(p.y - h / 2f).toDouble()) * 180.0 / PI).toFloat()

/** Minutes after midnight at an angle on the dial, midnight at the top, clockwise; to the nearest step. */
internal fun minutesAtAngle(degreesFromTop: Float): Int {
    val f = ((degreesFromTop % 360f) + 360f) % 360f / 360f
    return ((f * DAY_MINUTES / STEP_MINUTES).roundToInt() * STEP_MINUTES) % DAY_MINUTES
}

/**
 * Picks a time of day on a ring coloured like the day itself: night blue at the top (midnight),
 * sunrise on the right, noon's yellow at the bottom, dusk on the left. Drag the sun (a moon at
 * night) round, or tap the ring; −5 / +5 fine-tune. A tick every quarter hour, a firmer one on
 * the hour.
 */
@Composable
fun TimeDialSheet(title: String, initialMinutes: Int, is24: Boolean, hapticsOn: Boolean, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val P = LocalPalette.current
    val view = LocalView.current
    val haptics = remember(hapticsOn, view) { Haptics(hapticsOn, view) }
    var minutes by remember { mutableIntStateOf(initialMinutes) }
    // The knob's angle, animated: buttons glide it, a finger moves it directly.
    val angle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Opening: the sun swings round from midnight to the saved time.
    LaunchedEffect(Unit) { angle.animateTo(initialMinutes * 360f / DAY_MINUTES, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessVeryLow)) }

    fun set(m: Int, animate: Boolean) {
        val new = ((m % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
        if (new == minutes) return
        if (new / 60 != minutes / 60) haptics.confirm() else if (new % 15 == 0) haptics.tick()
        minutes = new
        // Take the short way round, across midnight too.
        val target = new * 360f / DAY_MINUTES
        var delta = (target - angle.value) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        scope.launch {
            if (animate) angle.animateTo(angle.value + delta, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
            else angle.snapTo(angle.value + delta)
        }
    }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = AppType.title, color = P.textPrimary, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            // A clock reads clockwise in every language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                DayDial(
                    angle = { angle.value },
                    minutes = minutes,
                    is24 = is24,
                    onAngle = { deg, animate -> set(minutesAtAngle(deg), animate) },
                    modifier = Modifier.fillMaxWidth(0.86f).aspectRatio(1f)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(tr("−5 min"), { set(minutes - STEP_MINUTES, true) }, Modifier.width(110.dp), height = 44.dp)
                SecondaryButton(tr("+5 min"), { set(minutes + STEP_MINUTES, true) }, Modifier.width(110.dp), height = 44.dp)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(tr("Save"), { onSave(minutes) }, Modifier.fillMaxWidth())
        }
    }
}

// Night, dawn, noon, dusk, night: the ring's colours from midnight round.
private val NightBlue = Color(0xFF2E3570)
private val Dawn = Color(0xFFFF8A4C)
private val Noon = Color(0xFFFFC94D)
private val Dusk = Color(0xFFE5501B)

@Composable
private fun DayDial(angle: () -> Float, minutes: Int, is24: Boolean, onAngle: (Float, Boolean) -> Unit, modifier: Modifier) {
    val P = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val labelStyle = AppType.caption.copy(color = P.textTertiary)
    val time = clockText(minutes, is24)
    val part = partOfDay(minutes)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = tr("{0}, {1}", time, part)
                    progressBarRangeInfo = ProgressBarRangeInfo(minutes.toFloat(), 0f..(DAY_MINUTES - STEP_MINUTES).toFloat(), DAY_MINUTES / STEP_MINUTES - 1)
                    setProgress { v -> onAngle(v * 360f / DAY_MINUTES, true); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures { onAngle(degreesFromTop(it, size.width, size.height), true) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { onAngle(degreesFromTop(it, size.width, size.height), false) }) { change, _ ->
                        change.consume()
                        onAngle(degreesFromTop(change.position, size.width, size.height), false)
                    }
                }
        ) {
            val ring = size.minDimension * 0.085f
            val radius = size.minDimension / 2f - ring / 2f - 18.dp.toPx()
            val c = center
            // The day as a ring. sweepGradient starts at 3 o'clock: turned so midnight is at the top.
            rotate(-90f) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to NightBlue, 0.17f to NightBlue, 0.27f to Dawn, 0.42f to Noon,
                        0.6f to Noon, 0.76f to Dusk, 0.88f to NightBlue, 1f to NightBlue,
                        center = c
                    ),
                    radius, c, style = Stroke(ring)
                )
            }
            // Hour ticks just inside, longer every six hours, with their times.
            for (h in 0 until 24) {
                val a = (h / 24f) * 2 * PI.toFloat()
                val major = h % 6 == 0
                val inner = radius - ring / 2f - (if (major) 12.dp else 7.dp).toPx()
                val outer = radius - ring / 2f - 3.dp.toPx()
                drawLine(
                    if (major) P.textTertiary else P.borderStrong,
                    Offset(c.x + sin(a) * inner, c.y - cos(a) * inner),
                    Offset(c.x + sin(a) * outer, c.y - cos(a) * outer),
                    strokeWidth = (if (major) 2.dp else 1.dp).toPx()
                )
                if (major) {
                    val text = measurer.measure(clockText(h * 60, is24).let { if (is24) it else it.replace(":00", "") }, labelStyle)
                    val lr = radius + ring / 2f + 10.dp.toPx()
                    // Outside the ring, centred on the hour.
                    val lx = c.x + sin(a) * lr - text.size.width / 2f + sin(a) * text.size.width / 2f
                    val ly = c.y - cos(a) * lr - text.size.height / 2f - cos(a) * text.size.height / 2f
                    drawText(text, topLeft = Offset(lx, ly))
                }
            }
            // The knob: a sun by day, a moon by night, riding the ring.
            val a = Math.toRadians(angle().toDouble()).toFloat()
            val k = Offset(c.x + sin(a) * radius, c.y - cos(a) * radius)
            val knob = ring * 0.95f
            drawCircle(Color.Black.copy(alpha = 0.18f), knob + 2.dp.toPx(), k + Offset(0f, 2.dp.toPx()))
            drawCircle(Color.White, knob, k)
            val day = minutes in 6 * 60 until 18 * 60
            if (day) {
                drawCircle(Dusk, knob * 0.42f, k)
                for (r in 0 until 8) {
                    val ra = r * PI.toFloat() / 4f
                    drawLine(
                        Dusk,
                        Offset(k.x + sin(ra) * knob * 0.56f, k.y - cos(ra) * knob * 0.56f),
                        Offset(k.x + sin(ra) * knob * 0.74f, k.y - cos(ra) * knob * 0.74f),
                        strokeWidth = knob * 0.1f
                    )
                }
            } else {
                // A crescent: a disc with a bite taken out of it.
                val moon = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(k, knob * 0.55f))
                }
                val bite = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(k + Offset(knob * 0.3f, -knob * 0.22f), knob * 0.48f))
                }
                drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, moon, bite), NightBlue)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, style = AppType.display.copy(fontSize = AppType.display.fontSize * 1.15f, fontFeatureSettings = "tnum"), color = P.textPrimary)
            Text(part, style = AppType.label, color = P.textSecondary)
        }
    }
}
