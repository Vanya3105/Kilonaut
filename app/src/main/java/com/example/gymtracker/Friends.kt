package com.example.gymtracker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Friends. Everyone picks a username (handles/{name} → their uid, so names are unique) and has a
 * profile (profiles/{uid}: name and username) any signed-in person can read, so they can be found.
 * Following is one-way and each side keeps its half: users/{me}/following/{them} and
 * users/{them}/followers/{me}, written together. Someone who follows you and whom you don't follow
 * back is a request: Accept follows them back, Remove deletes their follow. Following each other
 * makes you friends, and only friends can read each other's stats (users/{uid}/public/stats,
 * written by the owner's phone on every sync). firestore.rules holds all of this to the server.
 */

/** What a friend sees about you: a summary only, never body weight, notes or photos. */
internal fun friendStatsJson(history: List<CompletedWorkout>, weeklyGoal: Int, now: Long = System.currentTimeMillis()): JSONObject {
    val c = computeConsistency(history, nowMillis = now, weeks = 1)
    val weekStart = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        set(java.util.Calendar.DAY_OF_WEEK, firstDayOfWeek)
        if (timeInMillis > now) add(java.util.Calendar.WEEK_OF_YEAR, -1)
    }.timeInMillis
    val out = JSONObject()
        .put("v", 1)
        .put("total", c.total)
        .put("thisWeek", history.count { it.id in weekStart..now })
        .put("goal", weeklyGoal)
        .put("streak", c.weekStreak)
    history.maxByOrNull { it.id }?.let { w ->
        out.put("last", JSONObject()
            .put("title", w.splitTitle)
            .put("at", w.id)
            .put("seconds", w.durationSeconds)
            .put("sets", w.exercises.sumOf { ex -> ex.sets.count { it.isCompleted && !it.isWarmupSet } })
            .put("kg", w.totalVolumeKg))
    }
    // Best sets of the lifts done most often: what friends compare.
    val byExercise = historyByExercise(history)
    val bests = JSONArray()
    byExercise.entries.sortedByDescending { it.value.size }.asSequence()
        .mapNotNull { (key, sessions) ->
            // The name as last written, with its type.
            val entry = sessions.maxBy { it.id }.exercises.first { ignoreCaseKey(it.name) == key }
            val best = computeExerciseRecords(entry.name, sessions).bestSet ?: return@mapNotNull null
            JSONObject().put("name", entry.name).put("type", entry.type.name)
                .put("weight", best.weight).put("reps", best.reps).put("seconds", best.seconds).put("distanceM", best.distanceM)
        }
        .take(5).forEach { bests.put(it) }
    out.put("bests", bests)
    return out
}

/** A friend's stats as read back; [bests] in the reader's units when shown. */
data class FriendStats(
    val total: Int, val thisWeek: Int, val goal: Int, val streak: Int,
    val lastTitle: String?, val lastAt: Long, val lastSeconds: Long, val lastSets: Int, val lastKg: Int,
    val bests: List<Pair<String, String>>
)

internal fun parseFriendStats(json: String): FriendStats? = try {
    val o = JSONObject(json)
    val last = o.optJSONObject("last")
    val bests = o.optJSONArray("bests") ?: JSONArray()
    FriendStats(
        total = o.optInt("total"), thisWeek = o.optInt("thisWeek"), goal = o.optInt("goal"), streak = o.optInt("streak"),
        lastTitle = last?.optString("title"), lastAt = last?.optLong("at") ?: 0L, lastSeconds = last?.optLong("seconds") ?: 0L,
        lastSets = last?.optInt("sets") ?: 0, lastKg = last?.optInt("kg") ?: 0,
        bests = (0 until bests.length()).mapNotNull { i ->
            val b = bests.getJSONObject(i)
            val type = runCatching { ExerciseType.valueOf(b.optString("type")) }.getOrDefault(ExerciseType.WEIGHT_REPS)
            val set = SetEntry(1, b.optString("weight"), b.optString("reps"), isCompleted = true, seconds = b.optString("seconds"), distanceM = b.optString("distanceM"))
            b.optString("name").takeIf { it.isNotBlank() }?.let { it to formatSet(set, type) }
        }
    )
} catch (_: Exception) {
    null
}

/** Usernames: 3–20 of a–z, 0–9, dot and underscore, stored lower-case. */
internal fun cleanHandle(raw: String): String = raw.trim().removePrefix("@").lowercase()
internal fun isValidHandle(handle: String): Boolean = Regex("^[a-z0-9_.]{3,20}$").matches(handle)

object Friends {
    data class Person(val uid: String, val name: String, val handle: String)

    /** Friends (follow each other), requests (follow you, you don't follow back) and sent (you follow, not back yet). */
    data class Circle(val friends: List<Person>, val requests: List<Person>, val sent: List<Person>)

    var myHandle by mutableStateOf<String?>(null)
        private set
    var circle by mutableStateOf<Circle?>(null)
        private set
    /** Requests waiting, for the badge on Profile. */
    var requestCount by mutableIntStateOf(0)
        private set

    private fun db() = FirebaseFirestore.getInstance()
    private fun me(): String? = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
    private fun <T> Task<T>.waitFor(): T = Tasks.await(this, 20, TimeUnit.SECONDS)

    fun signedOut() {
        myHandle = null; circle = null; requestCount = 0
    }

    /** Your username, or null when none is picked yet. */
    suspend fun loadMe(): String? = withContext(Dispatchers.IO) {
        val uid = me() ?: return@withContext null
        val handle = db().collection("profiles").document(uid).get(Source.SERVER).waitFor().getString("handle")
        withContext(Dispatchers.Main) { myHandle = handle }
        handle
    }

    /** Claims [raw] as your username (and lets go of the old one). Null when done, else why not. */
    suspend fun claimHandle(raw: String, name: String): String? = withContext(Dispatchers.IO) {
        val uid = me() ?: return@withContext tr("Sign in first.")
        val handle = cleanHandle(raw)
        if (!isValidHandle(handle)) return@withContext tr("3 to 20 letters, numbers, dots or underscores.")
        try {
            val db = db()
            val old = myHandle
            db.runTransaction { t ->
                val taken = t.get(db.collection("handles").document(handle))
                if (taken.exists() && taken.getString("uid") != uid) throw IllegalStateException("taken")
                t.set(db.collection("handles").document(handle), mapOf("uid" to uid))
                t.set(db.collection("profiles").document(uid), mapOf("handle" to handle, "name" to name))
                if (old != null && old != handle) t.delete(db.collection("handles").document(old))
                null
            }.waitFor()
            withContext(Dispatchers.Main) { myHandle = handle }
            null
        } catch (e: Exception) {
            if (generateSequence(e as Throwable) { it.cause }.any { it.message == "taken" }) tr("That username is taken.") else tr("Couldn't save it. Check your connection.")
        }
    }

    /** The person with that username, or null. */
    suspend fun find(raw: String): Person? = withContext(Dispatchers.IO) {
        val handle = cleanHandle(raw)
        if (!isValidHandle(handle)) return@withContext null
        try {
            val uid = db().collection("handles").document(handle).get(Source.SERVER).waitFor().getString("uid") ?: return@withContext null
            profile(uid)
        } catch (_: Exception) {
            null
        }
    }

    private fun profile(uid: String): Person {
        val p = db().collection("profiles").document(uid).get(Source.SERVER).waitFor()
        return Person(uid, p.getString("name").orEmpty(), p.getString("handle").orEmpty())
    }

    /** Reads who follows whom and sorts it into friends, requests and sent. */
    suspend fun refresh(): Circle? = withContext(Dispatchers.IO) {
        val uid = me() ?: return@withContext null
        try {
            val user = db().collection("users").document(uid)
            val following = user.collection("following").get(Source.SERVER).waitFor().documents.map { it.id }.toSet()
            val followers = user.collection("followers").get(Source.SERVER).waitFor().documents.map { it.id }.toSet()
            val people = (following + followers).associateWith { runCatching { profile(it) }.getOrElse { _ -> Person(it, "", "") } }
            fun list(ids: Set<String>) = ids.mapNotNull { people[it] }.sortedBy { it.name.lowercase() }
            val c = Circle(list(following intersect followers), list(followers - following), list(following - followers))
            withContext(Dispatchers.Main) { circle = c; requestCount = c.requests.size }
            c
        } catch (_: Exception) {
            null
        }
    }

    /** Follow (or accept: following back makes you friends). */
    suspend fun follow(other: String): Boolean = change { db, uid ->
        val at = FieldValue.serverTimestamp()
        db.batch()
            .set(db.collection("users").document(uid).collection("following").document(other), mapOf("uid" to other, "at" to at))
            .set(db.collection("users").document(other).collection("followers").document(uid), mapOf("uid" to uid, "at" to at))
            .commit()
    }

    /** Stop following (unfriend, or take back a request). */
    suspend fun unfollow(other: String): Boolean = change { db, uid ->
        db.batch()
            .delete(db.collection("users").document(uid).collection("following").document(other))
            .delete(db.collection("users").document(other).collection("followers").document(uid))
            .commit()
    }

    /** Remove someone's follow of you (turn down a request, or end a friendship from your side). */
    suspend fun removeFollower(other: String): Boolean = change { db, uid ->
        db.batch()
            .delete(db.collection("users").document(uid).collection("followers").document(other))
            .delete(db.collection("users").document(other).collection("following").document(uid))
            .commit()
    }

    private suspend fun change(write: (FirebaseFirestore, String) -> Task<Void>): Boolean = withContext(Dispatchers.IO) {
        val uid = me() ?: return@withContext false
        try {
            write(db(), uid).waitFor()
            true
        } catch (_: Exception) {
            false
        }.also { if (it) refresh() }
    }

    /** A friend's stats, or null (not friends, or nothing shared yet). */
    suspend fun stats(other: String): FriendStats? = withContext(Dispatchers.IO) {
        try {
            val d = db().collection("users").document(other).collection("public").document("stats").get(Source.SERVER).waitFor()
            d.getString("data")?.let(::parseFriendStats)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes what friends see, from the sync's thread, when it changed since the last time.
     * Only with a username: without one nobody can be your friend.
     */
    fun publish(context: Context, uid: String) {
        try {
            val db = db()
            if (db.collection("profiles").document(uid).get(Source.SERVER).waitFor().getString("handle") == null) return
            val json = friendStatsJson(LocalStorage.loadHistory(context), programFor(LocalStorage.loadUserProfile(context).splitProgram).weeklyGoal)
            // "updated" isn't part of what changes; the day is, so "this week" turns over.
            val hash = syncHash(json.toString() + java.text.SimpleDateFormat("yyyy-ww", java.util.Locale.US).format(java.util.Date()))
            val prefs = context.getSharedPreferences("gym_tracker_cloud", Context.MODE_PRIVATE)
            if (prefs.getString("$uid.statsHash", null) == hash) return
            db.collection("users").document(uid).collection("public").document("stats")
                .set(mapOf("data" to json.toString(), "updatedAt" to FieldValue.serverTimestamp())).waitFor()
            prefs.edit().putString("$uid.statsHash", hash).apply()
        } catch (_: Exception) {
            // Friends see the last stats that went up; the next sync tries again.
        }
    }
}
