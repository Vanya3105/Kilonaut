package com.example.gymtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    splits: List<WorkoutSplit>,
    history: List<CompletedWorkout>,
    userProfile: UserProfile,
    programName: String,
    activeSplitTitle: String?,
    /** Exercises in the running workout, which may differ from the day's list. */
    activeExercises: List<ExerciseEntry>,
    onStartWorkout: (WorkoutSplit) -> Unit,
    /** The day's exercises as a workout would start them, for the time estimate and "Short on time?". */
    planFor: (WorkoutSplit) -> List<ExerciseEntry> = { emptyList() },
    onStartPlanned: (WorkoutSplit, List<ExerciseEntry>) -> Unit = { s, _ -> onStartWorkout(s) },
    onStartQuickWorkout: () -> Unit,
    onOpenHistory: () -> Unit,
    /** False while something covers Today (a workout, onboarding), so its tips wait. */
    tipsAllowed: Boolean = true
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val program = programFor(userProfile.splitProgram)
    val week = remember(history, program) { computeWeekSummary(history, 0, userProfile.splitProgram) }
    val recovery = remember(history) { computeMuscleRecoveryStatus(history) }
    // Not signed in: a card at the top of Today until they sign in or say not now.
    remember { Account.start(context) }
    var notNow by remember { mutableStateOf(!Account.available(context) || AccountPrompt.dismissed(context)) }
    val askToSignIn = !notNow && Account.user == null
    val coachVersion = CoachState.version
    val tip = remember(history, splits, coachVersion) {
        coachTip(Coach.days(context, splits), history, CoachState.load(context), System.currentTimeMillis())
    }
    val upNext = when (activeSplitTitle) {
        QUICK_WORKOUT_TITLE -> WorkoutSplit(QUICK_WORKOUT_TITLE, "Exercises you pick as you go", "")
        null -> splits.find { it.title == week.recommendedSplit } ?: splits.first()
        // A workout from a split that has since been switched for another stays itself: it was shown
        // as the new split's first day, "In progress", while Resume opened the running one.
        else -> splits.find { it.title == activeSplitTitle } ?: WorkoutSplit(activeSplitTitle, "", "")
    }

    val signedIn = Account.user?.uid
    LaunchedEffect(signedIn) {
        if (signedIn != null) {
            if (Friends.myHandle == null) Friends.loadMe(context)
            if (Friends.myHandle != null && Friends.circle == null) Friends.refresh()
        }
    }

    val listState = rememberLazyListState()
    var showRecovery by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tour = rememberTipTour(
        Tips.HOME,
        listOf(
            TutorialStepInfo("UP_NEXT", tr("Your next workout"), tr("The app picks the day of your plan you haven't done yet this week. Tap Start and your exercises are ready, with weights from last time.")),
            TutorialStepInfo("WEEK", tr("Your week"), tr("How many of this week's workouts are done. A filled day is a day you trained; tap for your history.")),
            TutorialStepInfo("RECOVERY", tr("Recovery"), tr("Which muscles are still recovering from your last sessions, so you can see what's ready to train. Tap it for every muscle.")),
            TutorialStepInfo("OTHERS", tr("Train something else"), tr("Any other day of your plan, or an empty workout where you pick exercises as you go."))
        ),
        ready = tipsAllowed
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp).enterAnimation(0),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()),
                            style = AppType.label,
                            color = P.textSecondary
                        )
                        Text(greeting(), style = AppType.display, color = P.textPrimary)
                    }
                }
            }

            // Friends where the app opens, not four tabs away (Hevy and Strava open on them).
            if (showTodayFriends()) item(key = "friends") { TodayFriends(Modifier.enterAnimation(1)) }

            item { UpdateCard() }

            if (askToSignIn) item(key = "account") {
                AccountCard(onDismiss = { AccountPrompt.dismiss(context); notNow = true }, modifier = Modifier.enterAnimation(1))
            }

            item {
                UpNextCard(
                    split = upNext,
                    history = history,
                    isInProgress = activeSplitTitle != null,
                    activeExercises = activeExercises,
                    onStart = { if (upNext.title == QUICK_WORKOUT_TITLE) onStartQuickWorkout() else onStartWorkout(upNext) },
                    planFor = planFor,
                    onStartPlanned = { onStartPlanned(upNext, it) },
                    modifier = Modifier
                        .enterAnimation(1)
                        .tipTarget(tour, "UP_NEXT")
                )
            }

            if (tip != null) item(key = "coach") {
                CoachCard(tip, history, userProfile.equipment, Modifier.enterAnimation(2))
            }

            item {
                WeekCard(
                    week = week,
                    programName = programName,
                    onOpenHistory = onOpenHistory,
                    modifier = Modifier
                        .enterAnimation(2)
                        .tipTarget(tour, "WEEK")
                )
            }

            item {
                RecoveryStrip(
                    items = recovery,
                    onClick = { showRecovery = true },
                    modifier = Modifier
                        .enterAnimation(3)
                        .tipTarget(tour, "RECOVERY")
                )
            }

            val others = splits.filter { it.title != upNext.title }
            val showQuick = activeSplitTitle != QUICK_WORKOUT_TITLE
            if (others.isNotEmpty() || showQuick) {
                item { SectionHeader(tr("Other workouts"), Modifier.enterAnimation(4)) }
                // One grouped list rather than a card per day; an empty workout for off-plan days last.
                item {
                    AppCard(Modifier.fillMaxWidth().enterAnimation(5).tipTarget(tour, "OTHERS"), contentPadding = PaddingValues(vertical = 4.dp)) {
                        others.forEachIndexed { i, split ->
                            if (i > 0) Box(Modifier.padding(start = 74.dp).fillMaxWidth().height(1.dp).background(P.borderSubtle))
                            SplitRow(
                                split = split,
                                doneThisWeek = split.title in week.completedCoreSplits,
                                lastDone = history.firstOrNull { it.splitTitle == split.title },
                                onClick = { onStartWorkout(split) }
                            )
                        }
                        if (showQuick) {
                            if (others.isNotEmpty()) Box(Modifier.padding(start = 74.dp).fillMaxWidth().height(1.dp).background(P.borderSubtle))
                            QuickWorkoutRow(onClick = onStartQuickWorkout)
                        }
                    }
                }
            }
        }

        if (showRecovery) RecoverySheet(recovery, onDismiss = { showRecovery = false })

        // Each card is scrolled into view before it's pointed at (Other workouts is below the fold).
        TipTourOverlay(tour, onShowStep = { key ->
            val coach = (if (tip != null) 1 else 0) + (if (askToSignIn) 1 else 0)
            val item = when (key) { "UP_NEXT" -> if (askToSignIn) 2 else 0; "WEEK" -> 2 + coach; "RECOVERY" -> 3 + coach; else -> 5 + coach }
            scope.launch { listState.animateScrollToItem(item, scrollOffset = if (item >= 3) -200 else 0) }
        })
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> tr("Good morning")
    in 12..17 -> tr("Good afternoon")
    else -> tr("Good evening")
}

fun relativeDay(timestamp: Long): String {
    val days = ((System.currentTimeMillis() - timestamp) / (24L * 60 * 60 * 1000)).toInt()
    return when {
        days <= 0 -> tr("today")
        days == 1 -> tr("yesterday")
        days < 14 -> tr("{0} days ago", days)
        else -> tr("{0} weeks ago", days / 7)
    }
}

@Composable
private fun UpNextCard(
    split: WorkoutSplit,
    history: List<CompletedWorkout>,
    isInProgress: Boolean,
    activeExercises: List<ExerciseEntry>,
    onStart: () -> Unit,
    planFor: (WorkoutSplit) -> List<ExerciseEntry>,
    onStartPlanned: (List<ExerciseEntry>) -> Unit,
    modifier: Modifier = Modifier
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    // History is a new list after a restore, so the count is read again then too.
    val plannedCount = remember(split.title, isInProgress, history) {
        if (isInProgress) 0 else LocalStorage.loadTemplateForSplit(context, split.title).size
    }
    val doing = activeExercises.filter { !it.isSkipped }
    val exerciseCount = if (isInProgress) doing.size else plannedCount
    // How long the day takes as planned; the base for "Short on time?".
    val plan = remember(split.title, isInProgress, history, CoachState.version) {
        if (isInProgress || split.title == QUICK_WORKOUT_TITLE) emptyList() else planFor(split)
    }
    val minutes = if (plan.isEmpty()) 0 else estimateMinutes(plan)
    var budgeting by remember { mutableStateOf(false) }
    val last = history.firstOrNull { it.splitTitle == split.title }

    AppCard(modifier = modifier.fillMaxWidth(), elevated = true, contentPadding = PaddingValues(0.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isInProgress) tr("In progress") else tr("Up next"), style = AppType.overline, color = P.accentPrimary, modifier = Modifier.weight(1f))
                if (isInProgress) RunningClock()
            }
            Spacer(Modifier.height(6.dp))
            Text(shown(split.title), style = AppType.display, color = P.textPrimary)
            if (isInProgress) {
                // What's happening now, like the bar on the other tabs: the exercise and its next set.
                val current = doing.firstOrNull { ex -> ex.sets.any { !it.isCompleted } }
                val done = doing.sumOf { ex -> ex.sets.count { it.isCompleted } }
                val total = doing.sumOf { it.sets.size }
                Text(
                    current?.let { ex -> tr("Now: {0} · set {1} of {2}", shown(ex.name), ex.sets.count { it.isCompleted } + 1, ex.sets.size) }
                        ?: if (doing.isEmpty()) tr("No exercises yet") else tr("Every set done. Finish when you're ready."),
                    style = AppType.body, color = P.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr("{0} of {1} sets", done, total), container = P.bgControl)
                    Pill(if (exerciseCount == 1) tr("1 exercise") else tr("{0} exercises", exerciseCount), container = P.bgControl)
                }
            } else {
                Text(daySubtitle(split), style = AppType.body, color = P.textSecondary)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (exerciseCount == 1) tr("1 exercise") else tr("{0} exercises", exerciseCount), container = P.bgControl)
                    if (minutes > 0) Pill(tr("~{0} min", minutes), icon = Icons.Rounded.Schedule, container = P.bgControl)
                    Pill(
                        last?.let { tr("Last done {0}", relativeDay(it.id)) } ?: tr("Not done yet"),
                        container = P.bgControl
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(
                text = if (isInProgress) tr("Resume workout") else tr("Start workout"),
                icon = Icons.Rounded.PlayArrow,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            )
            // Only worth offering when there's something to cut.
            if (minutes > 20) {
                Text(
                    tr("Short on time?"),
                    style = AppType.label,
                    color = P.textSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .pressable { budgeting = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
    if (budgeting) {
        TimeBudgetSheet(split, plan, onDismiss = { budgeting = false }) { trimmed ->
            budgeting = false
            onStartPlanned(trimmed)
        }
    }
}

@Composable
private fun WeekCard(
    week: WeekSummaryData,
    programName: String,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val P = LocalPalette.current
    val done = week.completedCoreSplits.size
    val goal = week.weeklyGoal
    val trainedDays = remember(week) {
        week.workouts.map { Calendar.getInstance().apply { timeInMillis = it.id }.get(Calendar.DAY_OF_WEEK) }.toSet()
    }
    val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

    AppCard(modifier = modifier.fillMaxWidth(), onClick = onOpenHistory) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("This week"), style = AppType.headline, color = P.textPrimary)
                Text(
                    when {
                        done >= goal -> tr("All {0} done. Nice work.", goal)
                        done == 0 -> tr("{0} workouts planned · {1}", goal, shown(programName))
                        else -> tr("{0} to go · {1}", goal - done, shown(programName))
                    },
                    style = AppType.caption,
                    color = P.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // A count reads at a glance; an empty ring at the start of the week looked unfinished.
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$done", style = AppType.bigNumber, color = if (done > 0) P.textPrimary else P.textTertiary)
                Text("/$goal", style = AppType.number, color = P.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            weekdayLetters().forEachIndexed { i, letter ->
                val day = i + 1 // Calendar.SUNDAY == 1
                val trained = day in trainedDays
                val isToday = day == today
                Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        letter,
                        style = AppType.caption.copy(fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (isToday) P.textPrimary else P.textTertiary
                    )
                    Spacer(Modifier.height(6.dp))
                    // Trained days are filled; the rest are small marks, so a fresh week isn't a row of empty buttons.
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when {
                            trained -> Box(Modifier.fillMaxSize().clip(CircleShape).background(P.accentPrimary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = P.onAccentPrimary, modifier = Modifier.size(14.dp))
                            }
                            isToday -> Box(Modifier.size(8.dp).clip(CircleShape).background(P.accentPrimary))
                            else -> Box(Modifier.size(6.dp).clip(CircleShape).background(P.borderStrong))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecoveryStrip(items: List<MuscleRecoveryItem>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val notReady = items.filter { it.status != RecoveryStatus.READY }.sortedBy { it.recoveryPercent }
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Recovery"), style = AppType.headline, color = P.textPrimary, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = P.textTertiary, modifier = Modifier.size(16.dp))
        }
        if (notReady.isEmpty()) {
            // Five green 100s say less than one sentence.
            Text(
                tr("Everything's recovered. Train whatever you like today."),
                style = AppType.caption,
                color = P.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            Text(
                tr("{0} of {1} muscles still recovering", notReady.size, items.size),
                style = AppType.caption,
                color = P.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(14.dp))
            // Only the ones that aren't ready; eleven rings don't fit and rested ones say nothing.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                notReady.take(5).forEach { item ->
                    val color = recoveryColor(item.status, P)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ProgressRing(
                            progress = item.recoveryPercent / 100f,
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 5.dp,
                            color = color
                        ) {
                            Text("${item.recoveryPercent}", style = AppType.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = P.textPrimary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(recoveryLabel(item.muscleGroup), style = AppType.caption, color = P.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

fun recoveryColor(status: RecoveryStatus, P: GymPalette) = when (status) {
    RecoveryStatus.TIRED -> P.dangerText
    RecoveryStatus.RECOVERING -> P.warningText
    RecoveryStatus.READY -> P.successText
}

@Composable
private fun SplitRow(
    split: WorkoutSplit,
    doneThisWeek: Boolean,
    lastDone: CompletedWorkout?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val P = LocalPalette.current
    Row(
        modifier.fillMaxWidth().pressable(pressedScale = 0.99f, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (doneThisWeek) P.successSurface else P.bgControl),
                contentAlignment = Alignment.Center
            ) {
                if (doneThisWeek) Icon(Icons.Rounded.Check, tr("Done this week"), tint = P.successText, modifier = Modifier.size(22.dp))
                // The day's place in the program; letters repeat too often (Push, Pull).
                else Text(split.code.trimStart('0').ifBlank { shown(split.title).take(1) }, style = AppType.headline.copy(fontFeatureSettings = "tnum"), color = P.textSecondary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(shown(split.title), style = AppType.bodyStrong, color = P.textPrimary)
                Text(
                    if (doneThisWeek) tr("Done this week") else lastDone?.let { tr("Last done {0}", relativeDay(it.id)) } ?: daySubtitle(split),
                    style = AppType.caption,
                    color = if (doneThisWeek) P.successText else P.textSecondary
                )
            }
            Icon(Icons.Rounded.PlayArrow, tr("Start {0}", shown(split.title)), tint = P.textTertiary, modifier = Modifier.size(22.dp))
    }
}

/** Starts a workout with no exercises, for days that don't follow the program. */
@Composable
private fun QuickWorkoutRow(onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.99f, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(P.bgControl), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = P.textSecondary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Empty workout"), style = AppType.bodyStrong, color = P.textPrimary)
            Text(tr("Pick exercises as you go"), style = AppType.caption, color = P.textSecondary)
        }
        Icon(Icons.Rounded.PlayArrow, tr("Start an empty workout"), tint = P.textTertiary, modifier = Modifier.size(22.dp))
    }
}

/**
 * Every muscle, least rested first: how recovered it is and when it was last trained. Opens from
 * the Recovery card; recovery is a "what can I train today" question, so it lives on Today.
 */
@Composable
private fun RecoverySheet(items: List<MuscleRecoveryItem>, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    var explain by remember { mutableStateOf(false) }
    val sorted = remember(items) { items.sortedBy { it.recoveryPercent } }
    val ready = items.count { it.status == RecoveryStatus.READY }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxHeight(0.85f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()
        ) {
            Text(tr("Recovery"), style = AppType.title, color = P.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ready == items.size) tr("Everything's recovered") else tr("{0} of {1} muscles still recovering", items.size - ready, items.size),
                    style = AppType.body,
                    color = P.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (explain) tr("Hide") else tr("How it works"),
                    style = AppType.label,
                    color = P.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable { explain = !explain }.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            if (explain) {
                Text(
                    tr("Based on when you last trained each muscle, how many sets it got and how long it usually takes ") +
                        tr("to recover (legs and back longest, arms and calves quickest). Muscles that help in a lift count ") +
                        tr("half a set, like biceps on rows or triceps on bench."),
                    style = AppType.caption,
                    color = P.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            sorted.forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(P.borderSubtle))
                val color = recoveryColor(item.status, P)
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(shown(item.muscleGroup), style = AppType.body, color = P.textPrimary, maxLines = 1)
                        Text(
                            item.hoursSinceTrained?.let { h -> tr("Trained ") + when { h < 1 -> tr("just now"); h < 48 -> tr("{0}h ago", h); else -> tr("{0} days ago", h / 24) } } ?: tr("Not trained yet"),
                            style = AppType.caption,
                            color = P.textSecondary
                        )
                    }
                    if (item.status == RecoveryStatus.READY) {
                        Text(tr("Ready"), style = AppType.caption, color = P.successText)
                    } else {
                        Column(Modifier.width(80.dp), horizontalAlignment = Alignment.End) {
                            Text("${item.recoveryPercent}%", style = AppType.label.copy(fontFeatureSettings = "tnum"), color = color)
                            Spacer(Modifier.height(4.dp))
                            AnimatedProgressBar(item.recoveryPercent / 100f, color = color, height = 4.dp)
                        }
                    }
                }
            }
        }
    }
}

/** The running workout's clock, or the rest left while resting (then in the accent colour), ticking each second. */
@Composable
private fun RunningClock() {
    val P = LocalPalette.current
    val context = LocalContext.current
    val startTime = remember { LocalStorage.getActiveSessionStartTime(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var restLeft by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            restLeft = ((LocalStorage.getRestEndTimeMillis(context) - now) / 1000L).coerceAtLeast(0L)
            kotlinx.coroutines.delay(1000L)
        }
    }
    if (restLeft > 0) Pill(tr("Rest {0}", formatClock(restLeft)), color = P.accentPrimary, container = P.accentSoft, icon = Icons.Rounded.Timer)
    else Pill(formatClock((now - startTime) / 1000L), icon = Icons.Rounded.Schedule, container = P.bgControl)
}
