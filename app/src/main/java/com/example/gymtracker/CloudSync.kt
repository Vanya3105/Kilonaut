package com.example.gymtracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ============================================================================
// CLOUD SYNC: a copy of the phone's data in the account (Firestore)
// ============================================================================
// The phone stays the source of truth; the account holds a copy that other phones merge in.
//   users/{uid}/workouts/{id}   one per workout (with its note)
//   users/{uid}/weighins/{id}   one per weigh-in
//   users/{uid}/state/plan      programs, days, exercise notes, profile and settings
//   users/{uid}/state/coach     the coach's memory (Coach.kt)
// Each document is {data: JSON text in the backup format, updatedAt: server time, deleted}.
// A deleted record leaves a tombstone, so the delete reaches the other phones.
//
// What was last synced is remembered as a hash per record. A sync first pulls what changed in the
// account since the last one, then uploads what changed here. A record edited here since the last
// sync keeps this phone's version; otherwise the account's wins. On a phone's first sync the
// account's version of anything on both wins, and everything only here is uploaded, so nothing is lost.

/** One changed document from the account; [payload] null for a tombstone. */
data class RemoteDoc(val id: String, val payload: String?, val updatedAtMillis: Long)

internal fun syncHash(payload: String): String =
    MessageDigest.getInstance("SHA-1").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * What to take from the account: id → payload to store here, or null to delete it here. Also the
 * synced hashes afterwards. Records edited here since the last sync ([synced] differs) are left for the upload.
 */
internal fun pullPlan(local: Map<String, String>, remote: List<RemoteDoc>, synced: Map<String, String>): Pair<Map<String, String?>, Map<String, String>> {
    val apply = LinkedHashMap<String, String?>()
    val after = HashMap(synced)
    for (r in remote) {
        val mine = local[r.id]
        val was = synced[r.id]
        val editedHere = was != null && (mine == null || syncHash(mine) != was)
        if (editedHere) continue
        if (r.payload == null) {
            if (mine != null) apply[r.id] = null
            after.remove(r.id)
        } else {
            val theirs = syncHash(r.payload)
            if (mine == null || syncHash(mine) != theirs) apply[r.id] = r.payload
            after[r.id] = theirs
        }
    }
    return apply to after
}

/** What to send: id → payload, or null for a tombstone (deleted here). Also the synced hashes afterwards. */
internal fun pushPlan(local: Map<String, String>, synced: Map<String, String>): Pair<Map<String, String?>, Map<String, String>> {
    val upload = LinkedHashMap<String, String?>()
    val after = HashMap(synced)
    for ((id, payload) in local) {
        val h = syncHash(payload)
        if (synced[id] != h) {
            upload[id] = payload
            after[id] = h
        }
    }
    for (id in synced.keys - local.keys) {
        upload[id] = null
        after.remove(id)
    }
    return upload to after
}

/** A workout as it goes to the account: without what only this phone has (its photo) or what Room doesn't keep. */
internal fun workoutPayload(w: CompletedWorkout, note: String): String {
    val clean = w.copy(
        photoPath = null,
        exercises = w.exercises.map { it.copy(planNote = null, programmed = false, checkIn = false, target = null) }
    )
    return JSONObject().put("workout", StoreCodec.serializeWorkout(clean)).put("note", note).toString()
}

internal fun parseWorkoutPayload(payload: String): Pair<CompletedWorkout, String>? = try {
    val o = JSONObject(payload)
    StoreCodec.parseHistory(JSONArray().put(o.getJSONObject("workout"))).firstOrNull()?.let { it to o.optString("note") }
} catch (_: Exception) {
    null
}

internal fun weighInPayload(w: WeightLogEntry): String =
    JSONObject().put("id", w.id).put("dateKey", w.dateKey).put("displayDate", w.displayDate).put("weightKg", w.weightKg.toDouble()).toString()

internal fun parseWeighInPayload(payload: String): WeightLogEntry? = try {
    StoreCodec.parseWeights(JSONArray().put(JSONObject(payload))).firstOrNull()
} catch (_: Exception) {
    null
}

object CloudSync {
    private const val TAG = "CloudSync"
    private const val PREFS = "gym_tracker_cloud"
    private const val DEBOUNCE_MS = 4_000L
    private const val TIMEOUT_S = 30L
    private const val BATCH = 400

    /** [waiting]: held back by a running workout, which syncs once it's saved. */
    data class Status(val running: Boolean = false, val lastSyncAt: Long = 0L, val error: String? = null, val waiting: Boolean = false)

    var status by mutableStateOf(Status())
        private set

    /**
     * Something only the user can decide before this phone's first sync with an account; the sync
     * waits until it's answered ([answer]).
     */
    sealed class Question {
        /** This phone has its own plan and the account a different one. */
        data class WhichPlan(val account: String, val phone: String) : Question()
        /** This phone last synced with another account ([previous]) and has training. [canReplace]: all of it is saved there. */
        data class OtherAccount(val previous: String, val current: String, val canReplace: Boolean) : Question()
    }

    var question by mutableStateOf<Question?>(null)
        private set

    /** The answer to [question]: "account" / "phone" for the plan, "merge" / "replace" for the other account. */
    fun answer(context: Context, choice: String) {
        val uid = signedInUid() ?: return
        val key = if (question is Question.WhichPlan) "plan" else "account"
        prefs(context).edit().putString("$uid.choice.$key", choice).apply()
        question = null
        request(context, now = true)
    }

    /** The question was turned down by signing out. */
    fun dismissQuestion() {
        question = null
    }

    /** Bumped when the account's changes landed here, so open screens read the data again. */
    var applied by mutableIntStateOf(0)
        private set

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cloud-sync").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null
    private val runnable = Runnable { appContext?.let { ctx -> executor.execute { runOnce(ctx) } } }
    @Volatile private var configured = false

    private fun signedInUid(): String? = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }

    /** A sync soon (edits come in bursts), or [now]. Does nothing without an account. */
    fun request(context: Context, now: Boolean = false) {
        val app = context.applicationContext
        if (!Account.available(app) || signedInUid() == null) return
        appContext = app
        if (status.lastSyncAt == 0L) status = status.copy(lastSyncAt = prefs(app).getLong("${signedInUid()}.lastSyncAt", 0L))
        main.removeCallbacks(runnable)
        main.postDelayed(runnable, if (now) 0L else DEBOUNCE_MS)
    }

    /**
     * Syncs now and waits: for the welcome screen, which needs the account's data before deciding
     * whether setup is needed. False if it couldn't sync (offline, an error).
     */
    suspend fun syncAndWait(context: Context): Boolean {
        val app = context.applicationContext
        appContext = app
        main.removeCallbacks(runnable)
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            executor.execute {
                val ok = runOnce(app)
                if (cont.isActive) cont.resumeWith(Result.success(ok))
            }
        }
    }

    fun signedOut() {
        main.removeCallbacks(runnable)
        status = Status()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun firestore(): FirebaseFirestore {
        val db = FirebaseFirestore.getInstance()
        if (!configured) {
            // The phone's own storage is the copy that counts; Firestore's cache would be a second one.
            try {
                db.firestoreSettings = FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            } catch (_: IllegalStateException) {}
            configured = true
        }
        return db
    }

    private fun <T> Task<T>.waitFor(): T = Tasks.await(this, TIMEOUT_S, TimeUnit.SECONDS)

    private class Kind(val collection: String, val local: (Context) -> Map<String, String>)

    private val workouts = Kind("workouts") { ctx ->
        val notes = LocalStorage.workoutNotes(ctx)
        LocalStorage.loadHistory(ctx).associate { it.id.toString() to workoutPayload(it, notes[it.id].orEmpty()) }
    }
    private val weighIns = Kind("weighins") { ctx -> LocalStorage.loadWeightHistory(ctx).associate { it.id.toString() to weighInPayload(it) } }
    private val state = Kind("state") { ctx ->
        mapOf("plan" to LocalStorage.cloudPlan(ctx).toString(), "coach" to CoachState.encode(CoachState.load(ctx)))
    }
    private val kinds = listOf(workouts, weighIns, state)

    /** Set while an account is being deleted: no sync may put its data back. */
    @Volatile var paused = false

    /** True when the sync went through. */
    private fun runOnce(context: Context): Boolean {
        if (paused) return false
        val uid = signedInUid() ?: return false
        // A workout in progress changes with every set; it syncs once it's saved.
        if (LocalStorage.loadActiveSession(context) != null) {
            main.post { status = status.copy(waiting = true) }
            return false
        }
        main.post { status = status.copy(running = true, error = null, waiting = false) }
        try {
            val db = firestore()
            val user = db.collection("users").document(uid)
            val p = prefs(context)
            val lastPull = p.getLong("$uid.lastPull", 0L)
            val synced = kinds.associate { k -> k.collection to readHashes(p.getString("$uid.${k.collection}", null)) }.toMutableMap()
            // This phone has never synced its plan with this account: the two are merged, not replaced.
            val firstSync = synced.getValue("state").isEmpty()

            // 1. What changed in the account.
            var newest = lastPull
            val remote = kinds.associate { k ->
                val docs = user.collection(k.collection).whereGreaterThanOrEqualTo("updatedAt", Timestamp(Date(lastPull))).get(Source.SERVER).waitFor()
                k.collection to docs.documents.map { d ->
                    val at = d.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                    newest = maxOf(newest, at)
                    RemoteDoc(d.id, if (d.getBoolean("deleted") == true) null else d.getString("data"), at)
                }
            }

            // 1b. On this phone's first sync with the account, what only the user can decide.
            var incoming = remote
            if (firstSync) {
                val decided = decideFirstSync(context, uid, remote) ?: return false
                incoming = decided
            }

            // 2. Take it in.
            val pulls = kinds.associate { k -> k.collection to pullPlan(k.local(context), incoming.getValue(k.collection), synced.getValue(k.collection)) }
            pulls.forEach { (c, plan) -> synced[c] = plan.second }
            val landed = applyHere(context, pulls.mapValues { it.value.first }, firstSync)
            if (landed) {
                // What's stored here now is what counts as synced (the same data, written the way this phone writes it).
                // Not a first sync's merged plan: it holds this phone's custom exercises and notes, so it goes up.
                kinds.filter { !(firstSync && it === state) }.forEach { k ->
                    val now = k.local(context)
                    val changed = pulls.getValue(k.collection).first.keys
                    synced[k.collection] = synced.getValue(k.collection).toMutableMap().apply {
                        changed.forEach { id -> now[id]?.let { put(id, syncHash(it)) } ?: remove(id) }
                    }
                }
            }

            // 3. Send what changed here.
            for (k in kinds) {
                val (upload, after) = pushPlan(k.local(context), synced.getValue(k.collection))
                upload.entries.chunked(BATCH).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { (id, payload) ->
                        val doc = user.collection(k.collection).document(id)
                        if (payload == null) batch.set(doc, mapOf("deleted" to true, "updatedAt" to FieldValue.serverTimestamp()))
                        else batch.set(doc, mapOf("data" to payload, "deleted" to false, "updatedAt" to FieldValue.serverTimestamp()))
                    }
                    batch.commit().waitFor()
                }
                synced[k.collection] = after
                p.edit().putString("$uid.${k.collection}", writeHashes(after)).apply()
            }

            Friends.publish(context, uid)

            val now = System.currentTimeMillis()
            // Which account this phone's data now belongs to, for the next sign-in with a different one.
            val email = try { FirebaseAuth.getInstance().currentUser?.email } catch (_: Exception) { null }
            p.edit().putLong("$uid.lastPull", newest).putLong("$uid.lastSyncAt", now)
                .putString("lastUid", uid).putString("lastEmail", email).apply()
            main.post {
                status = Status(lastSyncAt = now)
                if (landed) applied++
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            val message = errorText(e)
            main.post { status = status.copy(running = false, error = message) }
            return false
        }
    }

    /**
     * The account's documents to take in on this phone's first sync, after the user's choices, or
     * null when a question is now waiting (the sync stops until it's answered).
     */
    private fun decideFirstSync(context: Context, uid: String, remote: Map<String, List<RemoteDoc>>): Map<String, List<RemoteDoc>>? {
        val p = prefs(context)
        val localWorkouts = workouts.local(context)
        val localWeights = weighIns.local(context)

        // Training from a different account on this phone: add it to this one, or swap it for this one's.
        // Phones that synced before this was recorded (beta 1) still have that account's sync records.
        val lastUid = p.getString("lastUid", null)
            ?: p.all.keys.firstOrNull { it.endsWith(".workouts") && !it.startsWith("$uid.") }?.removeSuffix(".workouts")
        var replaced = false
        if (lastUid != null && lastUid != uid && (localWorkouts.isNotEmpty() || localWeights.isNotEmpty())) {
            when (p.getString("$uid.choice.account", null)) {
                null -> {
                    val canReplace = pushPlan(localWorkouts, readHashes(p.getString("$lastUid.workouts", null))).first.isEmpty() &&
                        pushPlan(localWeights, readHashes(p.getString("$lastUid.weighins", null))).first.isEmpty()
                    val previous = p.getString("lastEmail", null) ?: tr("another account")
                    val current = try { FirebaseAuth.getInstance().currentUser?.email } catch (_: Exception) { null } ?: tr("this account")
                    main.post { question = Question.OtherAccount(previous, current, canReplace); status = status.copy(running = false) }
                    return null
                }
                "replace" -> {
                    // All of it is saved in the other account (checked when asked): this phone starts again from this one.
                    LocalStorage.applyCloud(
                        context, emptyList(), localWorkouts.keys.mapNotNull { it.toLongOrNull() }.toSet(),
                        emptyList(), localWeights.keys.mapNotNull { it.toLongOrNull() }.toSet(), null
                    )
                    // Forget what this phone synced with the other account: those records are gone from
                    // here on purpose, and signing in to it again must merge, not send them as deletes.
                    p.edit().apply {
                        kinds.forEach { remove("$lastUid.${it.collection}") }
                        remove("$lastUid.lastPull"); remove("$lastUid.lastSyncAt")
                    }.apply()
                    // Open screens read again even if the account turns out to be empty.
                    main.post { applied++ }
                    replaced = true
                }
            }
        }

        // Two different plans: keep the account's or this phone's. Workouts and weigh-ins merge either way.
        val accountPlan = remote.getValue("state").firstOrNull { it.id == "plan" }?.payload ?: return remote
        if (replaced || !LocalStorage.loadUserProfile(context).hasCompletedOnboarding) return remote
        val phonePlan = LocalStorage.cloudPlan(context).toString()
        if (planShape(accountPlan) == planShape(phonePlan)) return remote
        return when (p.getString("$uid.choice.plan", null)) {
            null -> {
                main.post { question = Question.WhichPlan(planSummary(accountPlan), planSummary(phonePlan)); status = status.copy(running = false) }
                null
            }
            // This phone's plan stays and goes up: the account's isn't taken in.
            "phone" -> remote + ("state" to remote.getValue("state").filter { it.id != "plan" })
            else -> remote
        }
    }

    /** What makes two plans the same plan: the program and its days' exercises (not settings). */
    private fun planShape(plan: String): String = try {
        val o = JSONObject(plan)
        o.optJSONObject("userProfile")?.optString("splitProgram").orEmpty() + "|" + o.opt("splitTemplates")?.toString().orEmpty()
    } catch (_: Exception) {
        plan
    }

    /** "Upper/Lower: Upper A, Legs A, Upper B, Legs B", from a plan's program. */
    private fun planSummary(plan: String): String = try {
        val o = JSONObject(plan)
        val id = o.optJSONObject("userProfile")?.optString("splitProgram").orEmpty()
        val program = StoreCodec.readData(o).programs.find { it.id == id } ?: programFor(id)
        shown(program.name) + ": " + program.splits.joinToString(", ") { shown(it.title) }
    } catch (_: Exception) {
        tr("Your plan")
    }

    /** Stores what came from the account. True if anything changed here. */
    private fun applyHere(context: Context, apply: Map<String, Map<String, String?>>, firstSync: Boolean): Boolean {
        val w = apply.getValue("workouts")
        val b = apply.getValue("weighins")
        val s = apply.getValue("state")
        if (w.isEmpty() && b.isEmpty() && s.isEmpty()) return false
        val localPhotos = LocalStorage.loadHistory(context).associate { it.id to it.photoPath }
        val workouts = w.values.mapNotNull { payload -> payload?.let(::parseWorkoutPayload) }
            .map { (wk, note) -> wk.copy(photoPath = localPhotos[wk.id]) to note }
        LocalStorage.applyCloud(
            context,
            workouts = workouts,
            deletedWorkouts = w.filterValues { it == null }.keys.mapNotNull { it.toLongOrNull() }.toSet(),
            weights = b.values.mapNotNull { payload -> payload?.let(::parseWeighInPayload) },
            deletedWeights = b.filterValues { it == null }.keys.mapNotNull { it.toLongOrNull() }.toSet(),
            plan = s["plan"]?.let { runCatching { JSONObject(it) }.getOrNull() },
            firstSync = firstSync
        )
        s["coach"]?.let { raw -> CoachState.update(context) { CoachState.decode(raw) } }
        return true
    }

    private fun readHashes(raw: String?): Map<String, String> {
        val o = try { JSONObject(raw ?: return emptyMap()) } catch (_: Exception) { return emptyMap() }
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    private fun writeHashes(map: Map<String, String>): String = JSONObject(map as Map<*, *>).toString()

    private fun errorText(e: Exception): String {
        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
        return when {
            cause is FirebaseFirestoreException && cause.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                tr("The account's database refused access. Its security rules may not be set up yet.")
            cause is FirebaseFirestoreException && cause.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                tr("Today's free sync limit is used up. It'll catch up tomorrow.")
            cause is java.util.concurrent.TimeoutException ||
                (cause is FirebaseFirestoreException && cause.code == FirebaseFirestoreException.Code.UNAVAILABLE) ->
                tr("No connection. It'll sync when you're online.")
            else -> tr("Couldn't sync. It'll try again later.")
        }
    }
}
