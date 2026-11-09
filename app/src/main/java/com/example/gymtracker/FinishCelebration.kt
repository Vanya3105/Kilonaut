package com.example.gymtracker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The moment after Save: a badge that pops in, confetti, a short chime, and what the workout
 * meant (workout number, new bests and what they beat, the streak) with Share and Done.
 */
@Composable
fun WorkoutCelebration(data: ShareData, haptics: Haptics, onShare: () -> Unit, onDone: () -> Unit) {
    val P = LocalPalette.current
    BackHandler(onBack = onDone)
    val bests = data.bests
    val context = LocalContext.current
    val look = remember { lookFor(LocalStorage.loadUserProfile(context).age) }
    // Read after the save, so this workout counts.
    val week = remember {
        computeWeekSummary(LocalStorage.loadHistory(context), 0, LocalStorage.loadUserProfile(context).splitProgram)
    }
    val badge = remember { Animatable(0f) }
    var sharingToFeed by remember { mutableStateOf(false) }
    val content = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { badge.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)) }
        delay(180)
        haptics.confirm()
        content.animateTo(1f, tween(420))
    }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(200f)
            .background(P.bgPrimary)
            .background(Brush.radialGradient(listOf(P.accentSoft, P.bgPrimary), radius = 1400f))
            // Swallows touches meant for the screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            // You, celebrating: the setup figure (your age) hops with its arms up, once. A trophy beside it for a new best.
            Box(Modifier.size(width = 200.dp, height = 156.dp)) {
                CheerFigure(Modifier.fillMaxSize(), look = look)
                if (bests.isNotEmpty()) Box(
                    Modifier.align(Alignment.TopEnd).size(48.dp).graphicsLayer { scaleX = badge.value; scaleY = badge.value }
                        .clip(CircleShape).background(P.warningSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.EmojiEvents, null, tint = P.warningText, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.graphicsLayer { alpha = content.value; translationY = (1f - content.value) * 24.dp.toPx() },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    when {
                        bests.size > 1 -> tr("{0} new personal bests", bests.size)
                        bests.size == 1 -> tr("New personal best")
                        else -> tr("Workout complete")
                    },
                    style = AppType.display,
                    color = P.textPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    tr("{0} · your {1} workout", shown(data.title), ordinal(data.workoutNumber)) +
                        if (data.weekStreak >= 2) tr(" · {0} weeks in a row", data.weekStreak) else "",
                    style = AppType.body,
                    color = P.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountUpTile(data.durationSeconds.toFloat(), { formatDuration(it.toLong()) }, tr("Time"), Modifier.weight(1f), delayMillis = 350)
                    CountUpTile(data.workSets.toFloat(), { "${it.roundToInt()}" }, tr("Sets"), Modifier.weight(1f), delayMillis = 450)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        data.volumeKg > 0 -> CountUpTile(data.volumeKg.toFloat(), { formatVolume(it.roundToInt()) }, tr("Volume"), Modifier.weight(1f), delayMillis = 550)
                        data.reps > 0 || data.heldSeconds == 0 -> CountUpTile(data.reps.toFloat(), { "${it.roundToInt()}" }, tr("Reps"), Modifier.weight(1f), delayMillis = 550)
                        // Only timed sets (planks, hangs): "0 reps" said nothing.
                        else -> CountUpTile(data.heldSeconds.toFloat(), { formatDuration(it.toLong()) }, tr("Held"), Modifier.weight(1f), delayMillis = 550)
                    }
                    // No "0 new bests": without one, the tile counts exercises.
                    if (bests.isNotEmpty()) CountUpTile(
                        bests.size.toFloat(), { "${it.roundToInt()}" }, if (bests.size == 1) tr("New best") else tr("New bests"), Modifier.weight(1f),
                        delayMillis = 650, valueColor = P.warningText
                    ) else CountUpTile(
                        data.lifts.size.toFloat(), { "${it.roundToInt()}" }, if (data.lifts.size == 1) tr("Exercise") else tr("Exercises"), Modifier.weight(1f),
                        delayMillis = 650
                    )
                }
                volumeComparison(data.volumeKg)?.let { thing ->
                    Text(tr("You lifted {0}.", thing), style = AppType.label, color = P.textSecondary, modifier = Modifier.padding(top = 12.dp))
                }
                data.volumeChangePct?.takeIf { it > 0 }?.let { pct ->
                    Text(tr("{0}% more volume than your last {1}", pct, shown(data.title)), style = AppType.label, color = P.successText, modifier = Modifier.padding(top = 4.dp))
                }
                if (bests.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.bgCard).padding(16.dp)) {
                        bests.take(4).forEachIndexed { i, b ->
                            if (i > 0) Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.EmojiEvents, null, tint = P.warningText, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(shown(b.name), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(b.before?.let { tr("Was {0}", it) } ?: tr("First time logged"), style = AppType.caption, color = P.textSecondary)
                                }
                                Text(b.set, style = AppType.bodyStrong.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                            }
                        }
                        if (bests.size > 4) Text(tr("and {0} more", bests.size - 4), style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                WeekProgress(data.daysThisWeek, week.workouts.size, week.weeklyGoal, week.recommendedSplit)
                Spacer(Modifier.height(28.dp))
                PrimaryButton(tr("Done"), onDone, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                // Friends first (when there's an account), then the image for anywhere else.
                // Only with Friends set up (a username): sharing needs somewhere to go.
                LaunchedEffect(Account.user?.uid) { if (Account.user != null && Friends.myHandle == null) runCatching { Friends.loadMe(context) } }
                if (Account.user != null && Friends.myHandle != null) {
                    SecondaryButton(tr("Share to feed"), { sharingToFeed = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.DynamicFeed)
                    Spacer(Modifier.height(10.dp))
                }
                SecondaryButton(tr("Share"), onShare, Modifier.fillMaxWidth(), icon = Icons.Rounded.IosShare)
                Spacer(Modifier.height(24.dp))
            }
        }
        if (sharingToFeed) {
            val all = remember { LocalStorage.loadHistory(context) }
            ShareWorkoutComposer(all.firstOrNull { it.id == data.whenMillis }, all, onClose = { sharingToFeed = false })
        }
        // Confetti is for milestones only: after every workout it made the app feel like a game.
        if (isMilestone(data.workoutNumber)) ConfettiBurst(Modifier.fillMaxSize(), pieces = 90, cannons = true)
    }
}

/**
 * The week so far: a dot per day (Sunday first, as everywhere in the app), filled where you
 * trained, today ringed; how many of the plan's workouts are done; and what's next.
 */
@Composable
private fun WeekProgress(days: Set<Int>, done: Int, goal: Int, next: String) {
    val P = LocalPalette.current
    val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    val letters = (Calendar.SUNDAY..Calendar.SATURDAY).zip(weekdayLetters())
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.bgCard).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("This week"), style = AppType.bodyStrong, color = P.textPrimary, modifier = Modifier.weight(1f))
            Text(
                if (goal > 0) tr("{0} of {1}", done, goal) else "$done",
                style = AppType.bodyStrong.copy(fontFeatureSettings = "tnum"),
                color = if (goal in 1..done) P.successText else P.textPrimary
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            letters.forEach { (d, letter) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(letter, style = AppType.caption, color = if (d == today) P.textPrimary else P.textTertiary)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .background(if (d in days) P.accentPrimary else P.bgControl)
                            .then(if (d == today && d !in days) Modifier.border(1.5.dp, P.textTertiary, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        if (d in days) Icon(Icons.Rounded.Check, null, tint = P.onAccentPrimary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        if (next.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                if (goal in 1..done) tr("Plan done for the week. Next time: {0}", shown(next)) else tr("Next: {0}", shown(next)),
                style = AppType.caption, color = P.textSecondary
            )
        }
    }
}

/** The 1st, 10th, 25th, 50th and 100th workout, then every 100th. */
internal fun isMilestone(workoutNumber: Int) = workoutNumber in setOf(1, 10, 25, 50) || (workoutNumber > 0 && workoutNumber % 100 == 0)

private fun ordinal(n: Int): String {
    if (hebrewOn()) return "$n"
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}

/**
 * A short "ta-da" made in code (no sound file): a quick C-E-G run, then a bright C major chord
 * that rings out, about a second and a half. Only when the phone's sound is on; it plays as a
 * notification-type sound, so it ducks music instead of stopping it.
 */
object WorkoutChime {
    fun play(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        Thread {
            runCatching {
                val rate = 44_100
                val total = (rate * 1.75).toInt()
                val mix = FloatArray(total)
                // A brassy tone: the note with some of its 2nd and 3rd harmonics, a little vibrato once held.
                fun note(freq: Float, startSec: Float, lengthSec: Float, decay: Float, gain: Float) {
                    val start = (startSec * rate).toInt()
                    val length = (lengthSec * rate).toInt().coerceAtMost(total - start)
                    for (n in 0 until length) {
                        val t = n.toFloat() / rate
                        val f = freq * (1f + 0.004f * sin(2 * PI * 5.5 * t).toFloat() * (t * 3f).coerceAtMost(1f))
                        val phase = 2 * PI * f * t
                        val tone = sin(phase) + 0.35 * sin(2 * phase) + 0.15 * sin(3 * phase)
                        val env = (1f - exp(-t * 140f)) * exp(-t * decay)
                        mix[start + n] += (tone * env * gain).toFloat()
                    }
                }
                // The run up...
                floatArrayOf(523.25f, 659.25f, 783.99f).forEachIndexed { i, f -> note(f, i * 0.1f, 0.4f, 9f, 0.5f) }
                // ...and the chord, held.
                floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f).forEach { f -> note(f, 0.32f, 1.4f, 2.4f, 0.32f) }
                val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.001f)
                val pcm = ShortArray(total) { (mix[it] / peak * 0.85f * Short.MAX_VALUE).toInt().toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((total * 1000L / rate) + 200)
                track.release()
            }
        }.apply { isDaemon = true }.start()
    }
}
