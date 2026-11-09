package com.example.gymtracker

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The feed tab: friends' workouts and photos, newest first, with likes and comments. Cards in the
// style of Hevy / Strava: who and when, the words, the photo full width, the workout as a block
// of numbers and its top lifts, then the heart and the comment count.

/** Like Instagram's: "now", "5m", "3h", "2d", then the date ("Nov 2"). */
internal fun postAge(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> tr("now")
        minutes < 60 -> tr("{0}m", minutes)
        minutes < 24 * 60 -> tr("{0}h", minutes / 60)
        minutes < 7 * 24 * 60 -> tr("{0}d", minutes / (24 * 60))
        else -> DateUtils.formatShortDay(java.util.Date(at))
    }
}

/** A full-screen page over the app, with its own back. Status-bar icons follow the theme. */
@Composable
internal fun FullPage(title: String?, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, bottomBar: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val P = LocalPalette.current
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = P.bgPrimary.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = light }
        }
        Column(Modifier.fillMaxSize().background(P.bgPrimary).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), onClose)
                Spacer(Modifier.width(12.dp))
                if (title != null) Text(title, style = AppType.title, color = P.textPrimary, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                actions()
            }
            Column(Modifier.weight(1f)) { content() }
            bottomBar?.invoke()
        }
    }
}

/** What the composer starts with (a workout from the finish screen, or nothing). */
data class ComposeStart(val workout: CompletedWorkout?)

/** A workout in a post: the day, its numbers, and its lifts with their best sets (yours in your units). */
@Composable
internal fun WorkoutBlock(w: WorkoutPost, full: Boolean) {
    val P = LocalPalette.current
    Column(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.bgControl).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.FitnessCenter, null, tint = P.accentPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(shown(w.title), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateUtils.formatDisplayDate(java.util.Date(w.at)), style = AppType.caption, color = P.textSecondary)
            }
            if (w.bests > 0) Pill(
                if (w.bests == 1) tr("New best") else tr("{0} new bests", w.bests),
                icon = Icons.Rounded.EmojiEvents, color = P.warningText, container = P.warningSurface
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            @Composable
            fun Stat(value: String, label: String, modifier: Modifier) {
                Column(modifier) {
                    Text(value, style = AppType.title.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                    Text(label, style = AppType.caption, color = P.textSecondary)
                }
            }
            Stat(formatDuration(w.seconds), tr("Time"), Modifier.weight(1f))
            if (w.kg > 0) Stat(formatVolume(w.kg), tr("Volume"), Modifier.weight(1f))
            Stat("${w.sets}", tr("Sets"), Modifier.weight(1f))
        }
        if (w.exercises.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.borderSubtle))
            Spacer(Modifier.height(4.dp))
            val shownCount = if (full) w.exercises.size else 3
            w.exercises.take(shownCount).forEach { e ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    ExerciseThumb(e.name, size = 34.dp, showEmpty = true)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tr("{0} × {1}", e.sets, shown(e.name)), style = AppType.body, color = P.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    e.best?.let { b ->
                        Spacer(Modifier.width(8.dp))
                        if (e.isBest) Icon(Icons.Rounded.EmojiEvents, tr("New best"), tint = P.warningText, modifier = Modifier.size(15.dp).padding(end = 3.dp))
                        Text(formatSet(b, e.type), style = AppType.label.copy(fontFeatureSettings = "tnum"), color = if (e.isBest) P.warningText else P.textSecondary)
                    }
                }
            }
            if (w.exercises.size > shownCount) Text(
                if (w.exercises.size - shownCount == 1) tr("1 more exercise") else tr("{0} more exercises", w.exercises.size - shownCount),
                style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(top = 4.dp, start = 44.dp)
            )
        }
    }
}

/** The composer with [workout] attached, for the finish screen's Share to feed. */
@Composable
fun ShareWorkoutComposer(workout: CompletedWorkout?, history: List<CompletedWorkout>, onClose: () -> Unit) = PostComposer(workout, history, onClose)

/** A new post: a few words, a photo, a workout; any of them. */
@Composable
internal fun PostComposer(start: CompletedWorkout?, history: List<CompletedWorkout>, onClose: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    var workout by remember { mutableStateOf(start) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadingPhoto by remember { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loadingPhoto = true
            scope.launch {
                photo = withContext(Dispatchers.IO) { Feed.encodePhoto(context, uri) } ?: photo.also { AppSnackbar.show(tr("Couldn't open that photo.")) }
                loadingPhoto = false
            }
        }
    }
    val canPost = !busy && (text.isNotBlank() || photo != null || workout != null)
    val me = Friends.Person(Feed.me().orEmpty(), Account.user?.name.orEmpty(), Friends.myHandle.orEmpty())

    FullPage(
        tr("New post"), onClose = onClose,
        actions = {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (canPost) P.accentPrimary else P.bgControl)
                    .pressable(enabled = canPost) {
                        busy = true
                        scope.launch {
                            val error = Feed.publish(context, text, photo, workout)
                            busy = false
                            if (error == null) { AppSnackbar.show(tr("Posted.")); onClose() } else AppSnackbar.show(error)
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) { Text(if (busy) tr("Posting…") else tr("Post"), style = AppType.label, color = if (canPost) P.onAccentPrimary else P.textTertiary) }
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(P.bgCard).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AttachChip(Icons.Rounded.Image, if (photo == null) tr("Photo") else tr("Change photo")) {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (history.isNotEmpty()) AttachChip(Icons.Rounded.FitnessCenter, if (workout == null) tr("Workout") else tr("Change workout")) { picking = true }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                PersonAvatar(me, 40.dp, image = ProfilePhoto.mine)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).padding(top = 8.dp)) {
                    if (text.isEmpty()) Text(tr("How did it go?"), style = AppType.body, color = P.textTertiary)
                    BasicTextField(
                        value = text, onValueChange = { text = it.take(1000) },
                        textStyle = AppType.body.copy(color = P.textPrimary), cursorBrush = SolidColor(P.accentPrimary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                    )
                }
            }
            if (loadingPhoto) Text(tr("Loading…"), style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(top = 12.dp))
            Feed.image(photo)?.let { img ->
                Spacer(Modifier.height(14.dp))
                Box {
                    Image(
                        img, null,
                        Modifier.fillMaxWidth().aspectRatio((img.width.toFloat() / img.height).coerceIn(0.8f, 1.9f)).clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                    RemoveBadge(Modifier.align(Alignment.TopEnd).padding(10.dp)) { photo = null }
                }
            }
            workout?.let { w ->
                Spacer(Modifier.height(14.dp))
                val preview = remember(w.id) { parseWorkoutPost(workoutPostJson(w, history.filter { it.id < w.id }).toString()) }
                if (preview != null) Box {
                    Box(Modifier.padding(horizontal = (-14).dp)) { WorkoutBlock(preview, full = false) }
                    RemoveBadge(Modifier.align(Alignment.TopEnd).padding(8.dp)) { workout = null }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(tr("Only your friends see your posts."), style = AppType.caption, color = P.textTertiary)
        }
    }

    if (picking) AppBottomSheet(onDismiss = { picking = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(tr("Pick a workout"), style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(10.dp))
            history.sortedByDescending { it.id }.take(12).forEach { w ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable { workout = w; picking = false }.padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(shown(w.splitTitle), style = AppType.bodyStrong, color = P.textPrimary)
                        Text(
                            listOf(relativeDay(w.id), formatDuration(w.durationSeconds)).joinToString(" · "),
                            style = AppType.caption, color = P.textSecondary
                        )
                    }
                    if (workout?.id == w.id) Icon(Icons.Rounded.Check, null, tint = P.accentPrimary)
                }
            }
        }
    }
}

@Composable
private fun AttachChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(P.bgControl).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = P.textPrimary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = AppType.label, color = P.textPrimary)
    }
}

@Composable
private fun RemoveBadge(modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Close, tr("Remove"), tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
