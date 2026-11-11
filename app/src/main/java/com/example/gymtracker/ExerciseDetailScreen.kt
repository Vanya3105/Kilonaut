package com.example.gymtracker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/** Full history of one exercise: chart, records and every logged session. */
@Composable
fun ExerciseDetailScreen(
    exerciseName: String,
    history: List<CompletedWorkout>,
    haptics: Haptics,
    canRename: Boolean,
    onRename: (String) -> Boolean,
    onChangeType: (ExerciseType) -> Unit,
    onBack: () -> Unit,
    /** The note or training max changed, so a running workout can reread them. */
    onInfoChanged: () -> Unit = {},
    /** Merges two exercises (history, day lists, settings); null hides "Merge". */
    onMerge: ((from: String, into: String) -> Unit)? = null
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val backModifier = rememberPredictiveBack(onBack = onBack)

    val stats = remember(exerciseName, history) { buildExerciseHistory(exerciseName, history) }
    val info = remember(exerciseName) { ExerciseDatabase.find(exerciseName) }
    val muscle = info?.muscle ?: remember(exerciseName, history) {
        history.firstNotNullOfOrNull { w -> w.exercises.firstOrNull { exerciseKey(it.name) == exerciseKey(exerciseName) }?.targetMuscle }
    }
    var note by remember(exerciseName) { mutableStateOf(LocalStorage.getExerciseNote(context, exerciseName)) }
    var editingNote by remember { mutableStateOf(false) }
    var choosingMerge by remember { mutableStateOf(false) }
    var mergeWith by remember { mutableStateOf<String?>(null) }
    val others = remember(exerciseName, history) {
        loggedExerciseNames(history).filter { exerciseKey(it) != exerciseKey(exerciseName) }
    }
    var showRename by remember { mutableStateOf(false) }

    LazyColumn(
        backModifier.fillMaxSize().background(P.bgPrimary),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.statusBarsPadding().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), onBack)
                Spacer(Modifier.weight(1f))
                if (canRename && stats.sessions.isNotEmpty()) {
                    CircleIconButton(Icons.Rounded.Edit, tr("Rename"), { haptics.tick(); showRename = true })
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExerciseThumb(exerciseName, Modifier.padding(end = 14.dp), size = 64.dp, showEmpty = false)
                Column {
                    Text(shown(exerciseName), style = AppType.display, color = P.textPrimary)
                    val sub = listOfNotNull(muscle?.ifBlank { null }, info?.equipment).joinToString(" · ") { shown(it) }
                    if (sub.isNotEmpty()) Text(sub, style = AppType.body, color = P.textSecondary)
                }
            }
        }

        // Set-up first: a new exercise needs its type and note before it has any history.
        item { ExerciseTypeRow(stats.type, onChangeType) }
        if (programsUsingTrainingMaxOf(exerciseName).isNotEmpty()) {
            item { TrainingMaxRow(exerciseName, history, stats.best1RmKg, onChanged = onInfoChanged) }
        }
        item { NoteRow(note) { editingNote = true } }
        item { HowToCard(exerciseName, muscle.orEmpty(), startOpen = stats.sessions.size < 3) }
        if (onMerge != null && canRename && others.isNotEmpty()) {
            item { MergeRow { haptics.tick(); choosingMerge = true } }
        }

        if (stats.sessions.isEmpty()) {
            item { EmptyState(Icons.Rounded.FitnessCenter, tr("Nothing logged yet"), tr("Finish a workout with this exercise to see your progress.")) }
            return@LazyColumn
        }

        item {
            val type = stats.type
            val sessions = "${stats.sessions.size}" to if (stats.sessions.size == 1) tr("Session") else tr("Sessions")
            val tiles = when (type) {
                ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT -> listOf(
                    formatWeightKg(stats.best1RmKg) to tr("Est. 1-rep max"),
                    formatWeightKg(stats.heaviestKg) to if (type == ExerciseType.WEIGHT_REPS) tr("Heaviest") else tr("Most added"),
                    sessions
                )
                ExerciseType.BODYWEIGHT -> listOf(
                    ChartMetric.MOST_REPS.format(stats.bestValue) to tr("Most in a set"),
                    "${stats.allTimeReps}" to tr("Total reps"),
                    sessions
                )
                ExerciseType.ASSISTED -> listOf(
                    ChartMetric.ASSISTANCE.format(stats.bestValue) to tr("Least help"),
                    ChartMetric.MOST_REPS.format(stats.sessions.maxOf { it.maxReps }.toFloat()) to tr("Most in a set"),
                    sessions
                )
                ExerciseType.DURATION -> listOf(
                    formatClock(stats.bestValue.toLong()) to tr("Longest"),
                    formatClock(stats.allTimeSeconds.toLong()) to tr("Total time"),
                    sessions
                )
                ExerciseType.DISTANCE_TIME -> listOf(
                    formatDistance(stats.bestValue) to tr("Longest"),
                    formatDistance(stats.allTimeDistanceM) to tr("Total distance"),
                    sessions
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.forEach { (value, label) -> StatTile(value, label, Modifier.weight(1f)) }
            }
        }

        item { ProgressChartCard(stats, haptics, Modifier.enterAnimation(0)) }

        if (stats.type.hasWeightSuggestions) {
            item { SectionHeader(tr("Rep records")) }
            item { RepRecordsCard(stats, Modifier.enterAnimation(1)) }
        }

        // Plan a heavy or light day from the latest estimate, which moves with you more than the all-time best.
        val base = stats.sessions.firstOrNull()?.estimated1RmKg?.takeIf { it > 0f } ?: stats.best1RmKg
        if (stats.type == ExerciseType.WEIGHT_REPS && base > 0f) {
            item { SectionHeader(tr("Percentages")) }
            item { PercentagesCard(base, Modifier.enterAnimation(2)) }
        }

        item { SectionHeader(tr("History")) }
        items(stats.sessions, key = { it.workoutId }) { session -> SessionCard(session) }
    }

    if (choosingMerge) {
        MergePickerSheet(
            exerciseName = exerciseName,
            others = others,
            onPick = { choosingMerge = false; mergeWith = it },
            onDismiss = { choosingMerge = false }
        )
    }
    mergeWith?.let { other ->
        MergeExercisesDialog(
            a = exerciseName,
            b = other,
            history = history,
            onMerge = { from, into -> mergeWith = null; onMerge?.invoke(from, into) },
            onDismiss = { mergeWith = null }
        )
    }

    if (editingNote) {
        NoteDialog(
            current = note,
            onDismiss = { editingNote = false },
            onSave = { text ->
                LocalStorage.saveExerciseNote(context, exerciseName, text)
                note = LocalStorage.getExerciseNote(context, exerciseName)
                editingNote = false
                haptics.tick()
                onInfoChanged()
            }
        )
    }

    if (showRename) {
        RenameDialog(
            current = exerciseName,
            onDismiss = { showRename = false },
            onRename = { newName ->
                if (onRename(newName)) {
                    haptics.confirm()
                    showRename = false
                    true
                } else false
            }
        )
    }
}

@Composable
private fun ProgressChartCard(stats: ExerciseHistory, haptics: Haptics, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val metrics = metricsFor(stats.type)
    var metric by rememberSaveable(stats.type) { mutableStateOf(metrics.first()) }
    if (metric !in metrics) metric = metrics.first()
    var range by rememberSaveable { mutableStateOf(ChartRange.ALL) }
    val now = remember { System.currentTimeMillis() }
    val points = remember(stats, metric, range) { stats.chartPoints(metric, range, now) }
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    fun format(v: Float) = metric.format(v)

    AppCard(modifier.fillMaxWidth()) {
        SegmentedControl(
            options = metrics.map { it to it.label },
            selected = metric,
            onSelect = { haptics.tick(); metric = it }
        )
        Spacer(Modifier.height(16.dp))

        val shown = selected?.let { points.getOrNull(it) } ?: points.lastOrNull()
        if (shown != null) {
            Text(format(shown.second), style = AppType.bigNumber, color = P.textPrimary)
            Text(
                (if (selected == null) tr("Latest · ") else "") + DateUtils.formatShortDayYear(Date(shown.first)),
                style = AppType.caption,
                color = P.textSecondary
            )
            Spacer(Modifier.height(12.dp))
        }

        if (points.size >= 2) {
            SessionChart(points, selected, axisLabel = metric::short, onSelect = { idx ->
                if (idx != selected) haptics.tick()
                selected = idx
            })
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DateUtils.formatShortDayYear(Date(points.first().first)), style = AppType.caption, color = P.textTertiary)
                Text(DateUtils.formatShortDayYear(Date(points.last().first)), style = AppType.caption, color = P.textTertiary)
            }
        } else {
            Text(
                if (points.isEmpty()) tr("No sessions in this period.") else tr("Log one more session to see a trend."),
                style = AppType.label.copy(fontWeight = FontWeight.Normal),
                color = P.textTertiary,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        SegmentedControl(
            options = ChartRange.entries.map { it to it.label },
            selected = range,
            onSelect = { haptics.tick(); range = it }
        )
    }
}

/**
 * Line chart on a real time axis. Tap or drag to inspect a session; the min/max of the visible
 * values are labelled on the right.
 */
@Composable
private fun SessionChart(points: List<Pair<Long, Float>>, selected: Int?, axisLabel: (Float) -> String, onSelect: (Int?) -> Unit) {
    val P = LocalPalette.current
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }

    val minV = points.minOf { it.second }
    val maxV = points.maxOf { it.second }
    val currentOnSelect by rememberUpdatedState(onSelect)

    Row(Modifier.fillMaxWidth().height(160.dp)) {
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(points) {
                    detectTapGestures { currentOnSelect(nearestPoint(points, it.x, size.width.toFloat(), 8.dp.toPx())) }
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures { change, _ ->
                        currentOnSelect(nearestPoint(points, change.position.x, size.width.toFloat(), 8.dp.toPx()))
                    }
                }
        ) {
            val padX = 8.dp.toPx()
            val padY = 10.dp.toPx()
            val w = size.width - padX * 2
            val h = size.height - padY * 2
            val range = (maxV - minV).takeIf { it > 0f } ?: 1f
            val pts = points.mapIndexed { i, (_, v) ->
                val fx = xFraction(points, i)
                val fy = if (maxV == minV) 0.5f else (v - minV) / range
                Offset(padX + fx * w, padY + (1f - fy) * h)
            }

            // Hairlines at max, middle and min.
            for (f in listOf(0f, 0.5f, 1f)) {
                val y = padY + f * h
                drawLine(P.borderSubtle, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }

            val line = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(pts.last().x, size.height)
                lineTo(pts.first().x, size.height)
                close()
            }
            clipRect(right = size.width * reveal.value + 6.dp.toPx()) {
                drawPath(fill, Brush.verticalGradient(listOf(P.accentPrimary.copy(alpha = 0.20f), Color.Transparent)))
                drawPath(line, P.accentPrimary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                if (pts.size <= 40) pts.forEach { pt ->
                    drawCircle(P.bgCard, 4.dp.toPx(), pt)
                    drawCircle(P.accentPrimary, 2.5.dp.toPx(), pt)
                }
            }

            selected?.let { pts.getOrNull(it) }?.let { pt ->
                drawLine(P.textTertiary, Offset(pt.x, 0f), Offset(pt.x, size.height), 1.dp.toPx())
                drawCircle(P.bgCard, 7.dp.toPx(), pt)
                drawCircle(P.accentPrimary, 5.dp.toPx(), pt)
            }
        }
        Column(
            Modifier.width(48.dp).fillMaxHeight().padding(vertical = 2.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(maxV, (maxV + minV) / 2f, minV).forEach {
                Text(axisLabel(it), style = AppType.caption.copy(fontFeatureSettings = "tnum"), color = P.textTertiary)
            }
        }
    }
}

/**
 * The estimated 1-rep max worked back to training weights: 95 % for about 2 reps down to 60 % for
 * about 20, rounded to what the plates make.
 */
@Composable
private fun PercentagesCard(oneRmKg: Float, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val unit = Units.current
    AppCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        Text(
            tr("From your latest estimated 1-rep max, {0}", formatWeightKg(oneRmKg)),
            style = AppType.caption, color = P.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        PERCENTAGES.forEach { pct ->
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(P.borderSubtle))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$pct%", style = AppType.label.copy(fontFeatureSettings = "tnum"), color = P.accentPrimary, modifier = Modifier.width(52.dp))
                val reps = repsAtPercent(pct)
                Text(if (reps == 1) tr("1 rep") else tr("about {0} reps", reps), style = AppType.body, color = P.textSecondary, modifier = Modifier.weight(1f))
                Text(formatWeightKg(weightAtPercent(oneRmKg, pct, unit)), style = AppType.number, color = P.textPrimary)
            }
        }
    }
}

@Composable
private fun RepRecordsCard(stats: ExerciseHistory, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val byReps = stats.repRecords.associateBy { it.reps }
    AppCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        REP_RECORD_TARGETS.forEachIndexed { i, reps ->
            if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(P.borderSubtle))
            val rec = byReps[reps]
            Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (reps == 1) tr("1 rep") else tr("{0} reps", reps), style = AppType.body, color = P.textSecondary, modifier = Modifier.weight(1f))
                if (rec == null) {
                    Text("—", style = AppType.number, color = P.textTertiary)
                } else {
                    Text(DateUtils.formatShortDayYear(Date(rec.workoutId)), style = AppType.caption, color = P.textTertiary)
                    Spacer(Modifier.width(12.dp))
                    Text(formatWeightKg(rec.weightKg), style = AppType.number, color = P.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: ExerciseSession) {
    val P = LocalPalette.current
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(DateUtils.formatShortDayYear(Date(session.workoutId)), style = AppType.bodyStrong, color = P.textPrimary)
                Text(shown(session.splitTitle), style = AppType.caption, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (session.isRecord) {
                Pill(tr("Best yet"), color = P.warningText, container = P.warningSurface, icon = Icons.Rounded.EmojiEvents)
            }
        }
        Spacer(Modifier.height(10.dp))
        var workNumber = 0
        session.sets.forEach { set ->
            val tag = when {
                set.isWarmupSet -> tr("W")
                else -> "${++workNumber}"
            }
            val kind = when (set.intensityType) {
                "DROP" -> tr("drop set")
                "REST_PAUSE" -> "rest-pause"
                else -> null
            }
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tag, style = AppType.caption.copy(fontWeight = FontWeight.SemiBold), color = if (set.isWarmupSet) P.textTertiary else P.textSecondary, modifier = Modifier.width(24.dp))
                Text(
                    formatSet(set, session.type),
                    style = AppType.body.copy(fontFeatureSettings = "tnum"),
                    color = if (set.isWarmupSet) P.textSecondary else P.textPrimary
                )
                if (kind != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(kind, style = AppType.caption, color = P.textTertiary)
                }
                if (set.rir >= 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(effortText(set.rir), style = AppType.caption, color = P.textTertiary)
                }
            }
        }
        val footer = when (session.type) {
            ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT ->
                tr("Est. 1RM {0} · Volume {1}", formatWeightKg(session.estimated1RmKg), formatVolume(session.volumeKg.roundToInt()))
            ExerciseType.BODYWEIGHT, ExerciseType.ASSISTED -> tr("{0} reps in total", session.totalReps)
            ExerciseType.DURATION -> tr("{0} in total", formatClock(session.totalSeconds.toLong()))
            ExerciseType.DISTANCE_TIME -> ChartMetric.PACE.valueOf(session).takeIf { it > 0f }?.let { tr("Pace {0}", ChartMetric.PACE.format(it)) }
        }
        if (footer != null) {
            Spacer(Modifier.height(6.dp))
            Text(footer, style = AppType.caption, color = P.textTertiary)
        }
    }
}

/**
 * "Training max: 100 kg" for lifts a percentage program uses. Shows today's value (cycles add
 * to it); editing sets a new one and starts the program over at week 1.
 */
@Composable
fun TrainingMaxRow(
    exerciseName: String,
    history: List<CompletedWorkout>,
    best1RmKg: Float,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {}
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val programs = programsUsingTrainingMaxOf(exerciseName).takeIf { it.isNotEmpty() } ?: return
    val programNames = programs.joinToString(", ") { shown(it) }
    var version by remember { mutableIntStateOf(0) }
    val tm = remember(exerciseName, version) { LocalStorage.getTrainingMax(context, exerciseName) }
    val currentKg = remember(tm, history) { tm?.let { currentTrainingMaxKg(exerciseName, it, history) } }
    var editing by remember { mutableStateOf(false) }

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(P.bgControl)
            .pressable(pressedScale = 0.99f) { editing = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("Training max · {0}", programNames), style = AppType.caption, color = P.textSecondary)
            Text(currentKg?.let { formatWeightKg(it) } ?: tr("Not set"), style = AppType.bodyStrong, color = P.textPrimary)
            if (tm != null && currentKg != null && currentKg > tm.kg) {
                Text(tr("Started at {0}; the program has added the rest", formatWeightKg(tm.kg)), style = AppType.caption, color = P.textSecondary)
            }
        }
        Icon(Icons.Rounded.Edit, tr("Change"), tint = P.textSecondary, modifier = Modifier.size(20.dp))
    }

    if (editing) {
        var value by remember {
            mutableStateOf((currentKg ?: suggestedTrainingMaxKg(best1RmKg)).let { if (it > 0f) it.toString() else "" })
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = P.bgElevated,
            title = { Text(tr("Training max"), style = AppType.headline, color = P.textPrimary) },
            text = {
                Column {
                    Text(
                        // The number in brackets read as the best 1RM itself; it was the 90%.
                        (if (best1RmKg > 0f) tr(
                            "Your {0} weights are worked out from this. A good start is {1}, 90% of your best estimated 1-rep max ({2}).",
                            programNames, formatWeightKg(suggestedTrainingMaxKg(best1RmKg)), formatWeightKg(best1RmKg)
                        ) else tr("Your {0} weights are worked out from this. A good start is 90% of your best estimated 1-rep max", programNames) + ".") +
                            tr(" Saving starts the program again at week 1."),
                        style = AppType.caption,
                        color = P.textSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    WeightStepper(storedKg = value, onChange = { value = it }, step = Units.current.defaultStep, fallbackKg = 0f, height = 52.dp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (value.toFloatOrNull() ?: 0f) > 0f,
                    onClick = {
                        LocalStorage.setTrainingMax(context, exerciseName, value.toFloatOrNull())
                        version++
                        editing = false
                        onChanged()
                    }
                ) { Text(tr("Save"), color = P.accentPrimary, style = AppType.label) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (tm != null) {
                        LocalStorage.setTrainingMax(context, exerciseName, null)
                        version++
                        onChanged()
                    }
                    editing = false
                }) { Text(if (tm != null) tr("Clear") else tr("Cancel"), color = P.textSecondary, style = AppType.label) }
            }
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Boolean) {
    val P = LocalPalette.current
    // Starts focused with the old name selected, so typing replaces it.
    var field by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val text = field.text
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.bgElevated,
        title = { Text(tr("Rename exercise"), style = AppType.headline, color = P.textPrimary) },
        text = {
            Column {
                Text(tr("Your history, notes and workouts keep it under the new name."), style = AppType.body, color = P.textSecondary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = P.accentPrimary,
                        unfocusedBorderColor = P.borderStrong,
                        focusedTextColor = P.textPrimary,
                        unfocusedTextColor = P.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && text.trim() != current,
                onClick = { if (!onRename(text.trim())) error = tr("Another exercise already has that name.") }
            ) { Text(tr("Rename"), color = P.accentPrimary, style = AppType.label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
    )
}

/** Horizontal position (0..1) of point [i] on the time axis; evenly spaced if all share one time. */
private fun xFraction(points: List<Pair<Long, Float>>, i: Int): Float {
    val minT = points.first().first
    val span = points.last().first - minT
    return if (span <= 0L) i / (points.size - 1f) else (points[i].first - minT) / span.toFloat()
}

private fun nearestPoint(points: List<Pair<Long, Float>>, x: Float, width: Float, padX: Float): Int =
    points.indices.minBy { i -> abs(padX + xFraction(points, i) * (width - padX * 2) - x) }

/** The setup note (seat height, grip…), or an invitation to add one. Tap to edit. */
@Composable
private fun NoteRow(note: String, onEdit: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.bgControl).pressable(pressedScale = 0.99f, onClick = onEdit).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.PushPin, null, tint = P.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            note.ifBlank { tr("Add a setup note (seat height, grip…)") },
            style = AppType.body,
            color = if (note.isBlank()) P.textTertiary else P.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Rounded.Edit, tr("Edit note"), tint = P.textTertiary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun NoteDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val P = LocalPalette.current
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.bgElevated,
        title = { Text(tr("Setup note"), style = AppType.headline, color = P.textPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(tr("Seat height, grip, pin…")) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = P.accentPrimary,
                    unfocusedBorderColor = P.borderStrong
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(tr("Save"), color = P.accentPrimary, style = AppType.label) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
    )
}

@Composable
private fun MergeRow(onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(pressedScale = 0.99f, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.CallMerge, null, tint = P.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(tr("Same exercise under another name? Merge them"), style = AppType.label, color = P.textSecondary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Picks the exercise to merge with: likely duplicates first, then everything else, searchable. */
@Composable
private fun MergePickerSheet(exerciseName: String, others: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    var query by remember { mutableStateOf("") }
    val likely = remember(exerciseName, others) {
        likelyDuplicates(listOf(exerciseName) + others).flatMap { listOf(it.first, it.second) }
            .filter { exerciseKey(it) != exerciseKey(exerciseName) }.toSet()
    }
    val shown = remember(query, others, likely) {
        others.filter { it.contains(query.trim(), ignoreCase = true) }.sortedWith(compareByDescending<String> { it in likely }.thenBy { it })
    }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxHeight(0.8f).navigationBarsPadding().imePadding()) {
            Text(tr("Merge {0} with…", shown(exerciseName)), style = AppType.title, color = P.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
            Text(tr("Pick the exercise it's the same as. Next you choose which name to keep."), style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(tr("Search your exercises")) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = P.accentPrimary, unfocusedBorderColor = P.borderStrong),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(shown, key = { it }) { name ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.98f) { onPick(name) }.padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(shown(name), style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f))
                        if (name in likely) Text(tr("Looks the same"), style = AppType.caption, color = P.accentPrimary)
                    }
                }
            }
        }
    }
}

/**
 * Asks which of two names to keep, with how much each holds. The other one's sessions, records,
 * note and place in day lists move into the kept one.
 */
@Composable
fun MergeExercisesDialog(a: String, b: String, history: List<CompletedWorkout>, onMerge: (from: String, into: String) -> Unit, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    fun sessions(name: String) = history.count { w -> w.exercises.any { !it.isSkipped && exerciseKey(it.name) == exerciseKey(name) } }
    val options = remember(a, b, history) { listOf(a to sessions(a), b to sessions(b)).sortedByDescending { it.second } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.bgElevated,
        title = { Text(tr("Merge into one exercise?"), style = AppType.headline, color = P.textPrimary) },
        text = {
            Column {
                Text(
                    tr("Choose the name to keep. The other one's sessions, records and notes move into it. A copy of your data is kept first."),
                    style = AppType.body,
                    color = P.textSecondary
                )
                Spacer(Modifier.height(12.dp))
                options.forEach { (name, count) ->
                    val other = if (name == a) b else a
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(P.bgControl)
                            .pressable(pressedScale = 0.98f) { onMerge(other, name) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Keep “{0}”", shown(name)), style = AppType.bodyStrong, color = P.textPrimary)
                            Text(if (count == 1) tr("1 session") else tr("{0} sessions", count), style = AppType.caption, color = P.textSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
    )
}

/**
 * Which muscles the exercise works (front and back figures) and, for built-in exercises, how to
 * do it. Open at first; folded once the exercise is familiar (three sessions).
 */
@Composable
private fun HowToCard(exerciseName: String, muscle: String, startOpen: Boolean) {
    val P = LocalPalette.current
    var open by rememberSaveable(exerciseName) { mutableStateOf(startOpen) }
    val guide = remember(exerciseName) { ExerciseGuides.find(exerciseName) }
    val loads = remember(exerciseName, muscle) { muscleLoads("", exerciseName, muscle) }
    val main = RECOVERY_GROUPS.filterIndexed { i, _ -> loads[i] >= 1f }
    val helping = RECOVERY_GROUPS.filterIndexed { i, _ -> loads[i] > 0f && loads[i] < 1f }
    if (main.isEmpty() && guide == null) return

    AppCard(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).pressable(pressedScale = 0.99f) { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (guide != null) tr("How to do it") else tr("Muscles worked"), style = AppType.headline, color = P.textPrimary)
                Text(
                    listOfNotNull(main.joinToString(", ") { shown(it) }.ifEmpty { null }, helping.takeIf { it.isNotEmpty() }?.let { tr("helped by ") + it.joinToString(", ") { m -> shown(m) } })
                        .joinToString(", "),
                    style = AppType.caption,
                    color = P.textSecondary
                )
            }
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (open) tr("Hide") else tr("Show"),
                tint = P.textTertiary
            )
        }
        if (open) ExerciseGuideBody(exerciseName, muscle, Modifier.padding(top = 14.dp))
    }
}

/**
 * Everything about how an exercise is done: the moving figure, which muscles it works,
 * the steps and a tip. On the exercise's page and when a row is opened in "Add exercise".
 */
@Composable
fun ExerciseGuideBody(exerciseName: String, muscle: String, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val guide = remember(exerciseName) { ExerciseGuides.find(exerciseName) }
    val loads = remember(exerciseName, muscle) { muscleLoads("", exerciseName, muscle) }
    val main = RECOVERY_GROUPS.filterIndexed { i, _ -> loads[i] >= 1f }
    val helping = RECOVERY_GROUPS.filterIndexed { i, _ -> loads[i] > 0f && loads[i] < 1f }
    Column(modifier) {
        if (ExerciseFigures.has(exerciseName)) {
            ExerciseFigure(
                exerciseName,
                Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(14.dp)).background(P.bgControl).padding(12.dp)
            )
            Spacer(Modifier.height(14.dp))
        }
        if (main.isNotEmpty()) {
            MuscleMap(loads, Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(P.accentPrimary, tr("Main"))
                if (helping.isNotEmpty()) Legend(P.accentPrimary.copy(alpha = 0.5f), tr("Helping"))
            }
        }
        if (guide != null) {
            Spacer(Modifier.height(16.dp))
            guide.steps.forEachIndexed { i, step ->
                Row(Modifier.padding(bottom = 10.dp)) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(P.bgControl), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = AppType.caption.copy(fontFeatureSettings = "tnum"), color = P.textSecondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(step, style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(P.bgControl).padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Rounded.Lightbulb, null, tint = P.warningText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(guide.tip, style = AppType.label.copy(fontWeight = FontWeight.Normal), color = P.textPrimary)
            }
        }
    }
}

@Composable
internal fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    val P = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = AppType.caption, color = P.textSecondary)
    }
}
