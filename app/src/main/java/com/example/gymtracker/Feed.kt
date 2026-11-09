package com.example.gymtracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/*
 * The feed: what you and your friends post. users/{uid}/posts/{id} holds {text?, workout?, photo?,
 * createdAt}: a workout as a summary (WorkoutPost JSON, raw kg so each reader sees their units),
 * a photo as a ~1080 px JPEG (base64; Cloud Storage would need the paid plan). Likes are
 * posts/{id}/likes/{uid}, comments posts/{id}/comments/{id} {uid, text, at}. Only the author and
 * their friends (who follow each other) can read any of it: firestore.rules.
 */

data class PostExercise(val name: String, val type: ExerciseType, val sets: Int, val best: SetEntry?, val isBest: Boolean)
data class WorkoutPost(
    val title: String, val at: Long, val seconds: Long, val sets: Int, val kg: Int, val bests: Int,
    val exercises: List<PostExercise>
)

/** [w] as a post: its totals, how many new bests, and each exercise's best set (raw, for the reader's units). */
internal fun workoutPostJson(w: CompletedWorkout, historyBefore: List<CompletedWorkout>): JSONObject {
    val share = buildShareData(w.splitTitle, w.durationSeconds, w.exercises, historyBefore, w.id, null)
    val bestNames = share.bests.map { ignoreCaseKey(it.name) }.toSet()
    val exercises = JSONArray()
    performedExercises(w.exercises).forEach { ex ->
        val best = bestSetOf(ex.type, ex.workSets)
        exercises.put(JSONObject()
            .put("name", ex.name).put("type", ex.type.name).put("sets", ex.workSets.size)
            .put("best", best?.let { JSONObject().put("weight", it.weight).put("reps", it.reps).put("seconds", it.seconds).put("distanceM", it.distanceM) })
            .put("isBest", ignoreCaseKey(ex.name) in bestNames))
    }
    return JSONObject()
        .put("title", w.splitTitle).put("at", w.id).put("seconds", w.durationSeconds)
        .put("sets", share.workSets).put("kg", share.volumeKg).put("bests", share.bests.size)
        .put("exercises", exercises)
}

internal fun parseWorkoutPost(json: String?): WorkoutPost? = try {
    val o = JSONObject(json ?: return null)
    val list = o.optJSONArray("exercises") ?: JSONArray()
    WorkoutPost(
        o.optString("title"), o.optLong("at"), o.optLong("seconds"), o.optInt("sets"), o.optInt("kg"), o.optInt("bests"),
        (0 until list.length()).map { i ->
            val e = list.getJSONObject(i)
            val b = e.optJSONObject("best")
            PostExercise(
                e.optString("name"),
                runCatching { ExerciseType.valueOf(e.optString("type")) }.getOrDefault(ExerciseType.WEIGHT_REPS),
                e.optInt("sets"),
                b?.let { SetEntry(1, it.optString("weight"), it.optString("reps"), isCompleted = true, seconds = it.optString("seconds"), distanceM = it.optString("distanceM")) },
                e.optBoolean("isBest")
            )
        }
    )
} catch (_: Exception) {
    null
}

object Feed {
    data class Post(
        val id: String,
        val author: Friends.Person,
        val createdAt: Long,
        val text: String,
        val photo: String?,
        val workout: WorkoutPost?,
        /** Who liked it (uids), and whether you did. */
        val likes: List<String>,
        val comments: Int
    ) {
        val mine: Boolean get() = author.uid == me()
        fun likedByMe() = me() in likes
    }

    data class Comment(val id: String, val uid: String, val text: String, val at: Long)

    var posts by mutableStateOf<List<Post>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** People by uid seen in the feed (you and your friends), to name likers and commenters. */
    var people by mutableStateOf<Map<String, Friends.Person>>(emptyMap())
        private set

    private fun db() = FirebaseFirestore.getInstance()
    internal fun me(): String? = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
    private fun <T> Task<T>.waitFor(): T = Tasks.await(this, 20, TimeUnit.SECONDS)
    private fun posts(uid: String) = db().collection("users").document(uid).collection("posts")

    fun signedOut() {
        posts = null; people = emptyMap()
    }

    /** Reads the latest posts of you and your friends, newest first. False when it couldn't. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val uid = me() ?: return@withContext false
        withContext(Dispatchers.Main) { loading = true }
        try {
            val circle = Friends.refresh() ?: return@withContext false
            val handle = Friends.myHandle ?: Friends.loadMe(context)
            val me = Friends.Person(uid, Account.user?.name.orEmpty(), handle.orEmpty(), ProfilePhoto.local(context))
            val authors = circle.friends + me
            // All authors' queries at once, then the likes and comment counts of what came back.
            val queries = authors.map { a -> a to posts(a.uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(15).get(Source.SERVER) }
            val docs: List<Pair<Friends.Person, DocumentSnapshot>> = queries.flatMap { (a, q) ->
                runCatching { q.waitFor().documents.map { a to it } }.getOrDefault(emptyList())
            }.sortedByDescending { it.second.getTimestamp("createdAt")?.toDate()?.time ?: 0L }.take(40)
            val extras = docs.map { (author, d) ->
                Triple(author to d, d.reference.collection("likes").get(Source.SERVER), d.reference.collection("comments").count().get(AggregateSource.SERVER))
            }
            val list = extras.map { (post, likesTask, countTask) ->
                val (author, d) = post
                Post(
                    id = d.id,
                    author = author,
                    createdAt = d.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
                    text = d.getString("text").orEmpty(),
                    photo = d.getString("photo"),
                    workout = parseWorkoutPost(d.getString("workout")),
                    likes = runCatching { likesTask.waitFor().documents.map { it.id } }.getOrDefault(emptyList()),
                    comments = runCatching { countTask.waitFor().count.toInt() }.getOrDefault(0)
                )
            }
            withContext(Dispatchers.Main) {
                posts = list
                people = authors.associateBy { it.uid }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            withContext(Dispatchers.Main) { loading = false }
        }
    }

    /** Posts [text], [photo] (as stored) and/or [workout]. Null when done, else why not. */
    suspend fun publish(context: Context, text: String, photo: String?, workout: CompletedWorkout?): String? = withContext(Dispatchers.IO) {
        val uid = me() ?: return@withContext tr("Sign in first.")
        if (Friends.myHandle == null && Friends.loadMe(context) == null) return@withContext tr("Pick a username in Friends first.")
        val data = HashMap<String, Any>()
        text.trim().take(1000).takeIf { it.isNotEmpty() }?.let { data["text"] = it }
        photo?.let { data["photo"] = it }
        workout?.let { w ->
            val before = LocalStorage.loadHistory(context).filter { it.id < w.id }
            data["workout"] = workoutPostJson(w, before).toString()
        }
        if (data.isEmpty()) return@withContext tr("Add a photo, a workout or a few words.")
        data["createdAt"] = FieldValue.serverTimestamp()
        try {
            posts(uid).add(data).waitFor()
            refresh(context)
            null
        } catch (_: Exception) {
            tr("Couldn't post. Check your connection.")
        }
    }

    /** Deletes your post with its likes and comments. */
    suspend fun delete(context: Context, post: Post): Boolean = withContext(Dispatchers.IO) {
        try {
            val ref = posts(post.author.uid).document(post.id)
            val batch = db().batch()
            ref.collection("likes").get(Source.SERVER).waitFor().documents.forEach { batch.delete(it.reference) }
            ref.collection("comments").get(Source.SERVER).waitFor().documents.forEach { batch.delete(it.reference) }
            batch.delete(ref).commit().waitFor()
            withContext(Dispatchers.Main) { posts = posts?.filter { it.id != post.id } }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Likes or unlikes; the feed shows it at once and goes back if it fails. */
    suspend fun like(post: Post, on: Boolean): Boolean {
        val uid = me() ?: return false
        fun update(change: (List<String>) -> List<String>) {
            posts = posts?.map { if (it.id == post.id && it.author.uid == post.author.uid) it.copy(likes = change(it.likes)) else it }
        }
        update { if (on) (it - uid) + uid else it - uid }
        val ok = withContext(Dispatchers.IO) {
            try {
                val ref = posts(post.author.uid).document(post.id).collection("likes").document(uid)
                (if (on) ref.set(mapOf("at" to FieldValue.serverTimestamp())) else ref.delete()).waitFor()
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!ok) update { if (on) it - uid else it + uid }
        return ok
    }

    suspend fun comments(post: Post): List<Comment>? = withContext(Dispatchers.IO) {
        try {
            posts(post.author.uid).document(post.id).collection("comments").orderBy("at").get(Source.SERVER).waitFor().documents.map {
                Comment(it.id, it.getString("uid").orEmpty(), it.getString("text").orEmpty(), it.getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis())
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun comment(post: Post, text: String): Boolean = withContext(Dispatchers.IO) {
        val uid = me() ?: return@withContext false
        val clean = text.trim().take(500)
        if (clean.isEmpty()) return@withContext false
        try {
            posts(post.author.uid).document(post.id).collection("comments")
                .add(mapOf("uid" to uid, "text" to clean, "at" to FieldValue.serverTimestamp())).waitFor()
            withContext(Dispatchers.Main) { bump(post, +1) }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteComment(post: Post, c: Comment): Boolean = withContext(Dispatchers.IO) {
        try {
            posts(post.author.uid).document(post.id).collection("comments").document(c.id).delete().waitFor()
            withContext(Dispatchers.Main) { bump(post, -1) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun bump(post: Post, by: Int) {
        posts = posts?.map { if (it.id == post.id && it.author.uid == post.author.uid) it.copy(comments = (it.comments + by).coerceAtLeast(0)) else it }
    }

    // --- Photos in posts ---

    private const val MAX_SIDE = 1080
    /** Base64 characters; the rules allow 450 000. */
    private const val MAX_CHARS = 420_000
    private val images = object : LruCache<Int, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
    }

    /** A gallery photo for a post: upright, at most [MAX_SIDE] px, JPEG small enough for the rules. */
    fun encodePhoto(context: Context, uri: Uri): String? {
        val path = PhotoStorage.copyUriToInternalStorage(context, uri) ?: return null
        return try {
            val full = BitmapFactory.decodeFile(path) ?: return null
            val ratio = minOf(1f, MAX_SIDE.toFloat() / maxOf(full.width, full.height))
            val bmp = if (ratio < 1f) Bitmap.createScaledBitmap(full, (full.width * ratio).roundToInt(), (full.height * ratio).roundToInt(), true) else full
            // Lower the quality until it fits (a detailed photo can be large even at 1080 px).
            var quality = 80
            var text: String
            do {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                text = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                quality -= 10
            } while (text.length > MAX_CHARS && quality >= 40)
            text.takeIf { it.length <= MAX_CHARS }
        } catch (_: Exception) {
            null
        } finally {
            File(path).delete()
        }
    }

    fun image(data: String?): ImageBitmap? {
        if (data.isNullOrBlank()) return null
        val key = data.hashCode()
        images.get(key)?.let { return it }
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { images.put(key, it) }
        } catch (_: Exception) {
            null
        }
    }
}
