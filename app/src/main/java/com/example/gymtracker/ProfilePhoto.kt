package com.example.gymtracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * Your profile picture: a 256 px square JPEG (about 20–30 KB), kept on the phone (profile_photo.txt,
 * base64) and in your profile (profiles/{uid}.photo) so friends see it. In the profile document
 * rather than Cloud Storage: Storage needs Firebase's paid plan, and an avatar this small fits a
 * document easily (the rules cap it).
 */
object ProfilePhoto {
    private const val SIZE = 256
    private const val FILE = "profile_photo.txt"

    /** Yours, decoded; null when you haven't picked one. */
    var mine by mutableStateOf<ImageBitmap?>(null)
        private set
    private var loaded = false

    private val cache = LruCache<Int, ImageBitmap>(40)

    /** A photo as stored (base64 JPEG) → image, decoded once. */
    fun decode(data: String?): ImageBitmap? {
        if (data.isNullOrBlank()) return null
        val key = data.hashCode()
        cache.get(key)?.let { return it }
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(key, it) }
        } catch (_: Exception) {
            null
        }
    }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        mine = decode(local(context))
    }

    fun local(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    private fun keep(context: Context, data: String?) {
        val f = File(context.filesDir, FILE)
        if (data == null) f.delete() else f.writeText(data)
        mine = decode(data)
    }

    /** A gallery photo as a centred square, [SIZE] px, upright, base64 JPEG; null if it can't be read. */
    fun encode(context: Context, uri: Uri): String? {
        // The workout-photo path already shrinks big photos and turns them upright.
        val path = PhotoStorage.copyUriToInternalStorage(context, uri) ?: return null
        val file = File(path)
        return try {
            val full = BitmapFactory.decodeFile(path) ?: return null
            val side = minOf(full.width, full.height)
            val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 82, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    /** Signed out: the phone's copy goes (the account keeps it), so it can't follow into another account. */
    fun signedOut(context: Context) = keep(context, null)

    /** Sets (or with null, removes) your photo here and, when signed in, in your profile. False if the upload failed. */
    suspend fun set(context: Context, data: String?): Boolean {
        withContext(Dispatchers.Main) { keep(context, data) }
        return withContext(Dispatchers.IO) { upload(data) }
    }

    private fun upload(data: String?): Boolean {
        val uid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null } ?: return true
        return try {
            Tasks.await(
                FirebaseFirestore.getInstance().collection("profiles").document(uid)
                    .set(mapOf("photo" to (data ?: FieldValue.delete())), SetOptions.merge()),
                20, TimeUnit.SECONDS
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * After signing in: the account's photo comes to this phone if it has none, or this phone's goes
     * up if the account has none.
     */
    suspend fun reconcile(context: Context, inProfile: String?) {
        val here = local(context)
        when {
            here == null && inProfile != null -> withContext(Dispatchers.Main) { keep(context, inProfile) }
            here != null && inProfile == null -> withContext(Dispatchers.IO) { upload(here) }
        }
    }
}

/** Opens the photo picker; the picked photo becomes your profile picture. */
@Composable
fun rememberProfilePhotoPicker(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val data = withContext(Dispatchers.IO) { ProfilePhoto.encode(context, uri) }
            when {
                data == null -> AppSnackbar.show(tr("Couldn't open that photo."))
                !ProfilePhoto.set(context, data) -> AppSnackbar.show(tr("Saved on this phone. Friends will see it once you're online."))
                else -> Friends.refresh()
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/**
 * Your avatar with a small camera badge: tapping it picks a photo, or when there is one, offers a
 * new one or removing it.
 */
@Composable
fun EditablePhoto(avatar: @Composable () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberProfilePhotoPicker()
    var menu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                .pressable { if (ProfilePhoto.mine == null) pick() else menu = true }
        ) { avatar() }
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd)
                .size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(P.bgCard).padding(2.dp)
                .clip(androidx.compose.foundation.shape.CircleShape).background(P.accentPrimary),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Rounded.PhotoCamera, tr("Profile picture"),
                tint = P.onAccentPrimary, modifier = androidx.compose.ui.Modifier.size(11.dp)
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = P.bgElevated) {
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text(tr("Choose a new photo"), style = AppType.body) },
                onClick = { menu = false; pick() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text(tr("Remove photo"), style = AppType.body, color = P.dangerText) },
                onClick = {
                    menu = false
                    scope.launch { ProfilePhoto.set(context, null); Friends.refresh() }
                }
            )
        }
    }
}
