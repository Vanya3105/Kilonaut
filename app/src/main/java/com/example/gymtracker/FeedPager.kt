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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withLink
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
fun FeedScreen(history: List<CompletedWorkout>, active: Boolean = true, onTryWorkout: ((CompletedWorkout) -> Unit)? = null) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Account.user
    var friendsOpen by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf<ComposeStart?>(null) }
    var person by remember { mutableStateOf<Friends.Person?>(null) }
    var activityOpen by remember { mutableStateOf(false) }
    var openPost by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }

    LaunchedEffect(user?.uid) {
        if (user != null) {
            Friends.loadMe(context)
            Feed.refresh(context)
            Feed.me()?.let { ActivityLog.refreshUnread(context, it) }
        }
    }
    // Back on the tab after a while: read it again, as other apps do.
    LaunchedEffect(active) {
        if (active && user != null && Feed.refreshedAt > 0 && !Feed.loading && System.currentTimeMillis() - Feed.refreshedAt > 5 * 60_000) Feed.refresh(context)
    }
    val posts = Feed.posts
    val handle = Friends.myHandle
    remember { CommunityRules.load(context) }
    // "Do this workout" closes whatever is open over the feed, then starts it.
    val tryWorkout: ((CompletedWorkout) -> Unit)? = onTryWorkout?.let { start ->
        { w: CompletedWorkout -> openPost = null; person = null; activityOpen = false; start(w) }
    }
    val debuggable = remember { (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    val social = user != null && handle != null && !Feed.demo

    CompositionLocalProvider(LocalTryWorkout provides tryWorkout) {
    Column(Modifier.fillMaxSize().background(P.bgPrimary)) {
        FeedTopBar(
            onPosts = false,
            canPost = Feed.demo || (user != null && handle != null),
            // The bell is what's new for you; friends (list, requests, adding) have their own button.
            onFriends = { if (social) activityOpen = true else friendsOpen = true },
            onFindFriends = { friendsOpen = true },
            onNewPost = { composing = ComposeStart(null) },
            onTitleLongPress = if (debuggable) ({ Feed.showDemo() }) else null,
            onMe = if (Feed.demo || (user != null && handle != null)) ({
                person = if (Feed.demo) Friends.Person("demo-me", user?.name.orEmpty(), handle ?: "you")
                else Friends.meAsPerson() ?: Friends.Person(user!!.uid, user.name.orEmpty(), handle.orEmpty())
            }) else null
        )
        Box(Modifier.weight(1f)) {
        when {
            Feed.demo && !posts.isNullOrEmpty() -> FeedList(posts, Feed.people, onFriends = { friendsOpen = true }, onNewPost = { composing = ComposeStart(null) }, onOpenPerson = { person = it })
            user == null -> FeedEmpty(Icons.Rounded.Group, tr("Your friends' training"), tr("Sign in and add friends to see their workouts here, and share yours.")) {
                PrimaryButton(tr("Sign in"), { friendsOpen = true }, Modifier.fillMaxWidth())
            }
            handle == null -> FeedEmpty(Icons.Rounded.PersonAdd, tr("Set up Friends"), tr("Pick a username so friends can find you. Then their workouts show up here.")) {
                PrimaryButton(tr("Pick a username"), { friendsOpen = true }, Modifier.fillMaxWidth())
            }
            CommunityRules.agreed == false -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CommunityRulesGate(Modifier.padding(top = 8.dp))
            }
            posts == null && Feed.failed && !Feed.loading -> FeedEmpty(Icons.Rounded.CloudOff, tr("Couldn't load the feed"), tr("Check your connection and try again.")) {
                PrimaryButton(tr("Try again"), { scope.launch { Feed.refresh(context) } }, Modifier.fillMaxWidth())
            }
            posts == null -> FeedLoading()
            posts.isEmpty() -> {
                val last = history.maxByOrNull { it.id }
                FeedEmpty(Icons.Rounded.FitnessCenter, tr("No posts yet"), tr("When your friends share a workout, it shows up here. Be the first.")) {
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
                FeedList(posts, Feed.people, onFriends = { friendsOpen = true }, onNewPost = { composing = ComposeStart(null) }, onOpenPerson = { person = it })
            }
        }
        }
    }
    if (activityOpen) ActivityPage(
        onClose = { activityOpen = false },
        onFriends = { friendsOpen = true },
        onPerson = { person = it },
        onPost = { owner, id, comments -> openPost = Triple(owner, id, comments) }
    )
    openPost?.let { (owner, id, comments) -> PostViewer(owner, id, onClose = { openPost = null }, onPerson = { person = it }, openComments = comments) }
    person?.let { PersonPage(it, onClose = { person = null }) }

    if (friendsOpen) FullPage(tr("Friends"), onClose = { friendsOpen = false; scope.launch { Feed.refresh(context) } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) { FriendsSection() }
    }
    composing?.let { start -> ShareWorkoutComposer(start.workout, history, onClose = { composing = null }) }
    }
}

/** Starts a post's workout as your own (null where that can't be done, e.g. outside the feed). */
internal val LocalTryWorkout = staticCompositionLocalOf<((CompletedWorkout) -> Unit)?> { null }

/** "Feed" with Friends and New post, floating over the pages (white on photos, the theme's colours otherwise). */
@Composable
internal fun FeedTopBar(onPosts: Boolean, canPost: Boolean, onFriends: () -> Unit, onNewPost: () -> Unit, onTitleLongPress: (() -> Unit)? = null, onMe: (() -> Unit)? = null, onFindFriends: (() -> Unit)? = null) {
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
                    PersonAvatar(Friends.Person(Feed.me() ?: "demo-me", (Friends.myName ?: Account.user?.name).orEmpty(), Friends.myHandle.orEmpty()), 36.dp, image = ProfilePhoto.mine)
                }
                Spacer(Modifier.width(10.dp))
            }
            if (onFindFriends != null) {
                // Friends live here, next to their workouts: your list, requests, and adding someone.
                Box {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(if (onPosts) White.copy(alpha = 0.18f) else P.bgControl).pressable(onClick = onFindFriends),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Group, tr("Friends"), tint = ink, modifier = Modifier.size(22.dp)) }
                    if (Friends.requestCount > 0) Box(
                        Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(P.accentPrimary)
                            .border(2.dp, if (onPosts) Scrim else P.bgPrimary, CircleShape)
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Box {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (onPosts) White.copy(alpha = 0.18f) else P.bgControl).pressable(onClick = onFriends),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Notifications, tr("Activity"), tint = ink, modifier = Modifier.size(22.dp)) }
                if ((onFindFriends == null && Friends.requestCount > 0) || ActivityLog.unread > 0) Box(
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
    pager: PagerState = rememberPagerState { posts.size + 1 }, endPage: Boolean = true, onOpenPerson: (Friends.Person) -> Unit = {},
    openComments: Boolean = false
) {
    // A comment notification opens the post with its comments showing.
    var commentsFor by remember { mutableStateOf(if (openComments) posts.firstOrNull() else null) }
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
                    onComments = { commentsFor = post }, onWorkout = { workoutOf = post }, onPerson = { onOpenPerson(post.author) },
                    onOpenPerson = onOpenPerson
                )
            } else if (endPage) CaughtUp(onFriends, onNewPost) else Box(Modifier.fillMaxSize().background(Scrim))
        }
    }
    // A nudge on the first page: there's more below.
    if (pager.currentPage == 0 && posts.size > 1) SwipeHint()
    }

    commentsFor?.let { p ->
        val live = Feed.posts?.firstOrNull { it.id == p.id && it.author.uid == p.author.uid } ?: p
        CommentsSheet(live, people, onDismiss = { commentsFor = null }, onPerson = { commentsFor = null; onOpenPerson(it) })
    }
    workoutOf?.let { PostWorkoutSheet(it, onDismiss = { workoutOf = null }) }
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
internal fun PostPage(
    post: Feed.Post, people: Map<String, Friends.Person>, current: Boolean, onComments: () -> Unit, onWorkout: () -> Unit, onPerson: () -> Unit,
    onOpenPerson: (Friends.Person) -> Unit = {}
) {
    var likersOpen by remember { mutableStateOf(false) }
    if (likersOpen) LikersSheet(post, people, onPerson = onOpenPerson, onDismiss = { likersOpen = false })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bursts = remember { mutableStateListOf<Burst>() }
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    val haptics = rememberFeedHaptics()
    val like = { on: Boolean ->
        if (on) haptics.tick()
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
                Text("  ·  " + postAge(post.createdAt) + (if (post.edited) "  ·  " + tr("edited") else ""), style = AppType.caption, color = White.copy(alpha = 0.75f))
            }
            if (post.author.name.isNotBlank() && post.author.handle.isNotBlank()) Text("@${post.author.handle}", style = AppType.caption, color = White.copy(alpha = 0.75f))
            if (post.text.isNotBlank() && (photo != null || post.workout != null)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    post.text, style = AppType.body, color = White,
                    maxLines = if (expanded) 12 else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow }
                )
                // Long words: "more" opens them (a tap anywhere does too), "less" folds them again.
                if (overflows) Text(
                    if (expanded) tr("less") else tr("more"), style = AppType.label, color = White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).pressable { expanded = !expanded }.padding(vertical = 2.dp)
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
                        else -> tr("Liked by {0} and {1} more", likers[0], compactCount(post.likes.size - 1))
                    },
                    style = AppType.caption, color = White.copy(alpha = 0.85f),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable { likersOpen = true }.padding(vertical = 2.dp)
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
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            label = if (post.likes.isEmpty()) null else compactCount(post.likes.size),
            description = if (liked) tr("Unlike") else tr("Like"), scale = pop.value
        ) { onLike(!liked) }
        RailButton(
            icon = Icons.Rounded.ChatBubble, tint = White,
            label = if (post.comments == 0) null else compactCount(post.comments), description = tr("Comments")
        ) { onComments() }
        RailButton(icon = Icons.Rounded.Share, tint = White, label = null, description = tr("Share")) {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, postShareText(post, appShareLink(context)))
            runCatching { context.startActivity(android.content.Intent.createChooser(send, tr("Share"))) }
        }
        PostMoreButton(post) { open -> RailButton(icon = Icons.Rounded.MoreHoriz, tint = White, label = null, description = tr("More"), onClick = open) }
    }
}

/** ⋯ on a post: Edit caption / Delete on your own, Report / Block on others'. [button] opens it. */
@Composable
internal fun PostMoreButton(post: Feed.Post, button: @Composable (open: () -> Unit) -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }
    var blocking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Box {
        button { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = P.bgElevated) {
            if (post.mine || post.author.uid == "demo-me") {
                DropdownMenuItem(text = { Text(tr("Edit caption"), style = AppType.body) }, onClick = { menu = false; editing = true })
                DropdownMenuItem(text = { Text(tr("Delete post"), style = AppType.body, color = P.dangerText) }, onClick = { menu = false; confirmDelete = true })
            } else {
                DropdownMenuItem(text = { Text(tr("Report post"), style = AppType.body) }, onClick = {
                    menu = false
                    reporting = ReportTarget(post.author, Feed.pathOf(post), excerpt = post.text)
                })
                DropdownMenuItem(text = { Text(tr("Block {0}", post.author.title()), style = AppType.body, color = P.dangerText) }, onClick = { menu = false; blocking = true })
            }
        }
    }
    reporting?.let { ReportSheet(it, onDismiss = { reporting = null }) }
    if (blocking) BlockDialog(post.author, onDismiss = { blocking = false })
    if (editing) EditCaptionDialog(post, onDismiss = { editing = false })
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

/** A line in the comments: a comment (indented if it's a reply), or "View N more replies" under a thread. */
internal sealed interface CommentLine {
    data class One(val comment: Feed.Comment, val reply: Boolean) : CommentLine
    data class More(val root: String, val hidden: Int) : CommentLine
}

/**
 * Comments as they're listed: each thread's top comment, then its replies, oldest first. A thread
 * with more than two replies shows the first and "View N more" until it's opened ([open]).
 */
internal fun commentLines(list: List<Feed.Comment>, open: Set<String>): List<CommentLine> {
    val ids = list.map { it.id }.toSet()
    val roots = list.filter { it.replyTo == null || it.replyTo !in ids }.sortedBy { it.at }
    val replies = list.filter { it.replyTo != null && it.replyTo in ids }.groupBy { it.replyTo }
    return roots.flatMap { r ->
        val rs = replies[r.id].orEmpty().sortedBy { it.at }
        val shown = if (r.id in open || rs.size <= 2) rs else rs.take(1)
        listOf<CommentLine>(CommentLine.One(r, false)) + shown.map { CommentLine.One(it, true) } +
            (if (shown.size < rs.size) listOf(CommentLine.More(r.id, rs.size - shown.size)) else emptyList())
    }
}

/** Quick reactions over the comment box, like Instagram's. */
private val QUICK = listOf("💪", "🔥", "👏", "🙌", "🏆", "😮", "😂", "❤️")

/** Comments from the bottom, like TikTok's: threads with likes, then quick reactions and a box to add one. */
@Composable
internal fun CommentsSheet(post: Feed.Post, people: Map<String, Friends.Person>, onDismiss: () -> Unit, onPerson: (Friends.Person) -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    val haptics = rememberFeedHaptics()
    var comments by remember { mutableStateOf<List<Feed.Comment>?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }
    var blocking by remember { mutableStateOf<Friends.Person?>(null) }
    var replyingTo by remember { mutableStateOf<Feed.Comment?>(null) }
    var open by remember { mutableStateOf(emptySet<String>()) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(post.id) { comments = Feed.comments(post) }
    reporting?.let { ReportSheet(it, onDismiss = { reporting = null }) }
    blocking?.let { BlockDialog(it, onDismiss = { blocking = null }) }
    fun personOf(uid: String) = people[uid] ?: Friends.Person(uid, "", tr("someone"))
    val myId = if (Feed.demo) "demo-me" else Feed.me()

    fun toggleLike(c: Feed.Comment) {
        val me = myId ?: return
        val on = !c.likedByMe()
        fun set(on: Boolean) {
            comments = comments?.map { if (it.id == c.id) it.copy(likes = if (on) (it.likes - me) + me else it.likes - me) else it }
        }
        set(on)
        if (on) haptics.tick()
        scope.launch { if (!Feed.likeComment(post, c, on)) { set(!on); AppSnackbar.show(tr("Couldn't do that. Check your connection.")) } }
    }
    fun mention(handle: String) {
        val h = handle.trimEnd('.')
        scope.launch {
            val found = people.values.firstOrNull { it.handle == h } ?: Friends.find(h)
            if (found != null) onPerson(found) else AppSnackbar.show(tr("No one goes by @{0}", h))
        }
    }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f).imePadding()) {
            Text(
                if (post.comments == 1) tr("1 comment") else tr("{0} comments", compactCount(post.comments)),
                style = AppType.bodyStrong, color = P.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.borderSubtle))
            // Blocked people's comments stay hidden.
            val list = comments?.filter { !Friends.isBlocked(it.uid) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                when {
                    list == null -> items(3) { CommentSkeleton() }
                    list.isEmpty() -> item {
                        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.ChatBubble, null, tint = P.textTertiary, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(tr("No comments yet"), style = AppType.bodyStrong, color = P.textPrimary)
                            Text(tr("Start the conversation."), style = AppType.body, color = P.textSecondary)
                        }
                    }
                    else -> items(commentLines(list, open), key = { line -> if (line is CommentLine.One) line.comment.id else "more" + (line as CommentLine.More).root }) { line ->
                        when (line) {
                            is CommentLine.More -> Text(
                                if (line.hidden == 1) tr("View 1 more reply") else tr("View {0} more replies", line.hidden),
                                style = AppType.label, color = P.textSecondary,
                                modifier = Modifier.padding(start = 48.dp, top = 2.dp, bottom = 6.dp).clip(RoundedCornerShape(8.dp))
                                    .pressable { open = open + line.root }.padding(vertical = 4.dp, horizontal = 2.dp)
                            )
                            is CommentLine.One -> {
                                val c = line.comment
                                val who = personOf(c.uid)
                                CommentRow(
                                    c, who, reply = line.reply, byAuthor = c.uid == post.author.uid,
                                    canDelete = c.uid == myId || post.mine,
                                    onPerson = { onPerson(who) },
                                    onLike = { toggleLike(c) },
                                    onMention = ::mention,
                                    onReply = {
                                        // Replies join the thread of the comment at its top, naming who they answer.
                                        replyingTo = c
                                        if (line.reply && c.uid != myId) text = "@" + (who.handle.ifBlank { who.title() }) + " "
                                        runCatching { focus.requestFocus() }
                                    },
                                    onDelete = { scope.launch { if (Feed.deleteComment(post, c)) comments = comments?.filter { it.id != c.id } } },
                                    onReport = { reporting = ReportTarget(who, Feed.pathOf(post), c.id, c.text) },
                                    onBlock = { blocking = who }
                                )
                            }
                        }
                    }
                }
            }
            replyingTo?.let { r ->
                Row(Modifier.fillMaxWidth().background(P.bgControl).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Replying to {0}", personOf(r.uid).title()), style = AppType.caption, color = P.textSecondary, modifier = Modifier.weight(1f))
                    Box(Modifier.size(32.dp).clip(CircleShape).pressable { replyingTo = null; text = "" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, tr("Cancel"), tint = P.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(P.bgElevated).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QUICK.forEach { e ->
                    Text(e, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).pressable(pressedScale = 0.85f) { text = (text + e).take(500) }.padding(6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().background(P.bgElevated).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(Friends.Person(Feed.me().orEmpty(), (Friends.myName ?: Account.user?.name).orEmpty(), Friends.myHandle.orEmpty()), 34.dp, image = ProfilePhoto.mine)
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(P.bgControl).padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Text(if (replyingTo != null) tr("Add a reply…") else tr("Add a comment…"), style = AppType.body, color = P.textTertiary)
                        BasicTextField(
                            value = text, onValueChange = { text = it.take(500) },
                            textStyle = AppType.body.copy(color = P.textPrimary), cursorBrush = SolidColor(P.accentPrimary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            maxLines = 4, modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                val canSend = text.isNotBlank() && !sending
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (canSend) P.accentPrimary else P.bgControl).pressable(enabled = canSend) {
                        sending = true
                        val r = replyingTo
                        // A reply to a reply joins the thread of its top comment.
                        val thread = r?.let { it.replyTo?.takeIf { id -> comments.orEmpty().any { c -> c.id == id } } ?: it.id }
                        scope.launch {
                            if (Feed.comment(post, text, r, thread = thread)) {
                                text = ""; replyingTo = null
                                if (thread != null) open = open + thread
                                comments = Feed.comments(post)
                            } else AppSnackbar.show(tr("Couldn't send it. Check your connection."))
                            sending = false
                        }
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.AutoMirrored.Rounded.Send, tr("Send"), tint = if (canSend) P.onAccentPrimary else P.textTertiary, modifier = Modifier.size(19.dp)) }
            }
        }
    }
}

/** One comment: who (tap for their page), the words with @names you can tap, Reply, and a heart with its count. */
@Composable
private fun CommentRow(
    c: Feed.Comment, who: Friends.Person, reply: Boolean, byAuthor: Boolean, canDelete: Boolean,
    onPerson: () -> Unit, onLike: () -> Unit, onMention: (String) -> Unit, onReply: () -> Unit,
    onDelete: () -> Unit, onReport: () -> Unit, onBlock: () -> Unit
) {
    val P = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val mine = c.uid == (if (Feed.demo) "demo-me" else Feed.me())
    val liked = c.likedByMe()
    Row(
        Modifier.fillMaxWidth().padding(start = if (reply) 48.dp else 0.dp, top = 8.dp, bottom = 4.dp).pointerInput(c.id, liked) {
            detectTapGestures(onLongPress = { menu = true }, onDoubleTap = { if (!liked) onLike() })
        },
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.clip(CircleShape).pressable(onClick = onPerson)) { PersonAvatar(who, if (reply) 28.dp else 36.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    who.title(), style = AppType.label, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).pressable(onClick = onPerson)
                )
                if (byAuthor) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("Author"), style = AppType.caption, color = P.accentPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(P.accentSoft).padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                Text("  " + postAge(c.at), style = AppType.caption, color = P.textTertiary)
            }
            CommentText(c.text, onMention)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Reply"), style = AppType.label, color = P.textTertiary,
                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).pressable(onClick = onReply).padding(vertical = 4.dp, horizontal = 2.dp)
                )
            }
            Box {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = P.bgElevated) {
                    if (canDelete) DropdownMenuItem(
                        text = { Text(tr("Delete comment"), style = AppType.body, color = P.dangerText) },
                        onClick = { menu = false; onDelete() }
                    )
                    if (!mine) {
                        DropdownMenuItem(text = { Text(tr("Report comment"), style = AppType.body) }, onClick = { menu = false; onReport() })
                        DropdownMenuItem(text = { Text(tr("Block {0}", who.title()), style = AppType.body, color = P.dangerText) }, onClick = { menu = false; onBlock() })
                    }
                }
            }
        }
        // The heart: pops when you like it.
        val pop = remember { Animatable(1f) }
        LaunchedEffect(liked) {
            if (liked) {
                pop.snapTo(0.7f)
                pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
            }
        }
        Column(
            Modifier.padding(start = 8.dp).width(36.dp).clip(RoundedCornerShape(10.dp)).pressable(pressedScale = 0.88f, onClick = onLike).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) tr("Unlike") else tr("Like"),
                tint = if (liked) Color(0xFFFF4D5E) else P.textTertiary,
                modifier = Modifier.size(18.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            )
            if (c.likes.isNotEmpty()) Text(compactCount(c.likes.size), style = AppType.caption.copy(fontFeatureSettings = "tnum"), color = P.textTertiary)
        }
    }
}

/** A comment's words, with @usernames in the accent colour that open that person's page. */
@Composable
private fun CommentText(text: String, onMention: (String) -> Unit) {
    val P = LocalPalette.current
    val annotated = remember(text, P.accentPrimary) { withMentions(text, P.accentPrimary, onMention) }
    Text(annotated, style = AppType.body, color = P.textPrimary)
}

internal fun withMentions(text: String, color: Color, onMention: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var last = 0
    MENTION.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        val handle = m.groupValues[1].lowercase()
        withLink(LinkAnnotation.Clickable("@$handle", androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.SemiBold))) { onMention(handle) }) {
            append(m.value)
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** "@name" at the start or after a space (not in an email address). */
internal val MENTION = Regex("""(?<![\w@.])@([A-Za-z0-9_.]{3,20})""")

/** While comments load: grey shapes where they'll be. */
@Composable
private fun CommentSkeleton() {
    val P = LocalPalette.current
    val pulse = rememberInfiniteTransition(label = "c_load").animateFloat(0.4f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "c_load_a")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).graphicsLayer { alpha = pulse.value }) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(P.bgControl))
        Spacer(Modifier.width(12.dp))
        Column {
            Box(Modifier.size(90.dp, 10.dp).clip(RoundedCornerShape(5.dp)).background(P.bgControl))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(200.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(P.bgControl))
        }
    }
}

/** Change the words on your own post. */
@Composable
private fun EditCaptionDialog(post: Feed.Post, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(post.text) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.bgElevated,
        title = { Text(tr("Edit caption"), style = AppType.headline, color = P.textPrimary) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(14.dp)).background(P.bgControl).padding(14.dp)) {
                    if (text.isEmpty()) Text(tr("Say something about it…"), style = AppType.body, color = P.textTertiary)
                    BasicTextField(
                        value = text, onValueChange = { text = it.take(300) },
                        textStyle = AppType.body.copy(color = P.textPrimary), cursorBrush = SolidColor(P.accentPrimary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
                Text("${text.length}/300", style = AppType.caption, color = P.textTertiary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        confirmButton = {
            val can = !busy && text.trim() != post.text
            androidx.compose.material3.TextButton(enabled = can, onClick = {
                busy = true
                scope.launch {
                    if (Feed.editCaption(post, text)) onDismiss() else AppSnackbar.show(tr("Couldn't do that. Check your connection."))
                    busy = false
                }
            }) { Text(tr("Save"), color = if (can) P.accentPrimary else P.textTertiary, style = AppType.label) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
    )
}

/** Light taps for likes, following the app's vibration setting. */
@Composable
internal fun rememberFeedHaptics(): Haptics {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view) { Haptics(runCatching { LocalStorage.loadSettings(context).hapticsEnabled }.getOrDefault(true), view) }
}

/** Comments in threads: each top-level comment (oldest first) followed by its replies (oldest first). */
internal fun threaded(list: List<Feed.Comment>): List<Feed.Comment> {
    val ids = list.map { it.id }.toSet()
    val roots = list.filter { it.replyTo == null || it.replyTo !in ids }.sortedBy { it.at }
    val replies = list.filter { it.replyTo != null && it.replyTo in ids }.groupBy { it.replyTo }
    return roots.flatMap { r -> listOf(r) + replies[r.id].orEmpty().sortedBy { it.at } }
}

/** One post opened from Activity, full screen, with its comments a tap away. */
@Composable
fun PostViewer(owner: String, id: String, onClose: () -> Unit, onPerson: (Friends.Person) -> Unit, openComments: Boolean = false) {
    var post by remember { mutableStateOf<Feed.Post?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(owner, id) {
        post = Feed.loadPost(owner, id)
        loaded = true
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = false }
        }
        Box(Modifier.fillMaxSize().background(Scrim)) {
            val p = post
            when {
                p != null -> {
                    val live = Feed.posts?.firstOrNull { it.id == p.id && it.author.uid == p.author.uid } ?: p
                    FeedPager(listOf(live), Feed.people, onFriends = {}, onNewPost = {}, endPage = false, onOpenPerson = { onClose(); onPerson(it) }, openComments = openComments)
                }
                loaded -> Text(
                    tr("This post isn't available anymore."), style = AppType.body, color = White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center
                )
            }
            Box(
                Modifier.statusBarsPadding().padding(14.dp).size(40.dp).clip(CircleShape).background(Scrim.copy(alpha = 0.35f)).pressable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = White, modifier = Modifier.size(22.dp)) }
        }
    }
}

private fun Friends.Person.isMeOrDemo() = uid == Feed.me() || uid == "demo-me"

/** A post's whole workout in a sheet, with Do this workout. */
@Composable
internal fun PostWorkoutSheet(post: Feed.Post, onDismiss: () -> Unit) {
    val w = post.workout ?: return
    val tryIt = LocalTryWorkout.current
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(post.author.title(), style = AppType.caption, color = LocalPalette.current.textSecondary, modifier = Modifier.padding(horizontal = 28.dp))
            Spacer(Modifier.height(6.dp))
            WorkoutBlock(w, full = true)
            if (tryIt != null && w.exercises.isNotEmpty()) {
                // Hevy's "copy this workout": the same exercises and set counts, with your own numbers.
                Spacer(Modifier.height(14.dp))
                PrimaryButton(tr("Do this workout"), { val by = post.author; onDismiss(); tryIt(postAsWorkout(w, from = by.takeIf { !it.isMeOrDemo() }?.let { it.name.substringBefore(' ').ifBlank { it.handle } })) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), icon = Icons.Rounded.PlayArrow)
                Text(
                    tr("Same exercises and sets, with weights from your own training."), style = AppType.caption, color = LocalPalette.current.textTertiary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
    }
}
