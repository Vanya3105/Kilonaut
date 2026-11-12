package com.example.gymtracker

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ============================================================================
// SET KEYPAD
// Set values are plain numbers; tapping one opens this keypad (digits, −/+ by the step, Next),
// like Strong and Hevy. Calmer than −/+ buttons on every value, and quicker for big changes.
// ============================================================================

/** The boxes a set can show, in the order they appear in its row. */
enum class SetField { WEIGHT, DISTANCE, REPS, TIME }

/** Which box of which set the keypad types into. [exIdx] indexes the workout's full exercise list. */
data class FieldRef(val exIdx: Int, val setIdx: Int, val field: SetField)

fun fieldsFor(type: ExerciseType): List<SetField> = buildList {
    if (type.usesWeight) add(SetField.WEIGHT)
    if (type.usesDistance) add(SetField.DISTANCE)
    if (type.usesReps) add(SetField.REPS)
    if (type.usesTime) add(SetField.TIME)
}

private fun fallbackKg(type: ExerciseType) = if (type == ExerciseType.WEIGHT_REPS) 20f else 0f

/** What a box shows: the value in the display unit; "" when empty. */
fun fieldText(set: SetEntry, field: SetField, type: ExerciseType): String = when (field) {
    SetField.WEIGHT -> if (set.weight.isBlank()) "" else Units.current.number(parseWeightClean(set.weight, fallbackKg(type)))
    SetField.DISTANCE -> set.distanceM.toFloatOrNull()?.let { formatKgClean(it / Units.current.metersPerDistanceUnit) }.orEmpty()
    SetField.REPS -> set.reps.filter(Char::isDigit)
    SetField.TIME -> if (set.seconds.isBlank()) "" else formatClock(set.secondCount.toLong())
}

/** The text the keypad edits: like [fieldText], except times are their digits ("1:30" → "130"), typed like a microwave. */
fun editableText(set: SetEntry, field: SetField, type: ExerciseType): String =
    if (field == SetField.TIME) fieldText(set, field, type).filter(Char::isDigit).trimStart('0') else fieldText(set, field, type)

/** [editableText] as shown while typing. */
fun shownText(text: String, field: SetField): String =
    if (field == SetField.TIME && text.isNotEmpty()) formatClock(clockDigitsToSeconds(text).toLong()) else text

const val KEY_BACKSPACE = '⌫'

/**
 * One key press on [text]. When [fresh] (the box was just opened or stepped), the first digit
 * replaces the value instead of adding to it, so typing "100" over "95" gives 100. Returns null
 * when the key doesn't apply (a second ".", a 4-digit rep count).
 */
fun typeKey(text: String, key: Char, field: SetField, fresh: Boolean): String? {
    if (key == KEY_BACKSPACE) return text.dropLast(1)
    val base = if (fresh) "" else text
    val decimal = field == SetField.WEIGHT || field == SetField.DISTANCE
    if (key == '.') {
        if (!decimal || '.' in base) return null
        return base.ifEmpty { "0" } + "."
    }
    if (!key.isDigit()) return null
    val next = base + key
    return when (field) {
        SetField.REPS -> next.trimStart('0').ifEmpty { "0" }.takeIf { it.length <= 3 }
        SetField.TIME -> next.trimStart('0').takeIf { it.length <= 6 }
        // At most two decimals and five digits before the point (99999 lb is plenty).
        else -> next.takeIf { t ->
            val (whole, frac) = t.split('.').let { it[0] to it.getOrNull(1) }
            whole.trimStart('0').length <= 5 && (frac?.length ?: 0) <= 2
        }?.let { if (it.length > 1 && it.startsWith('0') && it[1] != '.') it.trimStart('0').ifEmpty { "0" } else it }
    }
}

/** [set] with the typed [text] stored (kg, metres, seconds). */
fun withFieldText(set: SetEntry, field: SetField, text: String): SetEntry = when (field) {
    SetField.WEIGHT -> set.copy(weight = text.toFloatOrNull()?.let(Units.current::stored).orEmpty())
    SetField.DISTANCE -> set.copy(distanceM = text.toFloatOrNull()?.let { formatKgClean(it * Units.current.metersPerDistanceUnit) }.orEmpty())
    SetField.REPS -> set.copy(reps = text)
    SetField.TIME -> set.copy(seconds = if (text.isEmpty()) "" else clockDigitsToSeconds(text).toString())
}

/** The −/+ keys: weight by the user's step, reps by 1, distance by 0.1, time by 15 s (60 s for cardio). */
fun steppedSet(set: SetEntry, field: SetField, type: ExerciseType, up: Boolean, weightStep: Float): SetEntry {
    val sign = if (up) 1 else -1
    return when (field) {
        SetField.WEIGHT -> set.copy(weight = stepWeight(set.weight, weightStep * sign, fallbackKg(type)))
        SetField.DISTANCE -> {
            val per = Units.current.metersPerDistanceUnit
            val current = (set.distanceM.toFloatOrNull() ?: 0f) / per
            set.copy(distanceM = formatKgClean(((current + 0.1f * sign).coerceAtLeast(0f) * 100f).roundToInt() / 100f * per))
        }
        SetField.REPS -> set.copy(reps = "${(parseRepsClean(set.reps, 8) + sign).coerceAtLeast(0)}")
        SetField.TIME -> set.copy(seconds = "${(set.secondCount + timeStep(type) * sign).coerceAtLeast(0)}")
    }
}

private fun timeStep(type: ExerciseType) = if (type == ExerciseType.DISTANCE_TIME) 60 else 15

fun stepLabel(field: SetField, type: ExerciseType, weightStep: Float): String = when (field) {
    // The step setting is already in the display unit (5 means 5 lb in pounds).
    SetField.WEIGHT -> formatKgClean(weightStep)
    SetField.DISTANCE -> "0.1"
    SetField.REPS -> "1"
    SetField.TIME -> tr("{0}s", timeStep(type))
}

/** The box after [ref] in its set, then the first box of the next set; null after the last set. */
fun nextField(ref: FieldRef, exercise: ExerciseEntry): FieldRef? {
    val fields = fieldsFor(exercise.type)
    val i = fields.indexOf(ref.field)
    return when {
        i in 0 until fields.lastIndex -> ref.copy(field = fields[i + 1])
        ref.setIdx + 1 < exercise.sets.size -> FieldRef(ref.exIdx, ref.setIdx + 1, fields.first())
        else -> null
    }
}

/** What the keypad is typing into, shared by the set boxes and the keypad. */
@Stable
class SetEditor {
    var target by mutableStateOf<FieldRef?>(null)
        private set
    var text by mutableStateOf("")
    /** The next digit replaces [text]. */
    var fresh by mutableStateOf(true)

    fun open(ref: FieldRef, set: SetEntry, type: ExerciseType) {
        target = ref
        text = editableText(set, ref.field, type)
        fresh = true
    }

    fun close() {
        target = null
    }
}

val LocalSetEditor = staticCompositionLocalOf<SetEditor?> { null }

/** A set value as plain text; tap to type into it. Outlined while the keypad edits it. */
@OptIn(ExperimentalFoundationApi::class)
/** Height of a set row's boxes: compact enough to see a whole exercise, still a big thumb target. */
val SET_FIELD_HEIGHT = 38.dp

@Composable
fun SetValueBox(ref: FieldRef, set: SetEntry, type: ExerciseType, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val editor = LocalSetEditor.current
    val editing = editor != null && editor.target == ref
    val text = if (editing) shownText(editor.text, ref.field) else fieldText(set, ref.field, type)
    val shape = RoundedCornerShape(12.dp)
    val border by animateColorAsState(if (editing) P.accentPrimary else Color.Transparent, tween(150), label = "value_border")
    // A done set's numbers sit straight on its green row: grey boxes on green looked muddy.
    val fill by animateColorAsState(
        when { editing -> P.bgElevated; set.isCompleted -> Color.Transparent; else -> P.bgControl },
        tween(250), label = "value_fill"
    )
    val bring = remember { BringIntoViewRequester() }
    // Keep the box above the keypad once it has slid up.
    if (editing) LaunchedEffect(ref) { delay(260); bring.bringIntoView() }
    Box(
        modifier
            .height(SET_FIELD_HEIGHT)
            .bringIntoViewRequester(bring)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, border, shape)
            .pressable(pressedScale = 0.95f) { editor?.open(ref, set, type) }
            // A screen reader would otherwise read a bare "20": say which value it is, with its unit.
            .semantics { contentDescription = spokenValue(ref.field, text) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.ifEmpty { "–" },
            style = AppType.number,
            color = if (text.isEmpty()) P.textTertiary else P.textPrimary,
            maxLines = 1
        )
    }
}

private fun spokenValue(field: SetField, text: String): String {
    val value = when {
        text.isEmpty() -> tr("empty")
        field == SetField.WEIGHT -> "$text ${Units.current.label}"
        field == SetField.DISTANCE -> "$text ${Units.current.distanceLabel}"
        else -> text
    }
    val name = when (field) {
        SetField.WEIGHT -> tr("Weight")
        SetField.DISTANCE -> tr("Distance")
        SetField.REPS -> tr("Reps")
        SetField.TIME -> tr("Time")
    }
    return tr("{0}: {1}", name, value)
}

/**
 * Digits, ".", backspace; −/+ by the step; Next moves weight → reps → the next set's weight.
 * The accent goes to Next only: it's the key pressed most.
 */
@Composable
fun SetKeypad(
    title: String,
    field: SetField,
    stepText: String,
    hasNext: Boolean,
    onKey: (Char) -> Unit,
    onStep: (up: Boolean) -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
    /** Bar lifts' weight only: opens the plate calculator, which was hidden in the set number's menu. */
    onPlates: (() -> Unit)? = null
) {
    val P = LocalPalette.current
    val decimal = field == SetField.WEIGHT || field == SetField.DISTANCE
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(P.bgElevated)
            .border(1.dp, P.borderSubtle, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppType.caption, color = P.textSecondary, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 6.dp))
            if (onPlates != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(P.bgControl)
                        .pressable(pressedScale = 0.95f, onClick = onPlates)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.FitnessCenter, null, tint = P.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Plates"), style = AppType.label, color = P.textPrimary)
                }
            }
            Text(
                tr("Done"),
                style = AppType.label,
                color = P.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onDone).padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        val gap = 6.dp
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            // Digits read 1 2 3 left to right in Hebrew too, like every phone and calculator keypad
            // (right to left, they came out 3 2 1).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    listOf("123", "456", "789").forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) { row.forEach { c -> DigitKey(c.toString()) { onKey(c) } } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        if (decimal) DigitKey(".") { onKey('.') } else Box(Modifier.weight(1f))
                        DigitKey("0") { onKey('0') }
                        Key(onClick = { onKey(KEY_BACKSPACE) }) {
                            Icon(Icons.AutoMirrored.Rounded.Backspace, tr("Delete"), tint = P.textSecondary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                // Left to right: in Hebrew "−2.5" was reordered to "2.5−" and cut to "2.–".
                val stepStyle = AppType.label.copy(textDirection = TextDirection.Ltr)
                Row { Key(onClick = { onStep(false) }) { Text("−$stepText", style = stepStyle, color = P.textPrimary, maxLines = 1) } }
                Row { Key(onClick = { onStep(true) }) { Text("+$stepText", style = stepStyle, color = P.textPrimary, maxLines = 1) } }
                Row {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp * 2 + gap)
                            .clip(RoundedCornerShape(14.dp))
                            .background(P.accentPrimary)
                            .pressable(pressedScale = 0.95f, onClick = if (hasNext) onNext else onDone),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (hasNext) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.Check,
                                null, tint = P.onAccentPrimary, modifier = Modifier.size(24.dp)
                            )
                            Text(if (hasNext) tr("Next") else tr("Done"), style = AppType.label, color = P.onAccentPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DigitKey(label: String, onClick: () -> Unit) = Key(onClick) {
    Text(label, style = AppType.number.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium), color = LocalPalette.current.textPrimary)
}

@Composable
private fun RowScope.Key(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LocalPalette.current.bgControl)
            .pressable(pressedScale = 0.93f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
