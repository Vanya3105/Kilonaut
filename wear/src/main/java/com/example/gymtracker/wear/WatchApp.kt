package com.example.gymtracker.wear

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Typography
import com.example.gymtracker.watchlink.SetKind
import com.example.gymtracker.watchlink.WatchAction
import com.example.gymtracker.watchlink.WatchFinished
import com.example.gymtracker.watchlink.WatchState
import kotlinx.coroutines.delay
import kotlin.math.abs

// The phone app's dark palette, so the two read as one product.
private val Orange = Color(0xFFFF6A2B)
private val Muted = Color(0xFFA1A1AA)
private val Control = Color(0xFF26262B)
private val HeartRed = Color(0xFFFF5A5F)

/** Inter, as on the phone (Hebrew falls back to the system font, which has it). */
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold)
)
private val InterDisplay = FontFamily(Font(R.font.inter_display_bold, FontWeight.Bold))

private val WatchTypography = Typography(defaultFontFamily = Inter).let { t ->
    t.copy(
        display2 = t.display2.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold),
        display3 = t.display3.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold),
        title2 = t.title2.copy(fontWeight = FontWeight.SemiBold),
        title3 = t.title3.copy(fontWeight = FontWeight.SemiBold),
        button = t.button.copy(fontWeight = FontWeight.SemiBold)
    )
}

/** A tap the phone hasn't acknowledged after this long shows t("Waiting for phone…"). */
private const val SLOW_PHONE_MS = 1_500L

/** The "Saved" screen shows for this long after a workout is saved, unless dismissed. */
private const val SAVED_SCREEN_MS = 30 * 60_000L

/**
 * Bezel or crown travel for one step. A Galaxy Watch bezel click is larger than this, so each
 * click is one step; a crown sends small events that add up.
 */
private const val ROTARY_STEP_PX = 24f

@Composable
fun WatchApp(link: PhoneLink, heartRate: HeartRate? = null) {
    val haptic = LocalHapticFeedback.current
    val state = link.state
    LaunchedEffect(state?.active) { heartRate?.setWorkout(state?.active == true) }
    val restEnd = state?.restEndsAtMillis ?: 0L
    // Ticks only while a rest is running.
    val now by produceState(System.currentTimeMillis(), restEnd, state?.active) {
        value = System.currentTimeMillis()
        while (restEnd > value) {
            delay(200)
            value = System.currentTimeMillis()
        }
    }
    // While the app is on screen; otherwise the phone's "Rest's over" notification buzzes the watch.
    val context = LocalContext.current
    LaunchedEffect(restEnd) {
        if (restEnd <= System.currentTimeMillis()) return@LaunchedEffect
        // A light tap at 3, 2 and 1 so the wrist knows it's coming, then the full buzz.
        for (s in 3 downTo 1) {
            val until = restEnd - s * 1_000L - System.currentTimeMillis()
            if (until < 0) continue
            delay(until)
            if (WatchActivity.visible) tick(context)
        }
        delay((restEnd - System.currentTimeMillis()).coerceAtLeast(0))
        buzz(context)
    }
    val since = link.waitingSince
    val slow by produceState(false, since) {
        value = false
        if (since > 0L) {
            delay((SLOW_PHONE_MS - (SystemClock.uptimeMillis() - since)).coerceAtLeast(0))
            value = true
        }
    }

    WatchScreen(
        state = state,
        now = now,
        phoneConnected = link.phoneConnected,
        waitingForPhone = slow,
        onAction = {
            // A firmer tick for finishing a set or the workout than for -/+.
            val firm = it == WatchAction.COMPLETE_SET || it == WatchAction.FINISH_WORKOUT
            haptic.performHapticFeedback(if (firm) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
            link.send(it)
        },
        onStart = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            link.startWorkout(it)
        },
        onOpenOnPhone = link::openOnPhone,
        heartRate = heartRate?.bpm
    )
}

/** Everything on screen, from plain values, so it can be rendered without a phone (see the screenshot test). */
@Composable
internal fun WatchScreen(
    state: WatchState?,
    now: Long,
    phoneConnected: Boolean,
    waitingForPhone: Boolean,
    onAction: (WatchAction) -> Unit,
    onStart: (String) -> Unit,
    onOpenOnPhone: () -> Unit,
    initialOverview: Boolean = false,
    initialConfirmFinish: Boolean = false,
    heartRate: Int? = null
) {
    var overview by rememberSaveable { mutableStateOf(initialOverview) }
    var confirmFinish by rememberSaveable { mutableStateOf(initialConfirmFinish) }
    var dismissedSaved by rememberSaveable { mutableLongStateOf(0L) }
    val active = state != null && state.active
    // Both belong to a running workout: close them when it ends.
    LaunchedEffect(active) {
        if (!active) {
            overview = false
            confirmFinish = false
        }
    }

    WatchText.follow(state)
    val direction = if (WatchText.hebrew) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
    MaterialTheme(colors = Colors(primary = Orange, onPrimary = Color.Black, secondary = Orange, background = Color.Black), typography = WatchTypography) {
        val restEnd = state?.restEndsAtMillis ?: 0L
        // The set screen needs the top of the display for the exercise name, the rest screen for its
        // countdown (with the heart rate it no longer fit under the time on a 40 mm watch).
        val fullScreen = active && !overview && !confirmFinish && (restEnd > now || state?.exerciseName != null)
        Scaffold(Modifier.background(Color.Black), timeText = { if (!fullScreen) TimeText() }) {
            val note = when {
                !phoneConnected -> t("Phone not connected")
                waitingForPhone -> t("Waiting for phone…")
                else -> null
            }
            val saved = state?.lastWorkout?.takeIf { now - it.finishedAtMillis in 0 until SAVED_SCREEN_MS && it.finishedAtMillis != dismissedSaved }
            when {
                state == null -> Message(
                    if (phoneConnected) t("Connecting…") else t("Phone not connected"),
                    t("Keep your phone nearby with Kilonaut installed")
                )
                !state.active && saved != null -> SavedScreen(saved) { dismissedSaved = saved.finishedAtMillis }
                !state.active && state.days.isNotEmpty() -> StartScreen(state.days, state.days.indices.map(state::dayLabel), note, onStart, onOpenOnPhone)
                !state.active -> Message(t("No workout"), t("Start one on your phone"), onOpenOnPhone)
                confirmFinish -> ConfirmFinish(
                    state,
                    onConfirm = { confirmFinish = false; overview = false; onAction(WatchAction.FINISH_WORKOUT) },
                    onCancel = { confirmFinish = false }
                )
                overview -> {
                    BackHandler { overview = false }
                    // Swipe right goes back to the set or rest screen, which shows underneath while swiping.
                    SwipeToDismissBox(onDismissed = { overview = false }) { isBackground ->
                        if (isBackground) WorkoutScreen(state, now, note, heartRate, onAction, onOpenOverview = {}, bezel = false)
                        else Overview(state, now, onAction, onFinish = { confirmFinish = true }, onOpenOnPhone)
                    }
                }
                state.exerciseName == null && restEnd <= now -> AllDone(state, note, onAction, onFinish = { confirmFinish = true }, onOpenOverview = { overview = true })
                else -> WorkoutScreen(state, now, note, heartRate, onAction, onOpenOverview = { overview = true })
            }
        }
    }
    }
}

@Composable
private fun WorkoutScreen(
    state: WatchState,
    now: Long,
    note: String?,
    heartRate: Int?,
    onAction: (WatchAction) -> Unit,
    onOpenOverview: () -> Unit,
    // Off for the copy drawn under the overview, which would take the bezel from its list.
    bezel: Boolean = true
) {
    val restEnd = state.restEndsAtMillis
    if (restEnd > now) RestScreen(state, restEnd - now, note, heartRate, bezel, onAction, onOpenOverview)
    else SetScreen(state, note, bezel, onAction, onOpenOverview)
}

private fun tick(context: Context) {
    context.getSystemService(Vibrator::class.java)
        ?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
}

internal fun buzz(context: Context) {
    context.getSystemService(Vibrator::class.java)
        ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
}

/**
 * Turns bezel or crown rotation into one call per step, at most one per event so a bezel
 * click never counts twice. The screen needs focus to receive rotation, so it takes it.
 */
@Composable
private fun Modifier.rotarySteps(enabled: Boolean, key: Any?, onStep: (up: Boolean) -> Unit): Modifier {
    if (!enabled) return this
    val focus = remember { FocusRequester() }
    var travel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(key) { runCatching { focus.requestFocus() } }
    return this
        .onRotaryScrollEvent { e ->
            travel += e.verticalScrollPixels
            if (abs(travel) >= ROTARY_STEP_PX) {
                onStep(travel > 0)
                travel = 0f
            }
            true
        }
        .focusRequester(focus)
        .focusable()
}

@Composable
private fun Message(title: String, body: String, onOpenOnPhone: (() -> Unit)? = null) {
    var opened by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.body2, color = Muted, textAlign = TextAlign.Center)
        if (onOpenOnPhone != null) {
            Spacer(Modifier.height(10.dp))
            OpenOnPhone(opened) { onOpenOnPhone(); opened = true }
        }
    }
    if (opened) LaunchedEffect(Unit) { delay(4_000); opened = false }
}

@Composable
private fun OpenOnPhone(opened: Boolean, onClick: () -> Unit) {
    if (opened) {
        Text(t("Check your phone"), style = MaterialTheme.typography.caption1, color = Orange)
    } else {
        CompactChip(onClick = onClick, label = { Text(t("Open on phone")) }, colors = ChipDefaults.secondaryChipColors())
    }
}

/** A scrolling list that the bezel scrolls, with the scroll position on the edge. */
@Composable
private fun WatchList(content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
        PositionIndicator(scalingLazyListState = listState)
    }
}

/** No workout: the program's days, the one that's up next first. */
@Composable
private fun StartScreen(days: List<String>, labels: List<String>, note: String?, onStart: (String) -> Unit, onOpenOnPhone: () -> Unit) {
    var starting by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    WatchList {
        item { ListHeader { Text(t("Start a workout"), color = Muted) } }
        itemsIndexed(days) { i, day ->
            Chip(
                onClick = { if (starting == null) { starting = day; onStart(day) } },
                label = { Text(labels.getOrNull(i) ?: day, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = when {
                    starting == day -> { { Text(note ?: t("Starting…")) } }
                    i == 0 -> { { Text(t("Up next")) } }
                    else -> null
                },
                colors = if (i == 0) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Box(Modifier.padding(top = 4.dp)) { OpenOnPhone(opened) { onOpenOnPhone(); opened = true } } }
    }
    // The phone answers by sending the new workout; if it never does, let the user try again.
    if (starting != null) LaunchedEffect(starting) { delay(10_000); starting = null }
    if (opened) LaunchedEffect(Unit) { delay(4_000); opened = false }
}

/**
 * Everything for the set fits on a 40 mm watch without scrolling: one-line name, the set,
 * two -/+ rows and t("Set done"), kept inside the round edge. The bezel moves the outlined value;
 * tap the other value to move that one instead. Tap the name for the workout overview.
 */
@Composable
private fun SetScreen(state: WatchState, note: String?, bezel: Boolean, onAction: (WatchAction) -> Unit, onOpenOverview: () -> Unit) {
    var bezelOnSecondary by remember(state.exerciseName, state.secondary == null) { mutableStateOf(false) }
    val hasSecondary = state.secondary != null
    Box(
        Modifier.fillMaxSize().rotarySteps(bezel, state.exerciseName) { up ->
            onAction(
                when {
                    bezelOnSecondary && hasSecondary -> if (up) WatchAction.SECONDARY_PLUS else WatchAction.SECONDARY_MINUS
                    else -> if (up) WatchAction.PRIMARY_PLUS else WatchAction.PRIMARY_MINUS
                }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        WorkoutProgressArc(state)
        Column(
            Modifier.fillMaxSize().padding(vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.padding(horizontal = 34.dp).clickable(onClickLabel = t("Workout overview"), onClick = onOpenOverview),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ScrollingName(state.exerciseName.orEmpty(), MaterialTheme.typography.button)
                Text(
                    note ?: setTitle(state),
                    style = MaterialTheme.typography.caption2,
                    color = if (note != null) Muted else Orange,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(2.dp))
            Stepper(
                state.primary, state.primaryLabel, selected = hasSecondary && !bezelOnSecondary,
                onSelect = { bezelOnSecondary = false },
                onMinus = { onAction(WatchAction.PRIMARY_MINUS) }, onPlus = { onAction(WatchAction.PRIMARY_PLUS) }
            )
            state.secondary?.let { value ->
                Stepper(
                    value, state.secondaryLabel.orEmpty(), selected = bezelOnSecondary,
                    onSelect = { bezelOnSecondary = true },
                    onMinus = { onAction(WatchAction.SECONDARY_MINUS) }, onPlus = { onAction(WatchAction.SECONDARY_PLUS) }
                )
            }
            Spacer(Modifier.height(4.dp))
            CompactChip(
                onClick = { onAction(WatchAction.COMPLETE_SET) },
                label = { Text(t("Set done")) },
                colors = ChipDefaults.primaryChipColors()
            )
        }
    }
}

/** "Set 2 of 4 ›", "Warm-up ›", "Drop set ›". The arrow hints at the overview. */
private fun setTitle(state: WatchState): String {
    val label = when {
        state.setKind == SetKind.WARMUP -> t("Warm-up")
        state.setKind == SetKind.DROP -> t("Drop set")
        state.workSetCount > 0 -> t("Set {0} of {1}", state.workSetNumber, state.workSetCount)
        // From a phone app older than the set kinds: its own label.
        else -> state.setLabel
    }
    // "›" is mirrored by the text itself in right-to-left, so it points forward either way.
    return if (state.exercises.isEmpty()) "$label · ${t("{0} left", state.setsLeft)}" else "$label  ›"
}

/**
 * One line that scrolls sideways when it doesn't fit (long exercise names), rather than being
 * cut off with "…". Pauses at the start before each pass.
 */
@Composable
private fun ScrollingName(text: String, style: TextStyle, color: Color = Color.White) {
    // The edges fade out, so a long name runs off softly instead of being cut by the round screen.
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(EdgeFade, blendMode = BlendMode.DstIn)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = style,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, repeatDelayMillis = 2_000, spacing = MarqueeSpacing(24.dp))
        )
    }
}

private val EdgeFade = Brush.horizontalGradient(0f to Color.Transparent, 0.1f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent)

/** The whole workout's progress as a thin ring round the edge, from the top. */
@Composable
private fun WorkoutProgressArc(state: WatchState) {
    val total = state.exercises.sumOf { it.total }
    if (total == 0) return
    CircularProgressIndicator(
        progress = state.exercises.sumOf { it.done }.toFloat() / total,
        modifier = Modifier.fillMaxSize().padding(2.dp),
        indicatorColor = Orange,
        trackColor = Control,
        strokeWidth = 3.dp
    )
}

@Composable
private fun Stepper(value: String, label: String, selected: Boolean, onSelect: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactButton(onClick = onMinus, colors = ButtonDefaults.buttonColors(backgroundColor = Control, contentColor = Color.White)) {
            Text("−", style = MaterialTheme.typography.title3)
        }
        Column(
            Modifier
                .width(84.dp)
                .border(1.5.dp, if (selected) Orange.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = t("Turn the bezel to change {0}", label), onClick = onSelect),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Bodyweight sets come as "12 reps": the label under it already says reps.
            Text(value.removeSuffix(" $label"), style = MaterialTheme.typography.title3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.caption3, color = Muted)
        }
        CompactButton(onClick = onPlus, colors = ButtonDefaults.buttonColors(backgroundColor = Control, contentColor = Orange)) {
            Text("+", style = MaterialTheme.typography.title3)
        }
    }
}

/**
 * The bezel adds or takes off 15 s. Undo un-ticks the set that started the rest. The three
 * buttons are round so they fit inside the ring on a 40 mm watch (Skip was cut off as a chip).
 */
@Composable
private fun RestScreen(state: WatchState, remainingMillis: Long, note: String?, heartRate: Int?, bezel: Boolean, onAction: (WatchAction) -> Unit, onOpenOverview: () -> Unit) {
    Box(
        Modifier.fillMaxSize().rotarySteps(bezel, "rest") { up -> onAction(if (up) WatchAction.ADD_REST else WatchAction.REMOVE_REST) },
        contentAlignment = Alignment.Center
    ) {
        if (state.restTotalMillis > 0) {
            CircularProgressIndicator(
                progress = (remainingMillis.toFloat() / state.restTotalMillis).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize().padding(3.dp),
                indicatorColor = Orange,
                trackColor = Control,
                strokeWidth = 6.dp
            )
        }
        Column(Modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The ring and countdown already say "rest"; the line is kept for phone problems.
            note?.let { Text(it, style = MaterialTheme.typography.caption1, color = Muted) }
            Text(clock(remainingMillis), style = MaterialTheme.typography.display2)
            heartRate?.let { HeartRateLine(it) }
            Column(
                Modifier.padding(horizontal = 22.dp).clickable(onClickLabel = t("Workout overview"), onClick = onOpenOverview),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val next = state.exerciseName
                if (next != null) {
                    Text(t("Next"), style = MaterialTheme.typography.caption3, color = Orange)
                    ScrollingName(next, MaterialTheme.typography.caption1)
                    Text(state.summary, style = MaterialTheme.typography.caption2, color = Muted, maxLines = 1)
                } else {
                    Text(t("Last set done"), style = MaterialTheme.typography.caption2, color = Muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.canUndo) {
                    RoundButton(onClick = { onAction(WatchAction.UNDO_SET) }) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = t("Undo set"), Modifier.size(18.dp))
                    }
                }
                RoundButton(onClick = { onAction(WatchAction.ADD_REST) }, label = t("Add 15 seconds")) {
                    Text(t("+15"), style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold))
                }
                RoundButton(onClick = { onAction(WatchAction.SKIP_REST) }, primary = true) {
                    Icon(painterResource(R.drawable.ic_skip), contentDescription = t("Skip rest"), Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, primary: Boolean = false, label: String? = null, content: @Composable () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (primary) ButtonDefaults.buttonColors(backgroundColor = Orange, contentColor = Color.Black)
        else ButtonDefaults.buttonColors(backgroundColor = Control, contentColor = Color.White),
        modifier = Modifier.size(40.dp).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
    ) { content() }
}

/** The heart rate with a small heart, from the watch's own sensor. */
@Composable
private fun HeartRateLine(bpm: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_heart), contentDescription = t("Heart rate"), tint = HeartRed, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text("$bpm", style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(3.dp))
        Text(t("bpm"), style = MaterialTheme.typography.caption3, color = Muted)
    }
}

/** Every set is ticked: finish here, or undo the last one. */
@Composable
private fun AllDone(state: WatchState, note: String?, onAction: (WatchAction) -> Unit, onFinish: () -> Unit, onOpenOverview: () -> Unit) {
    WatchList {
        item { CheckBadge() }
        item {
            Text(
                t("All sets done"),
                style = MaterialTheme.typography.title3,
                modifier = Modifier.clickable(onClickLabel = t("Workout overview"), onClick = onOpenOverview)
            )
        }
        item { Text(note ?: state.workoutName, style = MaterialTheme.typography.caption1, color = Muted) }
        item {
            Chip(
                onClick = onFinish,
                label = { Text(t("Finish workout")) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.addSetTo?.let { name -> item { AddSetChip(t("One more set"), name, onAction) } }
        if (state.canUndo) item { UndoChip(onAction) }
    }
}

/** A copy of the last set of [exercise], done next. */
@Composable
private fun AddSetChip(label: String, exercise: String, onAction: (WatchAction) -> Unit) {
    Chip(
        onClick = { onAction(WatchAction.ADD_SET) },
        label = { Text(label) },
        secondaryLabel = { Text(exercise, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(ChipDefaults.IconSize)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun UndoChip(onAction: (WatchAction) -> Unit) {
    Chip(
        onClick = { onAction(WatchAction.UNDO_SET) },
        label = { Text(t("Undo last set")) },
        icon = { Icon(painterResource(R.drawable.ic_undo), contentDescription = null, Modifier.size(ChipDefaults.IconSize)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CheckBadge() {
    Box(Modifier.size(40.dp).background(Orange, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.Black, modifier = Modifier.size(26.dp))
    }
}

/** The whole workout: time, sets, each exercise's progress, then Finish. */
@Composable
private fun Overview(state: WatchState, now: Long, onAction: (WatchAction) -> Unit, onFinish: () -> Unit, onOpenOnPhone: () -> Unit) {
    val elapsedNow = rememberTicking(now)
    val done = state.exercises.sumOf { it.done }
    val total = state.exercises.sumOf { it.total }
    var opened by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        WatchList {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.workoutName, style = MaterialTheme.typography.title3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            state.startedAtMillis.takeIf { it > 0L }?.let { clock(elapsedNow - it) },
                            t("{0} of {1} sets", done, total)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.caption1,
                        color = Muted
                    )
                }
            }
            itemsIndexed(state.exercises) { i, ex ->
                val current = i == state.currentExercise
                val finished = ex.total > 0 && ex.done == ex.total
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Control, RoundedCornerShape(20.dp))
                        .border(1.5.dp, if (current) Orange else Color.Transparent, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        ex.name,
                        style = MaterialTheme.typography.caption1,
                        color = if (finished) Muted else Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    if (finished) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = t("Done"), tint = Orange, modifier = Modifier.size(16.dp))
                    } else {
                        Text("${ex.done}/${ex.total}", style = MaterialTheme.typography.caption1, color = if (current) Orange else Muted)
                    }
                }
            }
            item {
                if (done > 0) {
                    Chip(
                        onClick = onFinish,
                        label = { Text(t("Finish workout")) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                } else {
                    Text(
                        t("Tick a set to finish here, or discard on your phone"),
                        style = MaterialTheme.typography.caption2,
                        color = Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
            // Mid-workout: one more set of what was just done (or what's on now).
            if (state.exerciseName != null) state.addSetTo?.let { name -> item { AddSetChip(t("Add a set"), name, onAction) } }
            if (state.canUndo) item { UndoChip(onAction) }
            item { OpenOnPhone(opened) { onOpenOnPhone(); opened = true } }
        }
    }
    if (opened) LaunchedEffect(Unit) { delay(4_000); opened = false }
}

@Composable
private fun ConfirmFinish(state: WatchState, onConfirm: () -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val left = state.setsLeft
    Column(
        Modifier.fillMaxSize().padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(t("Finish {0}?", state.workoutName), style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                left == 1 -> t("1 set isn't ticked. It's saved as it is.")
                left > 1 -> t("{0} sets aren't ticked. It's saved as it is.", left)
                else -> t("It's saved to your history.")
            },
            style = MaterialTheme.typography.caption1,
            color = Muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(onClick = onCancel, colors = ButtonDefaults.secondaryButtonColors()) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = t("Keep going"))
            }
            Button(onClick = onConfirm, colors = ButtonDefaults.primaryButtonColors()) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = t("Finish and save"))
            }
        }
    }
}

/** Shown after the workout is saved, from here or the phone. */
@Composable
private fun SavedScreen(saved: WatchFinished, onDone: () -> Unit) {
    WatchList {
        item { CheckBadge() }
        item { Text(t("Workout saved"), style = MaterialTheme.typography.title3) }
        item {
            Text(
                "${saved.workoutName} · ${minutes(saved.durationSeconds)}",
                style = MaterialTheme.typography.caption1,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("${saved.sets}", if (saved.sets == 1) t("set") else t("sets"))
                saved.volume?.let { Stat(it, t("volume")) }
            }
        }
        if (saved.newBests > 0) {
            item {
                Text(
                    if (saved.newBests == 1) t("1 new best") else t("{0} new bests", saved.newBests),
                    style = MaterialTheme.typography.caption1,
                    color = Orange
                )
            }
        }
        item {
            Chip(
                onClick = onDone,
                label = { Text(t("Done")) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.title3)
        Text(label, style = MaterialTheme.typography.caption3, color = Muted)
    }
}

/** [now], moving on by itself once a second. Starts at [now] so screenshots are stable. */
@Composable
private fun rememberTicking(now: Long): Long {
    val ticking by produceState(now, now) {
        val base = SystemClock.uptimeMillis()
        while (true) {
            delay(1_000)
            value = now + SystemClock.uptimeMillis() - base
        }
    }
    return ticking
}

/** 95 000 ms → "1:35", rounding up so the timer never shows 0:00 while still resting. */
internal fun clock(millis: Long): String {
    val total = ((millis + 999) / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = (total % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
}

/** 3 120 s → "52 min", 4 500 s → "1 h 15 min". */
internal fun minutes(seconds: Long): String {
    val m = (seconds + 30) / 60
    return if (m >= 60) t("{0} h {1} min", m / 60, m % 60) else t("{0} min", m)
}
