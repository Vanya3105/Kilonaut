package com.example.gymtracker

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// LOCAL STORAGE
// ============================================================================

/**
 * The app's single storage entry point. Two backends sit behind it:
 * - Room ([GymDatabase]) for history, body weight, split templates and per-exercise notes/rest.
 * - A small JSON file for profile, settings, the active session and widget settings.
 *
 * Reads come from memory and are safe on any thread. Writes update memory at once and are
 * applied to disk in order on one storage thread, so a Room write is always on disk before any
 * later JSON write (e.g. a finished workout lands before its active session is cleared).
 * Widgets and the lock screen are redrawn once per burst of edits. BroadcastReceivers must call
 * [flush] before returning.
 */
object LocalStorage {
    private const val TAG = "LocalStorage"
    private const val FILE_NAME = "gym_tracker_store_v2.json"
    private const val SURFACE_REFRESH_DEBOUNCE_MS = 250L

    /** JSON sections that moved to Room. Old store files and backups still carry them. */
    private val ROOM_SECTIONS = listOf("history", "weightHistory", "splitTemplates", "exerciseNotes", "exerciseRestDefaults", "exerciseTypes", "programs", "splitTargets", "trainingMaxes")

    private val lock = Any()
    private var cachedRoot: JSONObject? = null
    private var cachedData: StoreData? = null
        set(value) {
            field = value
            value?.let { UserPrograms.set(it.programs) }
        }

    @Volatile private var ioThread: Thread? = null
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "gym-storage").also { ioThread = it } }
    private val pendingSnapshot = AtomicReference<String?>(null)

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var refreshContext: Context? = null
    // Redrawing widgets, the lock screen notification and the watch state took 13–60 ms per edit
    // on the main thread, right when the check-button and rest-timer animations run. All of it
    // (AppWidgetManager, NotificationManager, the Wear data client) is fine off the main thread.
    private val surfaceLock = Any()
    private val surfaceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gym-surfaces").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    // The debounce stays on the main handler (just a timer); the work goes to the surface thread.
    private val refreshRunnable = Runnable { refreshContext?.let { ctx -> surfaceExecutor.execute { refreshSurfacesNow(ctx) } } }

    private fun storeFile(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
    private fun previousFile(file: File) = File(file.parentFile, "${file.name}.bak")

    /** Copies of the data a restore replaced, newest last; a wrong file picked in the restore dialog stays recoverable. */
    private const val PRE_IMPORT_PREFIX = "before-import-"
    private const val PRE_IMPORT_KEEP = 3

    private fun db(context: Context) = GymDatabase.get(context)

    // --- JSON backend ---

    private fun root(context: Context): JSONObject = synchronized(lock) {
        cachedRoot ?: run {
            val file = storeFile(context)
            val parsed = readJson(file) ?: run {
                if (file.exists()) {
                    // Keep the unreadable file aside: the next save would otherwise overwrite all history.
                    Log.e(TAG, "Store file unreadable, falling back to the previous copy")
                    try {
                        file.copyTo(File(file.parentFile, "$FILE_NAME.corrupt-${System.currentTimeMillis()}"))
                    } catch (_: Exception) {}
                }
                // Also covers a crash between the two renames in writeAtomically.
                readJson(previousFile(file))
            } ?: JSONObject()
            cachedRoot = parsed
            Units.set(unitIn(parsed))
            parsed
        }
    }

    private fun readJson(file: File): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun unitIn(root: JSONObject) = WeightUnit.fromKey(root.optJSONObject("settings")?.optString("weightUnit"))

    private inline fun edit(context: Context, notifyInApp: Boolean = false, block: (JSONObject) -> Unit) {
        synchronized(lock) {
            val root = root(context)
            block(root)
            persist(context, root)
        }
        scheduleSurfaceRefresh(context, notifyInApp)
    }

    private fun persist(context: Context, root: JSONObject) {
        val file = storeFile(context)
        pendingSnapshot.set(root.toString())
        ioExecutor.execute {
            val snapshot = pendingSnapshot.getAndSet(null) ?: return@execute
            writeAtomically(file, snapshot, keepPrevious = true)
        }
    }

    /** [keepPrevious] keeps the last good version as `*.bak`, which [root] reads if the file is ever unreadable. */
    private fun writeAtomically(file: File, content: String, keepPrevious: Boolean = false) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            if (keepPrevious && file.exists()) file.renameTo(previousFile(file))
            if (!tmp.renameTo(file)) {
                file.writeText(content)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't write ${file.name}", e)
        }
    }

    // --- Room backend ---

    /** Runs [block] on the storage thread and waits for it. */
    private fun <T> onIoThread(context: Context, block: (GymDatabase) -> T): T {
        val db = db(context)
        if (Thread.currentThread() === ioThread) return block(db)
        return ioExecutor.submit(Callable { block(db) }).get()
    }

    private fun data(context: Context): StoreData = synchronized(lock) {
        cachedData ?: run {
            migrateJsonSectionsIfNeeded(context)
            onIoThread(context, RoomStore::load).also { cachedData = it }
        }
    }

    /** Applies [change] to the in-memory copy now and queues [write] for Room. */
    private fun editData(context: Context, change: (StoreData) -> StoreData, write: (GymDatabase) -> Unit) {
        synchronized(lock) {
            cachedData = change(data(context))
            val db = db(context)
            ioExecutor.execute {
                try {
                    write(db)
                } catch (e: Exception) {
                    Log.e(TAG, "Room write failed", e)
                }
            }
        }
        scheduleSurfaceRefresh(context, notifyInApp = false)
    }

    /**
     * One-time move of the JSON sections into Room (stores written before v3.1). The original file
     * is kept as `*.pre-room`. Merging over what Room already has makes a retry after an
     * interrupted migration harmless. The sections are removed from the JSON only after Room commits.
     */
    private fun migrateJsonSectionsIfNeeded(context: Context) {
        val root = root(context)
        if (ROOM_SECTIONS.none(root::has)) return

        val file = storeFile(context)
        val backup = File(file.parentFile, "$FILE_NAME.pre-room")
        if (!backup.exists()) writeAtomically(backup, root.toString())

        val legacy = StoreCodec.readData(root)
        onIoThread(context) { db -> RoomStore.replaceAll(db, RoomStore.load(db).mergedOver(legacy)) }

        ROOM_SECTIONS.forEach(root::remove)
        pendingSnapshot.set(null)
        onIoThread(context) { writeAtomically(file, root.toString(), keepPrevious = true) }
    }

    private fun scheduleSurfaceRefresh(context: Context, notifyInApp: Boolean) {
        val appCtx = context.applicationContext
        refreshContext = appCtx
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, SURFACE_REFRESH_DEBOUNCE_MS)

        if (notifyInApp) {
            try {
                appCtx.sendBroadcast(Intent(ACTION_SESSION_SYNC).setPackage(appCtx.packageName))
            } catch (_: Exception) {}
        }
    }

    /** Serialized: runs on [surfaceExecutor], or on the caller's thread from [flush]. */
    private fun refreshSurfacesNow(appCtx: Context) = synchronized(surfaceLock) {
        GymWidgetProvider.refreshAllWidgets(appCtx)
        MuscleFatigueWidgetProvider.refreshAll(appCtx)
        SquarePulseWidgetProvider.refreshAll(appCtx)
        BodyWeightWidgetProvider.refreshAll(appCtx)
        LockScreenWorkoutController.sync(appCtx)
        WatchSync.publish(appCtx)
    }

    /** Drops in-memory state and closes the database so a test can start from the files on disk. */
    @VisibleForTesting
    internal fun resetForTests() {
        ioExecutor.submit {}.get()
        synchronized(lock) {
            cachedRoot = null
            cachedData = null
            pendingSnapshot.set(null)
            GymDatabase.closeForTests()
        }
    }

    /** Redraws widgets now and blocks until pending writes reach disk. */
    fun flush(context: Context) {
        mainHandler.removeCallbacks(refreshRunnable)
        refreshSurfacesNow(context.applicationContext)
        try {
            ioExecutor.submit {}.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    // --- Profile & body weight ---

    fun loadUserProfile(context: Context): UserProfile {
        // The profile names a program that may be one of the user's own: make sure they're loaded.
        data(context)
        val obj = root(context).optJSONObject("userProfile") ?: return UserProfile()
        fun <T : Enum<T>> enumOf(values: List<T>, key: String): T? = obj.optString(key, "").let { v -> values.find { it.name == v } }
        val limits = obj.optJSONArray("limits")
        return UserProfile(
            hasCompletedOnboarding = obj.optBoolean("hasCompletedOnboarding", false),
            age = obj.optInt("age", 0),
            currentWeightKg = obj.optDouble("currentWeightKg", 0.0).toFloat(),
            splitProgram = obj.optString("splitProgram", "PPL_ARNOLD"),
            experience = enumOf(Experience.entries, "experience"),
            goal = enumOf(TrainingGoal.entries, "goal"),
            equipment = enumOf(Equipment.entries, "equipment"),
            daysPerWeek = obj.optInt("daysPerWeek", 0),
            sessionMinutes = obj.optInt("sessionMinutes", 0),
            focus = enumOf(Focus.entries, "focus"),
            limits = (0 until (limits?.length() ?: 0)).mapNotNull { i -> Limit.entries.find { it.name == limits!!.optString(i) } }.toSet(),
            skipMuscles = obj.optJSONArray("skipMuscles").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it in BUILDER_MUSCLES }.toSet() },
            trainingAsked = obj.optBoolean("trainingAsked", false)
        ).also { TrainingProfile.current = it }
    }

    fun saveUserProfile(context: Context, profile: UserProfile) = edit(context) { root ->
        root.put("userProfile", JSONObject().apply {
            put("hasCompletedOnboarding", profile.hasCompletedOnboarding)
            put("age", profile.age)
            put("currentWeightKg", profile.currentWeightKg.toDouble())
            put("splitProgram", profile.splitProgram)
            profile.experience?.let { put("experience", it.name) }
            profile.goal?.let { put("goal", it.name) }
            profile.equipment?.let { put("equipment", it.name) }
            if (profile.daysPerWeek > 0) put("daysPerWeek", profile.daysPerWeek)
            if (profile.sessionMinutes > 0) put("sessionMinutes", profile.sessionMinutes)
            profile.focus?.let { put("focus", it.name) }
            if (profile.limits.isNotEmpty()) put("limits", JSONArray(profile.limits.map { it.name }))
            if (profile.skipMuscles.isNotEmpty()) put("skipMuscles", JSONArray(profile.skipMuscles.toList()))
            if (profile.trainingAsked) put("trainingAsked", true)
        })
        TrainingProfile.current = profile
    }

    /** Newest first. */
    fun loadWeightHistory(context: Context): List<WeightLogEntry> = data(context).weights

    fun addWeightLog(context: Context, weightKg: Float): List<WeightLogEntry> {
        val now = Date()
        // 0.01 kg keeps pound entries exact (180 lb → 81.65 kg → 180 lb).
        val rounded = (weightKg * 100f).roundToInt() / 100f
        val entry = WeightLogEntry(
            id = System.currentTimeMillis(),
            dateKey = DateUtils.formatDayKey(now),
            displayDate = DateUtils.formatDisplayDate(now),
            weightKg = rounded
        )
        editData(context, { it.copy(weights = listOf(entry) + it.weights) }) { RoomStore.addBodyWeight(it, entry) }
        edit(context) { root ->
            val profObj = root.optJSONObject("userProfile") ?: JSONObject().also { root.put("userProfile", it) }
            profObj.put("currentWeightKg", rounded.toDouble())
        }
        HealthSync.weightLogged(context, entry)
        return loadWeightHistory(context)
    }

    /**
     * Weigh-ins read from Health Connect (scales, Samsung Health). Unlike [addWeightLog] they keep
     * their own time and aren't written back. Entries already logged, or imported before (even if
     * deleted since, so a deleted weigh-in doesn't come back), are skipped.
     */
    fun importWeights(context: Context, entries: List<WeightLogEntry>): List<WeightLogEntry> {
        val known = loadWeightHistory(context).map { it.id }.toSet() + importedWeightIds(context)
        val fresh = entries.filter { it.id !in known }.distinctBy { it.id }
        if (fresh.isEmpty()) return loadWeightHistory(context)
        edit(context) { root ->
            val arr = root.optJSONArray("healthImportedWeights") ?: JSONArray().also { root.put("healthImportedWeights", it) }
            fresh.forEach { arr.put(it.id) }
        }
        editData(context, { d -> d.copy(weights = (d.weights + fresh).sortedByDescending { it.id }) }) { db ->
            fresh.forEach { RoomStore.addBodyWeight(db, it) }
        }
        // The profile shows the latest weigh-in, whoever logged it.
        val newest = loadWeightHistory(context).first()
        if (fresh.any { it.id == newest.id }) edit(context) { root ->
            val profObj = root.optJSONObject("userProfile") ?: JSONObject().also { root.put("userProfile", it) }
            profObj.put("currentWeightKg", newest.weightKg.toDouble())
        }
        return loadWeightHistory(context)
    }

    /** Undo of [deleteWeightLog]. A weigh-in that came from Health Connect isn't written back to it. */
    fun restoreWeightLog(context: Context, entry: WeightLogEntry): List<WeightLogEntry> {
        editData(context, { d -> d.copy(weights = (d.weights.filter { it.id != entry.id } + entry).sortedByDescending { it.id }) }) {
            RoomStore.addBodyWeight(it, entry)
        }
        if (entry.id !in importedWeightIds(context)) HealthSync.weightLogged(context, entry)
        if (loadWeightHistory(context).maxByOrNull { it.id }?.id == entry.id) setProfileWeight(context, entry.weightKg)
        return loadWeightHistory(context)
    }

    fun deleteWeightLog(context: Context, id: Long): List<WeightLogEntry> {
        val wasNewest = loadWeightHistory(context).maxByOrNull { it.id }?.id == id
        editData(context, { d -> d.copy(weights = d.weights.filter { it.id != id }) }) { RoomStore.deleteBodyWeight(it, id) }
        HealthSync.weightDeleted(context, id)
        // The profile weight is the latest weigh-in, so it goes back to the one before.
        val newest = loadWeightHistory(context).maxByOrNull { it.id }
        if (wasNewest && newest != null) setProfileWeight(context, newest.weightKg)
        return loadWeightHistory(context)
    }

    private fun setProfileWeight(context: Context, kg: Float) = edit(context) { root ->
        val profObj = root.optJSONObject("userProfile") ?: JSONObject().also { root.put("userProfile", it) }
        profObj.put("currentWeightKg", kg.toDouble())
    }

    /**
     * Older builds let Profile change the weight without logging a weigh-in, so the profile and
     * the weigh-in log could disagree. Once, log the profile weight so both say the same thing.
     */
    fun reconcileProfileWeight(context: Context) {
        if (root(context).optBoolean("profileWeightReconciled", false)) return
        val profileKg = loadUserProfile(context).currentWeightKg
        val newest = loadWeightHistory(context).maxByOrNull { it.id }
        if (profileKg > 0f && newest != null && abs(profileKg - newest.weightKg) >= 0.01f) addWeightLog(context, profileKg)
        edit(context) { root -> root.put("profileWeightReconciled", true) }
    }

    // --- Widget settings (one entry per placed widget) ---

    /** A workout's own note ("felt strong", "left shoulder"), by workout id; "" when none. Kept in the JSON store, so backups carry it. */
    fun getWorkoutNote(context: Context, workoutId: Long): String =
        root(context).optJSONObject("workoutNotes")?.optString(workoutId.toString()).orEmpty()

    fun setWorkoutNote(context: Context, workoutId: Long, note: String) = edit(context) { root ->
        val all = root.optJSONObject("workoutNotes") ?: JSONObject().also { root.put("workoutNotes", it) }
        if (note.isBlank()) all.remove(workoutId.toString()) else all.put(workoutId.toString(), note.trim())
    }

    fun loadWidgetSettings(context: Context, appWidgetId: Int, kind: String): WidgetSettings {
        val obj = root(context).optJSONObject("widgetSettings")?.optJSONObject(appWidgetId.toString())
            ?: return defaultWidgetSettings(kind)
        val optionsArr = obj.optJSONArray("options")
        return WidgetSettings(
            theme = obj.optString("theme", THEME_SYSTEM),
            opacityPercent = obj.optInt("opacityPercent", 100).coerceIn(0, 100),
            style = obj.optString("style", WIDGET_STYLE_STANDARD),
            options = optionsArr?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                ?: defaultWidgetSettings(kind).options
        )
    }

    fun saveWidgetSettings(context: Context, appWidgetId: Int, settings: WidgetSettings) = edit(context) { root ->
        val all = root.optJSONObject("widgetSettings") ?: JSONObject().also { root.put("widgetSettings", it) }
        all.put(appWidgetId.toString(), JSONObject().apply {
            put("theme", settings.theme)
            put("opacityPercent", settings.opacityPercent.coerceIn(0, 100))
            put("style", settings.style)
            put("options", JSONArray(settings.options.toList()))
        })
    }

    fun deleteWidgetSettings(context: Context, appWidgetIds: IntArray) = edit(context) { root ->
        val all = root.optJSONObject("widgetSettings") ?: return@edit
        appWidgetIds.forEach { all.remove(it.toString()) }
    }

    // --- App settings ---

    fun loadSettings(context: Context): AppSettings {
        val obj = root(context).optJSONObject("settings") ?: return AppSettings()
        // Older versions stored one of four named themes; map them onto light/dark.
        val theme = when (val stored = obj.optString("themeChoice", THEME_SYSTEM)) {
            THEME_SYSTEM, THEME_LIGHT, THEME_DARK -> stored
            "ARCTIC" -> THEME_LIGHT
            else -> THEME_DARK
        }
        return AppSettings(
            themeChoice = theme,
            targetFps = obj.optInt("targetFps", 120),
            hapticsEnabled = obj.optBoolean("hapticsEnabled", true),
            weightStep = obj.optDouble("weightStepKg", 2.5).toFloat(), // key predates units; value is in weightUnit
            weightUnit = WeightUnit.fromKey(obj.optString("weightUnit")),
            restTimerSeconds = obj.optInt("restTimerSeconds", 90),
            keepScreenAwake = obj.optBoolean("keepScreenAwake", true),
            restSoundEnabled = obj.optBoolean("restSoundEnabled", true),
            trackEffort = obj.optBoolean("trackEffort", false),
            reminderDays = obj.optJSONArray("reminderDays")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it in 1..7 }.toSet() }.orEmpty(),
            reminderMinutes = obj.optInt("reminderMinutes", 18 * 60).coerceIn(0, 24 * 60 - 1),
            weighReminderOn = obj.optBoolean("weighReminderOn", false),
            weighReminderMinutes = obj.optInt("weighReminderMinutes", 7 * 60 + 30).coerceIn(0, 24 * 60 - 1)
        )
    }

    fun saveSettings(context: Context, settings: AppSettings) = edit(context) { root ->
        root.put("settings", JSONObject().apply {
            put("targetFps", settings.targetFps)
            put("hapticsEnabled", settings.hapticsEnabled)
            put("weightStepKg", settings.weightStep.toDouble())
            put("weightUnit", settings.weightUnit.name)
            put("restTimerSeconds", settings.restTimerSeconds)
            put("keepScreenAwake", settings.keepScreenAwake)
            put("restSoundEnabled", settings.restSoundEnabled)
            put("trackEffort", settings.trackEffort)
            put("reminderDays", org.json.JSONArray(settings.reminderDays.sorted()))
            put("reminderMinutes", settings.reminderMinutes)
            put("weighReminderOn", settings.weighReminderOn)
            put("weighReminderMinutes", settings.weighReminderMinutes)
            put("themeChoice", settings.themeChoice)
        })
        Units.set(settings.weightUnit)
    }

    // --- Plate calculator (bar and plates per unit, values in that unit) ---

    fun loadPlateSetup(context: Context, unit: WeightUnit): PlateSetup {
        val obj = root(context).optJSONObject("plateSetup")?.optJSONObject(unit.name) ?: return defaultPlateSetup(unit)
        val arr = obj.optJSONArray("plates") ?: JSONArray()
        return PlateSetup(
            barWeight = obj.optDouble("bar", defaultPlateSetup(unit).barWeight.toDouble()).toFloat(),
            plates = (0 until arr.length()).map { arr.optDouble(it).toFloat() }.filter { it > 0f }
        )
    }

    fun savePlateSetup(context: Context, unit: WeightUnit, setup: PlateSetup) = edit(context) { root ->
        val all = root.optJSONObject("plateSetup") ?: JSONObject().also { root.put("plateSetup", it) }
        all.put(unit.name, JSONObject().apply {
            put("bar", setup.barWeight.toDouble())
            put("plates", JSONArray().apply { setup.plates.forEach { put(it.toDouble()) } })
        })
    }

    // --- Health Connect ---

    /** Whether the user turned sharing on; Health Connect permissions are checked separately. */
    fun isHealthSyncOn(context: Context): Boolean = root(context).optBoolean("healthConnect", false)

    fun setHealthSyncOn(context: Context, on: Boolean) = edit(context) { root -> root.put("healthConnect", on) }

    /** Weigh-ins that came from Health Connect: never written back to it. */
    fun importedWeightIds(context: Context): Set<Long> {
        val arr = root(context).optJSONArray("healthImportedWeights") ?: return emptySet()
        return (0 until arr.length()).map { arr.optLong(it) }.toSet()
    }

    // --- Rest timer ---

    fun setRestEndTimeMillis(context: Context, endTimeMillis: Long, fromWidget: Boolean = false) {
        edit(context, notifyInApp = fromWidget) { root ->
            val activeObj = root.optJSONObject("activeSession") ?: return
            val now = System.currentTimeMillis()
            // A new rest starts the clock; extending a running one (+15 s) keeps its start.
            if (endTimeMillis <= 0L) activeObj.remove("restStartedMillis")
            else if (activeObj.optLong("restEndTimeMillis", 0L) <= now) activeObj.put("restStartedMillis", now)
            activeObj.put("restEndTimeMillis", endTimeMillis)
        }
        RestAlarmScheduler.scheduleOrCancel(context.applicationContext, endTimeMillis)
    }

    fun getRestEndTimeMillis(context: Context): Long =
        root(context).optJSONObject("activeSession")?.optLong("restEndTimeMillis", 0L) ?: 0L

    /** When the current rest began; 0 if not resting or it began before this was recorded. */
    fun getRestStartedMillis(context: Context): Long =
        root(context).optJSONObject("activeSession")?.optLong("restStartedMillis", 0L) ?: 0L

    // --- Backup ---

    /** Same single-file format as before Room, so old and new builds can read each other's backups. */
    fun exportJsonBackupString(context: Context): String = synchronized(lock) {
        val out = JSONObject(root(context).toString())
        StoreCodec.writeData(out, data(context))
        out.toString(2)
    }

    fun importJsonBackupString(context: Context, jsonContent: String): Boolean {
        val parsed = try {
            JSONObject(jsonContent.removePrefix("\uFEFF").trim())
        } catch (e: Exception) {
            return false
        }
        // Any other JSON file would read as "no data" and replace everything with nothing.
        if (!StoreCodec.looksLikeBackup(parsed)) return false
        try {
            savePreImportCopy(context)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save a copy before restoring", e)
            return false
        }
        val restored = StoreCodec.readData(parsed)
        ROOM_SECTIONS.forEach(parsed::remove)
        synchronized(lock) {
            try {
                cachedData = onIoThread(context) { db ->
                    RoomStore.replaceAll(db, restored)
                    RoomStore.load(db)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                cachedData = null
                return false
            }
            cachedRoot = parsed
            Units.set(unitIn(parsed))
            persist(context, parsed)
        }
        scheduleSurfaceRefresh(context, notifyInApp = false)
        return true
    }

    /**
     * Adds a Kilonaut backup to what's here instead of replacing it ([combineData]); settings,
     * profile and the running workout stay as they are. Null if [jsonContent] isn't a backup.
     */
    fun addBackupJsonString(context: Context, jsonContent: String): CombineResult? {
        val parsed = try {
            JSONObject(jsonContent.removePrefix("\uFEFF").trim())
        } catch (e: Exception) {
            return null
        }
        if (!StoreCodec.looksLikeBackup(parsed)) return null
        return addData(context, StoreCodec.readData(parsed))
    }

    /** Adds [incoming] (a backup or another app's export) to what's here; a copy is kept first. */
    fun addData(context: Context, incoming: StoreData): CombineResult? {
        try {
            savePreImportCopy(context)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save a copy before adding data", e)
            return null
        }
        val result: CombineResult
        synchronized(lock) {
            val (combined, summary) = combineData(data(context), incoming)
            try {
                cachedData = onIoThread(context) { db ->
                    RoomStore.replaceAll(db, combined)
                    RoomStore.load(db)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Adding data failed", e)
                cachedData = null
                return null
            }
            result = summary
        }
        scheduleSurfaceRefresh(context, notifyInApp = false)
        AutoBackup.request(context)
        return result
    }

    private fun savePreImportCopy(context: Context) {
        val dir = context.applicationContext.filesDir
        File(dir, "$PRE_IMPORT_PREFIX${System.currentTimeMillis()}.json").writeText(exportJsonBackupString(context))
        dir.listFiles { f -> f.name.startsWith(PRE_IMPORT_PREFIX) }.orEmpty()
            .sortedByDescending { it.name }
            .drop(PRE_IMPORT_KEEP)
            .forEach { it.delete() }
    }

    // --- Per-exercise notes & rest defaults ---

    fun getExerciseNote(context: Context, exerciseName: String): String =
        data(context).notes[exerciseKey(exerciseName)].orEmpty()

    fun saveExerciseNote(context: Context, exerciseName: String, note: String) {
        val key = exerciseKey(exerciseName)
        val trimmed = note.trim()
        editData(context, { d ->
            d.copy(notes = if (trimmed.isEmpty()) d.notes - key else d.notes + (key to trimmed))
        }) { RoomStore.setNote(it, exerciseName, trimmed) }
    }

    fun getExerciseDefaultRest(context: Context, exerciseName: String, fallback: Int): Int =
        data(context).restDefaults[exerciseKey(exerciseName)] ?: fallback

    fun saveExerciseDefaultRest(context: Context, exerciseName: String, restSec: Int) {
        editData(context, { d -> d.copy(restDefaults = d.restDefaults + (exerciseKey(exerciseName) to restSec)) }) {
            RoomStore.setDefaultRest(it, exerciseName, restSec)
        }
    }

    /** The exercise's type: its own setting, else the library's, else weight & reps. */
    fun exerciseType(context: Context, exerciseName: String): ExerciseType =
        data(context).types?.get(exerciseKey(exerciseName))
            ?: ExerciseDatabase.find(exerciseName)?.type
            ?: ExerciseType.WEIGHT_REPS

    /**
     * Changes how an exercise is logged. Logged numbers are kept as they are, so switching back
     * restores the old view exactly.
     */
    fun setExerciseType(context: Context, exerciseName: String, type: ExerciseType) {
        synchronized(lock) {
            data(context)
            cachedData = onIoThread(context) { db ->
                RoomStore.setType(db, exerciseName, type)
                RoomStore.load(db)
            }
        }
        scheduleSurfaceRefresh(context, notifyInApp = true)
    }

    /**
     * Renames an exercise in history, templates, notes and the active session.
     * Returns false if the new name is blank or already used by another exercise.
     */
    fun renameExercise(context: Context, oldName: String, newName: String): Boolean {
        synchronized(lock) {
            data(context)
            val renamed = onIoThread(context) { db -> RoomStore.renameExercise(db, oldName, newName) }
            if (!renamed) return false
            cachedData = onIoThread(context, RoomStore::load)
            loadActiveSession(context)?.let { (split, exercises) ->
                saveActiveSession(context, split, exercises.map {
                    if (exerciseKey(it.name) == exerciseKey(oldName)) it.copy(name = newName.trim()) else it
                })
            }
        }
        scheduleSurfaceRefresh(context, notifyInApp = false)
        return true
    }

    /**
     * Makes [fromName] part of [intoName] everywhere (history, day lists, settings, the running
     * workout) and removes [fromName]. A copy of the data is kept first, as a restore does.
     * Returns false if either exercise doesn't exist or they're the same one.
     */
    fun mergeExercise(context: Context, fromName: String, intoName: String): Boolean {
        synchronized(lock) {
            data(context)
            try {
                savePreImportCopy(context)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't save a copy before merging", e)
                return false
            }
            val merged = onIoThread(context) { db -> RoomStore.mergeExercise(db, fromName, intoName) }
            if (!merged) return false
            cachedData = onIoThread(context, RoomStore::load)
            // The kept exercise's spelling, as logged.
            val into = data(context).history.firstNotNullOfOrNull { w ->
                w.exercises.firstOrNull { exerciseKey(it.name) == exerciseKey(intoName) }?.name
            } ?: intoName.trim()
            loadActiveSession(context)?.let { (split, exercises) ->
                if (exercises.any { exerciseKey(it.name) == exerciseKey(fromName) }) {
                    saveActiveSession(context, split, mergeEntries(exercises, fromName, into))
                }
            }
        }
        scheduleSurfaceRefresh(context, notifyInApp = true)
        AutoBackup.request(context)
        return true
    }

    /** Pairs the user said aren't the same exercise, as sorted [exerciseKey]s joined by "|". */
    fun notDuplicates(context: Context): Set<String> {
        val arr = root(context).optJSONArray("notDuplicates") ?: return emptySet()
        return (0 until arr.length()).map { arr.optString(it) }.toSet()
    }

    fun markNotDuplicates(context: Context, a: String, b: String) = edit(context) { root ->
        val arr = root.optJSONArray("notDuplicates") ?: JSONArray().also { root.put("notDuplicates", it) }
        arr.put(duplicatePairKey(a, b))
    }

    // --- Programs ---

    /** The user's own programs (built-in ones are in [SPLIT_PROGRAMS]). */
    fun loadPrograms(context: Context): List<SplitProgramInfo> = data(context).programs

    /** Adds the program, or replaces the one with the same id, keeping its place in the list. */
    fun saveProgram(context: Context, program: SplitProgramInfo) {
        val current = data(context).programs
        val index = current.indexOfFirst { it.id == program.id }.takeIf { it >= 0 } ?: current.size
        val updated = if (index < current.size) current.toMutableList().also { it[index] = program } else current + program
        editData(context, { it.copy(programs = updated) }) { RoomStore.saveProgram(it, program, index) }
    }

    fun deleteProgram(context: Context, id: String) {
        editData(context, { d -> d.copy(programs = d.programs.filter { it.id != id }) }) { RoomStore.deleteProgram(it, id) }
    }

    /**
     * Renames a day of one of the user's programs. Its exercise list is copied if any other
     * program (built-in ones included) still uses the old title, else moved. Logged workouts move
     * with it unless another of the user's own programs still uses the old title: renaming
     * "Push" in a customized copy should keep its history, not leave it with the built-in preset.
     */
    fun renameSplit(context: Context, programId: String, oldTitle: String, newTitle: String) {
        synchronized(lock) {
            val program = data(context).programs.find { it.id == programId } ?: return
            val renamed = program.copy(splits = program.splits.map { if (it.title == oldTitle) it.copy(title = newTitle) else it })
            fun usedBy(programs: List<SplitProgramInfo>) = programs.any { p -> p.id != programId && p.splits.any { it.title == oldTitle } }
            val keepTemplate = usedBy(allPrograms())
            val keepHistory = usedBy(UserPrograms.all)
            saveProgram(context, renamed)
            cachedData = onIoThread(context) { db ->
                RoomStore.renameSplit(db, oldTitle, newTitle, keepTemplate, keepHistory)
                RoomStore.load(db)
            }
            if (!keepHistory) {
                loadActiveSession(context)?.let { (split, exercises) ->
                    if (split == oldTitle) saveActiveSession(context, newTitle, exercises)
                }
            }
        }
        scheduleSurfaceRefresh(context, notifyInApp = true)
    }

    // --- Split templates ---

    fun loadTemplateForSplit(context: Context, splitTitle: String): List<Pair<String, String>> =
        data(context).templates[splitTitle]
            ?: builtInDay(splitTitle).also { saveTemplateForSplit(context, splitTitle, it) }

    /** The exercises a day of this name already has (saved or built-in), without saving anything; null if none. */
    fun existingTemplateForSplit(context: Context, splitTitle: String): List<Pair<String, String>>? =
        (data(context).templates[splitTitle] ?: builtInDay(splitTitle)).takeIf { it.isNotEmpty() }

    /** A built-in day's exercises, fitted to the setup answers. */
    private fun builtInDay(splitTitle: String) = personalize(defaultTemplateForSplit(splitTitle), TrainingProfile.current)

    /**
     * Saves the plan made for [profile] ([planWeek]) under its fixed id, replacing an earlier one,
     * with each exercise's planned sets and reps as its target. A day whose name is taken by a day
     * the user saved with something else gets a new name instead of overwriting it.
     */
    fun saveGeneratedPlan(context: Context, profile: UserProfile): SplitProgramInfo? {
        val plan = generatedPlan(profile) ?: return null
        val week = planWeek(profile)
        val saved = data(context).templates
        val previous = data(context).programs.find { it.id == RECOMMENDED_PLAN_ID }?.splits.orEmpty().map { it.title }.toSet()
        val used = HashSet<String>()
        val days = plan.splits.mapIndexed { i, day ->
            val planned = week[i].exercises
            val exercises = planned.map { it.name to it.muscle }
            var title = day.title
            var n = 2
            // A day of the user's own (not an earlier version of this plan) keeps its name and exercises.
            while (!used.add(title.lowercase(Locale.US)) ||
                (title !in previous && saved[title] != null && saved[title] != exercises && saved[title] != builtInDay(title))
            ) title = "${day.title} ${n++}"
            saveTemplateForSplit(context, title, exercises)
            planned.forEach { setTarget(context, title, it.name, it.target) }
            day.copy(title = title)
        }
        val program = plan.copy(splits = days)
        saveProgram(context, program)
        return program
    }

    /**
     * After the setup answers change: built-in days nobody has edited (still exactly what the old
     * answers gave) are fitted to the new ones. Days the user changed are left alone.
     */
    fun refitBuiltInDays(context: Context, old: UserProfile, new: UserProfile) {
        val templates = data(context).templates
        val titles = SPLIT_PROGRAMS.values.flatMap { p -> p.splits.map { it.title } }.distinct()
        for (title in titles) {
            val saved = templates[title] ?: continue
            val base = defaultTemplateForSplit(title)
            if (base.isEmpty()) continue
            if (saved == base || saved == personalize(base, old)) {
                val fitted = personalize(base, new)
                if (fitted != saved) saveTemplateForSplit(context, title, fitted)
            }
        }
    }

    /** Replaces a day's exercise list. Targets of exercises that stay on it are kept. */
    fun saveTemplateForSplit(context: Context, splitTitle: String, list: List<Pair<String, String>>) {
        val keys = list.mapTo(HashSet()) { exerciseKey(it.first) }
        val kept = data(context).targets[splitTitle].orEmpty().filterKeys { it in keys }
        editData(context, { it.copy(templates = it.templates + (splitTitle to list), targets = it.targets + (splitTitle to kept)) }) {
            RoomStore.saveTemplate(it, splitTitle, list, kept)
        }
    }

    fun getTrainingMax(context: Context, exerciseName: String): TrainingMax? =
        data(context).trainingMaxes[exerciseKey(exerciseName)]

    /**
     * Sets (or clears) a lift's training max. Setting it starts its program over at week 1,
     * because the week is counted from sessions after this moment.
     */
    fun setTrainingMax(context: Context, exerciseName: String, kg: Float?) {
        val key = exerciseKey(exerciseName)
        val tm = kg?.let { TrainingMax(it, System.currentTimeMillis()) }
        editData(context, { d -> d.copy(trainingMaxes = if (tm == null) d.trainingMaxes - key else d.trainingMaxes + (key to tm)) }) {
            RoomStore.setTrainingMax(it, exerciseName, tm)
        }
    }

    fun getTarget(context: Context, splitTitle: String, exerciseName: String): ExerciseTarget? =
        data(context).targets[splitTitle]?.get(exerciseKey(exerciseName))

    /** Sets or clears ([target] null) an exercise's target on a day. */
    fun setTarget(context: Context, splitTitle: String, exerciseName: String, target: ExerciseTarget?) {
        val list = loadTemplateForSplit(context, splitTitle)
        val key = exerciseKey(exerciseName)
        val current = data(context).targets[splitTitle].orEmpty()
        val updated = if (target == null) current - key else current + (key to target)
        editData(context, { it.copy(targets = it.targets + (splitTitle to updated)) }) {
            RoomStore.saveTemplate(it, splitTitle, list, updated)
        }
    }

    fun addExerciseToSplitTemplate(context: Context, splitTitle: String, name: String, muscle: String = "Custom") {
        val current = loadTemplateForSplit(context, splitTitle)
        if (current.none { it.first.equals(name, ignoreCase = true) }) {
            saveTemplateForSplit(context, splitTitle, current + (name to muscle))
        }
    }

    /** A day's exercise list and targets as they are now, for [restoreDay] (Undo). */
    class DaySnapshot internal constructor(
        internal val splitTitle: String,
        internal val list: List<Pair<String, String>>,
        internal val targets: Map<String, ExerciseTarget>
    )

    fun daySnapshot(context: Context, splitTitle: String) =
        DaySnapshot(splitTitle, loadTemplateForSplit(context, splitTitle), data(context).targets[splitTitle].orEmpty())

    // Not through saveTemplateForSplit: that drops the targets of exercises missing from the list.
    fun restoreDay(context: Context, day: DaySnapshot) {
        editData(context, { it.copy(templates = it.templates + (day.splitTitle to day.list), targets = it.targets + (day.splitTitle to day.targets)) }) {
            RoomStore.saveTemplate(it, day.splitTitle, day.list, day.targets)
        }
    }

    fun removeExerciseFromSplitTemplate(context: Context, splitTitle: String, name: String) {
        val current = loadTemplateForSplit(context, splitTitle)
        saveTemplateForSplit(context, splitTitle, current.filterNot { it.first.equals(name, ignoreCase = true) })
    }

    // --- Active session ---

    fun saveActiveSession(context: Context, splitTitle: String?, exercises: List<ExerciseEntry>, fromWidget: Boolean = false) {
        edit(context, notifyInApp = fromWidget) { root ->
            if (splitTitle == null) {
                root.remove("activeSession")
            } else {
                val prevObj = root.optJSONObject("activeSession")
                val now = System.currentTimeMillis()
                val serialized = StoreCodec.serializeExercises(exercises)
                // Saves that change nothing (a reload, a rename elsewhere) aren't activity.
                val changed = prevObj == null || prevObj.optJSONArray("exercises")?.toString() != serialized.toString()
                root.put("activeSession", JSONObject().apply {
                    put("splitTitle", splitTitle)
                    put("startTimeMillis", prevObj?.optLong("startTimeMillis", 0L)?.takeIf { it > 0L } ?: now)
                    put("restEndTimeMillis", prevObj?.optLong("restEndTimeMillis", 0L) ?: 0L)
                    prevObj?.optLong("restStartedMillis", 0L)?.takeIf { it > 0L }?.let { put("restStartedMillis", it) }
                    put("lastActivityMillis", if (changed) now else prevObj?.optLong("lastActivityMillis", 0L) ?: now)
                    put("exercises", serialized)
                })
            }
        }
        if (splitTitle == null) RestAlarmScheduler.scheduleOrCancel(context.applicationContext, 0L)
    }

    /** The running workout's start, or null with none (unlike [getActiveSessionStartTime], never "now"). */
    fun activeSessionStartedAt(context: Context): Long? =
        root(context).optJSONObject("activeSession")?.optLong("startTimeMillis", 0L)?.takeIf { it > 0L }

    fun getActiveSessionStartTime(context: Context): Long =
        root(context).optJSONObject("activeSession")?.optLong("startTimeMillis", 0L)?.takeIf { it > 0L }
            ?: System.currentTimeMillis()

    /**
     * When the workout last changed (a set ticked, a number typed, an exercise added). Sessions
     * saved before this was recorded fall back to their start time.
     */
    fun getActiveSessionLastActivity(context: Context): Long {
        val obj = root(context).optJSONObject("activeSession") ?: return 0L
        return obj.optLong("lastActivityMillis", 0L).takeIf { it > 0L } ?: obj.optLong("startTimeMillis", 0L)
    }

    /** "Still training": counts as activity, so the forgotten-workout question waits again. */
    fun touchActiveSession(context: Context) = edit(context) { root ->
        root.optJSONObject("activeSession")?.put("lastActivityMillis", System.currentTimeMillis())
    }

    fun loadActiveSession(context: Context): Pair<String, List<ExerciseEntry>>? {
        val activeObj = root(context).optJSONObject("activeSession") ?: return null
        val splitTitle = activeObj.optString("splitTitle", "")
        if (splitTitle.isBlank()) return null
        // Types come from the exercise definitions, so a change of type shows up mid-workout too.
        return splitTitle to StoreCodec.deserializeExercises(activeObj.optJSONArray("exercises"))
            .map { it.copy(type = exerciseType(context, it.name)) }
    }

    // --- Workout history ---

    /** Saves the workout and ends the active session; the workout reaches disk first. */
    fun saveCompletedWorkout(context: Context, workout: CompletedWorkout, fromWidget: Boolean = false) {
        editData(context, { d ->
            d.copy(history = (listOf(workout) + d.history.filter { it.id != workout.id }).sortedByDescending { it.id })
        }) { RoomStore.insertWorkout(it, workout) }
        edit(context, notifyInApp = fromWidget) { root -> root.remove("activeSession") }
        RestAlarmScheduler.scheduleOrCancel(context.applicationContext, 0L)
        HealthSync.workoutSaved(context, workout)
        AutoBackup.request(context)
    }

    fun updateCompletedWorkout(context: Context, updatedWorkout: CompletedWorkout) {
        editData(context, { d ->
            d.copy(history = d.history.map { if (it.id == updatedWorkout.id) updatedWorkout else it })
        }) { RoomStore.insertWorkout(it, updatedWorkout) }
        HealthSync.workoutSaved(context, updatedWorkout)
        AutoBackup.request(context)
    }

    /** Undo of [deleteWorkoutFromHistory]: puts the same workout back, leaving any running one alone. */
    fun restoreWorkout(context: Context, workout: CompletedWorkout) {
        editData(context, { d ->
            d.copy(history = (d.history.filter { it.id != workout.id } + workout).sortedByDescending { it.id })
        }) { RoomStore.insertWorkout(it, workout) }
        HealthSync.workoutSaved(context, workout)
        AutoBackup.request(context)
    }

    fun deleteWorkoutFromHistory(context: Context, workoutId: Long) {
        editData(context, { d -> d.copy(history = d.history.filter { it.id != workoutId }) }) {
            RoomStore.deleteWorkout(it, workoutId)
        }
        HealthSync.workoutDeleted(context, workoutId)
        AutoBackup.request(context)
    }

    /** Newest first. The same list instance is returned until history changes, so callers can use it as a stable key. */
    fun loadHistory(context: Context): List<CompletedWorkout> = data(context).history
}

/** Entries in this copy win over [base] (matched by id or key). */
fun StoreData.mergedOver(base: StoreData): StoreData {
    val historyIds = history.mapTo(HashSet()) { it.id }
    val weightIds = weights.mapTo(HashSet()) { it.id }
    return StoreData(
        history = (history + base.history.filter { it.id !in historyIds }).sortedByDescending { it.id },
        weights = (weights + base.weights.filter { it.id !in weightIds }).sortedByDescending { it.id },
        templates = base.templates + templates,
        notes = base.notes + notes,
        restDefaults = base.restDefaults + restDefaults,
        // Unknown on either side (data from before types) means the merge can't know them either.
        types = types?.let { mine -> base.types?.let { it + mine } },
        programs = programs + base.programs.filter { b -> programs.none { it.id == b.id } },
        targets = base.targets + targets,
        trainingMaxes = base.trainingMaxes + trainingMaxes
    )
}

/**
 * Pure JSON <-> model mapping for the store file, kept free of Context so it can be unit-tested
 * and reused by data migrations. Reading is lenient: older saves and hand-edited backups must still load.
 */
internal object StoreCodec {
    /** Every export has a `history` array; store files from before history existed always have a profile. */
    fun looksLikeBackup(root: JSONObject): Boolean =
        if (root.has("history")) root.optJSONArray("history") != null
        else root.optJSONObject("userProfile") != null

    fun parseHistory(arr: JSONArray?): List<CompletedWorkout> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val skippedJson = obj.optJSONArray("skippedExercises")
            CompletedWorkout(
                id = obj.optLong("id"),
                splitTitle = obj.optString("splitTitle"),
                dateKey = obj.optString("dateKey"),
                displayDate = obj.optString("displayDate"),
                durationSeconds = obj.optLong("durationSeconds", 0L),
                bodyWeightKg = obj.optDouble("bodyWeightKg", 0.0).toFloat(),
                photoPath = obj.optString("photoPath", "").ifBlank { null },
                skippedExercises = skippedJson?.let { s -> (0 until s.length()).map { s.optString(it) } }.orEmpty(),
                exercises = deserializeExercises(obj.optJSONArray("exercises")),
                totalVolumeKg = obj.optInt("totalVolumeKg", 0),
                prsHit = obj.optInt("prsHit", 0)
            )
        }.sortedByDescending { it.id }
    }

    fun serializeWorkout(workout: CompletedWorkout) = JSONObject().apply {
        put("id", workout.id)
        put("splitTitle", workout.splitTitle)
        put("dateKey", workout.dateKey)
        put("displayDate", workout.displayDate)
        put("durationSeconds", workout.durationSeconds)
        put("bodyWeightKg", workout.bodyWeightKg.toDouble())
        put("photoPath", workout.photoPath ?: "")
        put("skippedExercises", JSONArray(workout.skippedExercises))
        put("exercises", serializeExercises(workout.exercises))
        put("totalVolumeKg", workout.totalVolumeKg)
        put("prsHit", workout.prsHit)
    }

    fun serializeExercises(exercises: List<ExerciseEntry>): JSONArray = JSONArray().apply {
        for (ex in exercises) {
            put(JSONObject().apply {
                put("name", ex.name)
                put("targetMuscle", ex.targetMuscle)
                put("restSeconds", ex.restSeconds)
                put("isSkipped", ex.isSkipped)
                put("isSuperset", ex.isSuperset)
                ex.planNote?.let { put("planNote", it) }
                if (ex.programmed) put("programmed", true)
                ex.target?.let { t ->
                    put("targetSets", t.sets)
                    put("repMin", t.repMin)
                    put("repMax", t.repMax)
                }
                put("sets", JSONArray().apply {
                    for (s in ex.sets) {
                        put(JSONObject().apply {
                            put("setNumber", s.setNumber)
                            put("weight", s.weight.replace("kg", "", ignoreCase = true).trim())
                            put("reps", s.reps.replace("r", "", ignoreCase = true).trim())
                            put("isWarmup", s.isWarmup)
                            put("intensityType", s.intensityType)
                            put("isCompleted", s.isCompleted)
                            put("restSeconds", s.restSeconds)
                            if (s.seconds.isNotEmpty()) put("seconds", s.seconds)
                            if (s.distanceM.isNotEmpty()) put("distanceM", s.distanceM)
                            if (s.rir >= 0) put("rir", s.rir)
                        })
                    }
                })
            })
        }
    }

    fun deserializeExercises(arr: JSONArray?): List<ExerciseEntry> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val exObj = arr.getJSONObject(i)
            val exRest = exObj.optInt("restSeconds", 90)
            val setsArr = exObj.optJSONArray("sets") ?: JSONArray()
            val sets = (0 until setsArr.length()).map { j ->
                val sObj = setsArr.getJSONObject(j)
                // Older saves only had isWarmup; intensityType was added later.
                val legacyWarmup = sObj.optBoolean("isWarmup", false)
                val type = sObj.optString("intensityType", if (legacyWarmup) "WARMUP" else "NORMAL")
                SetEntry(
                    setNumber = sObj.optInt("setNumber", j + 1),
                    weight = (sObj.opt("weight")?.toString() ?: "20").replace("kg", "", ignoreCase = true).trim(),
                    reps = (sObj.opt("reps")?.toString() ?: "8").replace("r", "", ignoreCase = true).trim(),
                    isWarmup = legacyWarmup || type == "WARMUP",
                    intensityType = type,
                    isCompleted = sObj.optBoolean("isCompleted", false),
                    restSeconds = sObj.optInt("restSeconds", exRest),
                    seconds = sObj.optString("seconds", ""),
                    distanceM = sObj.optString("distanceM", ""),
                    rir = sObj.optInt("rir", -1).coerceIn(-1, 4)
                )
            }
            ExerciseEntry(
                name = exObj.optString("name", ""),
                targetMuscle = exObj.optString("targetMuscle", ""),
                restSeconds = exRest,
                isSkipped = exObj.optBoolean("isSkipped", false),
                sets = sets,
                isSuperset = exObj.optBoolean("isSuperset", false),
                planNote = exObj.optString("planNote").ifBlank { null },
                programmed = exObj.optBoolean("programmed", false),
                target = if (exObj.has("targetSets")) {
                    ExerciseTarget(exObj.optInt("targetSets", 3), exObj.optInt("repMin", 8), exObj.optInt("repMax", 12))
                } else null
            )
        }
    }

    // --- Sections stored in Room (still part of the backup format) ---

    fun readData(root: JSONObject): StoreData = StoreData(
        history = parseHistory(root.optJSONArray("history")),
        weights = parseWeights(root.optJSONArray("weightHistory")),
        templates = root.optJSONObject("splitTemplates")?.let { obj ->
            obj.keys().asSequence().associateWith { split ->
                val arr = obj.optJSONArray(split) ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                    item.optString("name").takeIf { it.isNotBlank() }?.let { it to item.optString("muscle") }
                }
            }
        }.orEmpty(),
        notes = root.optJSONObject("exerciseNotes")?.let { obj ->
            obj.keys().asSequence().associate { exerciseKey(it) to obj.optString(it).trim() }.filterValues { it.isNotEmpty() }
        }.orEmpty(),
        restDefaults = root.optJSONObject("exerciseRestDefaults")?.let { obj ->
            obj.keys().asSequence().associate { exerciseKey(it) to obj.optInt(it, 90) }
        }.orEmpty(),
        // Missing in files from before exercise types: null tells Room to work them out safely.
        types = root.optJSONObject("exerciseTypes")?.let { obj ->
            obj.keys().asSequence().associate { exerciseKey(it) to ExerciseType.fromKey(obj.optString(it)) }
        },
        programs = parsePrograms(root.optJSONArray("programs")),
        trainingMaxes = root.optJSONObject("trainingMaxes")?.let { obj ->
            obj.keys().asSequence().mapNotNull { key ->
                val t = obj.optJSONObject(key) ?: return@mapNotNull null
                exerciseKey(key) to TrainingMax(t.optDouble("kg", 0.0).toFloat(), t.optLong("setAt", 0L))
            }.filter { it.second.kg > 0f }.toMap()
        }.orEmpty(),
        targets = root.optJSONObject("splitTargets")?.let { days ->
            days.keys().asSequence().associateWith { day ->
                val obj = days.optJSONObject(day) ?: JSONObject()
                obj.keys().asSequence().mapNotNull { key ->
                    val t = obj.optJSONObject(key) ?: return@mapNotNull null
                    exerciseKey(key) to ExerciseTarget(t.optInt("sets", 3), t.optInt("repMin", 8), t.optInt("repMax", 12))
                }.toMap()
            }
        }.orEmpty()
    )

    fun parsePrograms(arr: JSONArray?): List<SplitProgramInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val splitsArr = o.optJSONArray("splits") ?: JSONArray()
            SplitProgramInfo(
                id = id,
                name = o.optString("name", "My program"),
                tagline = o.optString("tagline", ""),
                weeklyGoal = o.optInt("weeklyGoal", 3).coerceIn(1, 7),
                splits = (0 until splitsArr.length()).mapNotNull { j ->
                    val s = splitsArr.optJSONObject(j) ?: return@mapNotNull null
                    s.optString("title").takeIf { it.isNotBlank() }?.let { WorkoutSplit(it, s.optString("subtitle"), "%02d".format(j + 1)) }
                }
            )
        }
    }

    fun writeData(root: JSONObject, data: StoreData) {
        root.put("history", JSONArray().apply { data.history.forEach { put(serializeWorkout(it)) } })
        root.put("weightHistory", JSONArray().apply {
            data.weights.forEach { w ->
                put(JSONObject().apply {
                    put("id", w.id)
                    put("dateKey", w.dateKey)
                    put("displayDate", w.displayDate)
                    put("weightKg", w.weightKg.toDouble())
                })
            }
        })
        root.put("splitTemplates", JSONObject().apply {
            data.templates.forEach { (split, items) ->
                put(split, JSONArray().apply {
                    items.forEach { (name, muscle) -> put(JSONObject().put("name", name).put("muscle", muscle)) }
                })
            }
        })
        root.put("exerciseNotes", JSONObject().apply { data.notes.forEach { (k, v) -> put(k, v) } })
        root.put("exerciseRestDefaults", JSONObject().apply { data.restDefaults.forEach { (k, v) -> put(k, v) } })
        data.types?.let { types -> root.put("exerciseTypes", JSONObject().apply { types.forEach { (k, v) -> put(k, v.name) } }) }
        root.put("trainingMaxes", JSONObject().apply {
            data.trainingMaxes.forEach { (key, tm) -> put(key, JSONObject().put("kg", tm.kg.toDouble()).put("setAt", tm.setAtMillis)) }
        })
        root.put("splitTargets", JSONObject().apply {
            data.targets.forEach { (day, byExercise) ->
                put(day, JSONObject().apply {
                    byExercise.forEach { (key, t) -> put(key, JSONObject().put("sets", t.sets).put("repMin", t.repMin).put("repMax", t.repMax)) }
                })
            }
        })
        root.put("programs", JSONArray().apply {
            data.programs.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("tagline", p.tagline)
                    put("weeklyGoal", p.weeklyGoal)
                    put("splits", JSONArray().apply {
                        p.splits.forEach { s -> put(JSONObject().put("title", s.title).put("subtitle", s.subtitle)) }
                    })
                })
            }
        })
    }

    fun parseWeights(arr: JSONArray?): List<WeightLogEntry> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WeightLogEntry(
                id = o.optLong("id"),
                dateKey = o.optString("dateKey"),
                displayDate = o.optString("displayDate"),
                weightKg = o.optDouble("weightKg", 0.0).toFloat()
            )
        }.sortedByDescending { it.id }
    }
}

/**
 * A list of today's exercises after merging [fromName] into [intoName]: [fromName]'s entry takes
 * the name, or, when both are in the list, [intoName]'s entry gets [fromName]'s sets after its own.
 */
internal fun mergeEntries(exercises: List<ExerciseEntry>, fromName: String, intoName: String): List<ExerciseEntry> {
    val from = exercises.firstOrNull { exerciseKey(it.name) == exerciseKey(fromName) } ?: return exercises
    val into = exercises.firstOrNull { exerciseKey(it.name) == exerciseKey(intoName) }
        ?: return exercises.map { if (it === from) it.copy(name = intoName) else it }
    val combined = into.copy(sets = (into.sets + from.sets).mapIndexed { i, set -> set.copy(setNumber = i + 1) })
    return exercises.filter { it !== from }.map { if (it === into) combined else it }
}

internal fun duplicatePairKey(a: String, b: String): String = listOf(exerciseKey(a), exerciseKey(b)).sorted().joinToString("|")
