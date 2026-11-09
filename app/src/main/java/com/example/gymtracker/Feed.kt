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
        fun likedByMe() = (if (demo) "demo-me" else me()) in likes
    }

    /** [replyTo]: the comment it answers, or null for a comment on the post. */
    data class Comment(val id: String, val uid: String, val text: String, val at: Long, val replyTo: String? = null)

    var posts by mutableStateOf<List<Post>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** The last refresh failed (offline, or the server didn't answer). */
    var failed by mutableStateOf(false)
        private set
    /** People by uid seen in the feed (you and your friends), to name likers and commenters. */
    var people by mutableStateOf<Map<String, Friends.Person>>(emptyMap())
        private set

    private fun db() = FirebaseFirestore.getInstance()
    internal fun me(): String? = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
    private fun <T> Task<T>.waitFor(): T = Tasks.await(this, 20, TimeUnit.SECONDS)
    private fun posts(uid: String) = db().collection("users").document(uid).collection("posts")

    fun signedOut() {
        posts = null; people = emptyMap(); demo = false
    }

    /**
     * Debug builds only: sample posts (long-press "Feed"), to feel the swiping, likes and comments
     * without accounts. Nothing is sent anywhere.
     */
    var demo by mutableStateOf(false)
        private set

    /** The feed is showing posts full screen (the app's status-bar backdrop steps aside). */
    var fullBleed by mutableStateOf(false)
    private val demoComments = HashMap<String, MutableList<Comment>>()

    fun showDemo() {
        val now = System.currentTimeMillis()
        val maya = Friends.Person("demo-maya", "Maya Cohen", "maya.lifts")
        val dan = Friends.Person("demo-dan", "Dan Levi", "danl")
        val noa = Friends.Person("demo-noa", "Noa", "noa_runs")
        val bench = WorkoutPost(
            "Push A", now - 2 * 3_600_000, 3720, 18, 6240, 2,
            listOf(
                PostExercise("Barbell Bench Press", ExerciseType.WEIGHT_REPS, 4, SetEntry(1, "85", "6", isCompleted = true), true),
                PostExercise("Overhead Press", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "52.5", "8", isCompleted = true), false),
                PostExercise("Cable Fly", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "15", "12", isCompleted = true), false),
                PostExercise("Triceps Pushdown", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "30", "12", isCompleted = true), true),
                PostExercise("Lateral Raise", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "10", "15", isCompleted = true), false)
            )
        )
        val legs = bench.copy(title = "Legs A", kg = 9120, bests = 1, exercises = listOf(
            PostExercise("Back Squat", ExerciseType.WEIGHT_REPS, 4, SetEntry(1, "120", "5", isCompleted = true), true),
            PostExercise("Romanian Deadlift", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "100", "8", isCompleted = true), false),
            PostExercise("Leg Press", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "180", "10", isCompleted = true), false),
            PostExercise("Plank", ExerciseType.DURATION, 2, SetEntry(1, "", "", isCompleted = true, seconds = "75"), false)
        ))
        demoComments.clear()
        demoComments["d1"] = mutableListOf(Comment("c1", dan.uid, "Monster!", now - 3_000_000), Comment("c2", noa.uid, "Form looked clean", now - 1_000_000))
        posts = listOf(
            Post("d1", maya, now - 2 * 3_600_000, "New bench best. Finally 85 for six", demoPhoto(), bench, listOf(dan.uid, noa.uid), 2),
            Post("d2", dan, now - 20 * 60_000, "", null, legs, listOf(maya.uid), 0),
            Post("d3", noa, now - 26 * 3_600_000, "Rest day. Walked 12k steps and slept 9 hours. Back at it tomorrow.", null, null, emptyList(), 0),
            Post("d4", maya, now - 3 * 24 * 3_600_000, "Sunday session done", demoPhoto(0xFF2E4057.toInt(), 0xFF0B1320.toInt()), null, emptyList(), 0)
        )
        people = listOf(maya, dan, noa, Friends.Person("demo-me", tr("You"), "")).associateBy { it.uid }
        demo = true
    }

    /** A stand-in photo: a soft gradient with a floor and a light, like a gym corner. */
    private fun demoPhoto(top: Int = 0xFF8A6A55.toInt(), bottom: Int = 0xFF2B2522.toInt()): String {
        val bmp = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, 1350f, top, bottom, android.graphics.Shader.TileMode.CLAMP) })
        c.drawCircle(780f, 260f, 160f, android.graphics.Paint().apply { color = 0x55FFFFFF; maskFilter = android.graphics.BlurMaskFilter(80f, android.graphics.BlurMaskFilter.Blur.NORMAL) })
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Reads the latest posts of you and your friends, newest first. False when it couldn't. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val uid = me() ?: return@withContext false
        withContext(Dispatchers.Main) { loading = true }
        var ok = false
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
            val list = build(docs)
            withContext(Dispatchers.Main) {
                posts = list
                people = authors.associateBy { it.uid }
            }
            ok = true
            true
        } catch (_: Exception) {
            false
        } finally {
            withContext(Dispatchers.Main) { loading = false; failed = !ok }
        }
    }

    /** Post documents → posts, with their likes and comment counts (all fetched at once). */
    private fun build(docs: List<Pair<Friends.Person, DocumentSnapshot>>): List<Post> {
        val extras = docs.map { (author, d) ->
            Triple(author to d, d.reference.collection("likes").get(Source.SERVER), d.reference.collection("comments").count().get(AggregateSource.SERVER))
        }
        return extras.map { (post, likesTask, countTask) ->
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
    }

    /** One person's posts for their page, newest first; null if they can't be read (not friends). */
    var personPosts by mutableStateOf<List<Post>?>(null)
        private set

    suspend fun loadPostsBy(person: Friends.Person): List<Post>? = withContext(Dispatchers.IO) {
        val list = if (demo) posts.orEmpty().filter { it.author.uid == person.uid } else try {
            build(posts(person.uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(30).get(Source.SERVER).waitFor().documents.map { person to it })
        } catch (_: Exception) {
            null
        }
        withContext(Dispatchers.Main) { personPosts = list }
        list
    }

    fun clearPersonPosts() {
        personPosts = null
    }

    /** Drops [uid]'s posts from what's showing (after blocking them). */
    fun forget(uid: String) {
        posts = posts?.filter { it.author.uid != uid }
        personPosts = personPosts?.filter { it.author.uid != uid }
    }

    /** Where a post lives, for reports. */
    fun pathOf(post: Post) = "users/${post.author.uid}/posts/${post.id}"

    /** Changes one post wherever it's shown (the feed and a person's page). */
    private fun change(post: Post, update: (Post) -> Post) {
        fun List<Post>.apply() = map { if (it.id == post.id && it.author.uid == post.author.uid) update(it) else it }
        posts = posts?.apply()
        personPosts = personPosts?.apply()
    }

    /** Posts [text], [photo] (as stored) and/or [workout]. Null when done, else why not. */
    suspend fun publish(context: Context, text: String, photo: String?, workout: CompletedWorkout?): String? = withContext(Dispatchers.IO) {
        // Posts are workouts you logged, with a few words; no photos (nothing to moderate them with).
        if (workout == null) return@withContext tr("Pick one of your workouts to share.")
        if (demo) {
            // The sample feed: the post goes on top here only.
            val w = workout?.let { parseWorkoutPost(workoutPostJson(it, LocalStorage.loadHistory(context).filter { h -> h.id < it.id }).toString()) }
            val me = Friends.Person("demo-me", Account.user?.name.orEmpty(), "you")
            withContext(Dispatchers.Main) { posts = listOf(Post("local${System.nanoTime()}", me, System.currentTimeMillis(), text.trim(), photo, w, emptyList(), 0)) + posts.orEmpty() }
            return@withContext null
        }
        val uid = me() ?: return@withContext tr("Sign in first.")
        if (Friends.myHandle == null && Friends.loadMe(context) == null) return@withContext tr("Pick a username in Friends first.")
        val data = HashMap<String, Any>()
        text.trim().take(300).takeIf { it.isNotEmpty() }?.let { data["text"] = it }
        workout?.let { w ->
            val before = LocalStorage.loadHistory(context).filter { it.id < w.id }
            data["workout"] = workoutPostJson(w, before).toString()
        }
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
            withContext(Dispatchers.Main) {
                posts = posts?.filter { it.id != post.id }
                personPosts = personPosts?.filter { it.id != post.id }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Likes or unlikes; the feed shows it at once and goes back if it fails. */
    suspend fun like(post: Post, on: Boolean): Boolean {
        if (demo) {
            change(post) { it.copy(likes = if (on) it.likes - "demo-me" + "demo-me" else it.likes - "demo-me") }
            return true
        }
        val uid = me() ?: return false
        fun update(edit: (List<String>) -> List<String>) = change(post) { it.copy(likes = edit(it.likes)) }
        update { if (on) (it - uid) + uid else it - uid }
        val ok = withContext(Dispatchers.IO) {
            try {
                val ref = posts(post.author.uid).document(post.id).collection("likes").document(uid)
                if (on) {
                    val batch = db().batch().set(ref, mapOf("at" to FieldValue.serverTimestamp()))
                    if (post.author.uid != uid) batch.set(Inbox.newNote(post.author.uid), Inbox.note("like", uid, post.author.uid, post.id))
                    batch.commit().waitFor()
                } else ref.delete().waitFor()
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!ok) update { if (on) it - uid else it + uid }
        return ok
    }

    suspend fun comments(post: Post): List<Comment>? = withContext(Dispatchers.IO) {
        if (demo) return@withContext demoComments[post.id].orEmpty().toList()
        try {
            posts(post.author.uid).document(post.id).collection("comments").orderBy("at").get(Source.SERVER).waitFor().documents.map {
                Comment(it.id, it.getString("uid").orEmpty(), it.getString("text").orEmpty(), it.getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis(), it.getString("replyTo"))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Comments on [post], or with [replyTo] answers that comment; the reply joins the thread of
     * [thread] (the top comment). Notes go to the post's author and to whoever is answered.
     */
    suspend fun comment(post: Post, text: String, replyTo: Comment? = null, thread: String? = replyTo?.id): Boolean = withContext(Dispatchers.IO) {
        if (demo) {
            demoComments.getOrPut(post.id) { mutableListOf() } += Comment("c${System.nanoTime()}", "demo-me", text.trim(), System.currentTimeMillis(), thread)
            withContext(Dispatchers.Main) { bump(post, +1) }
            return@withContext true
        }
        val uid = me() ?: return@withContext false
        val clean = text.trim().take(500)
        if (clean.isEmpty()) return@withContext false
        try {
            val ref = posts(post.author.uid).document(post.id).collection("comments").document()
            val data = hashMapOf<String, Any>("uid" to uid, "text" to clean, "at" to FieldValue.serverTimestamp())
            thread?.let { data["replyTo"] = it }
            val batch = db().batch().set(ref, data)
            val excerpt = clean.take(120)
            val answered = replyTo?.uid?.takeIf { it != uid }
            // The person answered hears "replied"; the post's author hears "commented" (unless they're the one answered, or it's you).
            if (answered != null) batch.set(Inbox.newNote(answered), Inbox.note("reply", uid, post.author.uid, post.id, ref.id, excerpt, replyTo.id))
            if (post.author.uid != uid && post.author.uid != answered) batch.set(Inbox.newNote(post.author.uid), Inbox.note("comment", uid, post.author.uid, post.id, ref.id, excerpt))
            batch.commit().waitFor()
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

    private fun bump(post: Post, by: Int) = change(post) { it.copy(comments = (it.comments + by).coerceAtLeast(0)) }

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
