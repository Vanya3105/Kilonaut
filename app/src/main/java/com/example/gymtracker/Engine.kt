package com.example.gymtracker

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ExifInterface
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

const val ACTION_SESSION_SYNC = "com.example.gymtracker.ACTION_SESSION_SYNC"

@Immutable
data class ExerciseRecords(
    val maxWeightKg: Float,
    /** Rounded to 0.5 kg, like every estimated 1RM the app shows. */
    val bestEstimated1Rm: Float,
    /** The set behind the best estimated 1RM, or null when nothing is logged. */
    val bestSet: SetEntry?,
    /** Up to five most recent sessions, newest first: label to work sets. */
    val recentHistory: List<Pair<String, List<SetEntry>>>,
    /** Oldest first: each session's [sessionValue]. */
    val chartPoints: List<Pair<String, Float>>,
    val type: ExerciseType = ExerciseType.WEIGHT_REPS,
    val maxReps: Int = 0,
    val maxSeconds: Int = 0,
    val maxDistanceM: Float = 0f,
    /** Least assistance ever used (assisted exercises); null until one is logged. */
    val minAssistKg: Float? = null
)

/**
 * The single number that sums up a session for [type]: top weight (or added weight), most reps,
 * least assistance, longest hold, or longest distance.
 */
fun sessionValue(type: ExerciseType, workSets: List<SetEntry>): Float = when (type) {
    ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT -> workSets.maxOf { it.weightKg }
    ExerciseType.BODYWEIGHT -> workSets.maxOf { it.repCount }.toFloat()
    ExerciseType.ASSISTED -> workSets.minOf { it.weightKg }
    ExerciseType.DURATION -> workSets.maxOf { it.secondCount }.toFloat()
    ExerciseType.DISTANCE_TIME -> workSets.maxOf { it.distanceMeters }
}

/** The set that best represents [sets] for [type] (heaviest, most reps, least help, longest). */
fun bestSetOf(type: ExerciseType, sets: List<SetEntry>): SetEntry? = when (type) {
    ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT -> sets.maxWithOrNull(compareBy({ it.weightKg }, { it.repCount }))
    ExerciseType.BODYWEIGHT -> sets.maxByOrNull { it.repCount }
    ExerciseType.ASSISTED -> sets.minWithOrNull(compareBy({ it.weightKg }, { -it.repCount }))
    ExerciseType.DURATION -> sets.maxByOrNull { it.secondCount }
    ExerciseType.DISTANCE_TIME -> sets.maxWithOrNull(compareBy({ it.distanceMeters }, { it.secondCount }))
}

/** A [sessionValue] in words: "80 kg", "15 reps", "−20 kg", "1:30", "5 km". */
fun formatSessionValue(type: ExerciseType, value: Float): String = when (type) {
    ExerciseType.WEIGHT_REPS -> formatWeightKg(value)
    ExerciseType.WEIGHTED_BODYWEIGHT -> "+${formatWeightKg(value)}"
    ExerciseType.BODYWEIGHT -> if (value.toInt() == 1) tr("1 rep") else tr("{0} reps", value.toInt())
    ExerciseType.ASSISTED -> if (value > 0f) "−${formatWeightKg(value)}" else tr("No help")
    ExerciseType.DURATION -> formatClock(value.toLong())
    ExerciseType.DISTANCE_TIME -> formatDistance(value)
}

/** The same value without its unit, for chart labels. */
fun formatSessionValueShort(type: ExerciseType, value: Float): String = when (type) {
    ExerciseType.BODYWEIGHT -> "${value.toInt()}"
    ExerciseType.DURATION -> formatClock(value.toLong())
    ExerciseType.DISTANCE_TIME -> formatKgClean(value / Units.current.metersPerDistanceUnit)
    else -> formatWeightNumber(value)
}

/** What [sessionValue] means, as a chart title. */
fun sessionValueTitle(type: ExerciseType): String = when (type) {
    ExerciseType.WEIGHT_REPS -> tr("Top weight per session")
    ExerciseType.WEIGHTED_BODYWEIGHT -> tr("Most added weight per session")
    ExerciseType.BODYWEIGHT -> tr("Most reps per session")
    ExerciseType.ASSISTED -> tr("Least assistance per session")
    ExerciseType.DURATION -> tr("Longest hold per session")
    ExerciseType.DISTANCE_TIME -> tr("Longest distance per session")
}

const val WIDGET_KIND_WORKOUT = "WORKOUT"
const val WIDGET_KIND_RECOVERY = "RECOVERY"
const val WIDGET_KIND_WEEKLY = "WEEKLY"
const val WIDGET_KIND_WEIGHT = "WEIGHT"

const val WIDGET_STYLE_STANDARD = "STANDARD"
const val WIDGET_STYLE_TINTED = "TINTED"
/** Widget theme from the wallpaper's colours (Android 12+); light and dark like the phone. */
const val WIDGET_THEME_WALLPAPER = "WALLPAPER"

/** Per-widget-instance look & content. [options] holds the on/off toggles listed in [widgetOptionLabels]. */
@Immutable
data class WidgetSettings(
    val theme: String = THEME_SYSTEM,
    val opacityPercent: Int = 100,
    val style: String = WIDGET_STYLE_STANDARD,
    val options: Set<String>
)

/** Toggle key → label shown in the widget settings screen, per widget kind. All on by default. */
fun widgetOptionLabels(kind: String): List<Pair<String, String>> = when (kind) {
    WIDGET_KIND_WORKOUT -> listOf(
        "controls" to tr("Weight and rep buttons"),
        "weekly" to tr("Weekly progress when not training")
    )
    WIDGET_KIND_RECOVERY -> listOf(
        "sets" to tr("Sets in the last 7 days"),
        "hours" to tr("Time since last trained")
    )
    WIDGET_KIND_WEIGHT -> listOf(
        "trend" to tr("Trend of the last 30 days"),
        "button" to tr("Log button")
    )
    else -> listOf(
        "volume" to tr("Weekly volume"),
        "next" to tr("Next workout")
    )
}

fun defaultWidgetSettings(kind: String) = WidgetSettings(options = widgetOptionLabels(kind).map { it.first }.toSet())

val SPLIT_PROGRAMS = mapOf(
    "PPL_ARNOLD" to SplitProgramInfo(
        id = "PPL_ARNOLD",
        name = "Push, Pull + Arnold split",
        tagline = "Push, pull, arms & shoulders, chest & back",
        weeklyGoal = 4,
        splits = listOf(
            WorkoutSplit("Push", "Chest, shoulders, triceps", "01"),
            WorkoutSplit("Pull", "Lats, upper back, biceps", "02"),
            WorkoutSplit("Arms & Shoulders", "Delts, biceps, triceps", "03"),
            WorkoutSplit("Chest & Back", "Chest and back together", "04")
        )
    ),
    "CLASSIC_PPL" to SplitProgramInfo(
        id = "CLASSIC_PPL",
        name = "Push / Pull / Legs",
        tagline = "The classic three-day split",
        weeklyGoal = 3,
        splits = listOf(
            WorkoutSplit("Push", "Chest, shoulders, triceps", "01"),
            WorkoutSplit("Pull", "Lats, mid back, biceps", "02"),
            WorkoutSplit("Legs", "Quads, hamstrings, calves", "03")
        )
    ),
    "UPPER_LOWER" to SplitProgramInfo(
        id = "UPPER_LOWER",
        name = "Upper / Lower",
        tagline = "Four days, each muscle twice a week",
        weeklyGoal = 4,
        splits = listOf(
            WorkoutSplit("Upper A", "Chest and back, plus arms", "01"),
            WorkoutSplit("Lower A", "Quads, hamstrings, calves", "02"),
            WorkoutSplit("Upper B", "Shoulders and arms", "03"),
            WorkoutSplit("Lower B", "Glutes, hamstrings, quads", "04")
        )
    ),
    "WENDLER_531" to SplitProgramInfo(
        id = "WENDLER_531",
        name = "5/3/1",
        tagline = "Four big lifts in four-week waves of a training max",
        weeklyGoal = 4,
        splits = listOf(
            WorkoutSplit("5/3/1 Squat", "Back squat, then legs", "01"),
            WorkoutSplit("5/3/1 Bench", "Bench press, then back and arms", "02"),
            WorkoutSplit("5/3/1 Deadlift", "Deadlift, then legs and core", "03"),
            WorkoutSplit("5/3/1 Press", "Overhead press, then back and arms", "04")
        )
    ),
    "GZCLP" to SplitProgramInfo(
        id = "GZCLP",
        name = "GZCLP",
        tagline = "Linear progression in tiers: heavy T1, volume T2, light T3",
        weeklyGoal = 4,
        splits = listOf(
            WorkoutSplit("GZCLP A1", "T1 squat, T2 bench, T3 pulldown", "01"),
            WorkoutSplit("GZCLP B1", "T1 press, T2 deadlift, T3 row", "02"),
            WorkoutSplit("GZCLP A2", "T1 bench, T2 squat, T3 pulldown", "03"),
            WorkoutSplit("GZCLP B2", "T1 deadlift, T2 press, T3 row", "04")
        )
    ),
    "NSUNS_5" to SplitProgramInfo(
        id = "NSUNS_5",
        name = "nSuns 5-day",
        tagline = "High-volume percentages; the 1+ sets raise your training max",
        weeklyGoal = 5,
        splits = listOf(
            WorkoutSplit("nSuns Day 1", "Bench volume, overhead press", "01"),
            WorkoutSplit("nSuns Day 2", "Squat, sumo deadlift", "02"),
            WorkoutSplit("nSuns Day 3", "Overhead press, incline bench", "03"),
            WorkoutSplit("nSuns Day 4", "Deadlift, front squat", "04"),
            WorkoutSplit("nSuns Day 5", "Heavy bench, close-grip bench", "05")
        )
    ),
    "FULL_BODY" to SplitProgramInfo(
        id = "FULL_BODY",
        name = "Full body",
        tagline = "Three days, whole body each time",
        weeklyGoal = 3,
        splits = listOf(
            WorkoutSplit("Full Body A", "Incline press, pulldown, squat", "01"),
            WorkoutSplit("Full Body B", "Shoulder press, row, RDL", "02"),
            WorkoutSplit("Full Body C", "Dips, chin-ups, leg press", "03")
        )
    ),
    "FULL_BODY_2" to SplitProgramInfo(
        id = "FULL_BODY_2",
        name = "Full body, 2 days",
        tagline = "Two whole-body days for a busy week",
        weeklyGoal = 2,
        splits = listOf(
            WorkoutSplit("Full Body A", "Incline press, pulldown, squat", "01"),
            WorkoutSplit("Full Body B", "Shoulder press, row, RDL", "02")
        )
    ),
    "BRO_SPLIT" to SplitProgramInfo(
        id = "BRO_SPLIT",
        name = "Bro split",
        tagline = "One muscle group a day, five days",
        weeklyGoal = 5,
        splits = listOf(
            WorkoutSplit("Chest", "Presses and flyes", "01"),
            WorkoutSplit("Back", "Pulldowns, rows, deadlift", "02"),
            WorkoutSplit("Shoulders", "Presses and raises", "03"),
            WorkoutSplit("Arms", "Biceps and triceps", "04"),
            WorkoutSplit("Legs", "Quads, hamstrings, calves", "05")
        )
    ),
    "PPL_6" to SplitProgramInfo(
        id = "PPL_6",
        name = "Push / Pull / Legs ×2",
        tagline = "Six days, every muscle twice a week",
        weeklyGoal = 6,
        splits = listOf(
            WorkoutSplit("Push A", "Bench, shoulder press, triceps", "01"),
            WorkoutSplit("Pull A", "Rows, pulldown, biceps", "02"),
            WorkoutSplit("Legs A", "Squat, RDL, calves", "03"),
            WorkoutSplit("Push B", "Overhead press, incline, triceps", "04"),
            WorkoutSplit("Pull B", "Pulldown, rows, rear delts", "05"),
            WorkoutSplit("Legs B", "Hack squat, leg curls, calves", "06")
        )
    ),
    "PHUL" to SplitProgramInfo(
        id = "PHUL",
        name = "PHUL",
        tagline = "Heavy upper/lower days plus muscle-building days",
        weeklyGoal = 4,
        splits = listOf(
            WorkoutSplit("Upper Power", "Heavy bench, row, press", "01"),
            WorkoutSplit("Lower Power", "Heavy squat and deadlift", "02"),
            WorkoutSplit("Upper Hypertrophy", "Higher reps, chest, back, arms", "03"),
            WorkoutSplit("Lower Hypertrophy", "Higher reps, quads, hamstrings", "04")
        )
    )
)

const val DEFAULT_PROGRAM_ID = "PPL_ARNOLD"

/**
 * Programs the user built or customized. Kept current by LocalStorage whenever its data loads
 * or changes; snapshot state, so program lists redraw on edits.
 */
object UserPrograms {
    private val state = mutableStateOf<List<SplitProgramInfo>>(emptyList())
    val all: List<SplitProgramInfo> get() = state.value

    fun set(programs: List<SplitProgramInfo>) {
        if (state.value != programs) state.value = programs
    }
}

/** Built-in programs first, then the user's own. */
fun allPrograms(): List<SplitProgramInfo> = SPLIT_PROGRAMS.values.toList() + UserPrograms.all

fun programFor(programId: String): SplitProgramInfo =
    SPLIT_PROGRAMS[programId] ?: UserPrograms.all.find { it.id == programId } ?: SPLIT_PROGRAMS.getValue(DEFAULT_PROGRAM_ID)

val SplitProgramInfo.isBuiltIn: Boolean get() = id in SPLIT_PROGRAMS

val REST_PRESETS = listOf(0, 60, 90, 120, 180, 240)

fun formatRestShort(sec: Int): String {
    if (sec <= 0) return tr("Off")
    return if (sec % 60 == 0) tr("{0}m", sec / 60) else tr("{0}s", sec)
}

fun formatClock(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatDuration(totalSec: Long): String {
    val mins = (totalSec / 60).toInt()
    return when {
        mins >= 60 -> tr("{0}h {1}m", mins / 60, mins % 60)
        mins > 0 -> tr("{0} min", mins)
        else -> tr("< 1 min")
    }
}

fun formatKgClean(value: Float): String {
    val clamped = (value.coerceAtLeast(0f) * 100f).roundToInt() / 100f
    return if (clamped % 1f == 0f) "${clamped.toInt()}" else "$clamped"
}

/**
 * Filters a typed weight: "62,5" (comma keyboards) → "62.5" and stray characters are dropped.
 * Returns null when the edit would add a second decimal point, so the field keeps its previous value.
 */
fun sanitizeDecimalInput(raw: String): String? {
    val cleaned = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
    return cleaned.takeIf { it.count { c -> c == '.' } <= 1 }
}

fun parseWeightClean(raw: String, fallback: Float = 20f): Float {
    val cleaned = raw.replace("kg", "", ignoreCase = true).trim()
    return cleaned.toFloatOrNull() ?: fallback
}

fun parseRepsClean(raw: String, fallback: Int = 8): Int {
    val cleaned = raw.replace("r", "", ignoreCase = true).trim()
    return cleaned.toIntOrNull() ?: fallback
}

object DateUtils {
    private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    // Saved with each workout and weigh-in, so it stays English; shown through the *Of helpers below.
    private val displayDateFmt = SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.US)
    // Only shown, so in the app's language (made once per run; a language change restarts the app).
    private val hebrew = hebrewOn()
    private val shownLocale = if (hebrew) Locale.forLanguageTag("he") else Locale.US
    private val shortDayFmt = SimpleDateFormat(if (hebrew) "d בMMM" else "MMM d", shownLocale)
    private val shortDayYearFmt = SimpleDateFormat(if (hebrew) "d בMMM yyyy" else "MMM d, yyyy", shownLocale)
    private val timeFmt = SimpleDateFormat("HH:mm", shownLocale)
    private val monthTitleFmt = SimpleDateFormat("MMMM yyyy", shownLocale)

    @Synchronized fun formatDayKey(date: Date): String = dayKeyFmt.format(date)
    @Synchronized fun parseDayKey(key: String): Date? = runCatching { dayKeyFmt.parse(key) }.getOrNull()
    @Synchronized fun formatDisplayDate(date: Date): String = displayDateFmt.format(date)
    @Synchronized fun formatShortDay(date: Date): String = shortDayFmt.format(date)
    @Synchronized fun formatShortDayYear(date: Date): String = shortDayYearFmt.format(date)
    @Synchronized fun formatMonthTitle(date: Date): String = monthTitleFmt.format(date)

    // A saved English display date ("Oct 25, 2026 • 18:05"), shown in the app's language from its time.
    fun shortDayOf(displayDate: String, millis: Long): String =
        if (hebrew) formatShortDay(Date(millis)) else displayDate.substringBefore(",")
    fun shortDayYearOf(displayDate: String, millis: Long): String =
        if (hebrew) formatShortDayYear(Date(millis)) else displayDate.substringBefore(" •")
    @Synchronized fun dateTimeOf(displayDate: String, millis: Long): String =
        if (hebrew) "${shortDayYearFmt.format(Date(millis))} · ${timeFmt.format(Date(millis))}" else displayDate.replace(" • ", " · ")
}

fun applyDisplayRefreshRate(activity: Activity, targetHz: Int) {
    try {
        val window = activity.window
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        } ?: return

        val currentMode = display.mode
        val sameResModes = display.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
        }.ifEmpty { display.supportedModes.toList() }

        val bestMode = sameResModes.minByOrNull { abs(it.refreshRate - targetHz.toFloat()) } ?: return
        val params = window.attributes
        if (params.preferredDisplayModeId != bestMode.modeId) {
            params.preferredDisplayModeId = bestMode.modeId
            window.attributes = params
        }
    } catch (_: Exception) {}
}

// ============================================================================
// NOTIFICATIONS
// ============================================================================

private const val OLD_REST_CHANNEL_ID = "gym_rest_timer_channel"
private const val OLD_SET_DONE_CHANNEL_ID = "gym_set_done_channel"
private const val OLD_REST_NOTIFICATION_ID = 1001
private const val OLD_SET_DONE_NOTIFICATION_ID = 1002

fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * The app's only notification is the running workout's (set and rest on the lock screen), so it asks
 * when the first workout starts, once: asking on a new install's first screen came before anyone
 * knew why, and asking every workout after a "Don't allow" nags. Profile links to the setting.
 */
object NotificationAsk {
    private const val PREFS = "gym_tracker_notification_ask"
    private const val KEY_ASKED = "asked"

    fun shouldAsk(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
}

/**
 * Removes the old "Rest timer" and "Logged sets" channels: a card for every set and rest was noise
 * next to the workout's own ongoing notification, which already shows both. Rest's end still buzzes
 * (see [RestAlarmScheduler.triggerRestFinishedAlert]).
 */
fun removeOldAlertChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    try {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(OLD_REST_NOTIFICATION_ID)
        manager.cancel(OLD_SET_DONE_NOTIFICATION_ID)
        manager.deleteNotificationChannel(OLD_REST_CHANNEL_ID)
        manager.deleteNotificationChannel(OLD_SET_DONE_CHANNEL_ID)
    } catch (_: Exception) {}
}

fun notificationBuilder(context: Context, channelId: String): Notification.Builder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, channelId)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
    }

fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
        context, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

// ============================================================================
// PHOTO STORAGE
// ============================================================================

object PhotoStorage {
    private const val MAX_PHOTO_DIM = 1400

    // Bounded by bytes (1/8 of the heap) so browsing many workout photos can't exhaust memory.
    private val memoryCache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    fun saveBitmapToInternalStorage(context: Context, bitmap: Bitmap): String? {
        return try {
            val folder = File(context.filesDir, "workout_photos").apply { mkdirs() }
            val file = File(folder, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            memoryCache.put(file.absolutePath, bitmap.asImageBitmap())
            file.absolutePath
        } catch (e: Exception) { null }
    }

    fun copyUriToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            // Read dimensions first and subsample, so a 50MP gallery photo is never decoded at full size.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= MAX_PHOTO_DIM && bounds.outHeight / (sampleSize * 2) >= MAX_PHOTO_DIM) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val original = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val scaled = if (original.width > MAX_PHOTO_DIM || original.height > MAX_PHOTO_DIM) {
                val ratio = minOf(MAX_PHOTO_DIM.toFloat() / original.width, MAX_PHOTO_DIM.toFloat() / original.height)
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * ratio).roundToInt().coerceAtLeast(1),
                    (original.height * ratio).roundToInt().coerceAtLeast(1),
                    true
                )
            } else original
            saveBitmapToInternalStorage(context, uprighted(context, uri, scaled))
        } catch (e: Exception) { null }
    }

    /**
     * Phone cameras (Samsung's among them) often save a portrait photo as landscape pixels plus an
     * EXIF note to turn it; decoding ignores the note, so the photo would come out sideways.
     */
    private fun uprighted(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Where the camera app writes a full-size photo; [copyUriToInternalStorage] then shrinks it and it's deleted. */
    fun newCameraFile(context: Context): File =
        File(File(context.filesDir, "workout_photos").apply { mkdirs() }, "camera_${System.currentTimeMillis()}.jpg")

    /** Only what's already decoded; never touches the disk. */
    fun cached(path: String?): ImageBitmap? = path?.takeIf { it.isNotBlank() }?.let { memoryCache.get(it) }

    fun loadImageBitmap(path: String?): ImageBitmap? {
        if (path.isNullOrBlank()) return null
        memoryCache.get(path)?.let { return it }
        return try {
            val file = File(path)
            if (!file.exists()) return null
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()?.also { memoryCache.put(path, it) }
        } catch (e: Exception) { null }
    }
}

/** Starting exercises (name to muscle) for each split, all from [ExerciseDatabase]. */
fun defaultTemplateForSplit(splitTitle: String): List<Pair<String, String>> {
    val names = when (splitTitle) {
        "Push" -> listOf("Incline Barbell Bench Press", "Pec Deck", "Lateral Raise", "Tricep Pushdown")
        "Pull" -> listOf("Lat Pulldown", "T-Bar Row", "Face Pull", "Preacher Curl")
        "Legs", "Lower A" -> listOf("Hack Squat", "Romanian Deadlift", "Leg Extension", "Seated Leg Curl", "Standing Calf Raise")
        "Lower B" -> listOf("Leg Press", "Lying Leg Curl", "Bulgarian Split Squat", "Seated Calf Raise")
        "5/3/1 Squat" -> listOf("Back Squat", "Leg Press", "Lying Leg Curl", "Hanging Leg Raise")
        "5/3/1 Bench" -> listOf("Barbell Bench Press", "Dumbbell Row", "Tricep Pushdown", "Face Pull")
        "5/3/1 Deadlift" -> listOf("Deadlift", "Bulgarian Split Squat", "Ab Wheel Rollout")
        "5/3/1 Press" -> listOf("Overhead Press", "Chin-Up", "Dips", "Face Pull")
        "GZCLP A1" -> listOf("Back Squat", "Barbell Bench Press", "Lat Pulldown")
        "GZCLP B1" -> listOf("Overhead Press", "Deadlift", "Dumbbell Row")
        "GZCLP A2" -> listOf("Barbell Bench Press", "Back Squat", "Lat Pulldown")
        "GZCLP B2" -> listOf("Deadlift", "Overhead Press", "Dumbbell Row")
        "nSuns Day 1" -> listOf("Barbell Bench Press", "Overhead Press", "Lat Pulldown", "Lateral Raise")
        "nSuns Day 2" -> listOf("Back Squat", "Sumo Deadlift", "Lying Leg Curl", "Hanging Leg Raise")
        "nSuns Day 3" -> listOf("Overhead Press", "Incline Barbell Bench Press", "Chin-Up", "Face Pull")
        "nSuns Day 4" -> listOf("Deadlift", "Front Squat", "Barbell Row", "Ab Wheel Rollout")
        "nSuns Day 5" -> listOf("Barbell Bench Press", "Close-Grip Bench Press", "Dumbbell Row", "Barbell Curl")
        "Arms & Shoulders" -> listOf(
            "Seated Dumbbell Shoulder Press", "Cable Lateral Raise", "Incline Dumbbell Curl",
            "Overhead Cable Tricep Extension", "Hammer Curl"
        )
        "Chest & Back", "Upper A" -> listOf("Incline Dumbbell Press", "Weighted Pull-Up", "Machine Chest Press", "Chest-Supported Row")
        "Upper B" -> listOf("Seated Dumbbell Shoulder Press", "Seated Cable Row", "Cable Lateral Raise", "Preacher Curl", "Rope Pushdown")
        "Full Body A" -> listOf("Incline Dumbbell Press", "Lat Pulldown", "Leg Press", "Lateral Raise")
        "Full Body B" -> listOf("Seated Dumbbell Shoulder Press", "Chest-Supported Row", "Romanian Deadlift", "Incline Dumbbell Curl")
        "Full Body C" -> listOf("Dips", "Weighted Pull-Up", "Leg Extension", "Overhead Cable Tricep Extension")
        "Chest" -> listOf("Barbell Bench Press", "Incline Dumbbell Press", "Machine Chest Press", "Cable Fly", "Pec Deck")
        "Back" -> listOf("Deadlift", "Lat Pulldown", "Barbell Row", "Seated Cable Row", "Straight-Arm Pulldown")
        "Shoulders" -> listOf("Overhead Press", "Lateral Raise", "Cable Lateral Raise", "Rear Delt Fly", "Face Pull")
        "Arms" -> listOf("Barbell Curl", "Skull Crusher", "Incline Dumbbell Curl", "Rope Pushdown", "Hammer Curl", "Overhead Cable Tricep Extension")
        "Push A" -> listOf("Barbell Bench Press", "Seated Dumbbell Shoulder Press", "Incline Dumbbell Press", "Lateral Raise", "Tricep Pushdown")
        "Pull A" -> listOf("Barbell Row", "Lat Pulldown", "Face Pull", "Barbell Curl", "Hammer Curl")
        "Legs A" -> listOf("Back Squat", "Romanian Deadlift", "Leg Press", "Lying Leg Curl", "Standing Calf Raise")
        "Push B" -> listOf("Overhead Press", "Incline Barbell Bench Press", "Cable Fly", "Cable Lateral Raise", "Overhead Cable Tricep Extension")
        "Pull B" -> listOf("Close-Grip Lat Pulldown", "Chest-Supported Row", "Seated Cable Row", "Rear Delt Fly", "Incline Dumbbell Curl")
        "Legs B" -> listOf("Hack Squat", "Stiff-Leg Deadlift", "Bulgarian Split Squat", "Seated Leg Curl", "Seated Calf Raise")
        "Upper Power" -> listOf("Barbell Bench Press", "Barbell Row", "Overhead Press", "Lat Pulldown", "Barbell Curl", "Skull Crusher")
        "Lower Power" -> listOf("Back Squat", "Deadlift", "Leg Press", "Lying Leg Curl", "Standing Calf Raise")
        "Upper Hypertrophy" -> listOf("Incline Dumbbell Press", "Seated Cable Row", "Cable Fly", "Lateral Raise", "Incline Dumbbell Curl", "Rope Pushdown")
        "Lower Hypertrophy" -> listOf("Front Squat", "Romanian Deadlift", "Leg Extension", "Seated Leg Curl", "Seated Calf Raise")
        else -> emptyList()
    }
    return names.map { it to (ExerciseDatabase.find(it)?.muscle ?: "") }
}

// ============================================================================
// RECORDS & WEEKLY SUMMARY
// ============================================================================

fun calculateEstimated1Rm(weightKg: Float, reps: Int): Float {
    if (weightKg <= 0f || reps <= 0) return 0f
    if (reps == 1) return weightKg
    return weightKg * (1f + reps / 30f)
}

fun computeExerciseRecords(exerciseName: String, history: List<CompletedWorkout>): ExerciseRecords {
    var maxWeight = 0f
    var best1Rm = 0f
    var bestSet: SetEntry? = null
    val recentLogs = ArrayList<Pair<String, List<SetEntry>>>(5)
    val recentChartPoints = ArrayList<Pair<String, Float>>(5)

    var type = ExerciseType.WEIGHT_REPS
    var maxReps = 0
    var maxSeconds = 0
    var maxDistance = 0f
    var minAssist: Float? = null

    for (workout in history) {
        val matched = workout.exercises.find {
            !it.isSkipped && it.name.equals(exerciseName, ignoreCase = true)
        } ?: continue
        type = matched.type
        val workSets = matched.workSets
        if (workSets.isEmpty()) continue

        if (recentLogs.size < 5) {
            val shortDate = DateUtils.shortDayOf(workout.displayDate, workout.id)
            recentLogs.add("${workout.splitTitle} ($shortDate)" to workSets)
            recentChartPoints.add(shortDate to sessionValue(type, workSets))
        }
        for (s in workSets) {
            val w = s.weightKg
            val e1rm = calculateEstimated1Rm(w, s.repCount)
            if (w > maxWeight) maxWeight = w
            if (e1rm > best1Rm) best1Rm = e1rm
            val better = when (type) {
                ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT -> bestSet == null || e1rm > calculateEstimated1Rm(bestSet.weightKg, bestSet.repCount)
                ExerciseType.BODYWEIGHT -> s.repCount > maxReps
                ExerciseType.ASSISTED -> minAssist == null || w < minAssist || (w == minAssist && s.repCount > (bestSet?.repCount ?: 0))
                ExerciseType.DURATION -> s.secondCount > maxSeconds
                ExerciseType.DISTANCE_TIME -> s.distanceMeters > maxDistance
            }
            if (better) bestSet = s
            maxReps = maxOf(maxReps, s.repCount)
            maxSeconds = maxOf(maxSeconds, s.secondCount)
            maxDistance = maxOf(maxDistance, s.distanceMeters)
            if (type == ExerciseType.ASSISTED) minAssist = minOf(minAssist ?: w, w)
        }
    }

    return ExerciseRecords(
        maxWeightKg = maxWeight,
        bestEstimated1Rm = roundToHalf(best1Rm),
        bestSet = bestSet,
        recentHistory = recentLogs,
        chartPoints = recentChartPoints.reversed(),
        type = type,
        maxReps = maxReps,
        maxSeconds = maxSeconds,
        maxDistanceM = maxDistance,
        minAssistKg = minAssist
    )
}

/** Whether a completed set beats this exercise's earlier best. Never true without earlier history. */
fun isSetPersonalRecord(setEntry: SetEntry, records: ExerciseRecords, type: ExerciseType = records.type): Boolean {
    if (!setEntry.isCompleted || !setEntry.isValidWorkSet(type)) return false
    val w = setEntry.weightKg
    val r = setEntry.repCount
    return when (type) {
        ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT -> {
            if (w <= 0f || records.bestEstimated1Rm <= 0f) false
            else roundToHalf(calculateEstimated1Rm(w, r)) > records.bestEstimated1Rm || w > records.maxWeightKg
        }
        ExerciseType.BODYWEIGHT -> records.maxReps > 0 && r > records.maxReps
        ExerciseType.ASSISTED -> records.minAssistKg?.let { w < it } ?: false
        ExerciseType.DURATION -> records.maxSeconds > 0 && setEntry.secondCount > records.maxSeconds
        ExerciseType.DISTANCE_TIME -> records.maxDistanceM > 0f && setEntry.distanceMeters > records.maxDistanceM
    }
}

/**
 * The set that best sums up a session of [sets]: heaviest for its reps (est. 1RM) on loaded lifts,
 * then most reps, least assistance, longest time or longest distance. Null without work sets.
 */
fun bestSetOf(sets: List<SetEntry>, type: ExerciseType): SetEntry? {
    val work = sets.filter { it.isValidWorkSet(type) }
    return when (type) {
        ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT ->
            work.maxWithOrNull(compareBy<SetEntry> { calculateEstimated1Rm(it.weightKg, it.repCount) }.thenBy { it.repCount })
                ?.takeIf { it.weightKg > 0f } ?: work.maxByOrNull { it.repCount }
        ExerciseType.BODYWEIGHT -> work.maxByOrNull { it.repCount }
        ExerciseType.ASSISTED -> work.minWithOrNull(compareBy<SetEntry> { it.weightKg }.thenByDescending { it.repCount })
        ExerciseType.DURATION -> work.maxByOrNull { it.secondCount }
        ExerciseType.DISTANCE_TIME -> work.maxByOrNull { it.distanceMeters }
    }
}

fun computeWeekSummary(
    history: List<CompletedWorkout>,
    weekOffset: Int = 0,
    splitProgramId: String = "PPL_ARNOLD"
): WeekSummaryData {
    val prog = programFor(splitProgramId)
    val coreRotation = prog.splits.map { it.title }

    val startCal = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.SUNDAY
        set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.WEEK_OF_YEAR, weekOffset)
    }
    val endCal = (startCal.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, 6)
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }

    val weekWorkouts = history.filter { it.id in startCal.timeInMillis..endCal.timeInMillis }
    val completedCore = weekWorkouts.map { it.splitTitle }.toSet().intersect(coreRotation.toSet())
    val remainingCore = coreRotation.filter { it !in completedCore }

    val titleLabel = when (weekOffset) {
        0 -> tr("This week")
        -1 -> tr("Last week")
        else -> tr("{0} weeks ago", -weekOffset)
    }

    // Next up is the first split not done this week; once all are done, a stable pick for a bonus day.
    val recommendedSplit = remainingCore.firstOrNull() ?: run {
        val weekSeed = startCal.get(Calendar.YEAR) * 100 + startCal.get(Calendar.WEEK_OF_YEAR)
        coreRotation[Random(weekSeed).nextInt(coreRotation.size)]
    }

    return WeekSummaryData(
        weekOffset = weekOffset,
        titleLabel = titleLabel,
        dateRangeLabel = "${DateUtils.formatShortDay(startCal.time)} – ${DateUtils.formatShortDayYear(endCal.time)}",
        workouts = weekWorkouts,
        completedCoreSplits = completedCore,
        recommendedSplit = recommendedSplit,
        weeklyGoal = prog.weeklyGoal
    )
}

// ============================================================================
// REST TIMER ALARM
// ============================================================================

object RestAlarmScheduler {
    private val fallbackHandler = Handler(Looper.getMainLooper())
    private var fallbackRunnable: Runnable? = null

    fun scheduleOrCancel(context: Context, endTimeMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 777, Intent(context, RestAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try { alarmManager.cancel(pendingIntent) } catch (_: Exception) {}
        fallbackRunnable?.let { fallbackHandler.removeCallbacks(it) }

        val delayMillis = endTimeMillis - System.currentTimeMillis()
        if (endTimeMillis <= 0L || delayMillis <= 500L) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, endTimeMillis, 2000L, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
            }
        } catch (_: Exception) {
            try { alarmManager.set(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent) } catch (_: Exception) {}
        }

        // In-process backup in case the alarm is delayed while the app is in the foreground.
        val task = Runnable { triggerRestFinishedAlert(context) }
        fallbackRunnable = task
        fallbackHandler.postDelayed(task, delayMillis)
    }

    @Synchronized
    fun triggerRestFinishedAlert(context: Context) {
        if (LocalStorage.getRestEndTimeMillis(context) <= 0L) return

        val settings = LocalStorage.loadSettings(context)
        LocalStorage.setRestEndTimeMillis(context, 0L, fromWidget = true)
        if (settings.restSoundEnabled) playRestBeep(context)
        buzzRestOver(context)
    }

    /** Two short pulses, unless the phone is on silent. The watch buzzes by itself when it hears. */
    private fun buzzRestOver(context: Context) {
        try {
            if (context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT) return
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
        } catch (_: Exception) {}
    }

    /** Short alarm beep that ducks (rather than stops) any music that's playing. */
    private fun playRestBeep(context: Context) {
        try {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                    .also { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                null
            }

            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 260)
            Handler(Looper.getMainLooper()).postDelayed({
                try { toneGen.release() } catch (_: Exception) {}
                if (focusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
            }, 320L)
        } catch (_: Exception) {}
    }
}

class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RestAlarmScheduler.triggerRestFinishedAlert(context.applicationContext)
        LocalStorage.flush(context)
    }
}
