package com.example.gymtracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class HistoryMode { WEEK, MONTH }

/** Workouts per exercise ([historyByExercise]) for the cards' summaries; null outside History. */
private val LocalHistoryIndex = staticCompositionLocalOf<Map<String, List<CompletedWorkout>>?> { null }

@Composable
fun HistoryScreen(
    history: List<CompletedWorkout>,
    userProfile: UserProfile,
    onOpenWorkout: (CompletedWorkout) -> Unit,
    onDeleteWorkout: (CompletedWorkout) -> Unit,
    onRepeatWorkout: (CompletedWorkout) -> Unit = {}
) {
    val P = LocalPalette.current
    var mode by rememberSaveable { mutableStateOf(HistoryMode.WEEK) }
    // The day the month calendar shows, shared with the training-days grid above it.
    var selectedDay by rememberSaveable { mutableStateOf(DateUtils.formatDayKey(java.util.Date())) }
    var photoToView by remember { mutableStateOf<String?>(null) }

    val byExercise = remember(history) { historyByExercise(history) }
    val tour = rememberTipTour(
        Tips.HISTORY,
        listOf(TutorialStepInfo("CARD", tr("Your workouts"), tr("Tap a workout to see or edit every set. ⋮ shares it as an image or deletes it. Swipe sideways for other weeks."))),
        ready = history.isNotEmpty()
    )
    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalHistoryIndex provides byExercise, LocalHistoryTip provides tour) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 12.dp)) {
            Text(tr("History"), style = AppType.display, color = P.textPrimary)
            Spacer(Modifier.height(14.dp))
            // One calendar, one place: six months at a glance; a day opens in the month below.
            TrainingDaysCard(
                history,
                selectedDayKey = if (mode == HistoryMode.MONTH) selectedDay else null,
                onPickDay = { millis ->
                    selectedDay = DateUtils.formatDayKey(java.util.Date(millis))
                    mode = HistoryMode.MONTH
                }
            )
            Spacer(Modifier.height(12.dp))
            SegmentedControl(
                options = listOf(HistoryMode.WEEK to tr("Week"), HistoryMode.MONTH to tr("Month")),
                selected = mode,
                onSelect = { mode = it }
            )
        }

        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(120))) },
            label = "history_mode"
        ) { m ->
            // Every card's menu offers "Do it again"; handed down this way rather than through each list.
            CompositionLocalProvider(LocalRepeatWorkout provides onRepeatWorkout) {
                when (m) {
                    HistoryMode.WEEK -> WeekView(history, userProfile, onOpenWorkout, onDeleteWorkout, { photoToView = it })
                    HistoryMode.MONTH -> MonthView(history, selectedDay, { selectedDay = it }, onOpenWorkout, onDeleteWorkout, { photoToView = it })
                }
            }
        }
    }
    }
    TipTourOverlay(tour)
    }

    photoToView?.let { path ->
        val photo = rememberPhoto(path)
        val bmp = photo.bitmap
        if (bmp == null) { if (!photo.loading) photoToView = null }
        else AppBottomSheet(onDismiss = { photoToView = null }) {
            Image(
                bitmap = bmp,
                contentDescription = tr("Workout photo"),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp))
            )
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun WeekView(
    history: List<CompletedWorkout>,
    userProfile: UserProfile,
    onOpen: (CompletedWorkout) -> Unit,
    onDelete: (CompletedWorkout) -> Unit,
    onViewPhoto: (String) -> Unit
) {
    val P = LocalPalette.current
    val rtl = isRtl()
    var weekOffset by rememberSaveable {
        val empty = computeWeekSummary(history, 0, userProfile.splitProgram).workouts.isEmpty()
        mutableIntStateOf(if (empty && computeWeekSummary(history, -1, userProfile.splitProgram).workouts.isNotEmpty()) -1 else 0)
    }
    val program = programFor(userProfile.splitProgram)
    val week = remember(history, weekOffset, program) { computeWeekSummary(history, weekOffset, userProfile.splitProgram) }
    val stats = remember(week) { week.workouts.map { computeWorkoutStats(it.exercises, emptyList()) } }

    Column(Modifier.fillMaxSize().swipeToPage({ weekOffset-- }, { weekOffset++ }, canGoNext = weekOffset < 0)) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, tr("Previous week"), { weekOffset-- })
            AnimatedContent(weekOffset, transitionSpec = { pageTransition(rtl) }, modifier = Modifier.weight(1f), label = "week_title") { offset ->
                // The title sliding out keeps its own week's name.
                val shownWeek = if (offset == weekOffset) week else remember(offset, history) { computeWeekSummary(history, offset, userProfile.splitProgram) }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(shownWeek.titleLabel, style = AppType.bodyStrong, color = P.textPrimary)
                    Text(shownWeek.dateRangeLabel, style = AppType.caption, color = P.textSecondary)
                }
            }
            CircleIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tr("Next week"), { weekOffset++ }, enabled = weekOffset < 0)
        }
        // A row of zeros says nothing: the totals only show for weeks with workouts.
        AnimatedVisibility(week.workouts.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${week.workouts.size}", if (week.workouts.size == 1) tr("Workout") else tr("Workouts"), Modifier.weight(1f))
                StatTile("${stats.sumOf { it.workSets }}", tr("Sets"), Modifier.weight(1f))
                // Bodyweight weeks lift no weight: "0 kg" says nothing, the reps do.
                val volume = stats.sumOf { it.volumeKg }
                if (volume > 0) StatTile(formatVolume(volume), tr("Volume"), Modifier.weight(1f))
                else StatTile("${week.workouts.sumOf { totalWorkReps(it.exercises) }}", tr("Reps"), Modifier.weight(1f))
            }
        }
        AnimatedContent(weekOffset, transitionSpec = { pageTransition(rtl) }, modifier = Modifier.weight(1f), label = "week_list") { offset ->
            val workouts = remember(history, offset, program) { computeWeekSummary(history, offset, userProfile.splitProgram).workouts }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                workoutList(
                    workouts, history, onOpen, onDelete, onViewPhoto,
                    emptyTitle = if (offset == 0) tr("No workouts yet this week") else tr("A rest week"),
                    emptyText = if (offset == 0) history.firstOrNull()?.let { tr("Your last one was {0}, {1}.", shown(it.splitTitle), relativeDay(it.id)) } ?: tr("Finished workouts show up here.")
                    else tr("Nothing was logged that week.")
                )
            }
        }
    }
}

@Composable
private fun MonthView(
    history: List<CompletedWorkout>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onOpen: (CompletedWorkout) -> Unit,
    onDelete: (CompletedWorkout) -> Unit,
    onViewPhoto: (String) -> Unit
) {
    val P = LocalPalette.current
    // Opens on the selected day's month (today, or the day picked on the grid above).
    val shownMonth = remember(selectedKey) { Calendar.getInstance().apply { time = DateUtils.parseDayKey(selectedKey) ?: java.util.Date() } }
    var year by rememberSaveable(selectedKey) { mutableIntStateOf(shownMonth.get(Calendar.YEAR)) }
    var month by rememberSaveable(selectedKey) { mutableIntStateOf(shownMonth.get(Calendar.MONTH)) }
    val rtl = isRtl()
    val todayKey = remember { DateUtils.formatDayKey(Date()) }
    val byDate = remember(history) { history.groupBy { it.dateKey } }

    fun previousMonth() { if (month == 0) { month = 11; year-- } else month-- }
    fun nextMonth() { if (month == 11) { month = 0; year++ } else month++ }

    AnimatedContent(
        year * 12 + month,
        transitionSpec = { pageTransition(rtl) },
        modifier = Modifier.swipeToPage(::previousMonth, ::nextMonth),
        label = "month_page"
    ) { page ->
    // Each page shows its own month, so the one sliding out keeps its days while the next comes in.
    val pageYear = page / 12
    val pageMonth = page % 12
    val (firstDayOffset, daysInMonth, monthTitle) = remember(pageYear, pageMonth) {
        val cal = Calendar.getInstance().apply { set(pageYear, pageMonth, 1) }
        Triple(cal.get(Calendar.DAY_OF_WEEK) - 1, cal.getActualMaximum(Calendar.DAY_OF_MONTH), DateUtils.formatMonthTitle(cal.time))
    }
    // "yyyy-MM-" for this month; day keys are built from it.
    val monthPrefix = remember(pageYear, pageMonth) { String.format(Locale.US, "%04d-%02d-", pageYear, pageMonth + 1) }
    val workoutsThisMonth = byDate.keys.count { it.startsWith(monthPrefix) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, tr("Previous month"), ::previousMonth, size = 36.dp)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(monthTitle, style = AppType.bodyStrong, color = P.textPrimary)
                        Text(if (workoutsThisMonth == 1) tr("1 training day") else tr("{0} training days", workoutsThisMonth), style = AppType.caption, color = P.textSecondary)
                    }
                    CircleIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tr("Next month"), ::nextMonth, size = 36.dp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    weekdayLetters().forEach {
                        Text(it, Modifier.weight(1f), style = AppType.caption, color = P.textTertiary, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(6.dp))
                val rows = (firstDayOffset + daysInMonth + 6) / 7
                for (week in 0 until rows) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        for (col in 0..6) {
                            val day = week * 7 + col - firstDayOffset + 1
                            if (day !in 1..daysInMonth) {
                                Spacer(Modifier.weight(1f).height(42.dp))
                                continue
                            }
                            val key = monthPrefix + if (day < 10) "0$day" else "$day"
                            val trained = byDate.containsKey(key)
                            val selected = key == selectedKey
                            val isToday = key == todayKey
                            val bg by animateColorAsState(
                                when {
                                    selected -> P.accentPrimary
                                    trained -> P.accentSoft
                                    else -> Color.Transparent
                                }, label = "day_bg"
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bg)
                                    .pressable(pressedScale = 0.9f) { onSelect(key) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$day",
                                    style = if (trained || isToday) AppType.label else AppType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                                    color = when {
                                        selected -> P.onAccentPrimary
                                        trained -> P.accentPrimary
                                        isToday -> P.textPrimary
                                        // Days to come recede, so today stands out in the month.
                                        key > todayKey -> P.textTertiary
                                        else -> P.textSecondary
                                    }
                                )
                                if (isToday && !selected) {
                                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp).clip(CircleShape).background(P.textPrimary))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (selectedKey.startsWith(monthPrefix)) {
            item {
                val label = if (selectedKey == todayKey) tr("Today") else selectedKey.toDayLabel()
                SectionHeader(label)
            }
            workoutList(byDate[selectedKey].orEmpty(), history, onOpen, onDelete, onViewPhoto, emptyTitle = tr("Rest day"), emptyText = tr("No workout on this day."), emptyIcon = Icons.Rounded.Event)
        } else {
            // The picked day is in another month: show this month's workouts rather than a day off the calendar.
            item { SectionHeader(monthTitle) }
            workoutList(
                history.filter { it.dateKey.startsWith(monthPrefix) }, history, onOpen, onDelete, onViewPhoto,
                emptyTitle = tr("A rest month"), emptyText = tr("Nothing was logged that month."), emptyIcon = Icons.Rounded.Event
            )
        }
    }
    }
}

private fun String.toDayLabel(): String = try {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(this)
    java.text.SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(d!!)
} catch (e: Exception) { this }

/** The History tip, for the first workout card wherever the week or month view puts it. */
private val LocalHistoryTip = staticCompositionLocalOf<TipTour?> { null }

private fun LazyListScope.workoutList(
    workouts: List<CompletedWorkout>,
    allHistory: List<CompletedWorkout>,
    onOpen: (CompletedWorkout) -> Unit,
    onDelete: (CompletedWorkout) -> Unit,
    onViewPhoto: (String) -> Unit,
    emptyTitle: String,
    emptyText: String,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector = AppIcons.Today
) {
    if (workouts.isEmpty()) {
        item(key = "empty") { EmptyState(emptyIcon, emptyTitle, emptyText, Modifier.enterAnimation()) }
    } else {
        itemsIndexed(workouts, key = { _, w -> w.id }) { i, workout ->
            WorkoutHistoryCard(
                workout = workout,
                allHistory = allHistory,
                onOpen = { onOpen(workout) },
                onDelete = { onDelete(workout) },
                onViewPhoto = onViewPhoto,
                modifier = Modifier.animateItem().enterAnimation(i).then(if (i == 0) Modifier.tipTarget(LocalHistoryTip.current, "CARD") else Modifier)
            )
        }
    }
}

/** Starts a past workout again; null where cards can't (no "Do it again" in the menu then). */
val LocalRepeatWorkout = staticCompositionLocalOf<((CompletedWorkout) -> Unit)?> { null }

@Composable
fun WorkoutHistoryCard(
    workout: CompletedWorkout,
    allHistory: List<CompletedWorkout>,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onViewPhoto: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val photo = rememberPhoto(workout.photoPath)
    val sets = remember(workout.exercises) { workout.exercises.sumOf { ex -> ex.sets.count { !it.isWarmupSet && (it.repCount > 0 || it.isValidWorkSet(ex.type)) } } }
    var menuOpen by remember { mutableStateOf(false) }
    val rows = summaryRows(workout, allHistory)
    // Exercises with a new best, as the trophies below and the finish celebration count them. The
    // saved count (prsHit) counted every record-beating set, so older workouts read "9 PRs" for 3.
    val bests = rows.count { it.third }

    AppCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(shown(workout.splitTitle), style = AppType.headline, color = P.textPrimary)
                Text(DateUtils.dateTimeOf(workout.displayDate, workout.id), style = AppType.caption, color = P.textSecondary)
            }
            if (bests > 0) {
                Pill(if (bests == 1) tr("1 PR") else tr("{0} PRs", bests), icon = Icons.Rounded.EmojiEvents, color = P.warningText, container = P.warningSurface)
            }
            Box {
                CircleIconButton(Icons.Rounded.MoreVert, tr("More"), { menuOpen = true }, size = 34.dp, container = Color.Transparent, tint = P.textSecondary)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = P.bgElevated) {
                    LocalRepeatWorkout.current?.let { repeat ->
                        DropdownMenuItem(
                            text = { Text(tr("Do it again"), style = AppType.body) },
                            leadingIcon = { Icon(Icons.Rounded.Replay, null) },
                            onClick = { menuOpen = false; repeat(workout) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(tr("Share"), style = AppType.body) },
                        leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                        onClick = {
                            menuOpen = false
                            shareWorkoutCardImage(
                                workout.splitTitle, workout.durationSeconds, workout.exercises,
                                allHistory.filter { it.id < workout.id }, workout.photoPath, whenMillis = workout.id
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Delete"), style = AppType.body, color = P.dangerText) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = P.dangerText) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(formatDuration(workout.durationSeconds), icon = Icons.Rounded.Schedule)
            Pill(tr("{0} sets", sets))
            if (workout.totalVolumeKg > 0) Pill(formatVolume(workout.totalVolumeKg))
        }
        ExerciseSummary(rows)
        val note = remember(workout, allHistory) { LocalStorage.getWorkoutNote(context, workout.id) }
        if (note.isNotBlank()) {
            Text(
                "“$note”",
                style = AppType.body,
                color = P.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        // From the watch via Health Connect; keyed on the permissions so it appears once allowed.
        val heart by produceState<HeartRateSummary?>(null, workout.id, HealthSync.granted) {
            value = HealthSync.heartRate(context, workout)
        }
        heart?.let {
            Spacer(Modifier.height(8.dp))
            Pill(tr("{0} bpm avg · {1} max", it.average, it.max), icon = Icons.Rounded.Favorite, color = P.dangerText, container = P.dangerSurface)
        }
        if (workout.skippedExercises.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Skipped: {0}", workout.skippedExercises.joinToString(", ") { shown(it) }), style = AppType.caption, color = P.warningText)
        }
        val bitmap = photo.bitmap
        if (bitmap != null && workout.photoPath != null) {
            Spacer(Modifier.height(12.dp))
            Image(
                bitmap = bitmap,
                contentDescription = tr("Workout photo"),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .pressable(pressedScale = 0.98f) { onViewPhoto(workout.photoPath) }
            )
        } else if (photo.loading) {
            // Same size as the photo, so the card doesn't jump when it arrives.
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(LocalPalette.current.bgControl))
        }
    }
}

private const val SUMMARY_ROWS = 4

/** Each exercise with its best set and whether it was a new best. */
@Composable
private fun summaryRows(workout: CompletedWorkout, allHistory: List<CompletedWorkout>): List<Triple<ExerciseEntry, SetEntry?, Boolean>> {
    val unit = Units.current
    val byExercise = LocalHistoryIndex.current
    return remember(workout, allHistory, byExercise, unit) {
        workout.exercises.filter { !it.isSkipped }.map { ex ->
            val best = bestSetOf(ex.sets, ex.type)
            // Only earlier workouts that have this exercise: same records, without scanning everything.
            val earlier = (byExercise?.get(ignoreCaseKey(ex.name)) ?: allHistory).filter { it.id < workout.id }
            val records = computeExerciseRecords(ex.name, earlier)
            val isBest = ex.sets.any { isSetPersonalRecord(it, records, ex.type) }
            Triple(ex, best, isBest)
        }
    }
}

/** Each exercise with its number of work sets and best set; a trophy where it was a new best. */
@Composable
private fun ExerciseSummary(rows: List<Triple<ExerciseEntry, SetEntry?, Boolean>>) {
    val P = LocalPalette.current
    if (rows.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.bgControl).padding(horizontal = 12.dp, vertical = 8.dp)) {
        rows.take(SUMMARY_ROWS).forEach { (ex, best, isBest) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Only warm-ups (or nothing) logged: a dash, not "0 ×".
                Text(ex.workSets.size.let { if (it == 0) "–" else "$it ×" }, style = AppType.caption.copy(fontFeatureSettings = "tnum"), color = P.textTertiary, modifier = Modifier.width(28.dp))
                Text(shown(ex.name), style = AppType.label, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isBest) {
                    Icon(Icons.Rounded.EmojiEvents, tr("New best"), tint = P.warningText, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                }
                if (best != null) Text(formatSet(best, ex.type), style = AppType.label.copy(fontFeatureSettings = "tnum"), color = if (isBest) P.warningText else P.textSecondary)
            }
        }
        if (rows.size > SUMMARY_ROWS) {
            val more = rows.size - SUMMARY_ROWS
            Text(if (more == 1) tr("+1 more exercise") else tr("+{0} more exercises", more), style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
        }
    }
}

/** A workout photo, or that it's still loading. */
private class PhotoState(val bitmap: ImageBitmap?, val loading: Boolean)

/**
 * A photo from [PhotoStorage]: at once when it's already decoded, otherwise decoded off the main
 * thread (a 1400 px JPEG takes tens of milliseconds, a dropped frame or two mid-scroll).
 */
@Composable
private fun rememberPhoto(path: String?): PhotoState {
    if (path.isNullOrBlank()) return PhotoState(null, loading = false)
    val cached = remember(path) { PhotoStorage.cached(path) }
    if (cached != null) return PhotoState(cached, loading = false)
    val state by produceState(PhotoState(null, loading = true), path) {
        value = PhotoState(withContext(Dispatchers.IO) { PhotoStorage.loadImageBitmap(path) }, loading = false)
    }
    return state
}
