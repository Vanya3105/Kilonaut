package com.example.gymtracker

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================================
// FIRST-RUN AND FIRST-TIME TIPS
// ============================================================================

@Immutable
data class TutorialStepInfo(val key: String, val title: String, val description: String)

/** Which tips have been seen, by id. "Show tips again" in Profile clears them all. */
object TutorialPrefs {
    private const val PREFS_NAME = "gym_tracker_tutorial_prefs"

    /** Bumped by "Show tips again", so screens that are open pick the tips up again. */
    var replayGeneration by mutableIntStateOf(0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSeen(context: Context, id: String) = prefs(context).getBoolean(id, false)
    fun markSeen(context: Context, id: String) = prefs(context).edit().putBoolean(id, true).apply()

    fun resetAllTutorials(context: Context) {
        prefs(context).edit().clear().apply()
        replayGeneration++
    }
}

/** Tip ids. A new version of a tour gets a new id, so people who saw the old one see it once. */
object Tips {
    const val HOME = "tour_home_v3"
    const val WORKOUT = "tour_workout_v3"
    const val REST_TIMER = "tip_rest_timer"
    const val PREVIOUS = "tip_previous_column"
    const val HISTORY = "tip_history"
    const val PROGRESS = "tip_progress"
}

/**
 * A run of tips that spotlight parts of a screen. Screens mark the parts with [tipTarget];
 * steps whose part isn't on screen are skipped, so a tour can name things that aren't always
 * there (the Previous column, a weight suggestion).
 */
@Stable
class TipTour internal constructor(val id: String, val steps: List<TutorialStepInfo>, startActive: Boolean) {
    var active by mutableStateOf(startActive)
        internal set
    var index by mutableIntStateOf(0)
        internal set
    internal val bounds = mutableStateMapOf<String, Rect>()
}

/**
 * A tour shown once: when [ready] first becomes true and it hasn't been seen. Show tips again
 * (Profile) brings it back.
 */
@Composable
fun rememberTipTour(id: String, steps: List<TutorialStepInfo>, ready: Boolean = true): TipTour {
    val context = LocalContext.current
    val tour = remember(id, TutorialPrefs.replayGeneration) { TipTour(id, steps, startActive = false) }
    LaunchedEffect(tour, ready) {
        if (ready && !tour.active && !TutorialPrefs.hasSeen(context, id)) {
            // Let the screen settle (enter animations, first layout) before dimming it.
            delay(450)
            tour.index = 0
            tour.active = true
        }
    }
    return tour
}

/** Reports where this part of the screen is while [tour] is showing; costs nothing otherwise. */
fun Modifier.tipTarget(tour: TipTour?, key: String): Modifier =
    if (tour != null && tour.active && tour.steps.any { it.key == key }) {
        onGloballyPositioned { tour.bounds[key] = it.boundsInRoot() }
    } else this

/**
 * The spotlight: the screen dims, a hole opens around the step's part with a soft pulsing ring,
 * and a card explains it. The hole starts on its target and glides between steps; the card
 * glides to the half of the screen the target isn't in, and its text slides the way you're
 * going. Tapping anywhere goes on. [onShowStep] lets a screen scroll the part into view.
 */
@Composable
fun TipTourOverlay(tour: TipTour, onShowStep: (String) -> Unit = {}) {
    if (!tour.active) return
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val step = tour.steps.getOrNull(tour.index) ?: return
    val rect = tour.bounds[step.key]?.takeIf { it.width > 0f && it.height > 0f }

    val shown = remember(tour) { Animatable(0f) }
    var leaving by remember(tour) { mutableStateOf(false) }
    var forward by remember(tour) { mutableStateOf(true) }
    LaunchedEffect(tour) { shown.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }

    fun close() {
        if (leaving) return
        leaving = true
        TutorialPrefs.markSeen(context, tour.id)
        scope.launch {
            shown.animateTo(0f, tween(200))
            tour.active = false
        }
    }
    fun go(delta: Int) {
        val next = tour.index + delta
        if (next !in tour.steps.indices) close() else { forward = delta > 0; tour.index = next }
    }

    // Ask the screen to bring the part into view; skip the step if it never shows up.
    LaunchedEffect(tour, tour.index) {
        onShowStep(step.key)
        delay(900)
        if (tour.bounds[step.key] == null && !leaving) go(if (forward) 1 else -1)
    }

    // The hole: snaps onto its first target, then springs between targets.
    val hole = remember(tour) { List(4) { Animatable(0f) } }
    var placed by remember(tour) { mutableStateOf(false) }
    LaunchedEffect(rect) {
        val r = rect ?: return@LaunchedEffect
        val target = listOf(r.left, r.top, r.width, r.height)
        if (!placed) {
            target.forEachIndexed { i, v -> hole[i].snapTo(v) }
            placed = true
        } else {
            target.forEachIndexed { i, v ->
                launch { hole[i].animateTo(v, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    }
    val pulse by rememberInfiniteTransition(label = "tip_pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "tip_pulse_t"
    )

    // The card sits in the half of the screen the target isn't in, and glides when that changes.
    val cardBias by animateFloatAsState(
        if (rect == null || rect.center.y < screenHeightPx * 0.52f) 1f else -1f,
        spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
        label = "tip_card"
    )
    val accent = P.accentPrimary

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(100f)
            .graphicsLayer { alpha = shown.value }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { go(1) }
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.74f))
            if (placed && rect != null) {
                val pad = 8.dp.toPx()
                val radius = 20.dp.toPx()
                val tl = Offset(hole[0].value - pad, hole[1].value - pad)
                val size = Size(hole[2].value + pad * 2, hole[3].value + pad * 2)
                drawRoundRect(Color.Transparent, tl, size, CornerRadius(radius), blendMode = BlendMode.Clear)
                // A ring that breathes outward from the hole, so the eye finds it.
                val grow = 10.dp.toPx() * pulse
                drawRoundRect(
                    color = accent.copy(alpha = 0.7f * (1f - pulse)),
                    topLeft = Offset(tl.x - grow, tl.y - grow),
                    size = Size(size.width + grow * 2, size.height + grow * 2),
                    cornerRadius = CornerRadius(radius + grow),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawRoundRect(accent.copy(alpha = 0.9f), tl, size, CornerRadius(radius), style = Stroke(width = 1.5.dp.toPx()))
            }
        }

        Box(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
            contentAlignment = BiasAlignment(0f, cardBias)
        ) {
            AppCard(
                Modifier.fillMaxWidth().graphicsLayer {
                    translationY = (1f - shown.value) * 24.dp.toPx() * (if (cardBias > 0f) 1f else -1f)
                },
                elevated = true,
                contentPadding = PaddingValues(20.dp)
            ) {
                val end = endSign()
                AnimatedContent(
                    targetState = tour.index,
                    transitionSpec = {
                        val dir = (if (targetState > initialState) 1 else -1) * end
                        (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(220, delayMillis = 60)))
                            .togetherWith(slideOutHorizontally(tween(200)) { -it / 6 * dir } + fadeOut(tween(140)))
                    },
                    label = "tip_text"
                ) { i ->
                    val s = tour.steps[i]
                    Column {
                        Text(s.title, style = AppType.headline, color = P.textPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(s.description, style = AppType.body, color = P.textSecondary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tour.steps.size > 1) {
                        Text(
                            tr("Skip"),
                            style = AppType.label,
                            color = P.textSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick = ::close).padding(8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        TipDots(tour.steps.size, tour.index)
                    }
                    Spacer(Modifier.weight(1f))
                    if (tour.index > 0) {
                        SecondaryButton(tr("Back"), { go(-1) }, height = 44.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    PrimaryButton(if (tour.index == tour.steps.lastIndex) tr("Got it") else tr("Next"), { go(1) }, height = 44.dp)
                }
            }
        }
    }
}

@Composable
private fun TipDots(count: Int, current: Int) {
    val P = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateFloatAsState(if (i == current) 16f else 6f, spring(dampingRatio = 0.7f), label = "tip_dot")
            Box(
                Modifier.size(width.dp, 6.dp).clip(CircleShape)
                    .background(if (i == current) P.accentPrimary else if (i < current) P.textTertiary else P.borderStrong)
            )
        }
    }
}
