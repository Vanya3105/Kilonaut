package com.example.gymtracker

import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.content.ContextCompat
import java.util.Calendar
import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils as AndroidDateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.Date
import org.json.JSONObject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
fun SettingsScreen(
    settings: AppSettings,
    userProfile: UserProfile,
    onUpdateSettings: (AppSettings) -> Unit,
    onUpdateProfile: (UserProfile) -> Unit,
    onReplayTips: () -> Unit,
    onDataRestored: () -> Unit,
    onEditProgram: (String) -> Unit,
    onBuildSplit: (String) -> Unit,
    onEditTraining: () -> Unit,
    history: List<CompletedWorkout> = emptyList()
) {
    val P = LocalPalette.current
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                val json = LocalStorage.exportJsonBackupString(context)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                AppSnackbar.show(tr("Backup saved"))
            } catch (_: Exception) {
                AppSnackbar.show(tr("Couldn't save the backup"))
            }
        }
    }
    // A picked backup waits here while the owner chooses to add it or replace everything with it.
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(content.removePrefix("\uFEFF").trim()) }.getOrNull()
                if (json != null && StoreCodec.looksLikeBackup(json)) pendingBackup = content
                else AppSnackbar.show(tr("That file isn't a Kilonaut backup"))
            } catch (_: Exception) {
                AppSnackbar.show(tr("Couldn't read that file"))
            }
        }
    }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    // Re-read on return from Android's settings, where it's turned on.
    var notificationsOn by remember { mutableStateOf(hasNotificationPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsOn = hasNotificationPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val history = LocalStorage.loadHistory(context)
                val csv = workoutsCsv(history) { LocalStorage.getWorkoutNote(context, it) }
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                AppSnackbar.show(if (history.size == 1) tr("Exported 1 workout") else tr("Exported {0} workouts", history.size))
            } catch (_: Exception) {
                AppSnackbar.show(tr("Couldn't save the file"))
            }
        }
    }
    var otherAppFile by remember { mutableStateOf<String?>(null) }
    val otherAppLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                otherAppFile = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                AppSnackbar.show(tr("Couldn't read that file"))
            }
        }
    }

    // The settings, one page at a time over the profile.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var shownPage by remember { mutableStateOf(page) }
    var showWhatsNew by remember { mutableStateOf(false) }
    if (page != null) shownPage = page

    @Composable
    fun PageContent(p: SettingsPage) {
        when (p) {
            SettingsPage.ACCOUNT -> SettingsGroup(tr("Account")) { AccountSection() }
            SettingsPage.FRIENDS -> FriendsSection()
            SettingsPage.SOCIAL -> SocialSettingsSection()
            SettingsPage.SPLIT -> {
                SettingsGroup(tr("Training split")) {
                    val current = programFor(userProfile.splitProgram)
                    SplitPicker(
                        selectedId = current.id,
                        onSelect = { onUpdateProfile(userProfile.copy(splitProgram = it.id)) },
                        onBuild = onBuildSplit,
                        onEdit = { onEditProgram(it.id) },
                        cardActionLabel = if (current.isBuiltIn) tr("Customize") else tr("Edit"),
                        contained = false,
                        onCardAction = {
                            if (current.isBuiltIn) {
                                // Built-in programs stay as they are; edits go to a copy.
                                val copy = customizedCopy(current)
                                LocalStorage.saveProgram(context, copy)
                                onUpdateProfile(userProfile.copy(splitProgram = copy.id))
                                onEditProgram(copy.id)
                            } else onEditProgram(current.id)
                        }
                    )
                }
            }
            SettingsPage.TRAINING -> {
                    SettingsGroup(tr("During a workout")) {
                        Text(tr("Units"), style = AppType.label, color = P.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        SegmentedControl(
                            options = listOf(WeightUnit.KG to tr("Kilograms (kg)"), WeightUnit.LB to tr("Pounds (lb)")),
                            selected = settings.weightUnit,
                            // Switching only changes how weights are shown; the step resets to the new unit's default.
                            onSelect = { if (it != settings.weightUnit) onUpdateSettings(settings.copy(weightUnit = it, weightStep = it.defaultStep)) }
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(tr("Weight step"), style = AppType.label, color = P.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        ChoiceChips(
                            options = settings.weightUnit.stepPresets.map { it to "${formatKgClean(it)} ${settings.weightUnit.label}" },
                            selected = settings.weightStep,
                            onSelect = { onUpdateSettings(settings.copy(weightStep = it)) }
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(tr("Default rest between sets"), style = AppType.label, color = P.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        ChoiceChips(
                            options = listOf(0 to tr("Off"), 60 to tr("1 min"), 90 to "1:30", 120 to tr("2 min"), 180 to tr("3 min"), 240 to tr("4 min")),
                            selected = settings.restTimerSeconds,
                            onSelect = { onUpdateSettings(settings.copy(restTimerSeconds = it)) }
                        )
                        Spacer(Modifier.height(8.dp))
                        ToggleRow(
                            tr("Sound when rest ends"),
                            tr("A short beep that lowers your music instead of stopping it"),
                            settings.restSoundEnabled
                        ) { onUpdateSettings(settings.copy(restSoundEnabled = it)) }
                        ToggleRow(tr("Effort per set"), tr("After a set, tap how many more reps you had in you"), settings.trackEffort) {
                            onUpdateSettings(settings.copy(trackEffort = it))
                        }
                        ToggleRow(tr("Keep screen on"), tr("While a workout is open"), settings.keepScreenAwake) {
                            onUpdateSettings(settings.copy(keepScreenAwake = it))
                        }
                        ToggleRow(tr("Vibration"), null, settings.hapticsEnabled) {
                            onUpdateSettings(settings.copy(hapticsEnabled = it))
                        }
                        if (!notificationsOn) {
                            LinkRow(Icons.Rounded.NotificationsOff, tr("Workout notification is off")) {
                                val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
                            }
                            Text(
                                tr("Turn it on to see your set and rest timer on the lock screen while you train."),
                                style = AppType.caption,
                                color = P.textTertiary
                            )
                        }
                    }
        
                RemindersGroup(settings, userProfile.daysPerWeek, onUpdateSettings)
            }
            SettingsPage.DATA -> {
                    SettingsGroup(tr("Backups")) {
                        BackupsSection(
                            onRestoreContent = { pendingBackup = it },
                            onRestoreFile = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            onSaveFile = { exportLauncher.launch("kilonaut_backup.json") }
                        )
                    }
        
                HealthConnectGroup(onImported = onDataRestored)
                    SettingsGroup(tr("Other apps")) {
                        LinkRow(Icons.Rounded.MoveToInbox, tr("Import from Strong or Hevy")) {
                            otherAppLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                        }
                        Text(
                            tr("Added to your history. A copy of your data is kept first."),
                            style = AppType.caption,
                            color = P.textTertiary
                        )
                        Spacer(Modifier.height(4.dp))
                        LinkRow(Icons.Rounded.TableChart, tr("Export workouts as CSV")) {
                            csvLauncher.launch("kilonaut-workouts-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())}.csv")
                        }
                        Text(
                            tr("One row per set, in Strong's layout, so other apps can read it."),
                            style = AppType.caption,
                            color = P.textTertiary
                        )
                    }
        
            }
            SettingsPage.APP -> {
                    SettingsGroup(tr("Appearance")) {
                        SegmentedControl(
                            options = listOf(THEME_SYSTEM to tr("System"), THEME_LIGHT to tr("Light"), THEME_DARK to tr("Dark")),
                            selected = settings.themeChoice,
                            onSelect = { onUpdateSettings(settings.copy(themeChoice = it)) }
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.settings_language), style = AppType.label, color = P.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        SegmentedControl(
                            options = AppLanguage.entries.map { it to it.label },
                            selected = remember { AppLanguage.current(context) },
                            onSelect = { language -> context.findActivity()?.let { AppLanguage.set(it, language) } }
                        )
                    }
        

                    SettingsGroup(tr("Help")) {
                        LinkRow(Icons.Rounded.Lightbulb, tr("Show tips again")) {
                            TutorialPrefs.resetAllTutorials(context)
                            onReplayTips()
                        }
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Widgets, null, tint = P.textSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                tr("Add widgets from your home screen. Long-press one to change how it looks."),
                                style = AppType.caption,
                                color = P.textSecondary
                            )
                        }
                    }
        

                    SettingsGroup(tr("Advanced")) {
                        ToggleRow(
                            title = tr("Smooth scrolling"),
                            subtitle = tr("120 Hz. Off saves battery."),
                            checked = settings.targetFps >= 120,
                            onCheckedChange = { onUpdateSettings(settings.copy(targetFps = if (it) 120 else 60)) }
                        )
                        if (CrashReports.available(context)) {
                            var crashReports by remember { mutableStateOf(CrashReports.isOn(context)) }
                            ToggleRow(
                                title = tr("Send crash reports"),
                                subtitle = tr("What went wrong and the phone model, never your workouts."),
                                checked = crashReports,
                                onCheckedChange = { crashReports = it; CrashReports.set(context, it) }
                            )
                        }
                        LinkRow(Icons.Rounded.DeleteForever, tr("Delete all data"), danger = true) { confirmDeleteAll = true }
                    }
        
                AboutGroup()
            }
            SettingsPage.WHATS_NEW -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(tr("Profile"), style = AppType.display, color = P.textPrimary, modifier = Modifier.statusBarsPadding().padding(top = 20.dp))
        }

        if (Account.available(context)) item { ProfileHeader(onOpen = { page = SettingsPage.ACCOUNT }) }

        item { ProfileStats(history, programFor(userProfile.splitProgram).weeklyGoal) }

        item { ProfileDashboard(history) }


        item {
            SettingsGroup(tr("Your training")) {
                val p = userProfile
                if (p.experience == null || p.goal == null) {
                    Text(tr("Answer a few questions so the app can pick exercises, sets, reps and rest for you."), style = AppType.body, color = P.textSecondary)
                } else {
                    // The goal as the headline, the rest as chips: one long "Some · Build muscle · …"
                    // line wrapped mid-phrase, and "Some" meant nothing without its question.
                    Text(p.goal.label, style = AppType.title, color = P.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.daysPerWeek > 0) Pill(tr("{0} days a week", p.daysPerWeek), icon = Icons.Rounded.CalendarMonth)
                        if (p.sessionMinutes > 0) Pill(tr("{0} min", p.sessionMinutes), icon = Icons.Rounded.Schedule)
                        p.equipment?.let { Pill(it.label, icon = Icons.Rounded.FitnessCenter) }
                        Pill(p.experience.detail, icon = Icons.AutoMirrored.Rounded.TrendingUp)
                        p.focus?.takeIf { it != Focus.BALANCED }?.let { Pill(tr("Focus: {0}", it.label), color = P.accentPrimary, container = P.accentSoft) }
                    }
                    if (p.skipMuscles.isNotEmpty() || p.limits.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    if (p.skipMuscles.isNotEmpty()) {
                        Text(tr("Not training: {0}", BUILDER_MUSCLES.filter { it in p.skipMuscles }.joinToString(", ") { shown(it) }), style = AppType.caption, color = P.textSecondary)
                    }
                    if (p.limits.isNotEmpty()) {
                        Text(tr("Going easy on: {0}", p.limits.joinToString(", ") { it.label }), style = AppType.caption, color = P.textSecondary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(if (p.experience == null) tr("Answer the questions") else tr("Change answers"), onEditTraining, Modifier.fillMaxWidth())
            }
        }

        item {
            SettingsGroup(tr("Training split")) {
                val current = programFor(userProfile.splitProgram)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(shown(current.name), style = AppType.title, color = P.textPrimary)
                        Text(tr("{0} days a week", current.splits.size), style = AppType.caption, color = P.textSecondary)
                    }
                    SecondaryButton(tr("Change"), { page = SettingsPage.SPLIT })
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    current.splits.forEach { Pill(shown(it.title)) }
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    if (current.isBuiltIn) tr("Customize") else tr("Edit"),
                    {
                        if (current.isBuiltIn) {
                            // Built-in programs stay as they are; edits go to a copy.
                            val copy = customizedCopy(current)
                            LocalStorage.saveProgram(context, copy)
                            onUpdateProfile(userProfile.copy(splitProgram = copy.id))
                            onEditProgram(copy.id)
                        } else onEditProgram(current.id)
                    },
                    Modifier.fillMaxWidth()
                )
            }
        }

        item {
            SettingsGroup(tr("Settings"), padding = PaddingValues(vertical = 4.dp)) {
                val pages = SettingsPage.entries.filter { it != SettingsPage.ACCOUNT && it != SettingsPage.SPLIT && it != SettingsPage.FRIENDS }
                pages.forEachIndexed { i, entry ->
                    if (i > 0) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(P.borderSubtle))
                    SettingsRow(entry.icon, entry.title, subtitle = entry.contents) {
                        if (entry == SettingsPage.WHATS_NEW) showWhatsNew = true else page = entry
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = page != null,
        enter = slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(150)),
        exit = slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(200))
    ) {
        shownPage?.let { p -> SettingsPageScreen(p.title, sections = p.sections, onBack = { page = null }) { PageContent(p) } }
    }
    if (showWhatsNew) {
        val version = remember { WhatsNew.currentVersion(context) }
        if (version != null && WhatsNew.notesFor(version).isNotEmpty()) WhatsNewSheet(version) { showWhatsNew = false }
        else showWhatsNew = false
    }
    }

    pendingBackup?.let { content ->
        RestoreChoiceDialog(
            onAdd = {
                pendingBackup = null
                val result = LocalStorage.addBackupJsonString(context, content)
                if (result != null) {
                    onDataRestored()
                    AppSnackbar.show(addedMessage(result))
                } else AppSnackbar.show(tr("Couldn't add that backup"))
            },
            onReplace = {
                pendingBackup = null
                if (LocalStorage.importJsonBackupString(context, content)) {
                    onDataRestored()
                    AppSnackbar.show(tr("Backup restored"))
                } else AppSnackbar.show(tr("That file isn't a Kilonaut backup"))
            },
            onDismiss = { pendingBackup = null }
        )
    }
    if (confirmDeleteAll) {
        DeleteAllDialog(
            onSaveBackup = { exportLauncher.launch("kilonaut_backup.json") },
            onDismiss = { confirmDeleteAll = false }
        )
    }
    otherAppFile?.let { text ->
        OtherAppImportSheet(
            text = text,
            defaultUnit = settings.weightUnit,
            onDone = { result ->
                otherAppFile = null
                if (result != null) {
                    onDataRestored()
                    AppSnackbar.show(addedMessage(result))
                }
            },
            onDismiss = { otherAppFile = null }
        )
    }

}

/** Days that suit [perWeek] workouts, spread out: Mon/Wed/Fri for three. */
internal fun defaultReminderDays(perWeek: Int): Set<Int> = when (perWeek) {
    1 -> setOf(Calendar.MONDAY)
    2 -> setOf(Calendar.MONDAY, Calendar.THURSDAY)
    4 -> setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    5 -> (Calendar.MONDAY..Calendar.FRIDAY).toSet()
    6 -> (Calendar.MONDAY..Calendar.SATURDAY).toSet()
    7 -> (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
    else -> setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY)
}

@Composable
private fun RemindersGroup(settings: AppSettings, perWeek: Int, onUpdateSettings: (AppSettings) -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val on = settings.reminderDays.isNotEmpty()
    // Which time is being picked: the workout reminder's or the weigh-in's.
    var picking by remember { mutableStateOf<ReminderKind?>(null) }
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val time = clockText(settings.reminderMinutes, is24)
    val weighTime = clockText(settings.weighReminderMinutes, is24)
    // Exact alarms: asked once, in the app's own words, the first time a reminder is turned on.
    var askExact by remember { mutableStateOf(false) }
    fun askExactOnce() {
        if (WorkoutReminders.mayBeLate(context) && !WorkoutReminders.askedExact(context)) {
            WorkoutReminders.markAskedExact(context)
            askExact = true
        }
    }
    // Reminders are notifications: on Android 13+ they need the permission, asked when turned on;
    // the alarm question follows, so the two don't open on top of each other.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askExactOnce() }
    fun askIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else askExactOnce()
    }
    SettingsGroup(tr("Reminders")) {
        ToggleRow(
            tr("Workout reminders"),
            if (on) tr("On your training days at {0}", time) else tr("A nudge on the days you train"),
            on
        ) { turnOn ->
            onUpdateSettings(settings.copy(reminderDays = if (turnOn) defaultReminderDays(perWeek) else emptySet()))
            if (turnOn) askIfNeeded()
        }
        AnimatedVisibility(on, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    weekdayLetters().forEachIndexed { i, letter ->
                        val day = i + 1 // Calendar.SUNDAY == 1
                        val picked = day in settings.reminderDays
                        // Inverted like every other choice in the app; orange is for actions.
                        val bg by animateColorAsState(if (picked) P.textPrimary else P.bgControl, label = "reminder_day")
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .pressable(pressedScale = 0.9f, role = Role.Checkbox) {
                                    val days = if (picked) settings.reminderDays - day else settings.reminderDays + day
                                    onUpdateSettings(settings.copy(reminderDays = days))
                                }
                                .semantics { this.selected = picked },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(letter, style = AppType.label, color = if (picked) P.bgPrimary else P.textSecondary)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TimeRow(time) { picking = ReminderKind.WORKOUT }
                Text(tr("Skipped on days you've trained."), style = AppType.caption, color = P.textTertiary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.borderSubtle))
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            tr("Morning weigh-in"),
            if (settings.weighReminderOn) tr("Every day at {0}", weighTime) else tr("A reminder to weigh yourself each morning"),
            settings.weighReminderOn
        ) { turnOn ->
            onUpdateSettings(settings.copy(weighReminderOn = turnOn))
            if (turnOn) askIfNeeded()
        }
        AnimatedVisibility(settings.weighReminderOn, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                TimeRow(weighTime) { picking = ReminderKind.WEIGH_IN }
                Text(tr("Skipped once you've weighed in."), style = AppType.caption, color = P.textTertiary)
            }
        }
    }
    picking?.let { kind ->
        TimeDialSheet(
            title = if (kind == ReminderKind.WORKOUT) tr("Workout reminder") else tr("Morning weigh-in"),
            initialMinutes = if (kind == ReminderKind.WORKOUT) settings.reminderMinutes else settings.weighReminderMinutes,
            is24 = is24,
            hapticsOn = settings.hapticsEnabled,
            onDismiss = { picking = null },
            onSave = { picked ->
                picking = null
                onUpdateSettings(if (kind == ReminderKind.WORKOUT) settings.copy(reminderMinutes = picked) else settings.copy(weighReminderMinutes = picked))
            }
        )
    }
    if (askExact) {
        AlertDialog(
            onDismissRequest = { askExact = false },
            containerColor = P.bgElevated,
            title = { Text(tr("Remind you right on time?"), style = AppType.title, color = P.textPrimary) },
            text = {
                Text(
                    tr("Without it, Android may hold a reminder back by up to an hour. Allow Kilonaut to set alarms and they come at the minute you picked."),
                    style = AppType.body, color = P.textSecondary
                )
            },
            confirmButton = {
                TextButton({
                    askExact = false
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + context.packageName))
                        )
                    }
                }) { Text(tr("Allow"), color = P.accentPrimary) }
            },
            dismissButton = { TextButton({ askExact = false }) { Text(tr("Not now"), color = P.textSecondary) } }
        )
    }
}

private enum class ReminderKind { WORKOUT, WEIGH_IN }

/** "Time" with the chosen time in a pill; tap to change it. */
@Composable
private fun TimeRow(time: String, onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.99f, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr("Time"), style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f))
        Text(time, style = AppType.label, color = P.textPrimary, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(P.bgControl).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun HealthConnectGroup(onImported: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availability = remember { HealthSync.availability(context) }
    var on by remember { mutableStateOf(LocalStorage.isHealthSyncOn(context)) }
    val granted = HealthSync.granted
    LaunchedEffect(Unit) { HealthSync.refresh(context) }

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            AppSnackbar.show(tr("Couldn't open Health Connect"))
        }
    }

    val askPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { _ ->
        scope.launch {
            HealthSync.refresh(context)
            if (HealthSync.granted.isEmpty()) {
                // Android stops showing the dialog after two refusals; access can still be given in Health Connect.
                AppSnackbar.show(tr("Nothing was allowed. You can allow it in Health Connect."))
                return@launch
            }
            LocalStorage.setHealthSyncOn(context, true)
            on = true
            HealthSync.shareExisting(context)
            if (HealthSync.importWeights(context)) onImported()
        }
    }

    SettingsGroup(tr("Health Connect")) {
        when (availability) {
            HealthSync.Availability.UNAVAILABLE ->
                Text(tr("Health Connect isn't available on this phone."), style = AppType.caption, color = P.textTertiary)
            HealthSync.Availability.NEEDS_INSTALL -> {
                Text(
                    tr("Install or update Health Connect to share workouts and body weight with Samsung Health and Google Fit."),
                    style = AppType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                    color = P.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(tr("Get Health Connect"), { open(HealthSync.installIntent()) }, Modifier.fillMaxWidth())
            }
            HealthSync.Availability.AVAILABLE -> if (!on || granted.isEmpty()) {
                Text(
                    tr("Share your workouts and body weight with Samsung Health, Google Fit and other apps, bring in weigh-ins from your scale, and see your watch's heart rate in your history."),
                    style = AppType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                    color = P.textSecondary
                )
                Spacer(Modifier.height(12.dp))
                SecondaryButton(tr("Connect"), { askPermissions.launch(HealthSync.PERMISSIONS) }, Modifier.fillMaxWidth())
                LinkRow(Icons.AutoMirrored.Rounded.OpenInNew, tr("Open Health Connect")) { open(HealthSync.manageIntent(context)) }
            } else {
                SharedRow(tr("Workouts you finish"), HealthSync.WRITE_WORKOUTS in granted)
                SharedRow(tr("Body weight you log"), HealthSync.WRITE_WEIGHT in granted)
                SharedRow(tr("Weigh-ins from scales and other apps"), HealthSync.READ_WEIGHT in granted)
                SharedRow(tr("Heart rate during workouts"), HealthSync.READ_HEART_RATE in granted)
                if (!granted.containsAll(HealthSync.PERMISSIONS)) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(tr("Allow the rest"), { askPermissions.launch(HealthSync.PERMISSIONS) }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                LinkRow(Icons.AutoMirrored.Rounded.OpenInNew, tr("Manage in Health Connect")) { open(HealthSync.manageIntent(context)) }
                LinkRow(Icons.Rounded.LinkOff, tr("Stop sharing")) {
                    HealthSync.disconnect(context)
                    on = false
                }
            }
        }
    }
}

internal const val PRIVACY_POLICY_URL = "https://vanya3105.github.io/Kilonaut/privacy.html"
private const val RELEASES_URL = "https://github.com/Vanya3105/Kilonaut/releases"

/** Where to get the app: the store page for Play installs, else the newest GitHub release (with the APK). */
internal fun appShareLink(context: android.content.Context): String {
    val version = AppUpdate.shareVersion(context)
    return when {
        AppUpdate.installedFromPlay(context) -> "https://play.google.com/store/apps/details?id=${context.packageName}"
        version != null -> "$RELEASES_URL/tag/v$version"
        else -> RELEASES_URL
    }
}
private const val FEEDBACK_EMAIL = "evtushenkovanya16@gmail.com"

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

@Composable
private fun AboutGroup() {
    val P = LocalPalette.current
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    val version = remember { appVersion(context) }

    fun open(intent: Intent, missing: String) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            AppSnackbar.show(missing)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsGroup(tr("About")) {
            LinkRow(Icons.Rounded.Share, tr("Share Kilonaut")) {
                // Play users get the store page; everyone else the newest GitHub release, with the APK to install.
                val link = appShareLink(context)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, tr("I track my workouts with Kilonaut: {0}", link))
                }
                open(Intent.createChooser(send, tr("Share Kilonaut")), tr("No app to share with"))
            }
            // Rating only means something on Play; installs from GitHub have nowhere to rate.
            if (AppUpdate.installedFromPlay(context)) {
                LinkRow(Icons.Rounded.StarOutline, tr("Rate on Google Play")) {
                    val market = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${context.packageName}"))
                    try { context.startActivity(market) } catch (_: ActivityNotFoundException) {
                        open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")), tr("No browser on this phone"))
                    }
                }
            }
            LinkRow(Icons.Rounded.MailOutline, tr("Send feedback")) {
                // The version and phone go along, so a bug report says where it happened.
                val body = "\n\n—\nKilonaut $version · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).apply {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, "Kilonaut feedback")
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                open(intent, tr("No email app on this phone"))
            }
            LinkRow(Icons.Rounded.PrivacyTip, tr("Privacy policy")) {
                open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_POLICY_URL)), tr("No browser on this phone"))
            }
            LinkRow(Icons.Rounded.Description, tr("Open-source licenses")) { showLicenses = true }
        }
        Spacer(Modifier.height(16.dp))
        Text("Kilonaut", style = AppType.label, color = P.textSecondary)
        if (version.isNotEmpty()) Text(tr("Version {0}", AppUpdate.shownVersion(version)), style = AppType.caption, color = P.textTertiary)
    }

    if (showLicenses) LicensesSheet(onDismiss = { showLicenses = false })
}

private class OpenSourcePart(val name: String, val author: String, val license: String)

private const val APACHE = "Apache License 2.0"
private const val OFL = "SIL Open Font License 1.1"

private val OPEN_SOURCE_PARTS = listOf(
    OpenSourcePart("Inter (font)", "The Inter Project Authors", OFL),
    OpenSourcePart("Jetpack Compose, Material 3", "The Android Open Source Project", APACHE),
    OpenSourcePart("Material Icons", "Google", APACHE),
    OpenSourcePart("AndroidX Core, Activity, Lifecycle, Splash Screen", "The Android Open Source Project", APACHE),
    OpenSourcePart("Room", "The Android Open Source Project", APACHE),
    OpenSourcePart("Health Connect client", "The Android Open Source Project", APACHE),
    OpenSourcePart("Firebase Crashlytics", "Google", APACHE),
    OpenSourcePart("Kotlin, kotlinx.coroutines", "JetBrains", APACHE),
)

@Composable
private fun LicensesSheet(onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    var openText by remember { mutableStateOf<String?>(null) }
    val texts = remember {
        mapOf(APACHE to R.raw.license_apache, OFL to R.raw.license_ofl).mapValues { (_, id) ->
            val raw = runCatching { context.resources.openRawResource(id).bufferedReader().use { it.readText() } }.getOrDefault("")
            // The files are wrapped for 80-column terminals; joining each paragraph lets it fill the screen.
            raw.split(Regex("\\n\\s*\\n")).joinToString("\n\n") { para -> para.lines().joinToString(" ") { it.trim() }.trim() }
        }
    }
    AppBottomSheet(onDismiss = onDismiss) {
        LazyColumn(
            Modifier.fillMaxHeight(0.85f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            item {
                Text(tr("Open-source licenses"), style = AppType.title, color = P.textPrimary)
                Text(
                    tr("Kilonaut is built with these open-source parts. Thank you to their authors."),
                    style = AppType.caption,
                    color = P.textSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
            }
            items(OPEN_SOURCE_PARTS) { part ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(part.name, style = AppType.bodyStrong, color = P.textPrimary)
                    Text("${part.author} · ${part.license}", style = AppType.caption, color = P.textSecondary)
                }
            }
            item {
                Text(
                    tr("License texts"),
                    style = AppType.overline,
                    color = P.textSecondary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            texts.forEach { (license, text) ->
                item(key = license) {
                    val expanded = openText == license
                    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "license_chevron")
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .pressable(pressedScale = 0.99f) { openText = if (expanded) null else license }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(license, style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Rounded.ExpandMore,
                                if (expanded) tr("Hide") else tr("Show"),
                                tint = P.textTertiary,
                                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn }
                            )
                        }
                        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Text(text, style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedRow(label: String, shared: Boolean) {
    val P = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (shared) Icons.Rounded.Check else Icons.Rounded.Close,
            if (shared) tr("Shared") else tr("Not shared"),
            tint = if (shared) P.successText else P.textTertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = AppType.body, color = if (shared) P.textPrimary else P.textTertiary)
    }
}

/** On a settings page the page's own title names the group, so the card goes without one. */
private val LocalOnSettingsPage = staticCompositionLocalOf { false }

@Composable
private fun SettingsGroup(title: String, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val P = LocalPalette.current
    Column {
        if (!LocalOnSettingsPage.current) {
            Text(title, style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        AppCard(Modifier.fillMaxWidth(), contentPadding = padding, content = content)
    }
}

/**
 * The settings, each a row on Profile that opens its own page. Related settings share a page
 * (owner, 2026-11-11): ten pages of one short card each left most of every page blank.
 */
enum class SettingsPage(val icon: ImageVector) {
    ACCOUNT(Icons.Rounded.AccountCircle),
    FRIENDS(Icons.Rounded.Group),
    SOCIAL(Icons.Rounded.Shield),
    SPLIT(Icons.Rounded.CalendarMonth),
    TRAINING(Icons.Rounded.FitnessCenter),
    DATA(Icons.Rounded.CloudUpload),
    APP(Icons.Rounded.Tune),
    WHATS_NEW(Icons.Rounded.NewReleases);

    val title: String get() = when (this) {
        ACCOUNT -> tr("Account")
        FRIENDS -> tr("Friends")
        SOCIAL -> tr("Friends and privacy")
        SPLIT -> tr("Training split")
        TRAINING -> tr("Training")
        DATA -> tr("Your data")
        APP -> tr("App")
        WHATS_NEW -> tr("What's new")
    }

    /** What's on the page, under its row on Profile. */
    val contents: String? get() = when (this) {
        SOCIAL -> tr("Notifications, blocked people, rules")
        TRAINING -> tr("Units, rest timer, reminders")
        DATA -> tr("Backups, Health Connect, Strong and Hevy")
        APP -> tr("Theme, language, help, about")
        else -> null
    }

    /** Pages made of several titled cards; the rest are one card named by the page's title. */
    val sections: Boolean get() = this == TRAINING || this == DATA || this == APP
}

@Composable
private fun SettingsRow(icon: ImageVector, text: String, danger: Boolean = false, subtitle: String? = null, onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.99f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (danger) P.dangerText else P.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = AppType.body, color = if (danger) P.dangerText else P.textPrimary)
            if (subtitle != null) Text(subtitle, style = AppType.caption, color = P.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
    }
}

/** A settings page over Profile: back, its title, then its settings. */
@Composable
private fun SettingsPageScreen(title: String, sections: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    val P = LocalPalette.current
    val backModifier = rememberPredictiveBack(onBack = onBack)
    Box(backModifier.fillMaxSize().background(P.bgPrimary)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                    CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), onBack)
                }
            }
            item { Text(title, style = AppType.display, color = P.textPrimary) }
            item {
                if (sections) Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { content() }
                else CompositionLocalProvider(LocalOnSettingsPage provides true) { content() }
            }
        }
        StatusBarScrim()
    }
}

@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val P = LocalPalette.current
    // Wraps instead of scrolling, so no option hides past the card's edge.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            // Selected reads as inverted rather than orange: the accent is for actions.
            val bg by animateColorAsState(if (isSel) P.textPrimary else P.bgControl, label = "chip_bg")
            val fg by animateColorAsState(if (isSel) P.bgPrimary else P.textPrimary, label = "chip_fg")
            Text(
                label,
                style = AppType.label,
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .pressable(pressedScale = 0.94f, role = Role.RadioButton) { onSelect(value) }
                    .semantics { this.selected = isSel }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Android's own "Clear storage" for the app: files, database, preferences, photos and the
 * pre-import copies all go, so nothing is left behind the way a hand-written wipe could leave it.
 * It ends the app's process; the next start is a first start.
 */
@Composable
private fun DeleteAllDialog(onSaveBackup: () -> Unit, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        containerColor = P.bgElevated,
        title = { Text(tr("Delete all your data?"), style = AppType.headline, color = P.textPrimary) },
        text = {
            Column {
                Text(
                    tr("Every workout, weigh-in, program, photo and setting on this phone is deleted. The app closes and starts again as new. This can't be undone."),
                    style = AppType.body,
                    color = P.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("Backups in Google Drive or a folder stay, and so does anything already shared with Health Connect."),
                    style = AppType.caption,
                    color = P.textTertiary
                )
                Spacer(Modifier.height(14.dp))
                SecondaryButton(tr("Save a backup file first"), onSaveBackup, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                onClick = {
                    deleting = true
                    scope.launch {
                        if (LocalStorage.isHealthSyncOn(context)) HealthSync.revokeAll(context)
                        // Pending writes would otherwise land after the wipe and bring data back.
                        withContext(Dispatchers.IO) { LocalStorage.flush(context) }
                        val am = context.getSystemService(android.app.ActivityManager::class.java)
                        if (am?.clearApplicationUserData() != true) {
                            onDismiss()
                            AppSnackbar.show(tr("Couldn't delete the data"))
                        }
                    }
                }
            ) { Text(if (deleting) tr("Deleting…") else tr("Delete everything"), color = P.dangerText, style = AppType.label) }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = onDismiss) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) }
        }
    )
}

@Composable
private fun LinkRow(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .pressable(pressedScale = 0.99f, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (danger) P.dangerText else P.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = AppType.body, color = if (danger) P.dangerText else P.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
    }
}

private fun addedMessage(r: CombineResult): String = buildString {
    append(if (r.addedWorkouts == 1) tr("Added 1 workout") else tr("Added {0} workouts", r.addedWorkouts))
    if (r.addedWeighIns > 0) append(if (r.addedWeighIns == 1) tr(" and 1 weigh-in") else tr(" and {0} weigh-ins", r.addedWeighIns))
    if (r.alreadyHere > 0) append(if (r.alreadyHere == 1) tr(" (1 was already here)") else tr(" ({0} were already here)", r.alreadyHere))
}

/** Restore used to replace everything; adding the backup to what's here now comes first. */
@Composable
private fun RestoreChoiceDialog(onAdd: () -> Unit, onReplace: () -> Unit, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.bgElevated,
        title = { Text(tr("Restore backup"), style = AppType.headline, color = P.textPrimary) },
        text = {
            Column {
                ChoiceRow(tr("Add to my data"), tr("Workouts and weigh-ins from the backup join yours. Nothing here is removed."), onAdd)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(tr("Replace everything"), tr("Your data becomes exactly what the backup holds."), onReplace, danger = true)
                Spacer(Modifier.height(8.dp))
                Text(tr("A copy of your data is kept first either way."), style = AppType.caption, color = P.textTertiary)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
    )
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, onClick: () -> Unit, danger: Boolean = false) {
    val P = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.bgControl)
            .pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, style = AppType.bodyStrong, color = if (danger) P.dangerText else P.textPrimary)
        Text(subtitle, style = AppType.caption, color = P.textSecondary)
    }
}

/**
 * Shows what a Strong or Hevy export holds before anything is added: workouts, sets, dates,
 * renamed exercises, and (for Strong, whose files don't say) which unit its weights are in.
 */
@Composable
private fun OtherAppImportSheet(text: String, defaultUnit: WeightUnit, onDone: (CombineResult?) -> Unit, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unit by remember { mutableStateOf(defaultUnit) }
    var adding by remember { mutableStateOf(false) }
    val parsed by produceState<Pair<ImportedHistory?, Int>?>(null, text, unit) {
        value = withContext(Dispatchers.Default) {
            val history = LocalStorage.loadHistory(context)
            val h = parseWorkoutExport(text, unit, loggedExerciseNames(history), LocalStorage.loadUserProfile(context).currentWeightKg)
            val have = history.mapTo(HashSet()) { it.id }
            h to (h?.workouts?.count { it.id in have } ?: 0)
        }
    }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            val state = parsed
            val h = state?.first
            when {
                state == null -> Text(tr("Reading the file…"), style = AppType.body, color = P.textSecondary)
                h == null -> {
                    Text(tr("Not a Strong or Hevy export"), style = AppType.title, color = P.textPrimary)
                    Text(
                        tr("Export from Strong (Settings → Export Strong Data) or Hevy (Settings → Export & Import Data → Export Workouts), then pick the .csv file here."),
                        style = AppType.body,
                        color = P.textSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                else -> {
                    val already = state.second
                    val first = h.workouts.minOf { it.id }
                    val last = h.workouts.maxOf { it.id }
                    Text(tr("Import from {0}", h.source.label), style = AppType.title, color = P.textPrimary)
                    Text(
                        tr("{0} workouts · {1} sets · ", h.workouts.size, String.format(Locale.US, "%,d", h.setCount)) +
                            "${DateUtils.formatShortDayYear(Date(first))} – ${DateUtils.formatShortDayYear(Date(last))}",
                        style = AppType.body,
                        color = P.textSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (already > 0) {
                        Text(tr("{0} of them are already here and stay as they are.", already), style = AppType.caption, color = P.textTertiary)
                    }
                    if (!h.unitInFile) {
                        Spacer(Modifier.height(16.dp))
                        Text(tr("Weights in this file are in"), style = AppType.label, color = P.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        ChoiceChips(options = listOf(WeightUnit.KG to tr("Kilograms (kg)"), WeightUnit.LB to tr("Pounds (lb)")), selected = unit, onSelect = { unit = it })
                        Text(
                            tr("{0} doesn't save the unit in its file: pick the one you used there.", h.source.label),
                            style = AppType.caption,
                            color = P.textTertiary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (h.renamed.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(tr("Matched to this app's exercises"), style = AppType.label, color = P.textSecondary)
                        h.renamed.entries.take(4).forEach { (from, to) ->
                            Text("$from → $to", style = AppType.caption, color = P.textPrimary, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (h.renamed.size > 4) {
                            Text(tr("and {0} more", h.renamed.size - 4), style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(
                        if (adding) tr("Adding…") else tr("Add to my history"),
                        {
                            adding = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { LocalStorage.addData(context, h.asStoreData()) }
                                if (result == null) AppSnackbar.show(tr("Couldn't add the workouts"))
                                onDone(result)
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = !adding && h.workouts.size > already
                    )
                    Text(
                        tr("Nothing here is changed or removed. A copy of your data is kept first."),
                        style = AppType.caption,
                        color = P.textTertiary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/** The backups in Drive, newest first; picking one hands its contents to Restore (add or replace). */
@Composable
private fun DriveRestoreSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf<String?>(null) }
    val files by produceState<List<DriveBackup.DriveBackupFile>?>(null) {
        value = withContext(Dispatchers.IO) { DriveBackup.listBackups(context) } ?: emptyList()
    }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(tr("Restore from Google Drive"), style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(4.dp))
            val list = files
            when {
                list == null -> Text(tr("Looking for your backups…"), style = AppType.body, color = P.textSecondary)
                list.isEmpty() -> Text(tr("No backups in this Google account yet."), style = AppType.body, color = P.textSecondary)
                else -> {
                    Text(tr("Pick one. Next you choose to add it to your data or replace it."), style = AppType.caption, color = P.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    list.take(12).forEachIndexed { i, f ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(enabled = downloading == null, pressedScale = 0.98f) {
                                downloading = f.file.id
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) { DriveBackup.download(context, f.file) }
                                    downloading = null
                                    if (text == null) AppSnackbar.show(tr("Couldn't download that backup"))
                                    else onPick(text)
                                }
                            }.padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    AndroidDateUtils.formatDateTime(
                                        context, f.modifiedMillis,
                                        AndroidDateUtils.FORMAT_SHOW_DATE or AndroidDateUtils.FORMAT_SHOW_TIME or AndroidDateUtils.FORMAT_SHOW_YEAR
                                    ),
                                    style = AppType.bodyStrong,
                                    color = P.textPrimary
                                )
                                Text(
                                    (if (i == 0) tr("Newest · ") else "") + tr("{0} KB", (f.sizeBytes + 1023) / 1024),
                                    style = AppType.caption,
                                    color = P.textSecondary
                                )
                            }
                            if (downloading == f.file.id) Text(tr("Downloading…"), style = AppType.caption, color = P.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One place for backups: where they go and when the last one ran, then one "Back up now" (every
 * destination that's on) and one "Restore" (asks: Google Drive or a file). The folder backup,
 * saving a file and signing out sit under "More options". Signed out, signing in with Google is
 * the main action. Right after signing in on a phone with nothing logged (a new phone), the
 * backups in Drive are offered straight away.
 */
@Composable
private fun BackupsSection(onRestoreContent: (String) -> Unit, onRestoreFile: () -> Unit, onSaveFile: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drive = DriveBackup.status
    val folder = AutoBackup.status
    LaunchedEffect(Unit) {
        DriveBackup.refreshStatus(context)
        AutoBackup.refreshStatus(context)
    }
    var signingIn by remember { mutableStateOf(false) }
    var choosingDrive by remember { mutableStateOf(false) }
    var choosingSource by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmFolderOff by remember { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }

    fun afterSignIn(step: DriveBackup.SignInStep) {
        signingIn = false
        when (step) {
            is DriveBackup.SignInStep.Failed -> if (step.message.isNotEmpty()) AppSnackbar.show(step.message)
            is DriveBackup.SignInStep.NeedsUser -> Unit
            is DriveBackup.SignInStep.Done -> scope.launch {
                val found = withContext(Dispatchers.IO) { DriveBackup.listBackups(context) }.orEmpty()
                if (found.isNotEmpty() && LocalStorage.loadHistory(context).isEmpty()) choosingDrive = true
                else DriveBackup.backUpNow(context)
            }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        DriveBackup.finishSignIn(context, result.data, ::afterSignIn)
    }
    fun signIn() {
        signingIn = true
        DriveBackup.startSignIn(context) { step ->
            if (step is DriveBackup.SignInStep.NeedsUser) consent.launch(IntentSenderRequest.Builder(step.intent).build())
            else afterSignIn(step)
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            try {
                AutoBackup.setFolder(context, tree)
            } catch (_: SecurityException) {
                AppSnackbar.show(tr("That folder can't be used for backups. Try another one."))
            }
        }
    }
    fun chooseFolder() {
        try {
            pickFolder.launch(null)
        } catch (_: ActivityNotFoundException) {
            AppSnackbar.show(tr("No file picker on this phone"))
        }
    }
    fun restore() {
        if (drive.signedIn) choosingSource = true else onRestoreFile()
    }
    fun whenText(millis: Long) = AndroidDateUtils.formatDateTime(
        context, millis, AndroidDateUtils.FORMAT_SHOW_DATE or AndroidDateUtils.FORMAT_SHOW_TIME or AndroidDateUtils.FORMAT_ABBREV_MONTH
    )

    val anyOn = drive.signedIn || folder.isOn
    val running = drive.running || folder.running
    val error = drive.error ?: folder.error
    val needsSignIn = drive.error == tr("Google needs you to sign in again.")

    // Status
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            when {
                !anyOn -> Icons.Rounded.CloudOff
                error != null -> Icons.Rounded.ErrorOutline
                else -> Icons.Rounded.CloudDone
            },
            null,
            tint = when {
                !anyOn -> P.textTertiary
                error != null -> P.dangerText
                else -> P.successText
            },
            modifier = Modifier.size(20.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    drive.signedIn -> tr("Backed up to Google Drive")
                    folder.isOn -> tr("Backed up to a folder")
                    else -> tr("Only on this phone")
                },
                style = AppType.bodyStrong,
                color = P.textPrimary
            )
            val where = listOfNotNull(drive.email, folder.folderName?.let { if (drive.signedIn) tr("and folder {0}", it) else it }).joinToString(" ")
            if (where.isNotEmpty()) Text(where, style = AppType.caption, color = P.textSecondary)
            val last = maxOf(drive.lastSuccessMillis, folder.lastSuccessMillis)
            Text(
                when {
                    !anyOn -> tr("Sign in and your workouts are saved to your own Google Drive after every workout. On a new phone, sign in and everything comes back.")
                    running -> tr("Backing up…")
                    error != null -> error
                    last > 0L -> tr("Last backup {0}", whenText(last))
                    else -> tr("Backs up after your next workout")
                },
                style = AppType.caption,
                color = if (anyOn && error != null && !running) P.dangerText else P.textSecondary
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    // Actions
    if (!drive.signedIn) {
        PrimaryButton(if (signingIn) tr("Signing in…") else tr("Connect Google Drive"), { signIn() }, Modifier.fillMaxWidth(), enabled = !signingIn, height = 48.dp)
        Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            needsSignIn -> SecondaryButton(tr("Sign in again"), { signIn() }, Modifier.weight(1f))
            anyOn -> SecondaryButton(
                tr("Back up now"),
                {
                    DriveBackup.backUpNow(context)
                    AutoBackup.backUpNow(context)
                },
                Modifier.weight(1f),
                icon = Icons.Rounded.CloudUpload,
                enabled = !running
            )
        }
        SecondaryButton(tr("Restore"), { restore() }, Modifier.weight(1f), icon = Icons.Rounded.SettingsBackupRestore)
    }

    // More options
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.99f) { moreOpen = !moreOpen }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr("More options"), style = AppType.label, color = P.textSecondary, modifier = Modifier.weight(1f))
        val turn by animateFloatAsState(if (moreOpen) 180f else 0f, label = "more_turn")
        Icon(Icons.Rounded.ExpandMore, null, tint = P.textTertiary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
    }
    AnimatedVisibility(moreOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            if (folder.isOn) {
                OptionRow(
                    Icons.Rounded.Folder,
                    (if (drive.signedIn) tr("Also backing up to ") else tr("Backing up to ")) + tr("folder {0}", folder.folderName),
                    (folder.error ?: (if (folder.lastSuccessMillis > 0L) tr("Last {0}", whenText(folder.lastSuccessMillis)) else tr("No backup there yet"))) + tr(" · tap to change")
                ) { chooseFolder() }
                OptionRow(Icons.Rounded.FolderOff, tr("Stop backing up to the folder"), null) { confirmFolderOff = true }
            } else {
                OptionRow(Icons.Rounded.CreateNewFolder, tr("Also back up to a folder"), tr("A copy after every workout, in a folder you pick (another cloud app or the phone)")) { chooseFolder() }
            }
            OptionRow(Icons.Rounded.FileDownload, tr("Save a backup file"), tr("One file with everything, to keep or send anywhere")) { onSaveFile() }
            if (drive.signedIn) OptionRow(Icons.AutoMirrored.Rounded.Logout, tr("Sign out of Google"), null) { confirmSignOut = true }
        }
    }

    if (choosingSource) {
        AppBottomSheet(onDismiss = { choosingSource = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                Text(tr("Restore from…"), style = AppType.title, color = P.textPrimary)
                Spacer(Modifier.height(14.dp))
                ChoiceRow(tr("Google Drive"), tr("Your automatic backups in {0}", drive.email), { choosingSource = false; choosingDrive = true })
                Spacer(Modifier.height(8.dp))
                ChoiceRow(tr("A file"), tr("A backup file you saved, or one from your backup folder"), { choosingSource = false; onRestoreFile() })
            }
        }
    }
    if (choosingDrive) {
        DriveRestoreSheet(
            onPick = { content -> choosingDrive = false; onRestoreContent(content) },
            onDismiss = {
                choosingDrive = false
                DriveBackup.backUpNow(context)
            }
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = P.bgElevated,
            title = { Text(tr("Sign out of Google?"), style = AppType.headline, color = P.textPrimary) },
            text = { Text(tr("Your backups stay in Google Drive, but new workouts won't be backed up there."), style = AppType.body, color = P.textSecondary) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; DriveBackup.signOut(context) }) { Text(tr("Sign out"), color = P.dangerText, style = AppType.label) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
        )
    }
    if (confirmFolderOff) {
        AlertDialog(
            onDismissRequest = { confirmFolderOff = false },
            containerColor = P.bgElevated,
            title = { Text(tr("Stop backing up to the folder?"), style = AppType.headline, color = P.textPrimary) },
            text = { Text(tr("Backups already in the folder stay there, but new workouts won't be copied to it."), style = AppType.body, color = P.textSecondary) },
            confirmButton = { TextButton(onClick = { confirmFolderOff = false; AutoBackup.turnOff(context) }) { Text(tr("Stop"), color = P.dangerText, style = AppType.label) } },
            dismissButton = { TextButton(onClick = { confirmFolderOff = false }) { Text(tr("Keep it"), color = P.textSecondary, style = AppType.label) } }
        )
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.99f, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = P.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.body, color = P.textPrimary)
            if (subtitle != null) Text(subtitle, style = AppType.caption, color = P.textSecondary)
        }
    }
}
