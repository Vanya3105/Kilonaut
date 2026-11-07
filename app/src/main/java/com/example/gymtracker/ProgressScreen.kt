package com.example.gymtracker

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(
    history: List<CompletedWorkout>,
    userProfile: UserProfile,
    weightHistory: List<WeightLogEntry>,
    haptics: Haptics,
    onLogWeight: (Float) -> Unit,
    onDeleteWeight: (Long) -> Unit,
    onOpenExercise: (String) -> Unit,
    onMergeExercises: (from: String, into: String) -> Unit,
    /** Goes up by one for each "Log body weight" shortcut: opens the weigh-in sheet. */
    logWeightRequest: Int = 0
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    var notSame by remember { mutableStateOf(LocalStorage.notDuplicates(context)) }
    val duplicate = remember(history, notSame) {
        likelyDuplicates(loggedExerciseNames(history)).firstOrNull { (a, b) -> duplicatePairKey(a, b) !in notSame }
    }
    var merging by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showLogSheet by remember { mutableStateOf(false) }
    LaunchedEffect(logWeightRequest) { if (logWeightRequest > 0) showLogSheet = true }
    var showAllWeighIns by rememberSaveable { mutableStateOf(false) }
    var showAllLifts by rememberSaveable { mutableStateOf(false) }
    val recovery = remember(history) { computeMuscleRecoveryStatus(history) }
    val bestLifts = remember(history) { computeBestLifts(history) }
    val listState = rememberLazyListState()
    val tour = rememberTipTour(
        Tips.PROGRESS,
        listOf(TutorialStepInfo("LIFTS", tr("Your lifts"), tr("Tap any exercise for its chart, records and every session. Your estimated 1-rep max shows how strong you're getting, even when your reps change."))),
        ready = bestLifts.isNotEmpty()
    )

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                tr("Progress"),
                style = AppType.display,
                color = P.textPrimary,
                modifier = Modifier.statusBarsPadding().padding(top = 20.dp, bottom = 2.dp)
            )
        }

        item { ConsistencyCard(history, Modifier.enterAnimation(0)) }

        item {
            SectionHeader(
                tr("Best lifts"),
                action = if (bestLifts.size > 6) (if (showAllLifts) tr("Show less") else tr("See all")) else null,
                onAction = { showAllLifts = !showAllLifts }
            )
        }
        if (bestLifts.isEmpty()) {
            item { EmptyState(Icons.Rounded.EmojiEvents, tr("No lifts yet"), tr("Finish a workout and your best sets show up here.")) }
        } else {
            item {
                AppCard(
                    Modifier.fillMaxWidth().enterAnimation(1).animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)).tipTarget(tour, "LIFTS"),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    (if (showAllLifts) bestLifts else bestLifts.take(6)).forEachIndexed { i, lift ->
                        if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(P.borderSubtle))
                        Row(
                            Modifier.fillMaxWidth().pressable { onOpenExercise(lift.name) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ExerciseThumb(lift.name, Modifier.padding(end = 12.dp), size = 40.dp, showEmpty = false)
                            val stacked = isLargeText()
                            Column(Modifier.weight(1f)) {
                                Text(shown(lift.name), style = AppType.bodyStrong, color = P.textPrimary)
                                Text(tr("Best set {0}", lift.bestSet?.let { formatSet(it, lift.type) } ?: "—"), style = AppType.caption, color = P.textSecondary)
                                if (stacked) Text("${lift.headline} · ${lift.headlineLabel}", style = AppType.caption, color = P.textPrimary)
                            }
                            if (!stacked) Column(horizontalAlignment = Alignment.End) {
                                Text(lift.headline, style = AppType.number, color = P.textPrimary)
                                Text(lift.headlineLabel, style = AppType.caption, color = P.textTertiary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        duplicate?.let { (a, b) ->
            item(key = "duplicate") {
                DuplicateCard(
                    a, b,
                    onMerge = { haptics.tick(); merging = a to b },
                    onNotSame = {
                        haptics.tick()
                        LocalStorage.markNotDuplicates(context, a, b)
                        notSame = LocalStorage.notDuplicates(context)
                    },
                    modifier = Modifier.animateItem()
                )
            }
        }

        item { SectionHeader(tr("Sets per muscle")) }
        item { WeeklySetsCard(recovery, Modifier.enterAnimation(2)) }

        item {
            BodyWeightCard(
                current = userProfile.currentWeightKg,
                weightHistory = weightHistory,
                haptics = haptics,
                onLog = { haptics.tick(); showLogSheet = true },
                modifier = Modifier.enterAnimation(3)
            )
        }

        if (weightHistory.isNotEmpty()) {
            item {
                SectionHeader(
                    tr("Weigh-ins"),
                    action = if (weightHistory.size > 4) (if (showAllWeighIns) tr("Show less") else tr("See all")) else null,
                    onAction = { showAllWeighIns = !showAllWeighIns }
                )
            }
            item {
                val shown = if (showAllWeighIns) weightHistory else weightHistory.take(4)
                AppCard(
                    Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    shown.forEachIndexed { idx, entry ->
                        key(entry.id) {
                            if (idx > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(P.borderSubtle))
                            WeighInRow(entry, weightHistory.getOrNull(idx + 1), onDelete = { haptics.confirm(); onDeleteWeight(entry.id) })
                        }
                    }
                }
                Text(
                    tr("Swipe a weigh-in to the left to delete it."),
                    style = AppType.caption,
                    color = P.textTertiary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }
        }

    }
    TipTourOverlay(tour)
    }

    merging?.let { (a, b) ->
        MergeExercisesDialog(
            a = a, b = b, history = history,
            onMerge = { from, into -> merging = null; onMergeExercises(from, into) },
            onDismiss = { merging = null }
        )
    }

    if (showLogSheet) {
        LogWeightSheet(
            startKg = userProfile.currentWeightKg.takeIf { it > 0f } ?: 70f,
            onDismiss = { showLogSheet = false },
            onSave = { kg ->
                haptics.confirm()
                onLogWeight(kg)
                showLogSheet = false
            }
        )
    }
}

private data class BestLift(
    val name: String,
    val type: ExerciseType,
    val bestSet: SetEntry?,
    /** Weighted lifts: estimated 1RM (also the sort key). */
    val estimated1Rm: Float,
    val headline: String,
    val headlineLabel: String
)

/** Loaded lifts by estimated 1RM first, then everything else (most recently trained first). */
private fun computeBestLifts(history: List<CompletedWorkout>): List<BestLift> {
    val byExercise = historyByExercise(history)
    return loggedExerciseNames(history)
        .map { name ->
            val rec = computeExerciseRecords(name, byExercise[ignoreCaseKey(name)].orEmpty())
            val type = rec.type
            if (type.hasWeightSuggestions && rec.bestEstimated1Rm > 0f) {
                BestLift(name, type, rec.bestSet, rec.bestEstimated1Rm, formatWeightKg(rec.bestEstimated1Rm), tr("est. 1-rep max"))
            } else if (type == ExerciseType.WEIGHTED_BODYWEIGHT) {
                // Nothing added yet: it's a bodyweight exercise for now.
                BestLift(name, type, rec.bestSet, 0f, ChartMetric.MOST_REPS.format(rec.maxReps.toFloat()), tr("most reps"))
            } else {
                val best = rec.bestSet
                val label = when (type) {
                    ExerciseType.BODYWEIGHT -> tr("most reps")
                    ExerciseType.ASSISTED -> tr("least help")
                    else -> tr("longest")
                }
                val headline = best?.let { formatSessionValue(type, sessionValue(type, listOf(it))) } ?: "—"
                BestLift(name, type, best, 0f, headline, label)
            }
        }
        .filter { it.bestSet != null }
        .sortedWith(compareByDescending<BestLift> { it.estimated1Rm > 0f }.thenByDescending { it.estimated1Rm })
}

@Composable
internal fun BodyWeightCard(current: Float, weightHistory: List<WeightLogEntry>, haptics: Haptics, onLog: () -> Unit, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val now = remember(weightHistory) { System.currentTimeMillis() }
    val daily = remember(weightHistory) { dailyWeighIns(weightHistory) }
    val ranges = remember(daily, now) { usefulWeightRanges(daily, now) }
    var rangeKey by rememberSaveable { mutableStateOf(WeightRange.QUARTER.name) }
    // The chosen range if it's offered, else three months, else the longest there is.
    val range = ranges.firstOrNull { it.name == rangeKey }
        ?: ranges.firstOrNull { it == WeightRange.QUARTER } ?: ranges.lastOrNull() ?: WeightRange.ALL
    val shown = remember(daily, range, now) { weighInsIn(range, daily, now) }
    var picked by remember(shown) { mutableStateOf<Int?>(null) }
    val pickedEntry = picked?.let { shown.getOrNull(it) }

    // The same span as the chart, so the arrow and the line agree.
    val first = shown.firstOrNull()
    val change = if (shown.size >= 2 && first != null && current > 0f) current - first.weightKg else null
    val unit = Units.current.label

    AppCard(modifier.fillMaxWidth(), elevated = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Body weight"), style = AppType.label, color = P.textSecondary)
                Row(verticalAlignment = Alignment.Bottom) {
                    // Following a finger it changes every few pixels: no rolling then.
                    if (pickedEntry != null) Text(formatWeightNumber(pickedEntry.weightKg), style = AppType.bigNumber, color = P.textPrimary)
                    else RollingText(formatWeightNumber(current), AppType.bigNumber, P.textPrimary)
                    Text(" $unit", style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(bottom = 3.dp))
                }
                when {
                    pickedEntry != null -> Text(
                        DateUtils.shortDayYearOf(pickedEntry.displayDate, pickedEntry.id),
                        style = AppType.caption, color = P.accentPrimary
                    )
                    change != null && first != null -> {
                        val rounded = roundedChange(change)
                        val since = tr("since {0}", DateUtils.shortDayOf(first.displayDate, first.id))
                        val (icon, text) = when {
                            rounded > 0f -> Icons.AutoMirrored.Rounded.TrendingUp to "+${formatKgClean(rounded)} $unit $since"
                            rounded < 0f -> Icons.AutoMirrored.Rounded.TrendingDown to "−${formatKgClean(abs(rounded))} $unit $since"
                            else -> Icons.AutoMirrored.Rounded.TrendingFlat to tr("No change {0}", since)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = P.textSecondary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(text, style = AppType.caption, color = P.textSecondary)
                        }
                    }
                }
            }
            PrimaryButton(tr("Log"), onLog, icon = Icons.Rounded.Add, height = 44.dp)
        }
        if (shown.size >= 2) {
            if (ranges.size > 1) {
                Spacer(Modifier.height(16.dp))
                SegmentedControl(
                    options = ranges.map { it to it.label },
                    selected = range,
                    onSelect = { if (it != range) haptics.tick(); rangeKey = it.name }
                )
            }
            Spacer(Modifier.height(14.dp))
            // From the first weigh-in shown (no empty stretch before it) up to today; "All" ends at the last one.
            val start = shown.first().id
            val end = if (range == WeightRange.ALL) shown.last().id else maxOf(now, shown.last().id)
            if (shown.size >= TREND_FROM) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(P.accentPrimary.copy(alpha = 0.35f)))
                    Spacer(Modifier.width(5.dp))
                    Text(tr("Weigh-ins"), style = AppType.caption, color = P.textTertiary)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(width = 12.dp, height = 2.5.dp).clip(CircleShape).background(P.accentPrimary))
                    Spacer(Modifier.width(5.dp))
                    Text(tr("Trend"), style = AppType.caption, color = P.textTertiary)
                }
                Spacer(Modifier.height(4.dp))
            }
            WeightChart(shown, start, end, picked, { picked = it }, haptics)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                val kg = shown.map { it.weightKg }
                WeightStat(tr("Lowest"), formatWeightKg(kg.min()), Modifier.weight(1f))
                WeightStat(tr("Highest"), formatWeightKg(kg.max()), Modifier.weight(1f))
                WeightStat(tr("Average"), formatWeightKg(kg.average().toFloat()), Modifier.weight(1f))
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Text(tr("Log your weight a few times to see a trend."), style = AppType.caption, color = P.textTertiary)
        }
    }
}

@Composable
private fun WeightStat(label: String, value: String, modifier: Modifier) {
    val P = LocalPalette.current
    Column(modifier) {
        Text(label, style = AppType.caption, color = P.textSecondary)
        Text(value, style = AppType.label.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
    }
}

/** Swipe left to delete: no delete button on every row, and no accidental taps. */
@Composable
private fun WeighInRow(entry: WeightLogEntry, prev: WeightLogEntry?, onDelete: () -> Unit) {
    val P = LocalPalette.current
    val diff = prev?.let { roundedChange(entry.weightKg - it.weightKg) }
    val dismiss = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        onDismiss = { onDelete() },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(P.dangerSurface).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Rounded.DeleteOutline, tr("Delete"), tint = P.dangerText)
            }
        }
    ) {
    Row(Modifier.background(P.bgCard).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(formatWeightKg(entry.weightKg), style = AppType.number, color = P.textPrimary)
            // With the time: two weigh-ins on one day otherwise read the same.
            Text(DateUtils.dateTimeOf(entry.displayDate, entry.id), style = AppType.caption, color = P.textSecondary)
        }
        if (diff != null && diff != 0f) {
            Text(
                (if (diff > 0) "+" else "−") + formatKgClean(abs(diff)),
                style = AppType.label.copy(fontFeatureSettings = "tnum"),
                color = P.textSecondary
            )
        }
    }
    }
}

/**
 * Hard sets each muscle got in the last 7 days, against the range most people grow well with
 * ([WEEKLY_SET_TARGET], shaded on every bar). Helping muscles count half, as recovery counts them.
 * Muscles with none are one line at the end rather than empty bars.
 */
@Composable
private fun WeeklySetsCard(items: List<MuscleRecoveryItem>, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    var explain by rememberSaveable { mutableStateOf(false) }
    val trained = remember(items) { items.filter { it.weeklyWorkingSets > 0 }.sortedByDescending { it.weeklyWorkingSets } }
    val none = remember(items) { items.filter { it.weeklyWorkingSets == 0 }.map { it.muscleGroup } }
    val scale = maxOf(WEEKLY_SET_TARGET.last + 4, trained.maxOfOrNull { it.weeklyWorkingSets } ?: 0).toFloat()
    AppCard(modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Last 7 days · aim for {0}–{1}", WEEKLY_SET_TARGET.first, WEEKLY_SET_TARGET.last),
                style = AppType.caption,
                color = P.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (explain) tr("Hide") else tr("Why"),
                style = AppType.label,
                color = P.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable { explain = !explain }.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        if (explain) {
            Text(
                tr("Most people build muscle well with {0}–{1} hard sets per muscle ", WEEKLY_SET_TARGET.first, WEEKLY_SET_TARGET.last) +
                    tr("each week. Muscles that help in a lift count half a set, like biceps on rows or triceps on bench. ") +
                    tr("The shaded part of each bar is that range."),
                style = AppType.caption,
                color = P.textSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }
        if (trained.isEmpty()) {
            Text(tr("No sets in the last 7 days yet."), style = AppType.body, color = P.textTertiary, modifier = Modifier.padding(top = 8.dp))
        }
        trained.forEach { item ->
            val inRange = item.weeklyWorkingSets in WEEKLY_SET_TARGET
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shown(item.muscleGroup), style = AppType.body, color = P.textPrimary, modifier = Modifier.width(96.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(P.bgControl)) {
                    // The target range, shaded behind the bar.
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(WEEKLY_SET_TARGET.last / scale)
                            .padding(start = 0.dp)
                            .background(P.successSurface)
                    )
                    Box(Modifier.fillMaxHeight().fillMaxWidth((WEEKLY_SET_TARGET.first - 0.5f) / scale).background(P.bgControl))
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth((item.weeklyWorkingSets / scale).coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (inRange) P.successText else P.textSecondary)
                    )
                }
                Text(
                    "${item.weeklyWorkingSets}",
                    style = AppType.label.copy(fontFeatureSettings = "tnum"),
                    color = if (inRange) P.successText else P.textPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(32.dp)
                )
            }
        }
        if (none.isNotEmpty()) {
            Text(
                tr("Not trained: ") + none.joinToString(", "),
                style = AppType.caption,
                color = P.textTertiary,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

/** A body-weight change in the current unit, to 0.1. */
private fun roundedChange(kg: Float): Float {
    val unit = Units.current
    return ((kg / unit.toKg(1f)) * 10).roundToInt() / 10f
}

@Composable
private fun LogWeightSheet(startKg: Float, onDismiss: () -> Unit, onSave: (Float) -> Unit) {
    val P = LocalPalette.current
    val unit = Units.current
    val start = unit.fromKg(startKg)
    // Typed in the current unit; converted to kg on save.
    var input by remember { mutableStateOf(formatKgClean(start)) }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            Text(tr("Log today's weight"), style = AppType.title, color = P.textPrimary)
            Text(tr("Weigh yourself at the same time each day for the clearest trend."), style = AppType.label.copy(fontWeight = FontWeight.Normal), color = P.textSecondary)
            Spacer(Modifier.height(20.dp))
            NumberStepper(
                value = input,
                onValueChange = { v -> sanitizeDecimalInput(v)?.let { input = it } },
                onMinus = { input = formatKgClean(unit.round(((input.toFloatOrNull() ?: start) - 0.1f).coerceAtLeast(0f))) },
                onPlus = { input = formatKgClean(unit.round((input.toFloatOrNull() ?: start) + 0.1f)) },
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                tr("Save"),
                onClick = { input.toFloatOrNull()?.takeIf { it > 0f }?.let { onSave(unit.toKg(it)) } },
                enabled = (input.toFloatOrNull() ?: 0f) > 0f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** One exercise logged under two names splits its history and records; offer to join them. */
@Composable
private fun DuplicateCard(a: String, b: String, onMerge: () -> Unit, onNotSame: () -> Unit, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    AppCard(modifier.fillMaxWidth()) {
        Text(tr("Same exercise?"), style = AppType.bodyStrong, color = P.textPrimary)
        Text(
            tr("“{0}” and “{1}” look like one exercise logged under two names, so their history and records are split.", shown(a), shown(b)),
            style = AppType.caption,
            color = P.textSecondary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(tr("Merge them"), onMerge, Modifier.weight(1f), height = 40.dp)
            SecondaryButton(tr("Not the same"), onNotSame, Modifier.weight(1f), height = 40.dp, contentColor = P.textSecondary)
        }
    }
}
