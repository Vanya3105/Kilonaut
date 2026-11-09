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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.rounded.Lock
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
    // The newest workout unless one was handed in: posts are always workouts.
    var workout by remember { mutableStateOf(start ?: history.maxByOrNull { it.id }) }
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
    val choosePhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val canPost = !busy && workout != null
    // Leaving with something written or attached asks first: one Back press shouldn't lose a post.
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave = { if (text.isNotBlank() || photo != null || (workout != null && workout != start)) confirmDiscard = true else onClose() }
    val me = Friends.Person(Feed.me() ?: "demo-me", Account.user?.name.orEmpty(), Friends.myHandle ?: if (Feed.demo) "you" else "")
    // What the post will look like, rebuilt as it changes.
    val preview = remember(text, photo, workout) {
        val w = workout?.let { parseWorkoutPost(workoutPostJson(it, history.filter { h -> h.id < it.id }).toString()) }
        if (text.isBlank() && photo == null && w == null) null
        else Feed.Post("preview", me, System.currentTimeMillis(), text.trim(), photo, w, emptyList(), 0)
    }

    FullPage(
        tr("New post"), onClose = leave,
        actions = {
            val bg by androidx.compose.animation.animateColorAsState(if (canPost) P.accentPrimary else P.bgControl, label = "post_bg")
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(bg)
                    .pressable(enabled = canPost) {
                        busy = true
                        scope.launch {
                            val error = Feed.publish(context, text, photo, workout)
                            busy = false
                            if (error == null) { AppSnackbar.show(tr("Posted.")); onClose() } else AppSnackbar.show(error)
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) { Text(if (busy) tr("Posting…") else tr("Post"), style = AppType.label, color = if (canPost) P.onAccentPrimary else P.textTertiary) }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            // You, and the words.
            Row(verticalAlignment = Alignment.Top) {
                PersonAvatar(me, 44.dp, image = ProfilePhoto.mine)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(me.title(), style = AppType.label, color = P.textSecondary)
                    Box(Modifier.padding(top = 4.dp)) {
                        if (text.isEmpty()) Text(tr("How did it go? (optional)"), style = AppType.title, color = P.textTertiary)
                        BasicTextField(
                            value = text, onValueChange = { text = it.take(300) },
                            textStyle = AppType.title.copy(color = P.textPrimary), cursorBrush = SolidColor(P.accentPrimary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        )
                    }
                    if (text.length > 240) Text("${text.length}/300", style = AppType.caption, color = if (text.length >= 300) P.dangerText else P.textTertiary)
                }
            }
            Spacer(Modifier.height(18.dp))

            // The workout: every post is one you logged (and nothing else, so there's nothing to moderate but words).
            if (workout == null) {
                AttachTile(
                    Icons.Rounded.FitnessCenter, tr("Pick a workout"),
                    if (history.isEmpty()) tr("Finish a workout first, then share it here") else tr("One of your recent ones"),
                    Modifier.fillMaxWidth(), enabled = history.isNotEmpty()
                ) { picking = true }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    workout?.let { w -> RemovableTag(Icons.Rounded.FitnessCenter, shown(w.splitTitle)) { workout = null } }
                    if (history.size > 1) AttachChip(Icons.Rounded.FitnessCenter, tr("Change workout")) { picking = true }
                }
            }
            // The preview: the post as a small feed page.
            AnimatedVisibility(preview != null, enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut() + scaleOut(targetScale = 0.92f)) {
                Column(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("Preview"), style = AppType.overline, color = P.textTertiary)
                    Spacer(Modifier.height(10.dp))
                    preview?.let { MiniFeedPage(it) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = P.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Only your friends see your posts."), style = AppType.caption, color = P.textTertiary)
            }
        }
    }

    if (confirmDiscard) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        containerColor = P.bgElevated,
        title = { Text(tr("Discard this post?"), style = AppType.headline, color = P.textPrimary) },
        text = { Text(tr("What you wrote and attached won't be kept."), style = AppType.body, color = P.textSecondary) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(tr("Discard"), color = P.dangerText, style = AppType.label) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDiscard = false }) { Text(tr("Keep editing"), color = P.textPrimary, style = AppType.label) } }
    )

    if (picking) AppBottomSheet(onDismiss = { picking = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(tr("Pick a workout"), style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(10.dp))
            history.sortedByDescending { it.id }.take(12).forEach { w ->
                val selected = workout?.id == w.id
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (selected) P.accentSoft else Color.Transparent)
                        .pressable { workout = w; picking = false }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) P.accentPrimary else P.bgControl), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.FitnessCenter, null, tint = if (selected) P.onAccentPrimary else P.textSecondary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(shown(w.splitTitle), style = AppType.bodyStrong, color = P.textPrimary)
                        Text(
                            listOfNotNull(relativeDay(w.id).replaceFirstChar { it.uppercase() }, formatDuration(w.durationSeconds), formatVolume(w.totalVolumeKg).takeIf { w.totalVolumeKg > 0 }).joinToString(" · "),
                            style = AppType.caption, color = P.textSecondary
                        )
                    }
                    if (selected) Icon(Icons.Rounded.Check, null, tint = P.accentPrimary)
                }
            }
        }
    }
}

/** A post as it will look in the feed, at about half size, in a phone-shaped frame. */
@Composable
private fun MiniFeedPage(post: Feed.Post) {
    val P = LocalPalette.current
    val full = androidx.compose.ui.unit.DpSize(380.dp, 720.dp)
    val scale = 0.5f
    Box(
        Modifier.size(full.width * scale, full.height * scale).clip(RoundedCornerShape(26.dp))
            .border(6.dp, P.textPrimary.copy(alpha = 0.9f), RoundedCornerShape(26.dp)).background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.requiredSize(full).graphicsLayer { scaleX = scale; scaleY = scale }) {
            PostPage(post, Feed.people, current = true, onComments = {}, onWorkout = {}, onPerson = {})
        }
    }
}

@Composable
private fun AttachTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val P = LocalPalette.current
    Column(
        modifier.height(132.dp).clip(RoundedCornerShape(20.dp)).background(P.bgCard)
            .border(1.5.dp, P.borderStrong, RoundedCornerShape(20.dp))
            .pressable(enabled = enabled, onClick = onClick).padding(14.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = P.accentPrimary, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(title, style = AppType.bodyStrong, color = P.textPrimary)
            Text(detail, style = AppType.caption, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RemovableTag(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onRemove: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(P.accentSoft).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = P.accentPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = AppType.label, color = P.accentPrimary, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(26.dp).clip(CircleShape).pressable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, tr("Remove"), tint = P.accentPrimary, modifier = Modifier.size(16.dp))
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
