package com.example.gymtracker

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/*
 * Deleting an account (Google Play requires it). Firebase only deletes a sign-in that's recent, so
 * the person confirms who they are first (Google's picker, or their password) and nothing is
 * deleted until that worked. Then, as them: their likes and comments on friends' posts, their own
 * posts with everything under them, every follow in both directions, their training, stats,
 * username and profile; and last the sign-in itself. Syncing is paused throughout, so a sync can't
 * put anything back. The phone's own copy is kept or wiped, as they chose.
 */
object AccountDeletion {
    private fun db() = FirebaseFirestore.getInstance()
    private fun <T> Task<T>.waitFor(): T = Tasks.await(this, 30, TimeUnit.SECONDS)

    /** Confirms who's signed in: Google's picker for Google accounts, [password] for email ones. Null when confirmed, else why not. */
    suspend fun confirm(context: Context, password: String?): String? {
        val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null } ?: return tr("Sign in first.")
        return try {
            val credential = if (Account.user?.viaGoogle == true) {
                val token = Account.googleIdToken(context) ?: return tr("Choose your Google account to confirm it's you.")
                GoogleAuthProvider.getCredential(token, null)
            } else {
                val email = user.email ?: return tr("Couldn't confirm it's you.")
                if (password.isNullOrEmpty()) return tr("Type your password.")
                EmailAuthProvider.getCredential(email, password)
            }
            user.reauthenticate(credential).await()
            null
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            tr("That password isn't right.")
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            tr("That's a different account. Choose the one you're signed in with.")
        } catch (e: Exception) {
            if (e.message?.contains("does not correspond", ignoreCase = true) == true) tr("That's a different account. Choose the one you're signed in with.")
            else tr("Couldn't confirm it's you. Check your connection.")
        }
    }

    /** Deletes the account's data and the account. Null when done, else why not (nothing more is deleted after a failure). */
    suspend fun deleteEverything(context: Context): String? = withContext(Dispatchers.IO) {
        val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null } ?: return@withContext tr("Sign in first.")
        val me = user.uid
        CloudSync.paused = true
        // A sync already under way finishes first (up to 30 s), so it can't upload after the delete.
        repeat(60) { if (CloudSync.status.running) Thread.sleep(500) }
        try {
            val db = db()
            val mine = db.collection("users").document(me)
            val doomed = ArrayList<DocumentReference>()
            fun all(ref: com.google.firebase.firestore.CollectionReference) =
                runCatching { ref.get(Source.SERVER).waitFor().documents.map { it.reference } }.getOrDefault(emptyList())

            // Friends' posts: my likes and comments there (their posts are theirs to keep).
            val following = all(mine.collection("following")).map { it.id }
            val followers = all(mine.collection("followers")).map { it.id }
            for (friend in following intersect followers.toSet()) {
                for (post in all(db.collection("users").document(friend).collection("posts"))) {
                    doomed += post.collection("likes").document(me)
                    doomed += runCatching {
                        post.collection("comments").whereEqualTo("uid", me).get(Source.SERVER).waitFor().documents.map { it.reference }
                    }.getOrDefault(emptyList())
                }
            }
            // My posts, with their likes and comments.
            for (post in all(mine.collection("posts"))) {
                doomed += all(post.collection("likes"))
                doomed += all(post.collection("comments"))
                doomed += post
            }
            // Follows both ways, each with its mirror on the other side.
            following.forEach { o -> doomed += mine.collection("following").document(o); doomed += db.collection("users").document(o).collection("followers").document(me) }
            followers.forEach { o -> doomed += mine.collection("followers").document(o); doomed += db.collection("users").document(o).collection("following").document(me) }
            // Training, stats, blocks.
            listOf("workouts", "weighins", "state", "public", "blocked", "inbox").forEach { doomed += all(mine.collection(it)) }
            // Username and profile.
            val handle = runCatching { db.collection("profiles").document(me).get(Source.SERVER).waitFor().getString("handle") }.getOrNull()
            if (handle != null) doomed += db.collection("handles").document(handle)
            doomed += db.collection("profiles").document(me)

            doomed.distinctBy { it.path }.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it) }
                batch.commit().waitFor()
            }

            user.delete().await()
            // Forget what this phone synced with the account.
            val prefs = context.getSharedPreferences("gym_tracker_cloud", Context.MODE_PRIVATE)
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("$me.") }.forEach { remove(it) }
                if (prefs.getString("lastUid", null) == me) { remove("lastUid"); remove("lastEmail") }
            }.apply()
            null
        } catch (e: Exception) {
            android.util.Log.w("AccountDeletion", "Delete failed", e)
            tr("Couldn't delete everything. Check your connection and try again.")
        } finally {
            CloudSync.paused = false
        }
    }
}
