package com.example.gymtracker

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// The pictures at the top of each setup step, in the welcome screen's style: the app's own cards,
// tilted a little, showing what the answers below mean and changing as they're picked.

private val HeroHeight = 196.dp

/**
 * The figure follows the user's age: a young teenager is smaller with a larger head (adult height
 * by 18), and hair greys from the late 30s, white by about 75. The goal doesn't change the body
 * (the owner found the muscular version off-putting, 2026-11-08).
 */
internal fun lookFor(age: Int?): FigureLook {
    val years = age?.takeIf { it > 0 } ?: 25
    val height = if (years >= 18) 1f else (0.74f + (years - 10).coerceAtLeast(0) / 8f * 0.26f)
    val grey = ((years - 38) / 37f).coerceIn(0f, 1f)
    return FigureLook(height = height, grey = grey, hair = true)
}

/** [look] with each part easing to its new value, so the figure changes as answers are picked. */
@Composable
private fun animated(look: FigureLook): FigureLook {
    val spec = tween<Float>(500)
    val height by animateFloatAsState(look.height, spec, label = "look_height")
    val grey by animateFloatAsState(look.grey, spec, label = "look_grey")
    return look.copy(height = height, grey = grey)
}

/** Step 1: you, with a squat in motion. */
@Composable
fun AboutYouHero(age: String, weightKg: String) {
    val P = LocalPalette.current
    val look = animated(lookFor(age.toIntOrNull()))
    Box(Modifier.fillMaxWidth().height(HeroHeight)) {
        AppCard(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(150.dp).rotate(-4f), elevated = true, contentPadding = PaddingValues(10.dp)) {
            ExerciseFigure("Back Squat", Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(P.bgControl).padding(8.dp), look = look)
        }
        AppCard(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).width(176.dp).rotate(3f), elevated = true) {
            Text(tr("You"), style = AppType.overline, color = P.accentPrimary)
            Spacer(Modifier.height(6.dp))
            Text(tr("{0} years", age.ifBlank { "–" }), style = AppType.title, color = P.textPrimary)
            Text(formatWeightKg(weightKg.toFloatOrNull() ?: 0f), style = AppType.title.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(tr("Your starting weights come from this"), style = AppType.caption, color = P.textSecondary)
        }
    }
}

/** Step 2: what the experience and goal mean for a main lift. */
@Composable
fun TrainingHero(experience: Experience?, goal: TrainingGoal?, age: String) {
    val P = LocalPalette.current
    val look = animated(lookFor(age.toIntOrNull()))
    val p = remember(experience, goal) {
        if (experience == null || goal == null) null
        else prescriptionFor(UserProfile(experience = experience, goal = goal), "Barbell Bench Press")
    }
    Box(Modifier.fillMaxWidth().height(HeroHeight)) {
        AppCard(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(140.dp).rotate(4f), elevated = true, contentPadding = PaddingValues(10.dp)) {
            ExerciseFigure("Barbell Bench Press", Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(P.bgControl).padding(8.dp), look = look)
        }
        AppCard(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).width(196.dp).rotate(-3f), elevated = true) {
            Text(shown("Barbell Bench Press"), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(goal?.label ?: tr("Your plan"), style = AppType.caption, color = P.accentPrimary)
            Spacer(Modifier.height(8.dp))
            if (p != null) {
                Text(tr("{0} × {1}", p.sets, p.repText), style = AppType.display.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                Pill(tr("Rest {0}", formatRestShort(p.restSeconds)), icon = Icons.Rounded.Schedule, container = P.bgControl)
            } else {
                Text("? × ?", style = AppType.display, color = P.textTertiary)
                Text(tr("Pick below to see your sets"), style = AppType.caption, color = P.textSecondary)
            }
        }
    }
}

/** Monday-based picks that spread [n] workouts over the week. */
private fun trainingDays(n: Int): Set<Int> = when (n) {
    2 -> setOf(Calendar.MONDAY, Calendar.THURSDAY)
    3 -> setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY)
    4 -> setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    5 -> (Calendar.MONDAY..Calendar.FRIDAY).toSet()
    else -> (Calendar.MONDAY..Calendar.SATURDAY).toSet()
}

/** Step 3: the week filling in with the days, length and place picked. */
@Composable
fun WeekHero(days: Int, minutes: Int, equipment: Equipment?) {
    val P = LocalPalette.current
    val picked = trainingDays(days)
    val letters = remember {
        // One letter, like the week strip on Today ("EEEEE" isn't narrow everywhere).
        val fmt = SimpleDateFormat("EEE", if (hebrewOn()) Locale.forLanguageTag("he") else Locale.getDefault())
        val cal = Calendar.getInstance()
        (Calendar.SUNDAY..Calendar.SATURDAY).associateWith { d -> cal.set(Calendar.DAY_OF_WEEK, d); fmt.format(cal.time).take(1).uppercase() }
    }
    val order = remember { Calendar.getInstance().firstDayOfWeek.let { first -> (0 until 7).map { (first - 1 + it) % 7 + 1 } } }
    Box(Modifier.fillMaxWidth().height(HeroHeight), contentAlignment = Alignment.Center) {
        AppCard(Modifier.fillMaxWidth().padding(horizontal = 8.dp).rotate(-2f), elevated = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Your week"), style = AppType.title, color = P.textPrimary, modifier = Modifier.weight(1f))
                Text("$days", style = AppType.display.copy(fontFeatureSettings = "tnum"), color = P.accentPrimary)
                Text(tr("/wk"), style = AppType.label, color = P.textSecondary)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                order.forEach { d ->
                    val on = d in picked
                    val bg by animateColorAsState(if (on) P.accentPrimary else P.bgControl, tween(250), label = "day_bg")
                    val scale by animateFloatAsState(if (on) 1f else 0.86f, spring(dampingRatio = 0.75f), label = "day_scale")
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(letters.getValue(d), style = AppType.caption, color = P.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(bg),
                            contentAlignment = Alignment.Center
                        ) {
                            if (on) Icon(Icons.Rounded.FitnessCenter, null, tint = P.onAccentPrimary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(tr("{0} min", minutes), icon = Icons.Rounded.Schedule, container = P.bgControl)
                equipment?.let { Pill(it.label, container = P.bgControl) }
            }
        }
    }
}

/** Step 4: the body, with focus muscles strong, the rest lighter and skipped ones blank. */
@Composable
fun BodyHero(skip: Set<String>, focus: Focus) {
    val loads = remember(skip, focus) {
        FloatArray(RECOVERY_GROUPS.size) { i ->
            val g = RECOVERY_GROUPS[i]
            when {
                g in skip -> 0f
                g in focus.muscles -> 1f
                focus == Focus.BALANCED -> 0.5f
                else -> 0.25f
            }
        }
    }
    // The map draws at its own 220 dp, so the box leaves room for it.
    Box(Modifier.fillMaxWidth().height(232.dp), contentAlignment = Alignment.Center) {
        MuscleMap(loads)
    }
}

/** Step 5: the plan's days, fanned out like cards. */
@Composable
fun PlanHero(program: SplitProgramInfo) {
    val P = LocalPalette.current
    val days = program.splits.take(4)
    if (days.isEmpty()) return
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        days.forEachIndexed { i, day ->
            val mid = (days.size - 1) / 2f
            val angle = (i - mid) * 6f
            val shift = (i - mid) * 84f
            AppCard(
                Modifier.width(104.dp).graphicsLayer { translationX = shift * density; translationY = kotlin.math.abs(i - mid) * 6f * density; rotationZ = angle },
                elevated = true
            ) {
                Text(tr("Day {0}", i + 1), style = AppType.overline, color = P.accentPrimary)
                Spacer(Modifier.height(4.dp))
                Text(shown(day.title), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
