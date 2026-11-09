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

/** "now", "5m", "3h", "Yesterday", then the date. */
internal fun postAge(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> tr("now")
        minutes < 60 -> tr("{0}m", minutes)
        minutes < 24 * 60 && android.text.format.DateUtils.isToday(at) -> tr("{0}h", minutes / 60)
        android.text.format.DateUtils.isToday(at + android.text.format.DateUtils.DAY_IN_MILLIS) -> tr("Yesterday")
        else -> DateUtils.formatDisplayDate(java.util.Date(at))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(history: List<CompletedWorkout>) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Account.user
    var friendsOpen by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf<ComposeStart?>(null) }
    var openPost by remember { mutableStateOf<Feed.Post?>(null) }

    LaunchedEffect(user?.uid) {
        if (user != null) {
            Friends.loadMe(context)
            Feed.refresh(context)
        }
    }
    val posts = Feed.posts
    val handle = Friends.myHandle

    PullToRefreshBox(
        isRefreshing = Feed.loading && posts != null,
        onRefresh = { scope.launch { Feed.refresh(context) } },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Feed"), style = AppType.display, color = P.textPrimary, modifier = Modifier.weight(1f))
                    Box {
                        CircleIconButton(Icons.Rounded.Group, tr("Friends"), { friendsOpen = true })
                        if (Friends.requestCount > 0) Box(
                            Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(P.accentPrimary).border(2.dp, P.bgPrimary, CircleShape)
                        )
                    }
                    if (user != null && handle != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(P.accentPrimary).pressable { composing = ComposeStart(null) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Add, tr("New post"), tint = P.onAccentPrimary, modifier = Modifier.size(24.dp)) }
                    }
                }
            }
            when {
                user == null -> item {
                    FeedMessage(Icons.Rounded.Group, tr("Your friends' training"), tr("Sign in and add friends to see their workouts and photos here, and share yours.")) {
                        PrimaryButton(tr("Sign in"), { friendsOpen = true }, Modifier.fillMaxWidth())
                    }
                }
                handle == null -> item {
                    FeedMessage(Icons.Rounded.PersonAdd, tr("Set up Friends"), tr("Pick a username so friends can find you. Then their workouts and photos show up here.")) {
                        PrimaryButton(tr("Pick a username"), { friendsOpen = true }, Modifier.fillMaxWidth())
                    }
                }
                posts == null -> items(3) { PostSkeleton() }
                posts.isEmpty() -> item {
                    val last = history.maxByOrNull { it.id }
                    FeedMessage(Icons.Rounded.FitnessCenter, tr("Nothing here yet"), tr("Share a workout or a photo, and add friends to see theirs.")) {
                        if (last != null) PrimaryButton(tr("Share your last workout"), { composing = ComposeStart(last) }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(tr("Find friends"), { friendsOpen = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PersonAdd)
                    }
                }
                else -> items(posts, key = { it.author.uid + "/" + it.id }) { post ->
                    PostCard(post, onOpen = { openPost = post })
                }
            }
        }
    }

    if (friendsOpen) FullPage(tr("Friends"), onClose = { friendsOpen = false; scope.launch { Feed.refresh(context) } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) { FriendsSection() }
    }
    composing?.let { start -> PostComposer(start.workout, history, onClose = { composing = null }) }
    openPost?.let { p ->
        // Follow the feed's copy, so likes and counts stay in step.
        val live = Feed.posts?.firstOrNull { it.id == p.id && it.author.uid == p.author.uid } ?: p
        PostPage(live, onClose = { openPost = null })
    }
}

/** What the composer starts with (a workout from the finish screen, or nothing). */
data class ComposeStart(val workout: CompletedWorkout?)

@Composable
private fun FeedMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, actions: @Composable ColumnScope.() -> Unit) {
    val P = LocalPalette.current
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = P.accentPrimary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(body, style = AppType.body, color = P.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            actions()
        }
    }
}

@Composable
private fun PostSkeleton() {
    val P = LocalPalette.current
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(P.bgControl))
            Spacer(Modifier.width(12.dp))
            Column {
                Box(Modifier.size(120.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(P.bgControl))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(70.dp, 10.dp).clip(RoundedCornerShape(5.dp)).background(P.bgControl))
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)).background(P.bgControl))
    }
}

/** One post: who and when, words, photo (double-tap to like), workout, then heart and comments. */
@Composable
private fun PostCard(post: Feed.Post, onOpen: (() -> Unit)?, full: Boolean = false) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val liked = post.likedByMe()
    val heartPop = remember { Animatable(0f) }
    val toggleLike = { on: Boolean ->
        scope.launch { if (!Feed.like(post, on)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) }
        Unit
    }

    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
        // Who and when.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(post.author, 40.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(post.author.title(), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(post.author.handle.takeIf { it.isNotBlank() && post.author.name.isNotBlank() }?.let { "@$it" }, postAge(post.createdAt)).joinToString(" · "),
                    style = AppType.caption, color = P.textSecondary, maxLines = 1
                )
            }
            if (post.mine) Box {
                CircleIconButton(Icons.Rounded.MoreHoriz, tr("More"), { menu = true }, size = 36.dp, tint = P.textSecondary, container = Color.Transparent)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = P.bgElevated) {
                    DropdownMenuItem(
                        text = { Text(tr("Delete post"), style = AppType.body, color = P.dangerText) },
                        onClick = { menu = false; confirmDelete = true }
                    )
                }
            }
        }

        if (post.text.isNotBlank()) Text(
            post.text, style = AppType.body, color = P.textPrimary,
            maxLines = if (full) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
        )

        Feed.image(post.photo)?.let { img ->
            val ratio = (img.width.toFloat() / img.height).coerceIn(0.8f, 1.9f)
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio).background(P.bgControl)
                    .pointerInput(post.id) {
                        detectTapGestures(
                            onTap = { onOpen?.invoke() },
                            onDoubleTap = {
                                if (!post.likedByMe()) toggleLike(true)
                                scope.launch {
                                    heartPop.snapTo(0f)
                                    heartPop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
                                    kotlinx.coroutines.delay(350)
                                    heartPop.animateTo(0f, tween(220))
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // The heart that pops on a double-tap.
                Icon(
                    Icons.Rounded.Favorite, null, tint = Color.White,
                    modifier = Modifier.size(96.dp).graphicsLayer {
                        scaleX = 0.6f + 0.4f * heartPop.value; scaleY = scaleX; alpha = heartPop.value.coerceIn(0f, 1f) * 0.95f
                    }
                )
            }
        }

        post.workout?.let { w ->
            Spacer(Modifier.height(if (post.photo != null) 12.dp else 0.dp))
            WorkoutBlock(w, full)
        }

        // Heart, comments, who liked it.
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val scale = remember { Animatable(1f) }
            LaunchedEffect(liked) {
                if (liked) {
                    scale.snapTo(0.7f)
                    scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
                }
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).pressable { toggleLike(!liked) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (liked) tr("Unlike") else tr("Like"),
                    tint = if (liked) Color(0xFFE5484D) else P.textSecondary,
                    modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                )
                if (post.likes.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text("${post.likes.size}", style = AppType.label.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                }
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).pressable { onOpen?.invoke() }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.ChatBubbleOutline, tr("Comments"), tint = P.textSecondary, modifier = Modifier.size(22.dp))
                if (post.comments > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("${post.comments}", style = AppType.label.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                }
            }
        }
        val likers = post.likes.mapNotNull { uid -> if (uid == Feed.me()) tr("you") else Feed.people[uid]?.title() }
        if (post.likes.isNotEmpty()) Text(
            when {
                likers.isEmpty() -> if (post.likes.size == 1) tr("1 like") else tr("{0} likes", post.likes.size)
                post.likes.size == 1 -> tr("Liked by {0}", likers[0])
                else -> tr("Liked by {0} and {1} more", likers[0], post.likes.size - 1)
            },
            style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
    }

    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = P.bgElevated,
        title = { Text(tr("Delete this post?"), style = AppType.headline, color = P.textPrimary) },
        text = { Text(tr("Its likes and comments go with it."), style = AppType.body, color = P.textSecondary) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                confirmDelete = false
                scope.launch { if (!Feed.delete(context, post)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) }
            }) { Text(tr("Delete"), color = P.dangerText, style = AppType.label) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
    )
}

/** A workout in a post: the day, its numbers, and its lifts with their best sets (yours in your units). */
@Composable
private fun WorkoutBlock(w: WorkoutPost, full: Boolean) {
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

/** A post on its own page: everything in it, all its comments, and a box to add one. */
@Composable
private fun PostPage(post: Feed.Post, onClose: () -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<Feed.Comment>?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(post.id) { comments = Feed.comments(post) }
    val people = Feed.people

    FullPage(
        null, onClose = onClose,
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(P.bgCard).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(P.bgControl).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Text(tr("Add a comment…"), style = AppType.body, color = P.textTertiary)
                        BasicTextField(
                            value = text, onValueChange = { text = it.take(500) },
                            textStyle = AppType.body.copy(color = P.textPrimary), cursorBrush = SolidColor(P.accentPrimary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            maxLines = 4, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                val canSend = text.isNotBlank() && !sending
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (canSend) P.accentPrimary else P.bgControl)
                        .pressable(enabled = canSend) {
                            sending = true
                            scope.launch {
                                if (Feed.comment(post, text)) {
                                    text = ""
                                    comments = Feed.comments(post)
                                } else AppSnackbar.show(tr("Couldn't send it. Check your connection."))
                                sending = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.AutoMirrored.Rounded.Send, tr("Send"), tint = if (canSend) P.onAccentPrimary else P.textTertiary, modifier = Modifier.size(20.dp)) }
            }
        }
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
            item { PostCard(post, onOpen = null, full = true) }
            item {
                Text(
                    if (post.comments == 1) tr("1 comment") else tr("{0} comments", post.comments),
                    style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
                )
            }
            val list = comments
            when {
                list == null -> item { Text(tr("Loading…"), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(4.dp)) }
                list.isEmpty() -> item { Text(tr("No comments yet. Say something nice."), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(4.dp)) }
                else -> items(list, key = { it.id }) { c ->
                    val who = people[c.uid] ?: Friends.Person(c.uid, "", tr("someone"))
                    var menu by remember { mutableStateOf(false) }
                    val canDelete = c.uid == Feed.me() || post.mine
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        PersonAvatar(who, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(P.bgCard).padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(who.title(), style = AppType.label, color = P.textPrimary, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(6.dp))
                                Text(postAge(c.at), style = AppType.caption, color = P.textTertiary)
                            }
                            Text(c.text, style = AppType.body, color = P.textPrimary)
                        }
                        if (canDelete) Box {
                            CircleIconButton(Icons.Rounded.MoreHoriz, tr("More"), { menu = true }, size = 32.dp, tint = P.textTertiary, container = Color.Transparent)
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = P.bgElevated) {
                                DropdownMenuItem(
                                    text = { Text(tr("Delete comment"), style = AppType.body, color = P.dangerText) },
                                    onClick = {
                                        menu = false
                                        scope.launch { if (Feed.deleteComment(post, c)) comments = comments?.filter { it.id != c.id } }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The composer with [workout] attached, for the finish screen's Share to feed. */
@Composable
fun ShareWorkoutComposer(workout: CompletedWorkout?, history: List<CompletedWorkout>, onClose: () -> Unit) = PostComposer(workout, history, onClose)

/** A new post: a few words, a photo, a workout; any of them. */
@Composable
private fun PostComposer(start: CompletedWorkout?, history: List<CompletedWorkout>, onClose: () -> Unit) {
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
