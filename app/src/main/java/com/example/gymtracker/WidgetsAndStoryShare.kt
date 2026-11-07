package com.example.gymtracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.gymtracker.watchlink.WatchExercise
import com.example.gymtracker.watchlink.WatchFinished
import com.example.gymtracker.watchlink.SetKind
import com.example.gymtracker.watchlink.WatchState
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import java.util.Calendar
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================================================================
// SHARED HELPERS
// ============================================================================

private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

/** Set while drawing a see-through widget: text gets a soft shadow to stand off the wallpaper. */
private val clearText: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

private fun textPaint(color: Int, size: Float, typeface: Typeface = MEDIUM, align: Paint.Align = Paint.Align.LEFT) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        this.typeface = typeface
        textAlign = align
        if (clearText.get() == true) {
            // Dark shadow under light text, a light glow under dark text.
            val light = Color.luminance(color) > 0.5f
            setShadowLayer(size * 0.12f, 0f, size * 0.03f, if (light) 0x8C000000.toInt() else 0x99FFFFFF.toInt())
        }
    }

private fun fillPaint(color: Int, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.alpha = alpha
}

private fun strokePaint(color: Int, width: Float, alpha: Int = 255) = fillPaint(color, alpha).apply {
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
}

/** Shrinks the text size so [text] fits in [maxWidth]. */
private fun Paint.fitTo(text: String, maxWidth: Float): Paint = apply {
    val w = measureText(text)
    if (w > maxWidth) textSize *= maxWidth / w
}

/** Cuts [text] with an ellipsis so it fits in [maxWidth]. */
private fun Paint.ellipsize(text: String, maxWidth: Float): String {
    if (measureText(text) <= maxWidth) return text
    var end = text.length
    while (end > 1 && measureText(text, 0, end) + measureText("…") > maxWidth) end--
    return text.substring(0, end).trimEnd() + "…"
}

/** Palette that follows the in-app theme setting (System/Light/Dark). */
private fun appPalette(context: Context): GymPalette = paletteFor(context, LocalStorage.loadSettings(context).themeChoice)

/**
 * A widget's light-mode and dark-mode palettes. Both go into the widget and the phone shows the
 * one that matches its mode, so Auto switches the moment the phone does; it used to pick one when
 * the widget was drawn and stayed light all evening until the app next refreshed it.
 */
internal fun widgetPalettes(context: Context, settings: WidgetSettings): Pair<GymPalette, GymPalette> {
    if (isClear(settings)) return clearPalette(context).let { it to it }
    return when (settings.theme) {
        THEME_LIGHT -> LightPalette to LightPalette
        THEME_DARK -> DarkPalette to DarkPalette
        WIDGET_THEME_WALLPAPER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wallpaperPalette(context, dark = false) to wallpaperPalette(context, dark = true)
        } else LightPalette to DarkPalette
        else -> LightPalette to DarkPalette
    }
}

internal fun isNightMode(context: Context) =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** The one palette for what can't switch by itself: Android before 12, previews. */
private fun widgetPalette(context: Context, settings: WidgetSettings, night: Boolean = isNightMode(context)): GymPalette =
    widgetPalettes(context, settings).let { if (night) it.second else it.first }

/** Below this the background barely shows, so text has to suit the wallpaper instead. */
private const val CLEAR_BELOW_PERCENT = 40

internal fun isClear(settings: WidgetSettings) = settings.opacityPercent < CLEAR_BELOW_PERCENT

/**
 * See-through widgets take dark text over a light wallpaper and light text over a dark one, from
 * what Android reports about the wallpaper; white text over a pale photo was unreadable.
 */
private fun clearPalette(context: Context): GymPalette {
    val darkText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            colors != null && (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        } catch (_: Exception) { false }
    } else false
    return if (darkText) LightPalette else DarkPalette
}

/** Material You: the phone's colours, taken from the wallpaper (the tones Android's own widgets use). */
@RequiresApi(Build.VERSION_CODES.S)
private fun wallpaperPalette(context: Context, dark: Boolean): GymPalette {
    fun c(id: Int) = androidx.compose.ui.graphics.Color(context.getColor(id))
    val base = if (dark) DarkPalette else LightPalette
    return if (dark) base.copy(
        bgPrimary = c(android.R.color.system_neutral1_900),
        bgCard = c(android.R.color.system_neutral1_900),
        bgElevated = c(android.R.color.system_neutral1_800),
        bgControl = c(android.R.color.system_neutral2_800),
        textPrimary = c(android.R.color.system_neutral1_50),
        textSecondary = c(android.R.color.system_neutral2_200),
        textTertiary = c(android.R.color.system_neutral2_400),
        borderSubtle = c(android.R.color.system_neutral2_800),
        borderStrong = c(android.R.color.system_neutral2_600),
        accentPrimary = c(android.R.color.system_accent1_200),
        onAccentPrimary = c(android.R.color.system_accent1_800),
        accentSoft = c(android.R.color.system_accent2_800),
        accentSecondary = c(android.R.color.system_accent3_200),
        accentSecondarySoft = c(android.R.color.system_accent3_800)
    ) else base.copy(
        bgPrimary = c(android.R.color.system_neutral1_50),
        bgCard = c(android.R.color.system_neutral1_10),
        bgElevated = c(android.R.color.system_neutral1_50),
        bgControl = c(android.R.color.system_neutral2_100),
        textPrimary = c(android.R.color.system_neutral1_900),
        textSecondary = c(android.R.color.system_neutral2_700),
        textTertiary = c(android.R.color.system_neutral2_500),
        borderSubtle = c(android.R.color.system_neutral2_100),
        borderStrong = c(android.R.color.system_neutral2_300),
        accentPrimary = c(android.R.color.system_accent1_600),
        onAccentPrimary = c(android.R.color.system_accent1_0),
        accentSoft = c(android.R.color.system_accent2_100),
        accentSecondary = c(android.R.color.system_accent3_600),
        accentSecondarySoft = c(android.R.color.system_accent3_100)
    )
}

private fun widgetBackground(p: GymPalette, settings: WidgetSettings): Int =
    (if (settings.style == WIDGET_STYLE_TINTED) p.accentSoft else p.bgCard).toArgb()

private fun alpha255(percent: Int) = (percent.coerceIn(0, 100) / 100f * 255f).roundToInt()

/** Kind of widget placed under [appWidgetId], from the provider class that owns it. */
fun widgetKindFor(context: Context, appWidgetId: Int): String =
    when (AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className) {
        MuscleFatigueWidgetProvider::class.java.name -> WIDGET_KIND_RECOVERY
        SquarePulseWidgetProvider::class.java.name -> WIDGET_KIND_WEEKLY
        else -> WIDGET_KIND_WORKOUT
    }

// ============================================================================
// 1. LIVE SESSION (shared by the workout widget and the lock-screen card)
// ============================================================================

/**
 * The set the widget is showing. The two +/- boxes show [primary] and [secondary], which depend
 * on the exercise type (weight and reps, reps only, time only, distance and time…).
 */
private class NextSetInfo(
    val exerciseName: String,
    val label: String,
    val summary: String,
    val primary: String,
    val secondary: String?,
    val setNumber: Int,
    val setCount: Int,
    val kind: SetKind,
    val workNumber: Int,
    val workCount: Int
)

private class LiveSession(
    val splitName: String,
    /** The workout's exercises without the skipped ones. */
    val exercises: List<ExerciseEntry>,
    val currentExercise: ExerciseEntry?,
    val totalSetsLeft: Int,
    val restRemainingMillis: Long
) {
    val isResting: Boolean get() = restRemainingMillis > 1000L

    val nextSet: NextSetInfo? get() {
        val ex = currentExercise ?: return null
        val idx = ex.nextSetIndex()
        if (idx == -1) return null
        val set = ex.sets[idx]
        val workNum = ex.sets.take(idx + 1).count { !it.isWarmupSet }
        return NextSetInfo(
            exerciseName = ex.name,
            label = when {
                set.isWarmupSet -> tr("Warm-up")
                set.intensityType == "DROP" -> tr("Drop set")
                else -> tr("Set {0}", workNum)
            },
            summary = formatSet(set, ex.type),
            primary = when (ex.type) {
                ExerciseType.WEIGHT_REPS -> formatWeightKg(parseWeightClean(set.weight, 20f))
                ExerciseType.WEIGHTED_BODYWEIGHT -> "+${formatWeightKg(set.weightKg)}"
                ExerciseType.ASSISTED -> "−${formatWeightKg(set.weightKg)}"
                ExerciseType.BODYWEIGHT -> tr("{0} reps", parseRepsClean(set.reps, 8))
                ExerciseType.DURATION -> formatClock(set.secondCount.toLong())
                ExerciseType.DISTANCE_TIME -> formatDistance(set.distanceMeters)
            },
            secondary = when (ex.type) {
                ExerciseType.BODYWEIGHT, ExerciseType.DURATION -> null
                ExerciseType.DISTANCE_TIME -> formatClock(set.secondCount.toLong())
                else -> "${parseRepsClean(set.reps, 8)}"
            },
            setNumber = idx + 1,
            setCount = ex.sets.size,
            kind = when {
                set.isWarmupSet -> SetKind.WARMUP
                set.intensityType == "DROP" -> SetKind.DROP
                else -> SetKind.WORK
            },
            workNumber = workNum,
            workCount = ex.sets.count { !it.isWarmupSet }
        )
    }
}

/** The live workout for the watch, formatted the same way as the widget. */
fun watchStateOf(context: Context): WatchState {
    val live = readLiveSession(context) ?: return idleWatchState(context)
    val next = live.nextSet
    val type = live.currentExercise?.type
    val restEnd = LocalStorage.getRestEndTimeMillis(context).takeIf { live.isResting } ?: 0L
    val restStart = LocalStorage.getRestStartedMillis(context)
    return WatchState(
        active = true,
        workoutName = shown(live.splitName),
        exerciseName = next?.exerciseName?.let(::shown),
        setLabel = next?.label.orEmpty(),
        primary = next?.primary.orEmpty(),
        primaryLabel = when (type) {
            ExerciseType.BODYWEIGHT -> tr("reps")
            ExerciseType.DURATION -> tr("time")
            ExerciseType.DISTANCE_TIME -> tr("distance")
            ExerciseType.WEIGHTED_BODYWEIGHT -> tr("added")
            ExerciseType.ASSISTED -> tr("assist")
            else -> tr("weight")
        },
        secondary = next?.secondary,
        secondaryLabel = next?.secondary?.let { if (type == ExerciseType.DISTANCE_TIME) tr("time") else tr("reps") },
        summary = next?.summary.orEmpty(),
        setsLeft = live.totalSetsLeft,
        restEndsAtMillis = restEnd,
        restTotalMillis = if (restEnd > 0L && restStart in 1 until restEnd) restEnd - restStart else 0L,
        startedAtMillis = LocalStorage.getActiveSessionStartTime(context),
        exercises = live.exercises.map { ex -> WatchExercise(shown(ex.name), ex.sets.count { it.isCompleted }, ex.sets.size) },
        currentExercise = live.exercises.indexOf(live.currentExercise),
        setNumber = next?.setNumber ?: 0,
        setCount = next?.setCount ?: 0,
        canUndo = live.exercises.any { ex -> ex.sets.any { it.isCompleted } },
        language = watchLanguage(),
        setKind = next?.kind ?: SetKind.WORK,
        workSetNumber = next?.workNumber ?: 0,
        workSetCount = next?.workCount ?: 0,
        addSetTo = live.exercises.getOrNull(WatchSync.addSetTarget(live.exercises))?.let { shown(it.name) }
    )
}

/** The watch speaks the phone app's language. */
private fun watchLanguage() = if (hebrewOn()) "he" else "en"

/** Shown on the watch for this long after a workout is saved. */
private const val WATCH_SAVED_WINDOW_MS = 60 * 60_000L

/** No workout: the days the watch can start (up next first) and the workout saved last, if recent. */
private fun idleWatchState(context: Context): WatchState {
    val programId = LocalStorage.loadUserProfile(context).splitProgram
    val history = LocalStorage.loadHistory(context)
    val titles = programFor(programId).splits.map { it.title }
    val upNext = computeWeekSummary(history, 0, programId).recommendedSplit
    val last = history.firstOrNull()?.takeIf { System.currentTimeMillis() - it.id in 0 until WATCH_SAVED_WINDOW_MS }
    val days = titles.filter { it == upNext } + titles.filter { it != upNext }
    return WatchState(
        active = false,
        days = days,
        dayLabels = days.map(::shown),
        language = watchLanguage(),
        lastWorkout = last?.let { w ->
            WatchFinished(
                workoutName = shown(w.splitTitle),
                finishedAtMillis = w.id,
                durationSeconds = w.durationSeconds,
                sets = computeWorkoutStats(w.exercises, emptyList()).workSets,
                volume = w.totalVolumeKg.takeIf { it > 0 }?.let(::formatVolume),
                newBests = w.prsHit
            )
        }
    )
}

private fun readLiveSession(context: Context): LiveSession? {
    val (splitName, exercises) = LocalStorage.loadActiveSession(context) ?: return null
    val active = exercises.filter { !it.isSkipped }
    return LiveSession(
        splitName = splitName,
        exercises = active,
        currentExercise = active.firstOrNull { ex -> ex.sets.any { !it.isCompleted } },
        totalSetsLeft = active.sumOf { ex -> ex.sets.count { !it.isCompleted } },
        restRemainingMillis = LocalStorage.getRestEndTimeMillis(context) - System.currentTimeMillis()
    )
}

private fun widgetActionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
    val intent = Intent(context, GymWidgetProvider::class.java).apply {
        this.action = action
        setPackage(context.packageName)
    }
    return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/**
 * Builds the workout widget / lock-screen card. Used by the provider, the lock-screen
 * notification and the live preview in [WidgetConfigActivity].
 */
fun buildWorkoutWidgetViews(
    context: Context,
    settings: WidgetSettings,
    compact: Boolean,
    requestCodeBase: Int = 10,
    forLockScreen: Boolean = false
): RemoteViews {
    val p = widgetPalette(context, settings)
    val (day, night) = widgetPalettes(context, settings)
    val views = RemoteViews(context.packageName, R.layout.gym_widget)
    val tinted = settings.style == WIDGET_STYLE_TINTED
    /** Sets a colour that follows the phone's light / dark mode on its own (Android 12+). */
    fun color(viewId: Int, method: String, pick: (GymPalette) -> androidx.compose.ui.graphics.Color) {
        val d = pick(day).toArgb()
        val n = pick(night).toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d != n) views.setColorInt(viewId, method, d, n)
        else views.setInt(viewId, method, pick(p).toArgb())
    }
    fun tint(viewId: Int, pick: (GymPalette) -> androidx.compose.ui.graphics.Color) = color(viewId, "setColorFilter", pick)
    fun text(viewId: Int, pick: (GymPalette) -> androidx.compose.ui.graphics.Color) = color(viewId, "setTextColor", pick)

    tint(R.id.widget_bg_image) { if (tinted) it.accentSoft else it.bgCard }
    views.setInt(R.id.widget_bg_image, "setImageAlpha", if (forLockScreen) 255 else alpha255(settings.opacityPercent))
    tint(R.id.widget_tile_bg) { if (tinted) it.bgCard else it.accentSoft }
    tint(R.id.widget_tile_icon) { it.accentPrimary }
    listOf(R.id.widget_weight_pill_bg, R.id.widget_reps_pill_bg).forEach { id -> tint(id) { if (tinted) it.bgCard else it.bgControl } }
    tint(R.id.widget_done_pill_bg) { it.accentPrimary }
    tint(R.id.widget_done_icon) { it.onAccentPrimary }
    tint(R.id.widget_action_bg) { it.accentPrimary }
    tint(R.id.widget_action_icon) { it.onAccentPrimary }
    text(R.id.widget_action_text) { it.onAccentPrimary }
    tint(R.id.widget_btn_weight_minus) { it.textSecondary }
    tint(R.id.widget_btn_reps_minus) { it.textSecondary }
    tint(R.id.widget_btn_weight_plus) { it.accentPrimary }
    tint(R.id.widget_btn_reps_plus) { it.accentPrimary }
    text(R.id.widget_split_title) { it.textPrimary }
    text(R.id.widget_subtitle) { it.textSecondary }
    text(R.id.widget_header_value) { it.textSecondary }
    text(R.id.widget_weight_value) { it.textPrimary }
    text(R.id.widget_reps_value) { it.textPrimary }
    text(R.id.widget_rest_chronometer) { it.accentSecondary }
    // A narrow widget has room for a two-line title but not for the +/- boxes.
    views.setInt(R.id.widget_split_title, "setMaxLines", if (compact) 2 else 1)

    val showControls = (WIDGET_OPTION_CONTROLS in settings.options || forLockScreen) && !compact
    val live = readLiveSession(context)
    val openApp = openAppPendingIntent(context, 0)

    fun status(label: String, pick: (GymPalette) -> androidx.compose.ui.graphics.Color) {
        views.setTextViewText(R.id.widget_status_label, label)
        text(R.id.widget_status_label, pick)
    }
    fun subtitle(text: String?) {
        views.setTextViewText(R.id.widget_subtitle, text.orEmpty())
        views.setViewVisibility(R.id.widget_subtitle, if (text.isNullOrBlank()) View.GONE else View.VISIBLE)
    }
    fun headerValue(text: String?) {
        views.setTextViewText(R.id.widget_header_value, text.orEmpty())
        views.setViewVisibility(R.id.widget_header_value, if (text.isNullOrBlank()) View.GONE else View.VISIBLE)
    }
    /** The one button at the bottom; a null [text] hides it. */
    fun action(text: String?, icon: Int = R.drawable.ic_widget_play, onTap: PendingIntent = openApp) {
        views.setViewVisibility(R.id.widget_action_button, if (text == null) View.GONE else View.VISIBLE)
        if (text == null) return
        views.setTextViewText(R.id.widget_action_text, text)
        views.setImageViewResource(R.id.widget_action_icon, icon)
        views.setOnClickPendingIntent(R.id.widget_action_button, onTap)
    }
    views.setViewVisibility(R.id.widget_rest_chronometer, View.GONE)
    views.setViewVisibility(R.id.widget_controls_row, View.GONE)

    if (live != null) {
        val next = live.nextSet
        if (next != null) {
            status(
                when {
                    live.isResting -> tr("Resting")
                    compact -> next.label
                    else -> "${shown(live.splitName)} · ${next.label}"
                },
                if (live.isResting) { pal -> pal.accentSecondary } else { pal -> pal.accentPrimary }
            )
            views.setTextViewText(R.id.widget_split_title, shown(next.exerciseName))
            if (live.isResting) {
                headerValue(null)
                views.setViewVisibility(R.id.widget_rest_chronometer, View.VISIBLE)
                views.setChronometerCountDown(R.id.widget_rest_chronometer, true)
                views.setChronometer(R.id.widget_rest_chronometer, SystemClock.elapsedRealtime() + live.restRemainingMillis, "%s", true)
                subtitle(tr("Next: {0}", next.summary))
                action(null)
            } else if (showControls) {
                headerValue(tr("{0} left", live.totalSetsLeft))
                subtitle(null)
                action(null)
                views.setViewVisibility(R.id.widget_controls_row, View.VISIBLE)
                views.setTextViewText(R.id.widget_weight_value, next.primary)
                views.setViewVisibility(R.id.widget_reps_group, if (next.secondary != null) View.VISIBLE else View.GONE)
                next.secondary?.let { views.setTextViewText(R.id.widget_reps_value, it) }
                listOf(
                    R.id.widget_btn_weight_minus to GymWidgetProvider.ACTION_WEIGHT_MINUS,
                    R.id.widget_btn_weight_plus to GymWidgetProvider.ACTION_WEIGHT_PLUS,
                    R.id.widget_btn_reps_minus to GymWidgetProvider.ACTION_REPS_MINUS,
                    R.id.widget_btn_reps_plus to GymWidgetProvider.ACTION_REPS_PLUS,
                    R.id.widget_btn_complete_set to GymWidgetProvider.ACTION_COMPLETE_SET
                ).forEachIndexed { i, (viewId, action) ->
                    views.setOnClickPendingIntent(viewId, widgetActionPendingIntent(context, action, requestCodeBase + i))
                }
            } else {
                // No room (or no wish) for the +/- boxes: the set as text and one big Done.
                headerValue(if (compact) null else tr("{0} left", live.totalSetsLeft))
                subtitle(next.summary)
                action(tr("Done"), R.drawable.ic_widget_check, widgetActionPendingIntent(context, GymWidgetProvider.ACTION_COMPLETE_SET, requestCodeBase + 4))
            }
        } else {
            status(tr("{0} · done", shown(live.splitName))) { it.successText }
            headerValue(null)
            views.setTextViewText(R.id.widget_split_title, tr("All sets done"))
            subtitle(tr("Open the app to save your workout"))
            action(tr("Open"))
        }
    } else {
        val profile = LocalStorage.loadUserProfile(context)
        val week = computeWeekSummary(LocalStorage.loadHistory(context), 0, profile.splitProgram)
        val split = programFor(profile.splitProgram).splits.find { it.title == week.recommendedSplit }
        status(tr("Up next")) { it.accentPrimary }
        headerValue(if (WIDGET_OPTION_WEEKLY in settings.options) "${week.completedCoreSplits.size}/${week.weeklyGoal}" else null)
        views.setTextViewText(R.id.widget_split_title, shown(week.recommendedSplit))
        subtitle(split?.let { daySubtitle(it, profile) })
        action(tr("Start workout"))
    }

    views.setOnClickPendingIntent(R.id.widget_header_click_area, openApp)
    if (live == null) views.setOnClickPendingIntent(R.id.widget_root, openApp)
    return views
}

private const val WIDGET_OPTION_CONTROLS = "controls"
private const val WIDGET_OPTION_WEEKLY = "weekly"

// ============================================================================
// 2. LOCK-SCREEN LIVE WORKOUT NOTIFICATION
// ============================================================================

object LockScreenWorkoutController {
    private const val LIVE_WORKOUT_CHANNEL_ID = "gym_live_lockscreen_channel"
    private const val LIVE_NOTIFICATION_ID = 2002
    private var channelCreated = false

    private fun ensureChannel(manager: NotificationManager) {
        if (channelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(LIVE_WORKOUT_CHANNEL_ID, tr("Workout in progress"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tr("Shows your current set on the lock screen while you train")
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        channelCreated = true
    }

    fun sync(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            val live = readLiveSession(context)
            if (live == null) {
                manager.cancel(LIVE_NOTIFICATION_ID)
                return
            }
            ensureChannel(manager)

            val settings = defaultWidgetSettings(WIDGET_KIND_WORKOUT)
            val expanded = buildWorkoutWidgetViews(context, settings, compact = false, requestCodeBase = 20, forLockScreen = true)
            val builder = notificationBuilder(context, LIVE_WORKOUT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_workout)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_WORKOUT)
                .setContentIntent(openAppPendingIntent(context, 0))
                .setCustomContentView(buildCompactWorkoutNotificationViews(context, live))
                .setCustomBigContentView(expanded)
                .setCustomHeadsUpContentView(expanded)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            manager.notify(LIVE_NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }
}

/**
 * The collapsed notification: Android 12+ gives it ~48dp, which cut the full widget card in half.
 * Two lines (exercise and set; the numbers or the rest countdown) and a "Set done" button.
 */
private fun buildCompactWorkoutNotificationViews(context: Context, live: LiveSession): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.notification_workout_compact)
    val accent = appPalette(context).accentPrimary.toArgb()
    val next = live.nextSet
    if (next == null) {
        views.setTextViewText(R.id.notif_title, tr("All sets done"))
        views.setTextViewText(R.id.notif_text, tr("Open the app to save {0}", shown(live.splitName)))
        views.setViewVisibility(R.id.notif_done, View.GONE)
        views.setViewVisibility(R.id.notif_rest, View.GONE)
        return views
    }
    views.setTextViewText(R.id.notif_title, "${shown(next.exerciseName)} · ${next.label}")
    if (live.isResting) {
        views.setViewVisibility(R.id.notif_rest, View.VISIBLE)
        views.setChronometerCountDown(R.id.notif_rest, true)
        views.setChronometer(R.id.notif_rest, SystemClock.elapsedRealtime() + live.restRemainingMillis, "%s", true)
        views.setTextColor(R.id.notif_rest, accent)
        views.setTextViewText(R.id.notif_text, tr("rest · next {0}", next.summary))
        views.setViewVisibility(R.id.notif_done, View.GONE)
    } else {
        views.setViewVisibility(R.id.notif_rest, View.GONE)
        val numbers = listOfNotNull(next.primary, next.secondary?.let { if (it.all(Char::isDigit)) tr("{0} reps", it) else it }).joinToString(" × ")
        views.setTextViewText(R.id.notif_text, tr("{0} · {1} sets left", numbers, live.totalSetsLeft))
        views.setViewVisibility(R.id.notif_done, View.VISIBLE)
        views.setInt(R.id.notif_done_bg, "setColorFilter", accent)
        views.setInt(R.id.notif_done_icon, "setColorFilter", appPalette(context).onAccentPrimary.toArgb())
        views.setOnClickPendingIntent(R.id.notif_done, widgetActionPendingIntent(context, GymWidgetProvider.ACTION_COMPLETE_SET, 40))
    }
    return views
}

// ============================================================================
// 3. WIDGET #1: INTERACTIVE WORKOUT WIDGET
// ============================================================================

class GymWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action in SET_ACTIONS) {
            applySetAction(context.applicationContext, action)
            LocalStorage.flush(context)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidgetUi(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidgetUi(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = LocalStorage.deleteWidgetSettings(context, appWidgetIds)

    companion object {
        const val ACTION_WEIGHT_MINUS = "com.example.gymtracker.ACTION_WEIGHT_MINUS"
        const val ACTION_WEIGHT_PLUS = "com.example.gymtracker.ACTION_WEIGHT_PLUS"
        const val ACTION_REPS_MINUS = "com.example.gymtracker.ACTION_REPS_MINUS"
        const val ACTION_REPS_PLUS = "com.example.gymtracker.ACTION_REPS_PLUS"
        const val ACTION_COMPLETE_SET = "com.example.gymtracker.ACTION_COMPLETE_SET"
        private val SET_ACTIONS = setOf(ACTION_WEIGHT_MINUS, ACTION_WEIGHT_PLUS, ACTION_REPS_MINUS, ACTION_REPS_PLUS, ACTION_COMPLETE_SET)

        /** One tap on the widget, lock-screen card or watch, applied to the next unfinished set. */
        fun applySetAction(context: Context, action: String) {
            // Set controls are hidden while resting; ignore stale taps (the caller's flush redraws).
            if (LocalStorage.getRestEndTimeMillis(context) - System.currentTimeMillis() > 1000L) return

            val (splitTitle, sessionExercises) = LocalStorage.loadActiveSession(context) ?: return
            val exercises = sessionExercises.toMutableList()
            val exIdx = exercises.indexOfFirst { ex -> !ex.isSkipped && ex.sets.any { !it.isCompleted } }
            if (exIdx == -1) return
            val currentEx = exercises[exIdx]
            val setIdx = currentEx.nextSetIndex()
            val target = currentEx.sets[setIdx]
            val settings = LocalStorage.loadSettings(context)
            val curReps = parseRepsClean(target.reps, 8)
            val type = currentEx.type

            fun reps(delta: Int) = target.copy(reps = "${(curReps + delta).coerceAtLeast(0)}")
            fun time(delta: Int) = target.copy(seconds = "${(target.secondCount + delta).coerceAtLeast(0)}")
            fun distance(deltaUnits: Float) = target.copy(distanceM = formatKgClean(
                (target.distanceMeters + deltaUnits * Units.current.metersPerDistanceUnit).coerceAtLeast(0f)
            ))

            // The first box is weight, reps (bodyweight), time (holds) or distance; the second is reps or time.
            val updated = when (action) {
                ACTION_WEIGHT_MINUS, ACTION_WEIGHT_PLUS -> {
                    val sign = if (action == ACTION_WEIGHT_PLUS) 1 else -1
                    when (type) {
                        ExerciseType.BODYWEIGHT -> reps(sign)
                        ExerciseType.DURATION -> time(15 * sign)
                        ExerciseType.DISTANCE_TIME -> distance(0.1f * sign)
                        else -> target.copy(weight = stepWeight(target.weight, settings.weightStep * sign))
                    }
                }
                ACTION_REPS_MINUS, ACTION_REPS_PLUS -> {
                    val sign = if (action == ACTION_REPS_PLUS) 1 else -1
                    if (type == ExerciseType.DISTANCE_TIME) time(60 * sign) else reps(sign)
                }
                ACTION_COMPLETE_SET -> target.copy(isCompleted = true)
                else -> return
            }
            exercises[exIdx] = currentEx.copy(sets = currentEx.sets.toMutableList().also { it[setIdx] = updated })
            LocalStorage.saveActiveSession(context, splitTitle, exercises, fromWidget = true)

            if (action == ACTION_COMPLETE_SET) {
                val restSec = target.restSeconds.takeIf { it >= 0 } ?: settings.restTimerSeconds
                val restEnd = if (restSec > 0) System.currentTimeMillis() + restSec * 1000L else 0L
                LocalStorage.setRestEndTimeMillis(context, restEnd, fromWidget = true)
            }
        }

        fun refreshAllWidgets(context: Context) {
            try {
                val appCtx = context.applicationContext
                val manager = AppWidgetManager.getInstance(appCtx)
                for (id in manager.getAppWidgetIds(ComponentName(appCtx, GymWidgetProvider::class.java))) {
                    updateWidgetUi(appCtx, manager, id)
                }
            } catch (_: Exception) {}
        }

        fun updateWidgetUi(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val compact = manager.getAppWidgetOptions(widgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 210
            val settings = LocalStorage.loadWidgetSettings(context, widgetId, WIDGET_KIND_WORKOUT)
            manager.updateAppWidget(widgetId, buildWorkoutWidgetViews(context, settings, compact))
        }
    }
}

// ============================================================================
// 4. CANVAS WIDGETS (#2 RECOVERY, #3 WEEKLY) — drawn at the widget's real size
// ============================================================================

/** Size of a placed widget in dp (portrait), falling back to [defaultW]×[defaultH]. */
private fun widgetSizeDp(manager: AppWidgetManager, widgetId: Int, defaultW: Int, defaultH: Int): Pair<Int, Int> {
    val o = manager.getAppWidgetOptions(widgetId)
    val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: defaultW
    val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: defaultH
    return w to h
}

/** Pixels per dp for a widget bitmap, capped so the bitmap stays within RemoteViews' memory budget. */
private fun bitmapScale(context: Context, wDp: Int, hDp: Int): Float {
    val density = context.resources.displayMetrics.density
    // Two drawings (light and dark) share RemoteViews' bitmap budget (about 1.5 screens).
    val maxScale = sqrt(1_200_000f / (wDp * hDp).coerceAtLeast(1))
    return min(density, maxScale)
}

private fun refreshCanvasWidgets(
    context: Context,
    provider: Class<*>,
    update: (Context, AppWidgetManager, Int) -> Unit
) {
    try {
        val appCtx = context.applicationContext
        val manager = AppWidgetManager.getInstance(appCtx)
        manager.getAppWidgetIds(ComponentName(appCtx, provider)).forEach { update(appCtx, manager, it) }
    } catch (_: Exception) {}
}

/** One drawing when light and dark look the same, else both, for the launcher to pick from. */
private fun pushCanvasWidget(
    context: Context,
    manager: AppWidgetManager,
    widgetId: Int,
    settings: WidgetSettings,
    requestCode: Int,
    render: (night: Boolean) -> Bitmap
) {
    val (day, night) = widgetPalettes(context, settings)
    val views = if (day == night) {
        RemoteViews(context.packageName, R.layout.gym_canvas_widget).apply {
            setImageViewBitmap(R.id.canvas_widget_image, render(isNightMode(context)))
        }
    } else {
        RemoteViews(context.packageName, R.layout.gym_canvas_widget_daynight).apply {
            setImageViewBitmap(R.id.canvas_widget_image, render(false))
            setImageViewBitmap(R.id.canvas_widget_image_night, render(true))
        }
    }
    views.setOnClickPendingIntent(R.id.canvas_widget_root, openAppPendingIntent(context, requestCode))
    manager.updateAppWidget(widgetId, views)
}

/** Draws the rounded card background shared by both canvas widgets. Returns the palette used. */
private fun drawWidgetCard(canvas: Canvas, context: Context, settings: WidgetSettings, w: Float, h: Float, s: Float, night: Boolean): GymPalette {
    val p = widgetPalette(context, settings, night)
    clearText.set(isClear(settings))
    val rect = RectF(0f, 0f, w, h)
    canvas.drawRoundRect(rect, 24 * s, 24 * s, fillPaint(widgetBackground(p, settings), alpha255(settings.opacityPercent)))
    if (settings.opacityPercent >= 30) {
        canvas.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), 24 * s, 24 * s, strokePaint(p.borderSubtle.toArgb(), 1 * s))
    }
    return p
}

/** Left-to-right positions turned around for Hebrew, so the canvas widgets read right to left too. */
private class Sides(val w: Float) {
    val rtl = hebrewOn()
    fun x(fromStart: Float) = if (rtl) w - fromStart else fromStart
    fun start(paint: Paint) = paint.apply { textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT }
    fun end(paint: Paint) = paint.apply { textAlign = if (rtl) Paint.Align.LEFT else Paint.Align.RIGHT }
}

/**
 * The header every widget shares (same as the workout widget's): an icon on a small accent tile,
 * a label in the accent, and an optional value at the far end. Returns where the body starts.
 */
private fun drawWidgetHeader(canvas: Canvas, context: Context, p: GymPalette, settings: WidgetSettings, sides: Sides, pad: Float, s: Float, label: String, value: String?): Float {
    val tile = 20 * s
    val tileStart = sides.x(pad)
    val left = if (sides.rtl) tileStart - tile else tileStart
    val tinted = settings.style == WIDGET_STYLE_TINTED
    canvas.drawRoundRect(RectF(left, pad, left + tile, pad + tile), 6 * s, 6 * s, fillPaint((if (tinted) p.bgCard else p.accentSoft).toArgb()))
    ContextCompat.getDrawable(context, R.drawable.ic_stat_workout)?.mutate()?.let { icon ->
        val inset = (4 * s).roundToInt()
        icon.setBounds(left.roundToInt() + inset, pad.roundToInt() + inset, (left + tile).roundToInt() - inset, (pad + tile).roundToInt() - inset)
        icon.setTint(p.accentPrimary.toArgb())
        icon.draw(canvas)
    }
    val baseline = pad + tile / 2 + 4.3f * s
    val valueWidth = value?.let { textPaint(0, 12 * s).measureText(it) + 10 * s } ?: 0f
    val labelPaint = sides.start(textPaint(p.accentPrimary.toArgb(), 12 * s))
    canvas.drawText(labelPaint.ellipsize(label, sides.w - pad * 2 - tile - 8 * s - valueWidth), sides.x(pad + tile + 8 * s), baseline, labelPaint)
    if (value != null) {
        canvas.drawText(value, sides.x(sides.w - pad), baseline, sides.end(textPaint(p.textSecondary.toArgb(), 12 * s)))
    }
    return pad + tile
}

class MuscleFatigueWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        update(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = LocalStorage.deleteWidgetSettings(context, appWidgetIds)

    companion object {
        fun refreshAll(context: Context) = refreshCanvasWidgets(context, MuscleFatigueWidgetProvider::class.java, ::update)

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val (wDp, hDp) = widgetSizeDp(manager, widgetId, 300, 140)
            val settings = LocalStorage.loadWidgetSettings(context, widgetId, WIDGET_KIND_RECOVERY)
            pushCanvasWidget(context, manager, widgetId, settings, 301) { night -> render(context, settings, wDp, hDp, night) }
        }

        /**
         * The least rested muscles first, as rows of name, percent and a bar, in as many columns
         * and rows as the widget's size allows.
         */
        fun render(context: Context, settings: WidgetSettings, wDp: Int, hDp: Int, night: Boolean = isNightMode(context)): Bitmap {
            val s = bitmapScale(context, wDp, hDp)
            val w = wDp * s
            val h = hDp * s
            val bmp = Bitmap.createBitmap(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val p = drawWidgetCard(canvas, context, settings, w, h, s, night)
            val sides = Sides(w)
            val pad = 16 * s

            val all = computeMuscleRecoveryStatus(LocalStorage.loadHistory(context))
            val ready = all.count { it.status == RecoveryStatus.READY }
            val bodyTop = drawWidgetHeader(
                canvas, context, p, settings, sides, pad, s, tr("Recovery"),
                when {
                    ready == all.size -> tr("All rested")
                    wDp < 230 -> "$ready/${all.size}"
                    else -> tr("{0} of {1} rested", ready, all.size)
                }
            ) + 12 * s

            val showSets = "sets" in settings.options
            val showHours = "hours" in settings.options
            val cols = when {
                wDp >= 380 -> 3
                wDp >= 230 -> 2
                else -> 1
            }
            val gap = 20 * s
            val cellW = (w - pad * 2 - gap * (cols - 1)) / cols
            val cellH = (if (showSets || showHours) 40 else 29) * s
            val rows = ((h - bodyTop - pad + 6 * s) / cellH).toInt().coerceAtLeast(1)
            val items = all.sortedBy { it.recoveryPercent }.take(rows * cols)

            items.forEachIndexed { i, item ->
                val start = pad + (i % cols) * (cellW + gap)
                val top = bodyTop + (i / cols) * cellH
                val color = when (item.status) {
                    RecoveryStatus.TIRED -> p.dangerText
                    RecoveryStatus.RECOVERING -> p.warningText
                    RecoveryStatus.READY -> p.successText
                }.toArgb()
                val baseline = top + 11 * s
                val pct = "${item.recoveryPercent}%"
                val pctPaint = sides.end(textPaint(color, 12 * s, BOLD))
                canvas.drawText(pct, sides.x(start + cellW), baseline, pctPaint)
                val namePaint = sides.start(textPaint(p.textPrimary.toArgb(), 12 * s))
                canvas.drawText(namePaint.ellipsize(recoveryLabel(item.muscleGroup), cellW - pctPaint.measureText(pct) - 6 * s), sides.x(start), baseline, namePaint)

                val barTop = baseline + 6 * s
                val barH = 5 * s
                val track = RectF(sides.x(start).let { if (sides.rtl) it - cellW else it }, barTop, 0f, barTop + barH).apply { right = left + cellW }
                canvas.drawRoundRect(track, barH / 2, barH / 2, fillPaint(p.bgControl.toArgb()))
                val filled = (cellW * item.recoveryPercent / 100f).coerceAtLeast(barH)
                val fill = if (sides.rtl) RectF(track.right - filled, track.top, track.right, track.bottom) else RectF(track.left, track.top, track.left + filled, track.bottom)
                canvas.drawRoundRect(fill, barH / 2, barH / 2, fillPaint(color))

                if (showSets || showHours) {
                    // "0 sets · 368d ago" read as noise: no count when nothing was done this week,
                    // and anything past two weeks is just "a while ago".
                    val details = listOfNotNull(
                        if (showSets && item.weeklyWorkingSets > 0) tr("{0} sets", item.weeklyWorkingSets) else null,
                        if (showHours) item.hoursSinceTrained?.let {
                            when {
                                it < 1 -> tr("Just now")
                                it < 48 -> tr("{0}h ago", it)
                                it < 24 * 14 -> tr("{0}d ago", it / 24)
                                else -> tr("A while ago")
                            }
                        } ?: tr("Not yet") else null
                    ).joinToString(" · ")
                    val detailPaint = sides.start(textPaint(p.textTertiary.toArgb(), 10.5f * s, REGULAR))
                    canvas.drawText(detailPaint.ellipsize(details, cellW), sides.x(start), barTop + barH + 13 * s, detailPaint)
                }
            }
            return bmp
        }
    }
}

class SquarePulseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        update(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = LocalStorage.deleteWidgetSettings(context, appWidgetIds)

    companion object {
        fun refreshAll(context: Context) = refreshCanvasWidgets(context, SquarePulseWidgetProvider::class.java, ::update)

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val (wDp, hDp) = widgetSizeDp(manager, widgetId, 160, 160)
            val settings = LocalStorage.loadWidgetSettings(context, widgetId, WIDGET_KIND_WEEKLY)
            pushCanvasWidget(context, manager, widgetId, settings, 302) { night -> render(context, settings, wDp, hDp, night) }
        }

        /**
         * This week's workouts against the goal as a ring, the days trained as a strip (as on
         * Today), and what's next. Square sizes stack them; wide sizes put the ring beside the rest.
         */
        fun render(context: Context, settings: WidgetSettings, wDp: Int, hDp: Int, night: Boolean = isNightMode(context)): Bitmap {
            val s = bitmapScale(context, wDp, hDp)
            val w = wDp * s
            val h = hDp * s
            val bmp = Bitmap.createBitmap(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val p = drawWidgetCard(canvas, context, settings, w, h, s, night)
            val sides = Sides(w)
            val pad = 16 * s

            val profile = LocalStorage.loadUserProfile(context)
            val week = computeWeekSummary(LocalStorage.loadHistory(context), 0, profile.splitProgram)
            val done = week.completedCoreSplits.size
            val goal = week.weeklyGoal.coerceAtLeast(1)
            // Nothing lifted with weight yet (or bodyweight only): no "0 kg" in the corner.
            val volume = if ("volume" in settings.options) week.workouts.sumOf { computeWorkoutStats(it.exercises, emptyList()).volumeKg }.takeIf { it > 0 }?.let(::formatVolume) else null
            val next = if ("next" in settings.options && done < goal) tr("Next: {0}", shown(week.recommendedSplit)) else null
            // A narrow widget has no room for the volume next to the label; it goes under the ring.
            val narrow = wDp < 230
            val bodyTop = drawWidgetHeader(canvas, context, p, settings, sides, pad, s, tr("This week"), volume.takeIf { !narrow }) + 10 * s

            val trainedDays = week.workouts.map { Calendar.getInstance().apply { timeInMillis = it.id }.get(Calendar.DAY_OF_WEEK) }.toSet()
            val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val stripH = 28 * s

            fun ring(cx: Float, cy: Float, d: Float) {
                val stroke = (d * 0.11f).coerceIn(5 * s, 11 * s)
                val r = d / 2 - stroke / 2
                val arc = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(arc, 0f, 360f, false, strokePaint(p.bgControl.toArgb(), stroke))
                val sweep = 360f * (done.toFloat() / goal).coerceAtMost(1f)
                if (done > 0) canvas.drawArc(arc, -90f, if (sides.rtl) -sweep else sweep, false, strokePaint(p.accentPrimary.toArgb(), stroke))
                // The count large and the goal small, as on Today.
                val big = textPaint(p.textPrimary.toArgb(), d * 0.34f, BOLD)
                val small = textPaint(p.textTertiary.toArgb(), d * 0.17f, MEDIUM)
                val goalText = "/$goal"
                val total = big.measureText("$done") + small.measureText(goalText)
                val x0 = cx - total / 2
                val y = cy + big.textSize * 0.35f
                canvas.drawText("$done", x0, y, big)
                canvas.drawText(goalText, x0 + big.measureText("$done"), y, small)
            }

            fun strip(start: Float, width: Float, top: Float) {
                val col = width / 7
                weekdayLetters().forEachIndexed { i, letter ->
                    val day = i + 1 // Calendar.SUNDAY == 1
                    val cx = sides.x(start + col * i + col / 2)
                    val isToday = day == today
                    val letterPaint = textPaint((if (isToday) p.textPrimary else p.textTertiary).toArgb(), 10 * s, if (isToday) BOLD else REGULAR, Paint.Align.CENTER)
                    canvas.drawText(letter, cx, top + 10 * s, letterPaint)
                    val cy = top + 20 * s
                    when {
                        day in trainedDays -> canvas.drawCircle(cx, cy, 4.5f * s, fillPaint(p.accentPrimary.toArgb()))
                        isToday -> canvas.drawCircle(cx, cy, 4f * s, strokePaint(p.accentPrimary.toArgb(), 1.5f * s))
                        else -> canvas.drawCircle(cx, cy, 2.5f * s, fillPaint(p.borderStrong.toArgb()))
                    }
                }
            }

            if (wDp >= hDp * 1.4f) {
                // Wide: the ring at the start, next workout and the days beside it.
                val d = (h - bodyTop - pad).coerceAtLeast(30 * s)
                ring(sides.x(pad + d / 2), bodyTop + d / 2, d)
                val restStart = pad + d + 18 * s
                val restW = w - restStart - pad
                strip(restStart, restW, h - pad - stripH)
                next?.let {
                    val paint = sides.start(textPaint(p.textPrimary.toArgb(), 15 * s, BOLD))
                    canvas.drawText(paint.ellipsize(it, restW), sides.x(restStart), bodyTop + 18 * s, paint)
                }
            } else {
                var lines = listOfNotNull(
                    next?.let { it to p.accentPrimary },
                    volume.takeIf { narrow }?.let { tr("{0} lifted", it) to p.textSecondary }
                )
                fun ringSize() = min(w - pad * 2, h - bodyTop - pad - stripH - lines.size * 16 * s - 6 * s)
                // The ring matters most: drop the volume line before the ring gets small.
                if (ringSize() < 64 * s && lines.size > 1) lines = lines.take(1)
                val d = ringSize().coerceAtLeast(24 * s)
                ring(w / 2, bodyTop + d / 2, d)
                lines.forEachIndexed { i, (text, color) ->
                    val paint = textPaint(color.toArgb(), 12 * s, if (i == 0 && next != null) MEDIUM else REGULAR, Paint.Align.CENTER)
                    canvas.drawText(paint.ellipsize(text, w - pad * 2), w / 2, bodyTop + d + 16 * s + i * 16 * s, paint)
                }
                strip(pad, w - pad * 2, h - pad - stripH + 4 * s)
            }
            return bmp
        }
    }
}
