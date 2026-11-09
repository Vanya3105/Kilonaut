package com.example.gymtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/*
 * Notifications for friend requests, likes, comments and replies, without a server (push would
 * need Firebase's paid plan). Whoever likes, comments or follows also leaves a small note in the
 * other person's inbox (users/{them}/inbox), in the same write; the rules only accept a note when
 * the like, comment or follow it describes really exists. Each phone looks in its own inbox every
 * ~15 minutes (WorkManager, when online) and when the app opens, shows a notification per note and
 * deletes the notes: one cheap read per look.
 */
object Inbox {
    private const val CHANNEL_ID = "friends"
    private const val WORK = "friend-news"

    private fun db() = FirebaseFirestore.getInstance()

    fun newNote(to: String): DocumentReference = db().collection("users").document(to).collection("inbox").document()

    /** A note: [type] follow / like / comment / reply; [owner] the post's author; [parent] the comment answered. */
    fun note(type: String, from: String, owner: String, post: String? = null, comment: String? = null, text: String? = null, parent: String? = null): Map<String, Any> =
        buildMap {
            put("type", type); put("from", from); put("owner", owner); put("at", FieldValue.serverTimestamp())
            post?.let { put("post", it) }
            comment?.let { put("comment", it) }
            text?.let { put("text", it) }
            parent?.let { put("parent", it) }
        }

    /** Starts the regular look (signed in), and looks once now. */
    fun start(context: Context) {
        val wm = WorkManager.getInstance(context)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        )
        wm.enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<InboxWorker>().setConstraints(online).build())
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** One look: notifications for what's new, then the notes go. */
    /** [notify]: also show notifications (not when the Activity page itself is looking). */
    suspend fun check(context: Context, notify: Boolean = true) = withContext(Dispatchers.IO) {
        AppLanguage.applyInBackground(context)
        val me = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null } ?: return@withContext
        val notes = runCatching {
            Tasks.await(db().collection("users").document(me).collection("inbox").limit(50).get(Source.SERVER), 20, TimeUnit.SECONDS).documents
        }.getOrNull() ?: return@withContext
        if (notes.isEmpty()) { ActivityLog.refreshUnread(context, me); return@withContext }
        val iFollow = runCatching {
            Tasks.await(db().collection("users").document(me).collection("following").get(Source.SERVER), 20, TimeUnit.SECONDS).documents.map { it.id }.toSet()
        }.getOrDefault(emptySet())
        val names = HashMap<String, String>()
        fun name(uid: String) = names.getOrPut(uid) { Friends.personNow(uid).title().ifBlank { tr("Someone") } }
        val canShow = notify && NotificationManagerCompat.from(context).areNotificationsEnabled()
        // One notification per thing: liking, unliking and liking again leaves two notes.
        val shown = HashSet<String>()
        val logged = ArrayList<ActivityLog.Item>()
        for (n in notes) {
            val from = n.getString("from").orEmpty()
            val key = listOf(n.getString("type"), from, n.getString("post"), n.getString("comment")).joinToString("|")
            val fresh = from.isNotBlank() && !Friends.isBlocked(from) && shown.add(key)
            if (fresh) logged += ActivityLog.Item(
                n.getString("type").orEmpty(), from, n.getString("text").orEmpty(), n.getString("owner").orEmpty(), n.getString("post").orEmpty(),
                n.getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis(), accepted = n.getString("type") == "follow" && from in iFollow
            )
            if (canShow && fresh && SocialPrefs.on(context, SocialPrefs.kindOf(n.getString("type")))) {
                val text = n.getString("text").orEmpty()
                val (title, body) = when (n.getString("type")) {
                    "follow" -> if (from in iFollow) tr("{0} accepted your friend request", name(from)) to tr("You can see each other's training now.")
                    else tr("{0} wants to be friends", name(from)) to tr("Open Friends to accept.")
                    "like" -> tr("{0} liked your workout", name(from)) to ""
                    "comment" -> tr("{0} commented", name(from)) to text
                    "reply" -> tr("{0} replied to you", name(from)) to text
                    else -> null to ""
                }
                if (title != null) show(context, n.id.hashCode(), title, body)
            }
            runCatching { n.reference.delete() }
        }
        ActivityLog.add(context, me, logged)
    }

    private fun show(context: Context, id: Int, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("Friends"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = tr("Friend requests, likes, comments and replies")
                }
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(title)
            .apply { if (body.isNotBlank()) setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)) }
            .setContentIntent(WorkoutReminders.openAppIntent(context, 7400 + (id and 0xff), AppShortcuts.OPEN_FEED))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()
        runCatching { manager.notify(id, n) }
    }
}

class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Inbox.check(applicationContext)
        return Result.success()
    }
}
