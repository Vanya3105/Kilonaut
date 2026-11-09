package com.example.gymtracker

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.TaskAlt
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/*
 * The feed as full-screen pages you swipe up through, one post each (TikTok / Instagram Reels).
 * A photo fills the page; a workout without one gets its own page: the poster's colour as a deep
 * gradient, the day in big type, big numbers and the top lifts. Name and words bottom left, the
 * heart and comments on the right, double-tap anywhere to like (hearts burst where you tap).
 * Comments open in a sheet so the post stays put.
 */

private val White = Color.White
private val Scrim = Color.Black

/** The feed tab. [active]: it's the tab showing (it stays composed when you switch away). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(history: List<CompletedWorkout>, active: Boolean = true) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Account.user
    var friendsOpen by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf<ComposeStart?>(null) }
    var person by remember { mutableStateOf<Friends.Person?>(null) }

    LaunchedEffect(user?.uid) {
        if (user != null) {
            Friends.loadMe(context)
            Feed.refresh(context)
        }
    }
    val posts = Feed.posts
    val handle = Friends.myHandle
    val showingPosts = (Feed.demo || (user != null && handle != null)) && !posts.isNullOrEmpty()
    SideEffect { Feed.fullBleed = showingPosts }
    val debuggable = remember { (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    // Posts are dark pages: light status-bar icons over them, the theme's own everywhere else.
    val view = LocalView.current
    if (active && showingPosts) DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }

    Box(Modifier.fillMaxSize().background(if (showingPosts) Scrim else P.bgPrimary)) {
        when {
            Feed.demo && !posts.isNullOrEmpty() -> FeedPager(posts, Feed.people, onFriends = { friendsOpen = true }, onNewPost = { composing = ComposeStart(null) }, onOpenPerson = { person = it })
            user == null -> FeedEmpty(Icons.Rounded.Group, tr("Your friends' training"), tr("Sign in and add friends to see their workouts and photos here, and share yours.")) {
                PrimaryButton(tr("Sign in"), { friendsOpen = true }, Modifier.fillMaxWidth())
            }
            handle == null -> FeedEmpty(Icons.Rounded.PersonAdd, tr("Set up Friends"), tr("Pick a username so friends can find you. Then their workouts and photos show up here.")) {
                PrimaryButton(tr("Pick a username"), { friendsOpen = true }, Modifier.fillMaxWidth())
            }
            posts == null -> FeedLoading()
            posts.isEmpty() -> {
                val last = history.maxByOrNull { it.id }
                FeedEmpty(Icons.Rounded.FitnessCenter, tr("No posts yet"), tr("When your friends post a workout or a photo, it shows up here. Be the first.")) {
                    if (last != null) PrimaryButton(tr("Share your last workout"), { composing = ComposeStart(last) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(tr("Find friends"), { friendsOpen = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PersonAdd)
                }
            }
            else -> PullToRefreshBox(
                isRefreshing = Feed.loading,
                onRefresh = { scope.launch { Feed.refresh(context) } },
                modifier = Modifier.fillMaxSize()
            ) {
                FeedPager(posts, Feed.people, onFriends = { friendsOpen = true }, onNewPost = { composing = ComposeStart(null) }, onOpenPerson = { person = it })
            }
        }
        FeedTopBar(
            onPosts = showingPosts,
            canPost = Feed.demo || (user != null && handle != null),
            onFriends = { friendsOpen = true },
            onNewPost = { composing = ComposeStart(null) },
            onTitleLongPress = if (debuggable) ({ Feed.showDemo() }) else null,
            onMe = if (Feed.demo || (user != null && handle != null)) ({
                person = if (Feed.demo) Friends.Person("demo-me", user?.name.orEmpty(), handle ?: "you")
                else Friends.Person(user!!.uid, user.name.orEmpty(), handle.orEmpty())
            }) else null
        )
    }
    person?.let { PersonPage(it, onClose = { person = null }) }

    if (friendsOpen) FullPage(tr("Friends"), onClose = { friendsOpen = false; scope.launch { Feed.refresh(context) } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) { FriendsSection() }
    }
    composing?.let { start -> ShareWorkoutComposer(start.workout, history, onClose = { composing = null }) }
}

/** "Feed" with Friends and New post, floating over the pages (white on photos, the theme's colours otherwise). */
@Composable
internal fun FeedTopBar(onPosts: Boolean, canPost: Boolean, onFriends: () -> Unit, onNewPost: () -> Unit, onTitleLongPress: (() -> Unit)? = null, onMe: (() -> Unit)? = null) {
    val P = LocalPalette.current
    val ink = if (onPosts) White else P.textPrimary
    Box(Modifier.fillMaxWidth()) {
        if (onPosts) Box(
            Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.55f), Color.Transparent)))
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                Text(
                    tr("Feed"), style = AppType.display, color = ink,
                    modifier = if (onTitleLongPress != null) Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onTitleLongPress() }) } else Modifier
                )
            }
            if (onMe != null) {
                // You: your page, with your posts and stats.
                Box(
                    Modifier.size(42.dp).clip(CircleShape).border(2.dp, if (onPosts) White.copy(alpha = 0.85f) else P.borderStrong, CircleShape)
                        .padding(3.dp).clip(CircleShape).pressable(onClick = onMe)
                ) {
                    PersonAvatar(Friends.Person(Feed.me() ?: "demo-me", Account.user?.name.orEmpty(), Friends.myHandle.orEmpty()), 36.dp, image = ProfilePhoto.mine)
                }
                Spacer(Modifier.width(10.dp))
            }
            Box {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (onPosts) White.copy(alpha = 0.18f) else P.bgControl).pressable(onClick = onFriends),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Group, tr("Friends"), tint = ink, modifier = Modifier.size(22.dp)) }
                if (Friends.requestCount > 0) Box(
                    Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(P.accentPrimary)
                        .border(2.dp, if (onPosts) Scrim else P.bgPrimary, CircleShape)
                )
            }
            if (canPost) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(P.accentPrimary).pressable(onClick = onNewPost),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Add, tr("New post"), tint = P.onAccentPrimary, modifier = Modifier.size(26.dp)) }
            }
        }
    }
}

/** The pages: one per post, then "You're all caught up". */
@Composable
internal fun FeedPager(
    posts: List<Feed.Post>, people: Map<String, Friends.Person>, onFriends: () -> Unit, onNewPost: () -> Unit,
    pager: PagerState = rememberPagerState { posts.size + 1 }, endPage: Boolean = true, onOpenPerson: (Friends.Person) -> Unit = {}
) {
    var commentsFor by remember { mutableStateOf<Feed.Post?>(null) }
    var workoutOf by remember { mutableStateOf<Feed.Post?>(null) }
    // Your new post goes on top: glide up to it.
    val top = posts.firstOrNull()
    LaunchedEffect(top?.id) {
        if (top != null && (top.mine || top.author.uid == "demo-me") && System.currentTimeMillis() - top.createdAt < 60_000 && pager.currentPage != 0) {
            pager.animateScrollToPage(0)
        }
    }
    Box(Modifier.fillMaxSize()) {
    VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { if (it < posts.size) posts[it].author.uid + "/" + posts[it].id else "end" }) { page ->
        // How far this page is from settled: it shrinks a little and its corners round as it leaves.
        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
        val leaving = offset.absoluteValue
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val s = 1f - 0.07f * leaving
                scaleX = s; scaleY = s
                alpha = 1f - 0.35f * leaving
                shape = RoundedCornerShape((28 * leaving).dp)
                clip = leaving > 0.001f
            }
        ) {
            if (page < posts.size) {
                val post = posts[page]
                PostPage(
                    post, people, current = pager.settledPage == page,
                    onComments = { commentsFor = post }, onWorkout = { workoutOf = post }, onPerson = { onOpenPerson(post.author) }
                )
            } else if (endPage) CaughtUp(onFriends, onNewPost) else Box(Modifier.fillMaxSize().background(Scrim))
        }
    }
    // A nudge on the first page: there's more below.
    if (pager.currentPage == 0 && posts.size > 1) SwipeHint()
    }

    commentsFor?.let { p ->
        val live = Feed.posts?.firstOrNull { it.id == p.id && it.author.uid == p.author.uid } ?: p
        CommentsSheet(live, people, onDismiss = { commentsFor = null })
    }
    workoutOf?.workout?.let { w ->
        AppBottomSheet(onDismiss = { workoutOf = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(workoutOf?.author?.title().orEmpty(), style = AppType.caption, color = LocalPalette.current.textSecondary, modifier = Modifier.padding(horizontal = 28.dp))
                Spacer(Modifier.height(6.dp))
                WorkoutBlock(w, full = true)
            }
        }
    }
}

/** A bouncing chevron while you're on the first post. */
@Composable
private fun BoxScope.SwipeHint() {
    val bob = rememberInfiniteTransition(label = "hint").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "hint_y")
    Icon(
        Icons.Rounded.KeyboardArrowUp, null, tint = White.copy(alpha = 0.7f),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(26.dp).offset { IntOffset(0, (-6 * bob.value).dp.roundToPx()) }
    )
}

/** A heart that burst out where the screen was double-tapped. */
private data class Burst(val id: Long, val at: Offset, val tilt: Float)

/** One post, the whole page. */
@Composable
internal fun PostPage(post: Feed.Post, people: Map<String, Friends.Person>, current: Boolean, onComments: () -> Unit, onWorkout: () -> Unit, onPerson: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bursts = remember { mutableStateListOf<Burst>() }
    var expanded by remember { mutableStateOf(false) }
    val like = { on: Boolean ->
        scope.launch { if (!Feed.like(post, on)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) }
        Unit
    }
    // The words come up as the page settles.
    val shown by animateFloatAsState(if (current) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "post_in")
    val photo = Feed.image(post.photo)

    Box(
        Modifier.fillMaxSize().pointerInput(post.id) {
            detectTapGestures(
                onDoubleTap = { at ->
                    if (!post.likedByMe()) like(true)
                    bursts += Burst(System.nanoTime(), at, listOf(-14f, -6f, 6f, 14f).random())
                },
                onTap = { if (post.text.isNotBlank()) expanded = !expanded }
            )
        }
    ) {
        // Backdrop: the photo, or the poster's colour as a deep gradient.
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val hue = hueFor(post.author.uid)
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(hue, Scrim, 0.25f), lerp(hue, Scrim, 0.78f)), start = Offset.Zero, end = Offset.Infinite)))
            // A large soft ring of the same colour, for depth.
            Box(Modifier.align(Alignment.TopEnd).offset(x = 120.dp, y = (-60).dp).size(340.dp).clip(CircleShape).background(White.copy(alpha = 0.06f)))
        }
        // Fades top and bottom so white text reads on any photo.
        Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.45f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f).background(Brush.verticalGradient(listOf(Color.Transparent, Scrim.copy(alpha = 0.78f)))))

        // A workout without a photo is the page's subject; a text-only post's words are.
        if (photo == null) {
            Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 70.dp, bottom = 150.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.graphicsLayer { alpha = 0.3f + 0.7f * shown; translationY = (1f - shown) * 28.dp.toPx() }) {
                    when {
                        post.workout != null -> WorkoutHero(post.workout, onWorkout)
                        post.text.isNotBlank() -> Text(
                            post.text, color = White, fontSize = if (post.text.length < 80) 34.sp else 24.sp,
                            lineHeight = if (post.text.length < 80) 40.sp else 30.sp, style = AppType.display,
                            maxLines = 9, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Bottom left: who, when, the words, and a workout under a photo as a frosted summary.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 86.dp, bottom = 22.dp)
                .graphicsLayer { alpha = shown; translationY = (1f - shown) * 18.dp.toPx() }
        ) {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick = onPerson), verticalAlignment = Alignment.CenterVertically) {
                Text(post.author.title(), style = AppType.bodyStrong, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  ·  " + postAge(post.createdAt), style = AppType.caption, color = White.copy(alpha = 0.75f))
            }
            if (post.author.name.isNotBlank() && post.author.handle.isNotBlank()) Text("@${post.author.handle}", style = AppType.caption, color = White.copy(alpha = 0.75f))
            if (post.text.isNotBlank() && (photo != null || post.workout != null)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    post.text, style = AppType.body, color = White,
                    maxLines = if (expanded) 12 else 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (photo != null && post.workout != null) {
                Spacer(Modifier.height(10.dp))
                WorkoutChip(post.workout, onWorkout)
            }
            val likers = post.likes.mapNotNull { uid -> if (uid == Feed.me()) tr("you") else people[uid]?.title() }
            if (post.likes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        likers.isEmpty() -> if (post.likes.size == 1) tr("1 like") else tr("{0} likes", post.likes.size)
                        post.likes.size == 1 -> tr("Liked by {0}", likers[0])
                        else -> tr("Liked by {0} and {1} more", likers[0], post.likes.size - 1)
                    },
                    style = AppType.caption, color = White.copy(alpha = 0.85f)
                )
            }
        }

        // The rail: who, heart, comments, and ⋯ on your own.
        Rail(post, Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 18.dp).graphicsLayer { alpha = 0.4f + 0.6f * shown }, onLike = like, onComments = onComments, onPerson = onPerson)

        // Hearts from double-taps, each floating up and fading where it was tapped.
        bursts.forEach { b -> key(b.id) { HeartBurst(b) { bursts.remove(b) } } }
    }
}

@Composable
private fun Rail(post: Feed.Post, modifier: Modifier, onLike: (Boolean) -> Unit, onComments: () -> Unit, onPerson: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liked = post.likedByMe()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.clip(CircleShape).border(2.dp, White, CircleShape).padding(2.dp).clip(CircleShape).pressable(onClick = onPerson)) { PersonAvatar(post.author, 44.dp) }

        val pop = remember { Animatable(1f) }
        LaunchedEffect(liked) {
            if (liked) {
                pop.snapTo(0.6f)
                pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
            }
        }
        RailButton(
            icon = Icons.Rounded.Favorite, tint = if (liked) Color(0xFFFF4D5E) else White,
            label = if (post.likes.isEmpty()) null else "${post.likes.size}",
            description = if (liked) tr("Unlike") else tr("Like"), scale = pop.value
        ) { onLike(!liked) }
        RailButton(
            icon = Icons.Rounded.ChatBubble, tint = White,
            label = if (post.comments == 0) null else "${post.comments}", description = tr("Comments")
        ) { onComments() }
        if (post.mine) Box {
            RailButton(icon = Icons.Rounded.MoreHoriz, tint = White, label = null, description = tr("More")) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = P.bgElevated) {
                DropdownMenuItem(text = { Text(tr("Delete post"), style = AppType.body, color = P.dangerText) }, onClick = { menu = false; confirmDelete = true })
            }
        }
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

@Composable
private fun RailButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, label: String?, description: String, scale: Float = 1f, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).pressable(pressedScale = 0.88f, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon, description, tint = tint,
            modifier = Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        )
        if (label != null) Text(label, style = AppType.label.copy(fontFeatureSettings = "tnum"), color = White)
    }
}

@Composable
private fun HeartBurst(b: Burst, onDone: () -> Unit) {
    val density = LocalDensity.current
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(800, easing = LinearEasing))
        onDone()
    }
    val size = 110.dp
    val half = with(density) { (size / 2).roundToPx() }
    Box(
        Modifier
            .offset { IntOffset(b.at.x.roundToInt() - half, b.at.y.roundToInt() - half - (60 * t.value).dp.roundToPx()) }
            .size(size)
            .graphicsLayer {
                // Pops in big, settles, then drifts up and fades.
                val v = t.value
                val s = when {
                    v < 0.18f -> 0.3f + v / 0.18f * 0.95f
                    v < 0.32f -> 1.25f - (v - 0.18f) / 0.14f * 0.25f
                    else -> 1f
                }
                scaleX = s; scaleY = s
                rotationZ = b.tilt
                alpha = if (v < 0.55f) 1f else 1f - (v - 0.55f) / 0.45f
            }
    ) {
        // A soft heart-shaped shadow under it (a layer shadow would be a square).
        Icon(Icons.Rounded.Favorite, null, tint = Scrim.copy(alpha = 0.28f), modifier = Modifier.fillMaxSize().offset(y = 4.dp).graphicsLayer { scaleX = 1.04f; scaleY = 1.04f })
        Icon(Icons.Rounded.Favorite, null, tint = Color(0xFFFF4D5E), modifier = Modifier.fillMaxSize())
    }
}

/** A workout as the page itself: the day huge, three big numbers, the top lifts. */
@Composable
private fun WorkoutHero(w: WorkoutPost, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(White.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FitnessCenter, null, tint = White, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Workout"), style = AppType.label, color = White)
                }
            }
            if (w.bests > 0) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFC53D)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.EmojiEvents, null, tint = Color(0xFF3D2A00), modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (w.bests == 1) tr("New best") else tr("{0} new bests", w.bests), style = AppType.label, color = Color(0xFF3D2A00))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(shown(w.title), color = White, fontSize = 40.sp, lineHeight = 44.sp, style = AppType.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            @Composable
            fun Big(value: String, label: String, modifier: Modifier) {
                Column(modifier) {
                    Text(value, color = White, fontSize = 26.sp, style = AppType.title.copy(fontFeatureSettings = "tnum"))
                    Text(label, style = AppType.caption, color = White.copy(alpha = 0.7f))
                }
            }
            Big(formatDuration(w.seconds), tr("Time"), Modifier.weight(1f))
            if (w.kg > 0) Big(formatVolume(w.kg), tr("Volume"), Modifier.weight(1.2f))
            Big("${w.sets}", tr("Sets"), Modifier.weight(0.8f))
        }
        Spacer(Modifier.height(18.dp))
        w.exercises.take(4).forEach { e ->
            Row(
                Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(White.copy(alpha = 0.12f))
                    .pressable(pressedScale = 0.98f, onClick = onOpen).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExerciseThumb(e.name, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(tr("{0} × {1}", e.sets, shown(e.name)), style = AppType.body, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                e.best?.let { b ->
                    if (e.isBest) Icon(Icons.Rounded.EmojiEvents, tr("New best"), tint = Color(0xFFFFC53D), modifier = Modifier.size(16.dp).padding(end = 3.dp))
                    Text(formatSet(b, e.type), style = AppType.label.copy(fontFeatureSettings = "tnum"), color = if (e.isBest) Color(0xFFFFC53D) else White)
                }
            }
        }
        if (w.exercises.size > 4) Text(
            tr("{0} more exercises", w.exercises.size - 4), style = AppType.label, color = White.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 8.dp, start = 4.dp).clip(RoundedCornerShape(8.dp)).pressable(onClick = onOpen).padding(4.dp)
        )
    }
}

/** A workout under a photo: one frosted line of its numbers; tap for all of it. */
@Composable
private fun WorkoutChip(w: WorkoutPost, onOpen: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(White.copy(alpha = 0.18f)).pressable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.FitnessCenter, null, tint = White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            listOfNotNull(shown(w.title), formatDuration(w.seconds), formatVolume(w.kg).takeIf { w.kg > 0 }, tr("{0} sets", w.sets)).joinToString("  ·  "),
            style = AppType.label, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (w.bests > 0) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.EmojiEvents, null, tint = Color(0xFFFFC53D), modifier = Modifier.size(16.dp))
        }
    }
}

/** After the last post. */
@Composable
private fun CaughtUp(onFriends: () -> Unit, onNewPost: () -> Unit) {
    val P = LocalPalette.current
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1D24), Color(0xFF0E0F13)))), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(P.accentPrimary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.TaskAlt, null, tint = P.accentPrimary, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(tr("You're all caught up"), style = AppType.title, color = White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(tr("That's everything your friends posted lately."), style = AppType.body, color = White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(P.accentPrimary).pressable(onClick = onNewPost).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(tr("New post"), style = AppType.label, color = P.onAccentPrimary)
                }
                Box(Modifier.clip(RoundedCornerShape(50)).background(White.copy(alpha = 0.14f)).pressable(onClick = onFriends).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(tr("Find friends"), style = AppType.label, color = White)
                }
            }
        }
    }
}

/** Not signed in, no username, or nothing posted: one centred message and what to do. */
@Composable
private fun FeedEmpty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, actions: @Composable ColumnScope.() -> Unit) {
    val P = LocalPalette.current
    val float = rememberInfiniteTransition(label = "empty").animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "empty_y")
    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Three phone-shaped cards fanned out, the middle one with the icon: a feed, empty for now.
            Box(Modifier.height(170.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                listOf(-14f to -64, 14f to 64).forEach { (angle, x) ->
                    Box(
                        Modifier.offset(x = x.dp).size(92.dp, 140.dp)
                            .graphicsLayer { rotationZ = angle; translationY = (6 * float.value).dp.toPx() }
                            .clip(RoundedCornerShape(18.dp)).background(P.bgControl)
                    )
                }
                Box(
                    Modifier.size(100.dp, 150.dp).graphicsLayer { translationY = (-6 * float.value).dp.toPx() }
                        .clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(P.accentPrimary, lerp(P.accentPrimary, Scrim, 0.35f)))),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = White, modifier = Modifier.size(40.dp)) }
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = AppType.title, color = P.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(body, style = AppType.body, color = P.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            actions()
        }
    }
}

/** While the first page loads: a dark page with a soft pulse where the words will be. */
@Composable
private fun FeedLoading() {
    val pulse = rememberInfiniteTransition(label = "load").animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "load_a")
    val P = LocalPalette.current
    Box(Modifier.fillMaxSize().background(P.bgPrimary)) {
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp).graphicsLayer { alpha = pulse.value }) {
            Box(Modifier.size(140.dp, 14.dp).clip(RoundedCornerShape(7.dp)).background(P.bgControl))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.size(220.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(P.bgControl))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(180.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(P.bgControl))
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp).graphicsLayer { alpha = pulse.value }, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(3) { Box(Modifier.size(40.dp).clip(CircleShape).background(P.bgControl)) }
        }
    }
}

/** Comments from the bottom, like TikTok's: the list, then a box to add one. */
@Composable
private fun CommentsSheet(post: Feed.Post, people: Map<String, Friends.Person>, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<Feed.Comment>?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(post.id) { comments = Feed.comments(post) }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).imePadding()) {
            Text(
                if (post.comments == 1) tr("1 comment") else tr("{0} comments", post.comments),
                style = AppType.bodyStrong, color = P.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.borderSubtle))
            val list = comments
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                when {
                    list == null -> item { Text(tr("Loading…"), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(8.dp)) }
                    list.isEmpty() -> item {
                        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.ChatBubble, null, tint = P.textTertiary, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(tr("No comments yet. Say something nice."), style = AppType.body, color = P.textSecondary)
                        }
                    }
                    else -> items(list, key = { it.id }) { c ->
                        val who = people[c.uid] ?: Friends.Person(c.uid, "", tr("someone"))
                        var menu by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp).pointerInput(c.id) {
                                detectTapGestures(onLongPress = { if (c.uid == Feed.me() || post.mine) menu = true })
                            },
                            verticalAlignment = Alignment.Top
                        ) {
                            PersonAvatar(who, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(who.title(), style = AppType.label, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    Text("  " + postAge(c.at), style = AppType.caption, color = P.textTertiary)
                                }
                                Text(c.text, style = AppType.body, color = P.textPrimary)
                            }
                            Box {
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
            Row(Modifier.fillMaxWidth().background(P.bgElevated).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(Friends.Person(Feed.me().orEmpty(), Account.user?.name.orEmpty(), Friends.myHandle.orEmpty()), 34.dp, image = ProfilePhoto.mine)
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(P.bgControl).padding(horizontal = 16.dp, vertical = 11.dp)) {
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
                    Modifier.size(42.dp).clip(CircleShape).background(if (canSend) P.accentPrimary else P.bgControl).pressable(enabled = canSend) {
                        sending = true
                        scope.launch {
                            if (Feed.comment(post, text)) { text = ""; comments = Feed.comments(post) } else AppSnackbar.show(tr("Couldn't send it. Check your connection."))
                            sending = false
                        }
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.AutoMirrored.Rounded.Send, tr("Send"), tint = if (canSend) P.onAccentPrimary else P.textTertiary, modifier = Modifier.size(19.dp)) }
            }
        }
    }
}
