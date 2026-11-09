package com.example.gymtracker

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Kilonaut account (Firebase Authentication): Google or email and password. Optional: without
 * one, or without google-services.json in the build, everything stays on the phone as before.
 * Sign-in for Drive backups is separate (DriveBackup), so signing out here leaves backups alone.
 */
object Account {
    private const val TAG = "Account"

    /** Who is signed in, for the UI; null when nobody is. */
    data class User(val uid: String, val email: String?, val name: String?, val viaGoogle: Boolean, val verified: Boolean, val photoUrl: String? = null)

    var user by mutableStateOf<User?>(null)
        private set

    private var listening = false

    /** False when the build has no Firebase project: the account section stays hidden. */
    fun available(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private fun auth() = FirebaseAuth.getInstance()

    /** Starts following sign-in changes (also ones from another screen or an expired session). */
    fun start(context: Context) {
        if (listening || !available(context)) return
        listening = true
        val app = context.applicationContext
        auth().addAuthStateListener {
            val now = it.currentUser?.toUser()
            // A new sign-in brings the account's data in (and sends this phone's) right away.
            if (now != null && now.uid != user?.uid) CloudSync.request(app, now = true)
            if (now == null) { CloudSync.signedOut(); Friends.signedOut(); Feed.signedOut(); ProfilePhoto.signedOut(app) }
            user = now
        }
        user = auth().currentUser?.toUser()
    }

    private fun FirebaseUser.toUser() = User(
        uid = uid,
        email = email,
        name = displayName?.takeIf { it.isNotBlank() },
        viaGoogle = providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID },
        // Google accounts' addresses are verified by Google.
        verified = isEmailVerified || providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID },
        photoUrl = photoUrl?.toString()
    )

    private fun refresh() {
        user = auth().currentUser?.toUser()
    }

    /** Google's account picker on its own: the chosen account's ID token, or null (cancelled or failed). */
    suspend fun googleIdToken(context: Context): String? {
        val activity = context.findActivity() ?: return null
        val clientId = webClientId(context) ?: return null
        return try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
                .build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Google confirm", e)
            null
        }
    }

    /** Google's account picker, then Firebase. Null on success (or when cancelled), else what went wrong. */
    suspend fun signInWithGoogle(context: Context): String? {
        val activity = context.findActivity() ?: return tr("Couldn't open Google sign-in").also { Log.w(TAG, "No activity") }
        val clientId = webClientId(context) ?: return tr("Couldn't open Google sign-in").also { Log.w(TAG, "No default_web_client_id") }
        return try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
                .build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Log.w(TAG, "Unexpected credential ${credential.type}")
                return tr("Couldn't sign in with Google")
            }
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            auth().signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            refresh()
            null
        } catch (_: GetCredentialCancellationException) {
            null
        } catch (_: NoCredentialException) {
            tr("No Google account on this phone. Add one in Android's settings, or use email.")
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Google sign-in", e)
            tr("Couldn't sign in with Google")
        } catch (e: Exception) {
            authError(e)
        }
    }

    /** Signs in, or with [create] makes the account and sends the verification email. */
    suspend fun withEmail(email: String, password: String, create: Boolean): String? = try {
        val address = email.trim()
        if (create) {
            val result = auth().createUserWithEmailAndPassword(address, password).await()
            result.user?.sendEmailVerification()?.await()
        } else {
            auth().signInWithEmailAndPassword(address, password).await()
        }
        refresh()
        null
    } catch (e: Exception) {
        authError(e)
    }

    suspend fun resetPassword(email: String): String? = try {
        auth().sendPasswordResetEmail(email.trim()).await()
        null
    } catch (e: Exception) {
        authError(e)
    }

    suspend fun resendVerification(): String? = try {
        auth().currentUser?.sendEmailVerification()?.await()
        null
    } catch (e: Exception) {
        authError(e)
    }

    /** After the link in the email was opened: reads the account again. True when it's verified now. */
    suspend fun checkVerified(): Boolean = try {
        auth().currentUser?.reload()?.await()
        refresh()
        user?.verified == true
    } catch (_: Exception) {
        false
    }

    /** Signs out here and forgets the Google account choice, so the next sign-in asks again. */
    suspend fun signOut(context: Context) {
        auth().signOut()
        user = null
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {}
    }

    private fun authError(e: Exception): String {
        Log.w(TAG, "Auth", e)
        return when (e) {
            is FirebaseNetworkException -> tr("No connection. Try again when you're online.")
            is FirebaseTooManyRequestsException -> tr("Too many tries. Wait a few minutes and try again.")
            is FirebaseAuthWeakPasswordException -> tr("Use a password of at least 6 characters.")
            is FirebaseAuthUserCollisionException -> tr("There's already an account with this email. Sign in instead.")
            // Wrong password and unknown email look the same on purpose (no way to probe for accounts).
            is FirebaseAuthInvalidUserException, is FirebaseAuthInvalidCredentialsException ->
                if ((e as com.google.firebase.auth.FirebaseAuthException).errorCode == "ERROR_INVALID_EMAIL") tr("That email address doesn't look right.")
                else tr("Wrong email or password.")
            else -> tr("Something went wrong. Try again.")
        }
    }

    /** The OAuth web client id the google-services plugin writes; looked up by name so builds without it compile. */
    private fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id)
    }
}

/** A Play services task as a suspend call. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) cont.resumeWithException(error)
        else if (task.isCanceled) cont.cancel()
        else cont.resume(task.result)
    }
}
