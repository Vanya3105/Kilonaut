package com.example.gymtracker

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

// ============================================================================
// PRESS FEEDBACK
// ============================================================================

/**
 * Clickable with ripple plus a small springy scale-down while pressed.
 *
 * The scale is part of the click's indication (a Modifier.Node), not a `composed` modifier with
 * its own state and graphics layer: nothing is allocated at composition, and the interaction
 * source is only created on the first touch. That matters because almost every tappable thing
 * in the app (cards, chips, calendar days, buttons) uses this.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    role: Role = Role.Button,
    onClick: () -> Unit
): Modifier = clickable(
    interactionSource = null,
    indication = PressScaleIndication.of(pressedScale),
    enabled = enabled,
    role = role,
    onClick = onClick
)

/** Ripple, with the content scaled down while held. */
private class PressScaleIndication private constructor(private val pressedScale: Float) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressScaleNode(interactionSource, pressedScale, RIPPLE.create(interactionSource))

    override fun equals(other: Any?) = other is PressScaleIndication && other.pressedScale == pressedScale
    override fun hashCode() = pressedScale.hashCode()

    companion object {
        private val RIPPLE = ripple()
        private val cache = HashMap<Float, PressScaleIndication>()
        fun of(pressedScale: Float) = cache.getOrPut(pressedScale) { PressScaleIndication(pressedScale) }
    }
}

private class PressScaleNode(
    private val source: InteractionSource,
    private val pressedScale: Float,
    ripple: DelegatableNode
) : DelegatingNode(), DrawModifierNode {
    private val scale = Animatable(1f)

    init {
        delegate(ripple)
    }

    override fun onAttach() {
        coroutineScope.launch {
            val held = ArrayList<PressInteraction.Press>(1)
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> held += interaction
                    is PressInteraction.Release -> held -= interaction.press
                    is PressInteraction.Cancel -> held -= interaction.press
                    else -> return@collect
                }
                val target = if (held.isEmpty()) 1f else pressedScale
                launch { scale.animateTo(target, spring(dampingRatio = 0.55f, stiffness = 700f)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = scale.value
        if (s == 1f) drawContent() else scale(s) { this@draw.drawContent() }
    }
}

// ============================================================================
// SURFACES
// ============================================================================

val CardShape = RoundedCornerShape(16.dp)

/**
 * The app's base card. Light mode lifts it with a soft warm shadow; dark mode separates it with a
 * slightly lighter tone and a hairline border (shadows don't read on dark backgrounds).
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    color: Color? = null,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val P = LocalPalette.current
    val bg = color ?: if (elevated) P.bgElevated else P.bgCard
    val shadowed = if (!P.isDark) {
        // Cards sit on a grey page; a hairline shadow is enough to lift them.
        modifier.shadow(
            elevation = if (elevated) 3.dp else 1.dp,
            shape = shape,
            ambientColor = P.shadow.copy(alpha = 0.06f),
            spotColor = P.shadow.copy(alpha = 0.08f)
        )
    } else modifier
    Column(
        modifier = shadowed
            .clip(shape)
            .background(bg)
            .border(1.dp, if (P.isDark) P.borderSubtle else P.borderSubtle.copy(alpha = 0.6f), shape)
            .then(if (onClick != null) Modifier.pressable(pressedScale = 0.985f, onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 54.dp
) {
    val P = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    // Flat and solid: a gradient and a coloured glow made it look like a toy.
    val bg = SolidColor(if (enabled) P.accentPrimary else P.bgControl)
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (enabled) P.onAccentPrimary else P.textTertiary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = AppType.bodyStrong, color = fg)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentColor: Color? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp
) {
    val P = LocalPalette.current
    val fg = if (enabled) contentColor ?: P.textPrimary else P.textTertiary
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(P.bgControl)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = AppType.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color? = null,
    container: Color? = null,
    enabled: Boolean = true
) {
    val P = LocalPalette.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container ?: P.bgControl)
            .pressable(enabled = enabled, pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription,
            tint = if (enabled) tint ?: P.textPrimary else P.textTertiary,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.textSecondary,
    container: Color = LocalPalette.current.bgControl,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = AppType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = color, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val P = LocalPalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = AppType.headline, color = P.textPrimary, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            // Quiet on purpose: the accent is kept for the screen's main action.
            Text(
                action,
                style = AppType.label,
                color = P.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    val P = LocalPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(P.bgControl)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RollingText(value, style = AppType.number.copy(fontSize = AppType.headline.fontSize), color = valueColor ?: P.textPrimary)
        Spacer(Modifier.height(2.dp))
        Text(label, style = AppType.caption, color = P.textSecondary, maxLines = 1)
    }
}

/** An icon in a quiet ring, a title and a line under it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                drawCircle(P.bgControl, size.minDimension * 0.34f, c)
            }
            Icon(icon, null, tint = P.textSecondary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = AppType.bodyStrong, color = P.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(body, style = AppType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = P.textSecondary, textAlign = TextAlign.Center)
    }
}

// ============================================================================
// CONTROLS
// ============================================================================

/** Segmented control whose highlight slides between options. */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val P = LocalPalette.current
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    // At least 42dp, taller when large text wraps a label ("Kilograms (kg)") onto two lines.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(P.bgControl)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "segment_indicator"
        )
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .shadow(if (P.isDark) 0.dp else 2.dp, RoundedCornerShape(11.dp), spotColor = P.shadow.copy(alpha = 0.2f))
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (P.isDark) P.borderStrong else P.bgCard)
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { i, (value, label) ->
                val isSel = i == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab
                        ) { onSelect(value) }
                        .semantics { this.selected = isSel }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = AppType.label,
                        color = if (isSel) P.textPrimary else P.textSecondary,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val P = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .pressable(pressedScale = 0.99f, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.body, color = P.textPrimary)
            if (subtitle != null) Text(subtitle, style = AppType.caption, color = P.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                // A white thumb on the accent track, like the system switches.
                checkedThumbColor = Color.White,
                checkedTrackColor = P.accentPrimary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = P.textTertiary,
                uncheckedTrackColor = P.bgControl,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** − value + control. The middle can be typed into. */
@Composable
fun NumberStepper(
    value: String,
    onValueChange: (String) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    height: Dp = 40.dp,
    /** Keep the cursor after the last character, for fields that reformat as you type (times). */
    cursorAtEnd: Boolean = false
) {
    val P = LocalPalette.current
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(P.bgControl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton(Icons.Rounded.Remove, tr("Decrease"), enabled, P.textSecondary, onMinus)
        if (enabled) {
            val textStyle = AppType.number.copy(color = P.textPrimary, textAlign = TextAlign.Center)
            val decoration: @Composable (@Composable () -> Unit) -> Unit = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) Text("0", style = AppType.number, color = P.textTertiary)
                    inner()
                }
            }
            if (cursorAtEnd) {
                BasicTextField(
                    value = TextFieldValue(value, TextRange(value.length)),
                    onValueChange = { onValueChange(it.text) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = textStyle,
                    cursorBrush = SolidColor(P.accentPrimary),
                    modifier = Modifier.weight(1f),
                    decorationBox = decoration
                )
            } else {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = textStyle,
                    cursorBrush = SolidColor(P.accentPrimary),
                    modifier = Modifier.weight(1f),
                    decorationBox = decoration
                )
            }
        } else {
            Text(value.ifEmpty { "0" }, style = AppType.number, color = P.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        StepperButton(Icons.Rounded.Add, tr("Increase"), enabled, P.textSecondary, onPlus)
    }
}

@Composable
private fun StepperButton(icon: ImageVector, description: String, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    val P = LocalPalette.current
    Box(
        Modifier
            .width(34.dp)
            .fillMaxHeight()
            .pressable(enabled = enabled, pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = if (enabled) tint else P.textTertiary, modifier = Modifier.size(18.dp))
    }
}

// ============================================================================
// DATA VISUALS
// ============================================================================

/** Text that rolls vertically when its value changes (counts, timers). */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(220)))
                .togetherWith(slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(150)))
        },
        modifier = modifier,
        label = "rolling_text"
    ) { value -> Text(value, style = style, color = color, maxLines = 1) }
}

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
    color: Color = LocalPalette.current.accentPrimary,
    trackColor: Color = LocalPalette.current.bgControl,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow),
        label = "ring"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (animated > 0f) {
                drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

@Composable
fun AnimatedProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = LocalPalette.current.accentPrimary,
    trackColor: Color = LocalPalette.current.bgControl
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "progress"
    )
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(trackColor)) {
        Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(CircleShape).background(color))
    }
}

/**
 * [NumberStepper] for a weight stored as a kg string but shown and typed in the current unit.
 * Keeps its own text while typing so half-typed input ("62.") survives the kg round trip.
 */
@Composable
fun WeightStepper(
    storedKg: String,
    onChange: (String) -> Unit,
    step: Float,
    modifier: Modifier = Modifier,
    minKg: Float = 0f,
    fallbackKg: Float = 20f,
    height: Dp = 40.dp,
    onStep: () -> Unit = {}
) {
    val unit = Units.current
    val shown = if (storedKg.isBlank()) "" else unit.number(parseWeightClean(storedKg, fallbackKg))
    var text by remember(unit) { mutableStateOf(shown) }
    LaunchedEffect(shown) { if (text.toFloatOrNull() != shown.toFloatOrNull()) text = shown }

    fun stepBy(delta: Float) {
        onStep()
        onChange(stepWeight(storedKg, delta, fallbackKg, minKg))
    }

    NumberStepper(
        value = text,
        onValueChange = { v ->
            sanitizeDecimalInput(v)?.let { t ->
                text = t
                onChange(t.toFloatOrNull()?.let(unit::stored) ?: "")
            }
        },
        onMinus = { stepBy(-step) },
        onPlus = { stepBy(step) },
        modifier = modifier,
        height = height
    )
}

/**
 * [NumberStepper] for a time stored as seconds. Typing works like a microwave: digits fill from
 * the right, so "130" is 1:30 and "10000" is 1:00:00.
 */
@Composable
fun DurationStepper(
    seconds: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 15,
    height: Dp = 40.dp,
    onStep: () -> Unit = {}
) {
    val total = seconds.toIntOrNull() ?: 0
    NumberStepper(
        value = if (seconds.isBlank()) "" else formatClock(total.toLong()),
        onValueChange = { v ->
            val digits = v.filter(Char::isDigit).trimStart('0').take(6)
            onChange(if (digits.isEmpty()) "" else clockDigitsToSeconds(digits).toString())
        },
        onMinus = { onStep(); onChange((total - step).coerceAtLeast(0).toString()) },
        onPlus = { onStep(); onChange((total + step).toString()) },
        keyboardType = KeyboardType.Number,
        modifier = modifier,
        height = height,
        cursorAtEnd = true
    )
}

/** "130" → 90 seconds (1:30); "10000" → 3600 (1:00:00). */
fun clockDigitsToSeconds(digits: String): Int {
    val d = digits.padStart(6, '0').takeLast(6)
    return d.substring(0, 2).toInt() * 3600 + d.substring(2, 4).toInt() * 60 + d.substring(4, 6).toInt()
}

/** [NumberStepper] for a distance stored in metres, shown and typed in km or mi. */
@Composable
fun DistanceStepper(
    meters: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0.1f,
    height: Dp = 40.dp,
    onStep: () -> Unit = {}
) {
    val per = Units.current.metersPerDistanceUnit
    val shown = meters.toFloatOrNull()?.let { formatKgClean(it / per) } ?: ""
    // Local text so "5." survives the metre round trip, like WeightStepper.
    var text by remember(per) { mutableStateOf(shown) }
    LaunchedEffect(shown) { if (text.toFloatOrNull() != shown.toFloatOrNull()) text = shown }

    fun stepBy(delta: Float) {
        onStep()
        val current = (meters.toFloatOrNull() ?: 0f) / per
        onChange(formatKgClean(((current + delta).coerceAtLeast(0f) * 100f).roundToInt() / 100f * per))
    }

    NumberStepper(
        value = text,
        onValueChange = { v ->
            sanitizeDecimalInput(v)?.let { t ->
                text = t
                onChange(t.toFloatOrNull()?.let { formatKgClean(it * per) } ?: "")
            }
        },
        onMinus = { stepBy(-step) },
        onPlus = { stepBy(step) },
        modifier = modifier,
        height = height
    )
}

// ============================================================================
// MOTION HELPERS
// ============================================================================

/**
 * Horizontal swipe to page (weeks, months): towards the end goes back, towards the start goes forward
 * when [canGoNext] (right and left in English, mirrored in Hebrew, like the arrows).
 * Vertical scrolling inside still works; only a clear sideways swipe counts.
 */
fun Modifier.swipeToPage(onPrevious: () -> Unit, onNext: () -> Unit, canGoNext: Boolean = true): Modifier = composed {
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val prev by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val allowNext by rememberUpdatedState(canGoNext)
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    pointerInput(sign) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total > threshold) prev()
                else if (total < -threshold && allowNext) next()
            }
        ) { change, amount ->
            change.consume()
            total += amount * sign
        }
    }
}

/**
 * Slide direction for paged content: newer pages come in from the end side (the right in English,
 * the left in Hebrew). [rtl] from [isRtl], since the transition itself can't read the layout direction.
 */
fun <S : Comparable<S>> AnimatedContentTransitionScope<S>.pageTransition(rtl: Boolean = false): ContentTransform {
    val forward = (targetState > initialState) != rtl
    val spec = spring<IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    return (slideInHorizontally(spec) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220)))
        .togetherWith(slideOutHorizontally(spec) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(150)))
}

/**
 * Fades and lifts content in on first appearance, staggered by [index]. A Modifier.Node: the
 * animation only updates the layer, so it costs no recomposition or relayout per frame.
 */
fun Modifier.enterAnimation(index: Int = 0): Modifier = this then EnterAnimationElement(index)

private data class EnterAnimationElement(val index: Int) : ModifierNodeElement<EnterAnimationNode>() {
    override fun create() = EnterAnimationNode(index)
    // Like a remember without keys: a new index doesn't replay it.
    override fun update(node: EnterAnimationNode) {}
}

private class EnterAnimationNode(private val index: Int) : Modifier.Node(), LayoutModifierNode {
    private val progress = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            delay(40L * index.coerceAtMost(8))
            progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow))
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 24.dp.toPx()
            }
        }
    }
}

// ============================================================================
// BOTTOM SHEET
// ============================================================================

/**
 * Scrolling content in a sheet hands what it can't use to the sheet. At the end of a list that
 * leftover moved the whole sheet up a few pixels and it sprang back every frame: a twitch while
 * the finger kept going. Scrolling back up, the leftover once the list reached the top dragged the
 * sheet down mid-swipe, so it jerked and sometimes closed. Keep both in the content: only a swipe
 * that starts with the list already at the top pulls the sheet down.
 */
private class SheetScrollGuard : NestedScrollConnection {
    /** The content moved during this swipe, so the swipe belongs to it. */
    private var contentScrolled = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y != 0f) contentScrolled = true
        return if (available.y < 0f || contentScrolled) Offset(0f, available.y) else Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val keep = available.y < 0f || contentScrolled || consumed.y != 0f
        contentScrolled = false
        return if (keep) Velocity(0f, available.y) else Velocity.Zero
    }
}

/** The phone's text size is set large (about 140% or more): rows with a value at the end stack it under the name instead. */
@Composable
fun isRtl(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

/** 1 or -1: multiplies a sideways slide so "towards the end" follows the reading direction. */
@Composable
fun endSign(): Int = if (isRtl()) -1 else 1

@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= 1.4f

/**
 * For text that has to stay on one short line (tab labels): it grows with the phone's text size
 * only up to [max], since a label broken over two lines ("Progres" / "s") reads worse than a smaller one.
 */
@Composable
fun CappedTextSize(max: Float = 1.3f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) content()
    else CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
}

/**
 * Back for a full-screen page, with Android's predictive back: while the finger is down the page
 * shrinks towards the swiped edge with rounded corners, showing what is behind it; letting go
 * closes it, pulling back puts it back. Apply the returned modifier to the page's root.
 * [animate] false keeps the page still, for a back that asks something first.
 */
@Composable
fun rememberPredictiveBack(enabled: Boolean = true, animate: Boolean = true, onBack: () -> Unit): Modifier {
    val progress = remember { Animatable(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentAnimate by rememberUpdatedState(animate)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { e ->
                fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                if (currentAnimate) progress.snapTo(e.progress)
            }
            // The page's own exit animation carries on from where the gesture left it.
            currentOnBack()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            throw e
        }
    }
    val corner = with(LocalDensity.current) { 28.dp.toPx() }
    return Modifier.graphicsLayer {
        val p = progress.value
        if (p > 0f) {
            // Material's numbers for a full-screen surface: 90% at the end, a nudge towards the edge.
            val scale = 1f - 0.1f * p
            scaleX = scale
            scaleY = scale
            translationX = (if (fromLeft) 1f else -1f) * size.width * 0.04f * p
            shape = RoundedCornerShape(corner * p)
            clip = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val P = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        containerColor = P.bgElevated,
        contentColor = P.textPrimary,
        scrimColor = Color.Black.copy(alpha = if (P.isDark) 0.6f else 0.35f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(P.borderStrong)
            )
        },
        content = {
            val guard = remember { SheetScrollGuard() }
            Box {
                Column(Modifier.nestedScroll(guard), content = content)
                // The sheet's window covers the screen's own message spot.
                AppSnackbarHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
            }
        }
    )
}

// ============================================================================
// COLOR HELPERS
// ============================================================================

fun Color.darken(amount: Float) = Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), alpha)
fun Color.lighten(amount: Float) = Color(red + (1 - red) * amount, green + (1 - green) * amount, blue + (1 - blue) * amount, alpha)

fun toneColors(tone: SuggestionTone, P: GymPalette): Pair<Color, Color> = when (tone) {
    SuggestionTone.GOOD -> P.successSurface to P.successText
    SuggestionTone.CAUTION -> P.warningSurface to P.warningText
    SuggestionTone.NEUTRAL -> P.bgControl to P.textSecondary
}
