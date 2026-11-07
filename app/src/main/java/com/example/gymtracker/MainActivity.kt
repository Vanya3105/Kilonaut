package com.example.gymtracker

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.animation.PathInterpolator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    /** Dark icons on the bars, for a light palette; set from Compose. */
    var lightBars = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // The app is ready almost at once, which would cut the logo animation off after a few
        // frames. Hold it until the arc has drawn (Android 12+; older versions show a still icon).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val shownAt = SystemClock.uptimeMillis()
            splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() - shownAt < SPLASH_HOLD_MS }
        }
        // The logo grows and fades as the app appears behind it.
        splash.setOnExitAnimationListener { provider ->
            val easing = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            // Launches from a notification, widget or the watch get a splash without the logo, and
            // iconView then throws instead of returning null.
            runCatching { provider.iconView }.getOrNull()
                ?.animate()?.scaleX(1.35f)?.scaleY(1.35f)?.setDuration(SPLASH_EXIT_MS)?.setInterpolator(easing)?.start()
            provider.view.animate().alpha(0f).setDuration(SPLASH_EXIT_MS).setInterpolator(easing)
                .withEndAction {
                    provider.remove()
                    // Removing the splash puts the bars back to the theme's light icons; on a light
                    // page that left the clock white on white.
                    applySystemBarAppearance(this, lightBars)
                }.start()
        }
        super.onCreate(savedInstanceState)
        // Exercise photos were downloaded here before the drawn figures replaced them (2026-10-31).
        Thread { java.io.File(noBackupFilesDir, "exercise_photos").deleteRecursively() }.start()
        enableEdgeToEdge()
        // Not again when Android recreates the activity: the shortcut was already handled.
        if (savedInstanceState == null) AppShortcuts.take(intent)
        setContent { GymTrackerRoot(activity = this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppShortcuts.take(intent)
    }
}

private fun applySystemBarAppearance(activity: Activity, light: Boolean) {
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light
    }
}

private const val SPLASH_HOLD_MS = 550L
private const val SPLASH_EXIT_MS = 320L

@Composable
fun GymTrackerRoot(activity: Activity) {
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(Unit) {
        removeOldAlertChannels(context)
        CrashReports.apply(context)
        AppUpdate.check(context)
        // Alarms don't survive the app being force-stopped or updated from Android Studio.
        WorkoutReminders.schedule(context)
    }

    var settings by remember { mutableStateOf(LocalStorage.loadSettings(context)) }
    val palette = paletteFor(settings.themeChoice, isSystemInDarkTheme())

    LaunchedEffect(settings.targetFps) { applyDisplayRefreshRate(activity, settings.targetFps) }
    SideEffect {
        (activity as? MainActivity)?.lightBars = !palette.isDark
        applySystemBarAppearance(activity, !palette.isDark)
    }

    val haptics = remember(settings.hapticsEnabled, view) {
        Haptics(enabled = settings.hapticsEnabled, view = view)
    }

    GymTheme(palette) {
        Box(Modifier.fillMaxSize().background(palette.bgPrimary)) {
            GymTrackerApp(
                activity = activity,
                settings = settings,
                haptics = haptics,
                onUpdateSettings = { newSettings ->
                    val old = settings
                    settings = newSettings
                    LocalStorage.saveSettings(context, newSettings)
                    if (newSettings.reminderDays != old.reminderDays || newSettings.reminderMinutes != old.reminderMinutes ||
                        newSettings.weighReminderOn != old.weighReminderOn || newSettings.weighReminderMinutes != old.weighReminderMinutes
                    ) {
                        WorkoutReminders.schedule(context)
                    }
                    haptics.tick()
                }
            )
        }
    }
}

/** Small wrapper so screens can ask for "tick" vs "confirm" feedback without checking the setting. */
class Haptics(private val enabled: Boolean, private val view: android.view.View) {
    fun tick() {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun confirm() {
        if (!enabled) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        view.performHapticFeedback(type)
    }

    /** Picking something up to drag it. */
    fun pickUp() {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Letting go of a dragged item. */
    fun drop() {
        if (!enabled) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
        view.performHapticFeedback(type)
    }
}

/** [icon] is shown when selected, [idleIcon] (outlined) otherwise. */
enum class AppTab(val label: String, val icon: ImageVector, val idleIcon: ImageVector) {
    TODAY(tr("Today"), AppIcons.TodayFilled, AppIcons.Today),
    HISTORY(tr("History"), AppIcons.HistoryFilled, AppIcons.History),
    PROGRESS(tr("Progress"), AppIcons.ProgressFilled, AppIcons.Progress),
    PROFILE(tr("Profile"), AppIcons.ProfileFilled, AppIcons.Profile)
}

private fun <T> List<T>.moved(fromIdx: Int, toIdx: Int): List<T> =
    toMutableList().apply { add(toIdx, removeAt(fromIdx)) }

private fun needsSetup(p: UserProfile) = !p.hasCompletedOnboarding || p.currentWeightKg <= 0f

/** Set up before the training questions existed and not asked them yet. */
private fun needsTrainingQuestions(p: UserProfile) = p.hasCompletedOnboarding && p.experience == null && !p.trainingAsked

/** Builds the logbook entry; PRs are judged only against workouts logged before this one. */
internal fun buildCompletedWorkout(base: CompletedWorkout, exercises: List<ExerciseEntry>, history: List<CompletedWorkout>, editing: Boolean = false): CompletedWorkout {
    // An edit keeps what is there: a set added to an old workout is rarely ticked.
    val performed = if (editing) exercises.filter { !it.isSkipped } else performedExercises(exercises)
    val untouched = exercises.filter { ex -> !ex.isSkipped && performed.none { it.name == ex.name } }.map { it.name }
    val stats = computeWorkoutStats(performed, history.filter { it.id < base.id })
    return base.copy(exercises = performed, skippedExercises = base.skippedExercises + untouched, totalVolumeKg = stats.volumeKg, prsHit = stats.prCount)
}

/**
 * Saves the running workout like Finish → Save (no photo), as if it ended at [endMillis]: the
 * watch finishes now, a forgotten workout at its last change.
 */
internal fun finishActiveWorkout(context: Context, endMillis: Long, fromWidget: Boolean): CompletedWorkout? {
    val (splitTitle, exercises) = LocalStorage.loadActiveSession(context) ?: return null
    val end = Date(endMillis)
    val start = LocalStorage.getActiveSessionStartTime(context)
    val completed = buildCompletedWorkout(
        CompletedWorkout(
            id = end.time,
            splitTitle = splitTitle,
            dateKey = DateUtils.formatDayKey(end),
            displayDate = DateUtils.formatDisplayDate(end),
            durationSeconds = ((end.time - start) / 1000L).coerceAtLeast(0L),
            bodyWeightKg = LocalStorage.loadUserProfile(context).currentWeightKg,
            photoPath = null,
            skippedExercises = exercises.filter { it.isSkipped }.map { it.name },
            exercises = emptyList()
        ),
        exercises,
        LocalStorage.loadHistory(context)
    )
    LocalStorage.saveCompletedWorkout(context, completed, fromWidget = fromWidget)
    return completed
}

@Composable
fun GymTrackerApp(
    activity: Activity,
    settings: AppSettings,
    haptics: Haptics,
    onUpdateSettings: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val P = LocalPalette.current

    // Once, before the first frame (not in an effect): Profile shouldn't show "off" for a frame and then jump.
    remember {
        LocalStorage.reconcileProfileWeight(context)
        AutoBackup.refreshStatus(context)
        true
    }
    var userProfile by remember { mutableStateOf(LocalStorage.loadUserProfile(context)) }
    var weightHistory by remember { mutableStateOf(LocalStorage.loadWeightHistory(context)) }
    var history by remember { mutableStateOf(LocalStorage.loadHistory(context)) }
    // Not remembered: programFor reads the user's programs, so edits to the active one show at once.
    val activeProgram = programFor(userProfile.splitProgram)
    val splits = activeProgram.splits

    var showOnboarding by remember { mutableStateOf(needsSetup(userProfile) || needsTrainingQuestions(userProfile)) }
    // 1 when it opens on the training questions (from Profile, or once for people set up before they existed).
    var onboardingStart by remember { mutableIntStateOf(if (!needsSetup(userProfile) && needsTrainingQuestions(userProfile)) 1 else 0) }
    // Only on a new phone; saved so it survives the restart when the language changes.
    var showWelcome by rememberSaveable { mutableStateOf(!userProfile.hasCompletedOnboarding) }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    // Saved by id: after Android closes the app in the background it reopens on the same workout.
    var editingPastWorkout by rememberSaveable(
        saver = Saver<MutableState<CompletedWorkout?>, Long>(
            save = { it.value?.id },
            restore = { id -> mutableStateOf(LocalStorage.loadHistory(context).find { it.id == id }) }
        )
    ) { mutableStateOf(null) }
    // Exercise detail screen, shown over everything else (including a running workout).
    var openExercise by rememberSaveable { mutableStateOf<String?>(null) }
    var editingProgramId by rememberSaveable { mutableStateOf<String?>(null) }
    // Split builder, opened from onboarding or Profile with the name typed in the search (may be blank).
    var buildingSplit by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped when an exercise's page changes its note or training max.
    var exerciseInfoVersion by remember { mutableIntStateOf(0) }
    // The congratulations screen after Save.
    var celebration by remember { mutableStateOf<ShareData?>(null) }
    // Another workout asked for while one is running: what to do with the running one.
    var switchingTo by remember { mutableStateOf<WorkoutSplit?>(null) }

    val initialActive = remember { LocalStorage.loadActiveSession(context) }
    var activeSplit by remember {
        mutableStateOf(initialActive?.let { (title, _) -> splits.find { it.title == title } ?: WorkoutSplit(title, "", "01") })
    }
    // A past workout's edits are only on disk once saved, so they're kept with the screen's state.
    var editDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var currentExercises by remember {
        val draft = editDraft?.takeIf { editingPastWorkout != null }
        val restored = draft?.let { runCatching { StoreCodec.deserializeExercises(org.json.JSONArray(it)) }.getOrNull() }
        mutableStateOf(restored ?: initialActive?.second ?: emptyList())
    }
    LaunchedEffect(currentExercises, editingPastWorkout) {
        editDraft = if (editingPastWorkout != null) StoreCodec.serializeExercises(currentExercises).toString() else null
    }
    // The live workout is either open full-screen or minimized to the "Resume" bar.
    var workoutExpanded by rememberSaveable { mutableStateOf(initialActive != null) }

    val reloadFromDisk = rememberUpdatedState {
        val latest = LocalStorage.loadActiveSession(context)
        if (latest != null && editingPastWorkout == null) {
            activeSplit = splits.find { it.title == latest.first } ?: WorkoutSplit(latest.first, "", "01")
            currentExercises = latest.second
        } else if (latest == null && activeSplit != null) {
            // Finished from the watch: this screen would otherwise offer to save it a second time.
            activeSplit = null
            workoutExpanded = false
            history = LocalStorage.loadHistory(context)
            if (editingPastWorkout == null) currentExercises = emptyList()
        }
    }

    // Weigh-ins from a scale or Samsung Health arrive through Health Connect while the app is away.
    val healthScope = rememberCoroutineScope()
    val pullFromHealth = rememberUpdatedState {
        healthScope.launch {
            HealthSync.refresh(context)
            if (HealthSync.importWeights(context)) {
                weightHistory = LocalStorage.loadWeightHistory(context)
                userProfile = LocalStorage.loadUserProfile(context)
            }
        }
    }

    // A running workout nobody touched for an hour: when it last changed, while asking about it.
    var forgottenSince by remember { mutableLongStateOf(0L) }
    val checkForgotten = rememberUpdatedState {
        if (activeSplit != null && editingPastWorkout == null && forgottenSince == 0L) {
            val last = LocalStorage.getActiveSessionLastActivity(context)
            if (last > 0L && System.currentTimeMillis() - last >= FORGOTTEN_WORKOUT_MS) forgottenSince = last
        }
    }
    LaunchedEffect(activeSplit) {
        while (activeSplit != null) {
            checkForgotten.value()
            delay(60_000L)
        }
        forgottenSince = 0L
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reloadFromDisk.value()
                pullFromHealth.value()
                checkForgotten.value()
            } else if (event == Lifecycle.Event.ON_STOP) {
                // Edits outside a workout (weigh-ins, programs, settings) reach the backup here.
                AutoBackup.request(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = reloadFromDisk.value()
        }
        val filter = IntentFilter(ACTION_SESSION_SYNC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    val workoutVisible = (activeSplit != null && workoutExpanded) || editingPastWorkout != null
    LaunchedEffect(workoutVisible, settings.keepScreenAwake) {
        if (workoutVisible && settings.keepScreenAwake) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Other tabs go back to Today. On Today, Android takes back itself: its back-to-home animation
    // (predictive back) only plays when the app isn't holding the gesture.
    BackHandler(enabled = !workoutVisible && !showOnboarding && selectedTab != AppTab.TODAY) {
        selectedTab = AppTab.TODAY
    }

    // Allowed: the running workout's notification can show straight away.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LockScreenWorkoutController.sync(context)
    }
    fun askForNotificationsOnce() {
        if (!NotificationAsk.shouldAsk(context)) return
        NotificationAsk.markAsked(context)
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startWorkout(split: WorkoutSplit) {
        haptics.confirm()
        val running = activeSplit
        if (running != null) {
            // The same day: bring it back. Another one: ask what to do with this one first.
            if (running.title == split.title) workoutExpanded = true
            else switchingTo = split
            return
        }
        val entries = LocalStorage.loadTemplateForSplit(context, split.title).map { (exName, muscle) ->
            newExerciseEntry(context, split.title, exName, muscle, history, settings)
        }
        activeSplit = split
        currentExercises = entries
        workoutExpanded = true
        WorkoutReminders.dismiss(context)
        LocalStorage.saveActiveSession(context, split.title, entries)
        askForNotificationsOnce()
    }

    /** Starts with no exercises; the workout screen opens the exercise picker. */
    fun startQuickWorkout() {
        haptics.confirm()
        val running = activeSplit
        if (running != null) {
            if (running.title == QUICK_WORKOUT_TITLE) workoutExpanded = true
            else switchingTo = WorkoutSplit(QUICK_WORKOUT_TITLE, "", "")
            return
        }
        activeSplit = WorkoutSplit(QUICK_WORKOUT_TITLE, "", "")
        currentExercises = emptyList()
        workoutExpanded = true
        LocalStorage.saveActiveSession(context, QUICK_WORKOUT_TITLE, emptyList())
        askForNotificationsOnce()
    }

    /** Ends the running workout (saved, or thrown away) and starts [next] in its place. */
    fun switchWorkout(next: WorkoutSplit, save: Boolean) {
        if (save) {
            val saved = finishActiveWorkout(context, System.currentTimeMillis(), fromWidget = false)
            history = LocalStorage.loadHistory(context)
            if (saved != null) AppSnackbar.show(tr("{0} saved: {1}", shown(saved.splitTitle), formatDuration(saved.durationSeconds)))
        } else {
            LocalStorage.setRestEndTimeMillis(context, 0L, fromWidget = true)
            LocalStorage.saveActiveSession(context, null, emptyList())
        }
        activeSplit = null
        currentExercises = emptyList()
        if (next.title == QUICK_WORKOUT_TITLE) startQuickWorkout() else startWorkout(next)
    }

    fun discardActiveWorkout() {
        LocalStorage.setRestEndTimeMillis(context, 0L, fromWidget = true)
        LocalStorage.saveActiveSession(context, null, emptyList())
        workoutExpanded = false
        activeSplit = null
        currentExercises = emptyList()
    }

    fun finishForgottenWorkout(endMillis: Long) {
        haptics.confirm()
        val saved = finishActiveWorkout(context, endMillis, fromWidget = false)
        history = LocalStorage.loadHistory(context)
        workoutExpanded = false
        activeSplit = null
        currentExercises = emptyList()
        if (saved != null) {
            AppSnackbar.show(tr("{0} saved: {1}", shown(saved.splitTitle), formatDuration(saved.durationSeconds)))
        }
    }

    fun openPastWorkout(workout: CompletedWorkout) {
        haptics.tick()
        currentExercises = workout.exercises
        editingPastWorkout = workout
    }

    fun changeExerciseType(name: String, type: ExerciseType) {
        haptics.tick()
        LocalStorage.setExerciseType(context, name, type)
        history = LocalStorage.loadHistory(context)
        currentExercises = currentExercises.map { if (exerciseKey(it.name) == exerciseKey(name)) it.copy(type = type) else it }
    }

    /** Joins two names for one exercise; returns whether it worked. */
    fun mergeExercises(from: String, into: String): Boolean {
        val ok = LocalStorage.mergeExercise(context, from, into)
        if (ok) {
            haptics.confirm()
            history = LocalStorage.loadHistory(context)
            if (activeSplit != null) currentExercises = LocalStorage.loadActiveSession(context)?.second ?: currentExercises
            exerciseInfoVersion++
            AppSnackbar.show(tr("Merged into {0}", into))
        } else {
            AppSnackbar.show(tr("Couldn't merge those exercises"))
        }
        return ok
    }

    // Deleting happens at once and offers Undo, instead of asking first.
    fun deletePastWorkout(workout: CompletedWorkout) {
        haptics.tick()
        LocalStorage.deleteWorkoutFromHistory(context, workout.id)
        history = LocalStorage.loadHistory(context)
        AppSnackbar.show(tr("{0} deleted", workoutTitle(workout.splitTitle)), tr("Undo")) {
            LocalStorage.restoreWorkout(context, workout)
            history = LocalStorage.loadHistory(context)
        }
    }

    fun deleteWeighIn(id: Long) {
        val entry = weightHistory.find { it.id == id } ?: return
        weightHistory = LocalStorage.deleteWeightLog(context, id)
        userProfile = LocalStorage.loadUserProfile(context)
        AppSnackbar.show(tr("Weigh-in deleted"), tr("Undo")) {
            weightHistory = LocalStorage.restoreWeightLog(context, entry)
            userProfile = LocalStorage.loadUserProfile(context)
        }
    }

    // Long-press shortcuts on the app icon follow the program and the running workout.
    val upNext = remember(history, userProfile.splitProgram) { computeWeekSummary(history, 0, userProfile.splitProgram).recommendedSplit }
    LaunchedEffect(upNext, activeSplit?.title) {
        withContext(Dispatchers.Default) { AppShortcuts.update(context, upNext.takeIf { it.isNotBlank() }, activeSplit?.title) }
    }
    var logWeightRequest by remember { mutableIntStateOf(0) }
    val shortcut = AppShortcuts.pending
    LaunchedEffect(shortcut, showOnboarding) {
        if (shortcut == null || showOnboarding) return@LaunchedEffect
        AppShortcuts.pending = null
        when (shortcut) {
            AppShortcuts.START_NEXT -> {
                val running = activeSplit
                if (running != null) workoutExpanded = true
                else (splits.find { it.title == upNext } ?: splits.firstOrNull())?.let { startWorkout(it) }
            }
            AppShortcuts.EMPTY_WORKOUT -> startQuickWorkout()
            AppShortcuts.LOG_WEIGHT -> {
                workoutExpanded = false
                selectedTab = AppTab.PROGRESS
                logWeightRequest++
            }
        }
    }

    // What the tab bar (and the Resume bar) take at the bottom, so messages sit just above them.
    var rootHeight by remember { mutableIntStateOf(0) }
    var tabsHeight by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().onSizeChanged { rootHeight = it.height }) {
        Column(Modifier.fillMaxSize()) {
            TabHost(selected = selectedTab, modifier = Modifier.weight(1f).onSizeChanged { tabsHeight = it.height }) { tab ->
                when (tab) {
                    AppTab.TODAY -> HomeScreen(
                        splits = splits,
                        history = history,
                        userProfile = userProfile,
                        programName = activeProgram.name,
                        activeSplitTitle = activeSplit?.title,
                        activeExerciseCount = currentExercises.count { !it.isSkipped },
                        onStartWorkout = { startWorkout(it) },
                        onStartQuickWorkout = { startQuickWorkout() },
                        onOpenHistory = { selectedTab = AppTab.HISTORY },
                        tipsAllowed = !showOnboarding && !(activeSplit != null && workoutExpanded) && editingPastWorkout == null
                    )
                    AppTab.HISTORY -> HistoryScreen(
                        history = history,
                        userProfile = userProfile,
                        onOpenWorkout = { openPastWorkout(it) },
                        onDeleteWorkout = { deletePastWorkout(it) }
                    )
                    AppTab.PROGRESS -> ProgressScreen(
                        history = history,
                        userProfile = userProfile,
                        weightHistory = weightHistory,
                        haptics = haptics,
                        onLogWeight = { kg ->
                            weightHistory = LocalStorage.addWeightLog(context, kg)
                            WorkoutReminders.dismissWeighIn(context)
                            userProfile = LocalStorage.loadUserProfile(context)
                        },
                        onDeleteWeight = { id -> deleteWeighIn(id) },
                        logWeightRequest = logWeightRequest,
                        onOpenExercise = { haptics.tick(); openExercise = it },
                        onMergeExercises = { from, into -> mergeExercises(from, into) }
                    )
                    AppTab.PROFILE -> SettingsScreen(
                        settings = settings,
                        userProfile = userProfile,
                        onUpdateSettings = onUpdateSettings,
                        onUpdateProfile = { updated ->
                            val weightChanged = updated.currentWeightKg != userProfile.currentWeightKg
                            userProfile = updated
                            LocalStorage.saveUserProfile(context, updated)
                            // One body weight everywhere: a new weight here is a weigh-in too.
                            if (weightChanged) weightHistory = LocalStorage.addWeightLog(context, updated.currentWeightKg)
                        },
                        onReplayTips = { selectedTab = AppTab.TODAY },
                        onDataRestored = {
                            history = LocalStorage.loadHistory(context)
                            userProfile = LocalStorage.loadUserProfile(context)
                            weightHistory = LocalStorage.loadWeightHistory(context)
                            onUpdateSettings(LocalStorage.loadSettings(context))
                            // The workout on screen is the one from before the restore: take the backup's, or none.
                            reloadFromDisk.value()
                        },
                        onEditProgram = { haptics.tick(); editingProgramId = it },
                        onBuildSplit = { haptics.tick(); buildingSplit = it },
                        onEditTraining = { haptics.tick(); onboardingStart = 1; showOnboarding = true }
                    )
                }
            }

            AnimatedVisibility(
                visible = activeSplit != null && !workoutExpanded && editingPastWorkout == null && selectedTab != AppTab.TODAY,
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                ResumeWorkoutBar(
                    splitTitle = activeSplit?.title.orEmpty(),
                    exercises = currentExercises,
                    onResume = {
                        haptics.tick()
                        workoutExpanded = true
                    }
                )
            }

            BottomNavBar(selected = selectedTab, onSelect = {
                if (it != selectedTab) haptics.tick()
                selectedTab = it
            })
        }
        // The tabs draw edge to edge; this keeps scrolled content from running under the clock.
        StatusBarScrim()

        // Full-screen workout (live or editing a past one) slides up over the tabs.
        AnimatedVisibility(
            visible = workoutVisible,
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(150)),
            exit = slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200, delayMillis = 60))
        ) {
            val editing = editingPastWorkout
            val split = editing?.let { e -> splits.find { it.title == e.splitTitle } ?: WorkoutSplit(e.splitTitle, "", "01") }
                ?: activeSplit
            if (split != null) {
                Box(Modifier.fillMaxSize().background(P.bgPrimary)) {
                    if (editing != null) {
                        ActiveWorkoutScreen(
                            split = split,
                            exercises = currentExercises,
                            history = remember(history, editing.id) { history.filter { it.id < editing.id } },
                            settings = settings,
                            userProfile = userProfile,
                            isEditingPastWorkout = true,
                            initialDurationSeconds = editing.durationSeconds,
                            initialPhotoPath = editing.photoPath,
                            haptics = haptics,
                            hasUnsavedEdits = currentExercises != editing.exercises,
                            infoVersion = exerciseInfoVersion,
                            onOpenExercise = { openExercise = it },
                            onChangeExerciseType = ::changeExerciseType,
                            onUpdateExercises = { currentExercises = it },
                            onReorderExercises = { from, to -> currentExercises = currentExercises.moved(from, to) },
                            onAddExercise = { _, _ -> },
                            onRemoveExercisePermanently = { exName ->
                                val before = currentExercises
                                currentExercises = before.filterNot { it.name.equals(exName, ignoreCase = true) }
                                AppSnackbar.show(tr("{0} removed", shown(exName)), tr("Undo")) {
                                    if (editingPastWorkout?.id == editing.id) currentExercises = withReturned(currentExercises, before, exName)
                                }
                            },
                            onMinimize = {
                                editingPastWorkout = null
                                currentExercises = LocalStorage.loadActiveSession(context)?.second ?: emptyList()
                            },
                            onDiscardWorkout = {
                                editingPastWorkout = null
                                currentExercises = LocalStorage.loadActiveSession(context)?.second ?: emptyList()
                            },
                            initialNote = remember(editing.id) { LocalStorage.getWorkoutNote(context, editing.id) },
                            onFinishWorkout = { _, photoPath, skipped, note ->
                                haptics.confirm()
                                LocalStorage.setWorkoutNote(context, editing.id, note)
                                val updated = buildCompletedWorkout(
                                    editing.copy(photoPath = photoPath, skippedExercises = skipped),
                                    currentExercises,
                                    history,
                                    editing = true
                                )
                                LocalStorage.updateCompletedWorkout(context, updated)
                                history = LocalStorage.loadHistory(context)
                                editingPastWorkout = null
                                currentExercises = LocalStorage.loadActiveSession(context)?.second ?: emptyList()
                            }
                        )
                    } else {
                        ActiveWorkoutScreen(
                            split = split,
                            exercises = currentExercises,
                            history = history,
                            settings = settings,
                            userProfile = userProfile,
                            isEditingPastWorkout = false,
                            initialPhotoPath = null,
                            haptics = haptics,
                            infoVersion = exerciseInfoVersion,
                            onOpenExercise = { openExercise = it },
                            onChangeExerciseType = ::changeExerciseType,
                            onUpdateExercises = { updated ->
                                currentExercises = updated
                                LocalStorage.saveActiveSession(context, split.title, updated)
                            },
                            onReorderExercises = { from, to ->
                                val reordered = currentExercises.moved(from, to)
                                currentExercises = reordered
                                LocalStorage.saveActiveSession(context, split.title, reordered)
                                if (split.title != QUICK_WORKOUT_TITLE) {
                                    LocalStorage.saveTemplateForSplit(context, split.title, reordered.map { it.name to it.targetMuscle })
                                }
                            },
                            onAddExercise = { name, muscle ->
                                if (split.title != QUICK_WORKOUT_TITLE) LocalStorage.addExerciseToSplitTemplate(context, split.title, name, muscle)
                            },
                            onRemoveExercisePermanently = { exName ->
                                val day = if (split.title != QUICK_WORKOUT_TITLE) LocalStorage.daySnapshot(context, split.title) else null
                                if (day != null) LocalStorage.removeExerciseFromSplitTemplate(context, split.title, exName)
                                val before = currentExercises
                                val updated = before.filterNot { it.name.equals(exName, ignoreCase = true) }
                                currentExercises = updated
                                LocalStorage.saveActiveSession(context, split.title, updated)
                                // Says where it's gone from, which the old confirmation explained.
                                val text = if (day != null) tr("{0} removed from {1}", shown(exName), shown(split.title)) else tr("{0} removed", shown(exName))
                                AppSnackbar.show(text, tr("Undo")) {
                                    if (day != null) LocalStorage.restoreDay(context, day)
                                    if (activeSplit?.title == split.title && editingPastWorkout == null) {
                                        currentExercises = withReturned(currentExercises, before, exName)
                                        LocalStorage.saveActiveSession(context, split.title, currentExercises)
                                    }
                                }
                            },
                            onMinimize = { workoutExpanded = false },
                            onDiscardWorkout = {
                                haptics.tick()
                                discardActiveWorkout()
                            },
                            onFinishWorkout = { durationSecs, photoPath, skipped, note ->
                                haptics.confirm()
                                val now = Date()
                                LocalStorage.setWorkoutNote(context, now.time, note)
                                val completed = buildCompletedWorkout(
                                    CompletedWorkout(
                                        id = now.time,
                                        splitTitle = split.title,
                                        dateKey = DateUtils.formatDayKey(now),
                                        displayDate = DateUtils.formatDisplayDate(now),
                                        durationSeconds = durationSecs,
                                        bodyWeightKg = userProfile.currentWeightKg,
                                        photoPath = photoPath,
                                        skippedExercises = skipped,
                                        exercises = emptyList()
                                    ),
                                    currentExercises,
                                    history
                                )
                                // What the workout meant, judged against the workouts before it.
                                celebration = buildShareData(split.title, durationSecs, currentExercises, history, now.time, photoPath)
                                WorkoutChime.play(context)
                                LocalStorage.saveCompletedWorkout(context, completed)
                                history = LocalStorage.loadHistory(context)
                                workoutExpanded = false
                                activeSplit = null
                                currentExercises = emptyList()
                                selectedTab = AppTab.TODAY
                            }
                        )
                    }
                }
            }
        }

        val end = endSign()
        var shownProgramId by remember { mutableStateOf(editingProgramId) }
        if (editingProgramId != null) shownProgramId = editingProgramId
        AnimatedVisibility(
            visible = editingProgramId != null,
            enter = pageEnter(end),
            exit = pageExit(end)
        ) {
            shownProgramId?.let { id ->
                ProgramEditorScreen(
                    programId = id,
                    isActive = userProfile.splitProgram == id,
                    haptics = haptics,
                    onUseProgram = {
                        userProfile = userProfile.copy(splitProgram = id)
                        LocalStorage.saveUserProfile(context, userProfile)
                    },
                    onDeleted = {
                        if (userProfile.splitProgram == id) {
                            userProfile = userProfile.copy(splitProgram = DEFAULT_PROGRAM_ID)
                            LocalStorage.saveUserProfile(context, userProfile)
                        }
                        editingProgramId = null
                    },
                    onHistoryChanged = { history = LocalStorage.loadHistory(context) },
                    onBack = { editingProgramId = null }
                )
            }
            StatusBarScrim()
        }

        // Keep the last name while the screen animates out.
        var shownExercise by remember { mutableStateOf(openExercise) }
        if (openExercise != null) shownExercise = openExercise
        AnimatedVisibility(
            visible = openExercise != null,
            enter = pageEnter(end),
            exit = pageExit(end)
        ) {
            shownExercise?.let { name ->
                ExerciseDetailScreen(
                    exerciseName = name,
                    history = history,
                    haptics = haptics,
                    // A past workout being edited still holds the old name and would write it back.
                    canRename = editingPastWorkout == null,
                    onRename = { newName ->
                        val renamed = LocalStorage.renameExercise(context, name, newName)
                        if (renamed) {
                            history = LocalStorage.loadHistory(context)
                            if (activeSplit != null) currentExercises = LocalStorage.loadActiveSession(context)?.second ?: currentExercises
                            openExercise = newName
                        }
                        renamed
                    },
                    onChangeType = { type -> changeExerciseType(name, type) },
                    onBack = { openExercise = null },
                    onMerge = { from, into ->
                        // The page follows the kept name if its own exercise was merged away.
                        if (mergeExercises(from, into) && exerciseKey(from) == exerciseKey(name)) openExercise = into
                    },
                    onInfoChanged = {
                        exerciseInfoVersion++
                        // A new training max lays the exercise out again, if none of today's sets are done.
                        val split = activeSplit
                        if (split != null && editingPastWorkout == null) {
                            val relaid = currentExercises.map { ex ->
                                if (exerciseKey(ex.name) == exerciseKey(name) && ex.sets.none { it.isCompleted } && liftPlanFor(split.title, ex.name) != null) {
                                    newExerciseEntry(context, split.title, ex.name, ex.targetMuscle, history, settings).copy(restSeconds = ex.restSeconds)
                                } else ex
                            }
                            if (relaid != currentExercises) {
                                currentExercises = relaid
                                LocalStorage.saveActiveSession(context, split.title, relaid)
                            }
                        }
                    }
                )
            }
            StatusBarScrim()
        }

        AnimatedVisibility(
            visible = showOnboarding,
            enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.98f),
            exit = fadeOut(tween(250))
        ) {
            Box(Modifier.fillMaxSize().background(P.bgPrimary)) {
                OnboardingScreen(
                    initialProfile = userProfile,
                    unit = settings.weightUnit,
                    onSelectUnit = { onUpdateSettings(settings.copy(weightUnit = it, weightStep = it.defaultStep)) },
                    canGoBack = userProfile.hasCompletedOnboarding && userProfile.currentWeightKg > 0f,
                    haptics = haptics,
                    onBack = {
                        // Asked once; Profile can bring the questions back.
                        if (needsTrainingQuestions(userProfile)) {
                            userProfile = userProfile.copy(trainingAsked = true)
                            LocalStorage.saveUserProfile(context, userProfile)
                        }
                        showOnboarding = false; onboardingStart = 0
                    },
                    onBuildSplit = { haptics.tick(); buildingSplit = it },
                    startStep = onboardingStart,
                    onSaveProfile = { newProfile ->
                        val weightChanged = newProfile.currentWeightKg != userProfile.currentWeightKg
                        val old = userProfile
                        userProfile = newProfile
                        LocalStorage.saveUserProfile(context, newProfile)
                        LocalStorage.refitBuiltInDays(context, old, newProfile)
                        if (newProfile.splitProgram == RECOMMENDED_PLAN_ID) LocalStorage.saveGeneratedPlan(context, newProfile)
                        if (weightChanged) weightHistory = LocalStorage.addWeightLog(context, newProfile.currentWeightKg)
                        showOnboarding = false
                        onboardingStart = 0
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = showWelcome,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250))
        ) {
            Box(Modifier.fillMaxSize().background(P.bgPrimary)) {
                WelcomeScreen(
                    activity = activity,
                    haptics = haptics,
                    onContinue = { showWelcome = false },
                    onRestored = {
                        history = LocalStorage.loadHistory(context)
                        userProfile = LocalStorage.loadUserProfile(context)
                        weightHistory = LocalStorage.loadWeightHistory(context)
                        onUpdateSettings(LocalStorage.loadSettings(context))
                        reloadFromDisk.value()
                        showOnboarding = needsSetup(userProfile) || needsTrainingQuestions(userProfile)
                        onboardingStart = if (!needsSetup(userProfile) && needsTrainingQuestions(userProfile)) 1 else 0
                        showWelcome = false
                    }
                )
            }
        }

        // Last, so it also covers onboarding.
        var shownBuilderName by remember { mutableStateOf(buildingSplit) }
        if (buildingSplit != null) shownBuilderName = buildingSplit
        AnimatedVisibility(
            visible = buildingSplit != null,
            enter = pageEnter(end),
            exit = pageExit(end)
        ) {
            shownBuilderName?.let { name ->
                SplitBuilderScreen(
                    initialName = name,
                    haptics = haptics,
                    onCreated = { program ->
                        userProfile = userProfile.copy(splitProgram = program.id)
                        LocalStorage.saveUserProfile(context, userProfile)
                        buildingSplit = null
                    },
                    onClose = { buildingSplit = null }
                )
            }
        }

        switchingTo?.let { next ->
            val current = activeSplit
            if (current == null) {
                switchingTo = null
            } else {
                val ticked = currentExercises.filter { !it.isSkipped }.sumOf { ex -> ex.sets.count { it.isCompleted } }
                SwitchWorkoutDialog(
                    currentTitle = workoutTitle(current.title),
                    nextTitle = workoutTitle(next.title),
                    tickedSets = ticked,
                    onSave = { switchingTo = null; haptics.confirm(); switchWorkout(next, save = true) },
                    onDiscard = { switchingTo = null; haptics.tick(); switchWorkout(next, save = false) },
                    onKeep = { switchingTo = null; workoutExpanded = true }
                )
            }
        }

        celebration?.let { c ->
            WorkoutCelebration(
                data = c,
                haptics = haptics,
                onShare = { ShareRequests.pending = c },
                onDone = { celebration = null }
            )
        }
        ShareSheetHost()

        val forgotten = activeSplit
        if (forgottenSince > 0L && forgotten != null) {
            val keepGoing = {
                LocalStorage.touchActiveSession(context)
                forgottenSince = 0L
            }
            ForgottenWorkoutSheet(
                splitTitle = forgotten.title,
                startMillis = LocalStorage.getActiveSessionStartTime(context),
                lastChangeMillis = forgottenSince,
                doneSets = currentExercises.filter { !it.isSkipped }.sumOf { ex -> ex.sets.count { it.isCompleted } },
                onFinish = {
                    val end = forgottenSince
                    forgottenSince = 0L
                    finishForgottenWorkout(end)
                },
                onKeepGoing = { haptics.tick(); keepGoing() },
                onDiscard = {
                    haptics.tick()
                    forgottenSince = 0L
                    discardActiveWorkout()
                },
                onDismiss = keepGoing
            )
        }

        val fullScreenPage = workoutVisible || openExercise != null || editingProgramId != null ||
            buildingSplit != null || showOnboarding || showWelcome || celebration != null
        val density = LocalDensity.current
        AppSnackbarHost(
            Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (fullScreenPage) Modifier.navigationBarsPadding().padding(bottom = 16.dp)
                    else Modifier.padding(bottom = with(density) { (rootHeight - tabsHeight).coerceAtLeast(0).toDp() } + 8.dp)
                )
        )
    }
}

/**
 * Asked when a running workout hasn't changed for [FORGOTTEN_WORKOUT_MS]: saving it as ending at
 * its last change keeps a forgotten Finish from logging a six-hour session.
 */
@Composable
private fun ForgottenWorkoutSheet(
    splitTitle: String,
    startMillis: Long,
    lastChangeMillis: Long,
    doneSets: Int,
    onFinish: () -> Unit,
    onKeepGoing: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    val P = LocalPalette.current
    val now = System.currentTimeMillis()
    // Time of day if it was today, else the date.
    fun whenText(millis: Long) = android.text.format.DateUtils.formatSameDayTime(millis, now, DateFormat.MEDIUM, DateFormat.SHORT).toString()
    val lastText = whenText(lastChangeMillis)
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(tr("Still training?"), style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                (if (lastChangeMillis - startMillis < 60_000L) tr("{0} started at {1} and nothing has changed since.", shown(splitTitle), whenText(startMillis))
                else tr("{0} started at {1} and nothing has changed since {2}.", shown(splitTitle), whenText(startMillis), lastText)) +
                    if (doneSets == 0) tr(" No sets are ticked yet.") else "",
                style = AppType.body,
                color = P.textSecondary
            )
            Spacer(Modifier.height(20.dp))
            if (doneSets > 0) {
                PrimaryButton(tr("Finish at {0}", lastText), onFinish, Modifier.fillMaxWidth())
                Text(
                    tr("Saves {0}, {1}.", if (doneSets == 1) tr("1 set") else tr("{0} sets", doneSets), formatDuration(((lastChangeMillis - startMillis) / 1000L).coerceAtLeast(0L))),
                    style = AppType.caption,
                    color = P.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                SecondaryButton(tr("Keep going"), onKeepGoing, Modifier.fillMaxWidth())
            } else {
                PrimaryButton(tr("Keep going"), onKeepGoing, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(tr("Discard workout"), onDiscard, Modifier.fillMaxWidth(), contentColor = P.dangerText)
        }
    }
}

/** Creates a fresh exercise for today: one warm-up plus two work sets at the suggested weight. */
fun newExerciseEntry(
    context: Context,
    splitTitle: String,
    name: String,
    muscle: String,
    history: List<CompletedWorkout>,
    settings: AppSettings
): ExerciseEntry {
    val plan = liftPlanFor(splitTitle, name)
        ?: return newExerciseEntryDefault(context, splitTitle, name, muscle, history, settings)

    // A program's main lift: its sets come from a training max (its own, or another lift's for
    // nSuns' secondary lifts).
    val tm = LocalStorage.getTrainingMax(context, plan.trainingMaxOf)
    if (tm == null) {
        val whose = if (exerciseKey(plan.trainingMaxOf) == exerciseKey(name)) tr("a training max (tap the name)") else tr("a {0} training max", shown(plan.trainingMaxOf))
        return newExerciseEntryDefault(context, splitTitle, name, muscle, history, settings)
            .copy(planNote = tr("Set {0} to get your {1} weights", whose, plan.scheme.label))
    }
    val type = LocalStorage.exerciseType(context, name)
    val rest = LocalStorage.getExerciseDefaultRest(context, name, settings.restTimerSeconds)
    val day = planLiftDay(plan, tm, history)
    val note = buildList {
        add(day.title)
        if (day.weightKg == null) add(tr("TM {0}", formatWeightKg(day.trainingMaxKg)))
        day.amrapText()?.let(::add)
    }.joinToString(" · ")
    return ExerciseEntry(
        name = name, targetMuscle = muscle, restSeconds = rest, type = type, programmed = true,
        planNote = note,
        sets = day.sets.mapIndexed { i, p ->
            SetEntry(
                setNumber = i + 1,
                weight = day.weightFor(p),
                reps = "${p.reps}",
                isWarmup = p.warmup,
                intensityType = if (p.warmup) "WARMUP" else "NORMAL",
                restSeconds = if (p.warmup) minOf(60, rest) else rest
            )
        }
    )
}

/** A new entry without a percentage program: targets, last session, or plain defaults. */
private fun newExerciseEntryDefault(
    context: Context,
    splitTitle: String,
    name: String,
    muscle: String,
    history: List<CompletedWorkout>,
    settings: AppSettings
): ExerciseEntry {
    val type = LocalStorage.exerciseType(context, name)
    // Sets, reps and rest from the setup answers when the day has no target of its own.
    val prescription = prescriptionFor(TrainingProfile.current, name)?.takeIf { type.usesReps }
    // A rest the owner chose in Settings wins over the recommended one.
    val restFallback = prescription?.restSeconds?.takeIf { settings.restTimerSeconds == AppSettings().restTimerSeconds } ?: settings.restTimerSeconds
    val rest = LocalStorage.getExerciseDefaultRest(context, name, restFallback)
    val target = LocalStorage.getTarget(context, splitTitle, name)
    val workSetCount = target?.sets ?: prescription?.sets
    val targetReps = target?.repMax?.toString() ?: prescription?.repMin?.toString()

    if (!type.hasWeightSuggestions) {
        // No weight to suggest: start from your own last session, else plain defaults.
        val last = history.firstNotNullOfOrNull { w ->
            w.exercises.find { !it.isSkipped && exerciseKey(it.name) == exerciseKey(name) }?.workSets?.takeIf { it.isNotEmpty() }
        }
        val defaultSet = SetEntry(
            setNumber = 0,
            weight = "",
            reps = if (type.usesReps) targetReps ?: "10" else "",
            seconds = if (type == ExerciseType.DURATION) "60" else ""
        )
        val count = workSetCount ?: last?.size ?: if (type == ExerciseType.DISTANCE_TIME) 1 else 3
        val template = List(count) { i -> last?.getOrNull(i) ?: last?.lastOrNull() ?: defaultSet }
        return ExerciseEntry(
            name = name, targetMuscle = muscle, restSeconds = rest, type = type, target = target,
            sets = template.mapIndexed { i, s ->
                SetEntry(i + 1, weight = s.weight, reps = (if (type.usesReps) targetReps else null) ?: s.reps, seconds = s.seconds, distanceM = s.distanceM, restSeconds = rest)
            }
        )
    }

    val suggestion = suggestFromSplitHistory(splitTitle, ExerciseEntry(name, muscle, type = type, target = target), history, settings.weightStep)
    val unit = Units.current
    if (type == ExerciseType.WEIGHTED_BODYWEIGHT) {
        // Added weight; no warm-up set (the warm-up is the bodyweight version).
        val added = suggestion?.suggestedWeight?.ifBlank { null } ?: "0"
        return ExerciseEntry(
            name = name, targetMuscle = muscle, restSeconds = rest, type = type, target = target,
            sets = List(workSetCount ?: 3) { i -> SetEntry(i + 1, weight = added, reps = targetReps ?: "8", restSeconds = rest) }
        )
    }
    val workWeight = suggestion?.suggestedWeight?.ifBlank { null }
        ?: startingWeightKg(TrainingProfile.current, name)?.let { kg -> unit.stored(kotlin.math.round(unit.fromKg(kg) / unit.defaultStep).coerceAtLeast(1f) * unit.defaultStep) }
        ?: unit.stored(unit.defaultWorkWeight)
    // Warm-up at half the work weight, rounded down to a whole default step (2.5 kg / 5 lb).
    val half = unit.fromKg(parseWeightClean(workWeight, unit.toKg(unit.defaultWorkWeight))) * 0.5f
    val warmupWeight = unit.stored((half / unit.defaultStep).toInt() * unit.defaultStep)
    // With a plan from the setup answers, only compound lifts get a warm-up set: a raise or a curl
    // after the big lifts is warm already, and the set would only lengthen the session.
    val warmup = if (prescription != null && !isCompound(name)) emptyList()
    else listOf(SetEntry(1, weight = warmupWeight, reps = "8", isWarmup = true, intensityType = "WARMUP", restSeconds = minOf(60, rest)))
    return ExerciseEntry(
        name = name,
        targetMuscle = muscle,
        restSeconds = rest,
        type = type,
        target = target,
        sets = warmup + List(workSetCount ?: 2) { i -> SetEntry(warmup.size + i + 1, weight = workWeight, reps = targetReps ?: "8", restSeconds = rest) }
    )
}

/** A page over the tabs comes in from the end side (the right; the left in Hebrew), [end] from [endSign]. */
private fun pageEnter(end: Int) =
    androidx.compose.animation.slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it * end } +
        fadeIn(tween(150))

/** And goes back out that way: a short slide while it fades, so a back press doesn't just blink it away. */
private fun pageExit(end: Int) =
    androidx.compose.animation.slideOutHorizontally(tween(220, easing = FastOutLinearInEasing)) { it / 4 * end } +
        fadeOut(tween(200))

/**
 * One pill slides (with a little overshoot) to the selected tab; the selected icon fills in
 * and gives a small bounce.
 */
@Composable
private fun BottomNavBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val P = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(if (P.isDark) 0.dp else 16.dp, spotColor = P.shadow.copy(alpha = 0.12f), ambientColor = P.shadow.copy(alpha = 0.06f))
            .background(P.bgCard)
    ) {
        if (P.isDark) Box(Modifier.fillMaxWidth().height(1.dp).background(P.borderSubtle))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            val tabWidth = maxWidth / AppTab.entries.size
            val pillWidth = 64.dp
            val pillX by animateDpAsState(
                tabWidth * selected.ordinal + (tabWidth - pillWidth) / 2,
                spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
                label = "nav_pill_x"
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .offset(x = pillX)
                    .size(pillWidth, 34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(P.bgControl)
            )
            Row(Modifier.fillMaxWidth()) {
                AppTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    val iconColor by animateColorAsState(if (isSelected) P.accentPrimary else P.textSecondary, label = "nav_icon")
                    val labelColor by animateColorAsState(if (isSelected) P.textPrimary else P.textSecondary, label = "nav_label")
                    val bounce = remember { Animatable(1f) }
                    LaunchedEffect(isSelected) {
                        if (isSelected) {
                            bounce.snapTo(0.82f)
                            bounce.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .pressable(pressedScale = 0.92f, role = Role.Tab) { onSelect(tab) }
                            .semantics { this.selected = isSelected }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                            Crossfade(isSelected, animationSpec = tween(180), label = "nav_icon_fill") { sel ->
                                Icon(
                                    if (sel) tab.icon else tab.idleIcon,
                                    // The label below names the tab; naming the icon too read it twice.
                                    contentDescription = null,
                                    tint = iconColor,
                                    modifier = Modifier.size(24.dp).graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }
                                )
                            }
                        }
                        Spacer(Modifier.height(1.dp))
                        CappedTextSize {
                            Text(tab.label, style = AppType.caption, color = labelColor, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Solid behind the clock (see-through, scrolled text showed between the icons), then a short fade
 * so content slides under it softly instead of being cut along a line.
 */
@Composable
fun StatusBarScrim() {
    val P = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(P.bgPrimary))
        Box(Modifier.fillMaxWidth().height(12.dp).background(Brush.verticalGradient(listOf(P.bgPrimary, P.bgPrimary.copy(alpha = 0f)))))
    }
}

@Composable
private fun ResumeWorkoutBar(splitTitle: String, exercises: List<ExerciseEntry>, onResume: () -> Unit) =
    // Always on screen above the tabs: at the largest text sizes it would take a fifth of the screen.
    CappedTextSize(max = 1.5f) { ResumeWorkoutBarContent(splitTitle, exercises, onResume) }

@Composable
private fun ResumeWorkoutBarContent(splitTitle: String, exercises: List<ExerciseEntry>, onResume: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val startTime = remember(splitTitle) { LocalStorage.getActiveSessionStartTime(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var restLeft by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            restLeft = ((LocalStorage.getRestEndTimeMillis(context) - now) / 1000L).coerceAtLeast(0L)
            delay(1000L)
        }
    }
    val active = exercises.filter { !it.isSkipped }
    val total = active.sumOf { it.sets.size }
    val done = active.sumOf { ex -> ex.sets.count { it.isCompleted } }

    AppCard(
        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth(),
        elevated = true,
        onClick = onResume,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = if (total == 0) 0f else done / total.toFloat(),
                modifier = Modifier.size(40.dp),
                strokeWidth = 4.dp
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(P.accentPrimary))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("{0} in progress", shown(splitTitle)), style = AppType.bodyStrong, color = P.textPrimary)
                Text(
                    tr("{0} · {1} of {2} sets", formatClock((now - startTime) / 1000L), done, total),
                    style = AppType.caption.copy(fontFeatureSettings = "tnum"),
                    color = P.textSecondary
                )
            }
            if (restLeft > 0) {
                Pill(formatClock(restLeft), color = P.accentPrimary, container = P.accentSoft, icon = Icons.Rounded.Timer)
                Spacer(Modifier.width(8.dp))
            }
            Text(tr("Resume"), style = AppType.label, color = P.accentPrimary)
        }
    }
}

/**
 * Keeps each tab composed after its first visit: switching back is instant (no rebuilding the
 * screen and measuring its text again) and returns to where it was scrolled. Hidden tabs aren't
 * measured, placed or drawn, so they take no touches and cost nothing per frame.
 *
 * Switching is Material's "fade through": the old tab fades out over the first 30%, then the new
 * one fades in while growing from 96%. Both are driven by one progress value that is reset in the
 * same frame the tab changes, so the new tab is never drawn at full opacity before its fade (it
 * used to flash in for a frame, vanish and fade back: the "twitch"). Timing starts after the new
 * tab's first frame, so a slow first visit doesn't swallow the animation.
 */
@Composable
private fun TabHost(selected: AppTab, modifier: Modifier, content: @Composable (AppTab) -> Unit) {
    val visited = remember { mutableStateListOf(selected) }
    if (selected !in visited) visited += selected
    // Which tab was showing before [selected]; a plain holder, worked out during composition.
    val order = remember { object { var current = selected; var previous: AppTab? = null } }
    if (order.current != selected) {
        order.previous = order.current
        order.current = selected
    }
    val from = order.previous
    // A new value per switch, created at 0 in the same composition the tab changes.
    val progress = remember(selected) { Animatable(if (from == null) 1f else 0f) }
    var settled by remember(selected) { mutableStateOf(from == null) }
    LaunchedEffect(selected) {
        if (settled) return@LaunchedEffect
        withFrameNanos { }
        progress.animateTo(1f, tween(300, easing = LinearEasing))
        settled = true
    }
    val shown = selected
    val leaving = if (settled) null else from

    Box(modifier) {
        for (tab in AppTab.entries) {
            if (tab !in visited) continue
            key(tab) {
                val incoming = tab == shown
                val outgoing = tab == leaving
                Box(
                    Modifier.fillMaxSize().then(
                        when {
                            incoming -> Modifier.zIndex(1f).graphicsLayer {
                                val t = ((progress.value - 0.3f) / 0.7f).coerceIn(0f, 1f)
                                val eased = FastOutSlowInEasing.transform(t)
                                alpha = eased
                                val scale = 0.96f + 0.04f * eased
                                scaleX = scale
                                scaleY = scale
                            }
                            outgoing -> Modifier.graphicsLayer {
                                alpha = 1f - FastOutLinearInEasing.transform((progress.value / 0.3f).coerceIn(0f, 1f))
                            }
                            else -> Modifier.layout { _, constraints -> layout(constraints.maxWidth, constraints.maxHeight) {} }
                        }
                    )
                ) { content(tab) }
            }
        }
    }
}

/** How a workout is named in a sentence: the day's name, or "Empty workout" for one started empty. */
/** [now] with the exercise named [name] put back where it was in [before] (Undo of a remove). */
private fun withReturned(now: List<ExerciseEntry>, before: List<ExerciseEntry>, name: String): List<ExerciseEntry> {
    val idx = before.indexOfFirst { it.name.equals(name, ignoreCase = true) }
    if (idx < 0 || now.any { it.name.equals(name, ignoreCase = true) }) return now
    // After the exercise that preceded it, so edits made in the meantime keep their place.
    val after = before.getOrNull(idx - 1)?.let { prev -> now.indexOfFirst { it.name.equals(prev.name, ignoreCase = true) } } ?: -1
    return now.toMutableList().apply { add(after + 1, before[idx]) }
}

private fun workoutTitle(title: String) = if (title == QUICK_WORKOUT_TITLE) tr("Empty workout") else shown(title)

/**
 * Starting another workout while one runs: save this one (when a set is ticked) or throw it away
 * and go on to [nextTitle], or keep going with this one.
 */
@Composable
private fun SwitchWorkoutDialog(
    currentTitle: String,
    nextTitle: String,
    tickedSets: Int,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeep: () -> Unit
) {
    val P = LocalPalette.current
    AppBottomSheet(onDismiss = onKeep) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(tr("{0} is still going", currentTitle), style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                if (tickedSets == 0) tr("Start {0} instead? Nothing is ticked in {1} yet.", nextTitle, currentTitle)
                else tr("Start {0} instead? Save {1} first, or discard it.", nextTitle, currentTitle),
                style = AppType.body,
                color = P.textSecondary
            )
            Spacer(Modifier.height(20.dp))
            if (tickedSets > 0) {
                PrimaryButton(tr("Save and start {0}", nextTitle), onSave, Modifier.fillMaxWidth())
                Text(
                    if (tickedSets == 1) tr("Saves 1 set.") else tr("Saves {0} sets.", tickedSets),
                    style = AppType.caption,
                    color = P.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                SecondaryButton(tr("Discard and start {0}", nextTitle), onDiscard, Modifier.fillMaxWidth(), contentColor = P.dangerText)
            } else {
                PrimaryButton(tr("Discard and start {0}", nextTitle), onDiscard, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(tr("Keep {0}", currentTitle), onKeep, Modifier.fillMaxWidth())
        }
    }
}
