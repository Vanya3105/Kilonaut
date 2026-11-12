package com.example.gymtracker

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * The feed as a list of cards (Hevy / Strava): several friends' workouts per screen, each card
 * who and when, the words, the photo, the workout's numbers and top lifts, then like / comment /
 * share. A card's workout opens the whole workout; double-tap a photo to like it.
 */

private val Heart = Color(0xFFFF4D5E)

@Composable
internal fun FeedList(
    posts: List<Feed.Post>,
    people: Map<String, Friends.Person>,
    onFriends: () -> Unit,
    onNewPost: () -> Unit,
    onOpenPerson: (Friends.Person) -> Unit,
    state: LazyListState = rememberLazyListState()
) {
    var commentsFor by remember { mutableStateOf<Feed.Post?>(null) }
    var workoutOf by remember { mutableStateOf<Feed.Post?>(null) }
    // Your new post goes on top: scroll up to it.
    val top = posts.firstOrNull()
    LaunchedEffect(top?.id) {
        if (top != null && (top.mine || top.author.uid == "demo-me") && System.currentTimeMillis() - top.createdAt < 60_000) state.animateScrollToItem(0)
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(posts, key = { it.author.uid + "/" + it.id }) { post ->
            PostCard(
                post, people,
                onComments = { commentsFor = post },
                onWorkout = { workoutOf = post },
                onPerson = onOpenPerson
            )
        }
        item(key = "end") { CaughtUpCard(onFriends, onNewPost) }
    }
    commentsFor?.let { p ->
        val live = Feed.posts?.firstOrNull { it.id == p.id && it.author.uid == p.author.uid } ?: p
        CommentsSheet(live, people, onDismiss = { commentsFor = null }, onPerson = { commentsFor = null; onOpenPerson(it) })
    }
    workoutOf?.let { PostWorkoutSheet(it, onDismiss = { workoutOf = null }) }
}

@Composable
internal fun PostCard(
    post: Feed.Post,
    people: Map<String, Friends.Person>,
    onComments: () -> Unit,
    onWorkout: () -> Unit,
    onPerson: (Friends.Person) -> Unit
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberFeedHaptics()
    var likersOpen by remember { mutableStateOf(false) }
    if (likersOpen) LikersSheet(post, people, onPerson = onPerson, onDismiss = { likersOpen = false })
    val liked = post.likedByMe()
    val like = { on: Boolean ->
        if (on) haptics.tick()
        scope.launch { if (!Feed.like(post, on)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) }
        Unit
    }

    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 12.dp, bottom = 6.dp)) {
        // Who and when.
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.98f) { onPerson(post.author) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                PersonAvatar(post.author, 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(post.author.title(), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            // Isolated left-to-right, or in Hebrew the @ jumps to the handle's far end.
                            post.author.handle.takeIf { it.isNotBlank() && post.author.name.isNotBlank() }?.let { "⁦@$it⁩" },
                            postAge(post.createdAt),
                            tr("edited").takeIf { post.edited }
                        ).joinToString(" · "),
                        style = AppType.caption, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            PostMoreButton(post) { open -> CircleIconButton(Icons.Rounded.MoreHoriz, tr("More"), open, size = 40.dp, container = Color.Transparent, tint = P.textSecondary) }
        }

        if (post.text.isNotBlank()) {
            var expanded by remember { mutableStateOf(false) }
            var overflows by remember { mutableStateOf(false) }
            Text(
                post.text, style = AppType.body, color = P.textPrimary,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp)
            )
            if (overflows || expanded) Text(
                if (expanded) tr("less") else tr("more"), style = AppType.label, color = P.textSecondary,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp).clip(RoundedCornerShape(6.dp)).pressable { expanded = !expanded }.padding(4.dp)
            )
        }

        // The numbers first, then a shorter photo, as Hevy does: two friends fit on a screen.
        post.workout?.let { w ->
            Spacer(Modifier.height(12.dp))
            Box(Modifier.pressable(pressedScale = 0.985f, onClick = onWorkout)) { WorkoutBlock(w, full = false) }
        }

        Feed.image(post.photo)?.let { photo ->
            Spacer(Modifier.height(12.dp))
            val heart = remember { Animatable(0f) }
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).pointerInput(post.id) {
                    detectTapGestures(onDoubleTap = {
                        if (!post.likedByMe()) like(true)
                        scope.launch {
                            heart.snapTo(0.01f)
                            heart.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
                            heart.animateTo(0f, tween(260, delayMillis = 300))
                        }
                    })
                },
                contentAlignment = Alignment.Center
            ) {
                Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (heart.value > 0f) Icon(
                    Icons.Rounded.Favorite, null, tint = Heart,
                    modifier = Modifier.size(96.dp).graphicsLayer { scaleX = heart.value; scaleY = heart.value; alpha = heart.value.coerceAtMost(1f) }
                )
            }
        }

        // Like, comment, share.
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val pop = remember { Animatable(1f) }
            LaunchedEffect(liked) {
                if (liked) {
                    pop.snapTo(0.7f)
                    pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
                }
            }
            CardAction(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) Heart else P.textSecondary,
                post.likes.size.takeIf { it > 0 }?.let(::compactCount),
                if (liked) tr("Unlike") else tr("Like"),
                scale = pop.value
            ) { like(!liked) }
            CardAction(Icons.Rounded.ChatBubbleOutline, P.textSecondary, post.comments.takeIf { it > 0 }?.let(::compactCount), tr("Comments"), onClick = onComments)
            Spacer(Modifier.weight(1f))
            CardAction(Icons.Rounded.Share, P.textSecondary, null, tr("Share")) { sharePost(context, post) }
        }
        likedByText(post, people)?.let {
            Text(
                it, style = AppType.caption, color = P.textSecondary,
                modifier = Modifier.padding(start = 10.dp, bottom = 6.dp).clip(RoundedCornerShape(8.dp)).pressable { likersOpen = true }.padding(4.dp)
            )
        }
    }
}

@Composable
private fun CardAction(icon: ImageVector, tint: Color, count: String?, description: String, scale: Float = 1f, onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).pressable(pressedScale = 0.9f, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale })
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count, style = AppType.label.copy(fontFeatureSettings = "tnum"), color = P.textSecondary)
        }
    }
}

/** "Liked by Maya and 3 more", or null with no likes. */
internal fun likedByText(post: Feed.Post, people: Map<String, Friends.Person>): String? {
    if (post.likes.isEmpty()) return null
    val likers = post.likes.mapNotNull { uid -> if (uid == Feed.me()) tr("you") else people[uid]?.title() }
    return when {
        likers.isEmpty() -> if (post.likes.size == 1) tr("1 like") else tr("{0} likes", post.likes.size)
        post.likes.size == 1 -> tr("Liked by {0}", likers[0])
        else -> tr("Liked by {0} and {1} more", likers[0], compactCount(post.likes.size - 1))
    }
}

internal fun sharePost(context: Context, post: Feed.Post) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, postShareText(post, appShareLink(context)))
    runCatching { context.startActivity(Intent.createChooser(send, tr("Share"))) }
}

/** After the last card. */
@Composable
private fun CaughtUpCard(onFriends: () -> Unit, onNewPost: () -> Unit) {
    val P = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.TaskAlt, null, tint = P.accentPrimary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(tr("You're all caught up"), style = AppType.headline, color = P.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(tr("That's everything your friends posted lately."), style = AppType.caption, color = P.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(tr("Find friends"), onFriends, icon = Icons.Rounded.PersonAdd, height = 40.dp)
            PrimaryButton(tr("New post"), onNewPost, height = 40.dp)
        }
    }
}
