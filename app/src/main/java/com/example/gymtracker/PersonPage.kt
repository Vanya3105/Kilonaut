package com.example.gymtracker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import java.util.Calendar

/*
 * A person's page, opened from their avatar or name anywhere in Friends and the feed (yours from
 * the feed's top bar). Instagram-like: a banner in their colour, the photo overlapping it, name
 * and the streak, three numbers, their week, then Posts (a grid; tap to swipe through them) and
 * Training (last workout, best sets). Someone you're not friends with yet sees a locked page.
 */

private enum class Relation { ME, FRIEND, WANTS_YOU, REQUESTED, BLOCKED, NONE }

@Composable
fun PersonPage(person: Friends.Person, onClose: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val circle = Friends.circle
    val relation = when {
        person.uid == Feed.me() || person.uid == "demo-me" -> Relation.ME
        Friends.isBlocked(person.uid) -> Relation.BLOCKED
        Feed.demo || circle?.friends?.any { it.uid == person.uid } == true -> Relation.FRIEND
        circle?.requests?.any { it.uid == person.uid } == true -> Relation.WANTS_YOU
        circle?.sent?.any { it.uid == person.uid } == true -> Relation.REQUESTED
        else -> Relation.NONE
    }
    val open = relation == Relation.ME || relation == Relation.FRIEND
    var stats by remember { mutableStateOf<FriendStats?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(person.uid, open) {
        if (open) {
            stats = if (relation == Relation.ME) Friends.myStats(context) else Friends.stats(person.uid)
            Feed.loadPostsBy(person)
        }
        loaded = true
    }
    DisposableEffect(person.uid) { onDispose { Feed.clearPersonPosts() } }
    val posts = Feed.personPosts
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }
    var blocking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var friendsOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    if (editing) EditProfilePage(onClose = { editing = false })
    if (friendsOpen) FullPage(tr("Friends"), onClose = { friendsOpen = false }) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) { FriendsSection() }
    }
    if (settingsOpen) FullPage(tr("Social settings"), onClose = { settingsOpen = false }) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) { SocialSettingsSection() }
    }
    reporting?.let { ReportSheet(it, onDismiss = { reporting = null }) }
    if (blocking) BlockDialog(person, onDismiss = { blocking = false }, onBlocked = onClose)
    val hue = hueFor(person.uid)
    val photo = if (relation == Relation.ME) ProfilePhoto.mine else ProfilePhoto.decode(person.photo)
    val grid = rememberLazyGridState()
    // The banner moves at half speed and fades as the page scrolls; past it, a solid bar with the name.
    val bannerShift by remember { derivedStateOf { if (grid.firstVisibleItemIndex == 0) grid.firstVisibleItemScrollOffset.toFloat() else 2000f } }
    val density = LocalDensity.current
    val collapsed by remember { derivedStateOf { bannerShift > with(density) { 230.dp.toPx() } } }
    val bar by animateFloatAsState(if (collapsed) 1f else 0f, tween(220), label = "bar")

    // Back steps out one level: the posts viewer first, then the page.
    val back = { if (viewing != null) viewing = null else onClose() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightTheme = P.bgPrimary.luminance() > 0.5f
        SideEffect {
            // Light icons over the dark banner; the theme's own once the solid bar is up.
            (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = collapsed && lightTheme }
        }
        Box(Modifier.fillMaxSize().background(P.bgPrimary)) {
            LazyVerticalGrid(
                GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                item(span = { GridItemSpan(3) }) {
                    Header(person, relation, photo, hue, stats, bannerShift)
                }
                if (!open) {
                    item(span = { GridItemSpan(3) }) { Locked(person, relation) }
                } else {
                    if (relation == Relation.ME && !Feed.demo) item(span = { GridItemSpan(3) }) {
                        MyActions(onEdit = { editing = true }, onFriends = { friendsOpen = true }, onSettings = { settingsOpen = true })
                    }
                    item(span = { GridItemSpan(3) }) { Numbers(stats, hue, loaded) }
                    stats?.let { s -> item(span = { GridItemSpan(3) }) { Week(s, hue) } }
                    item(span = { GridItemSpan(3) }) {
                        Tabs(tab, posts?.size) { tab = it }
                    }
                    if (tab == 0) {
                        when {
                            posts == null -> item(span = { GridItemSpan(3) }) {
                                Text(if (loaded) tr("Couldn't load posts. Check your connection.") else tr("Loading…"), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(24.dp))
                            }
                            posts.isEmpty() -> item(span = { GridItemSpan(3) }) {
                                NoPosts(if (relation == Relation.ME) tr("Your posts show up here. Share a workout from the feed's ＋ or after a workout.") else tr("{0} hasn't posted yet.", person.title()))
                            }
                            else -> items(posts, key = { it.id }) { p ->
                                PostTile(p, onOpen = { viewing = posts.indexOf(p) })
                            }
                        }
                    } else {
                        item(span = { GridItemSpan(3) }) { Training(stats) }
                    }
                }
            }

            // Back and ⋮ on frosted circles over the banner; once it's gone, a solid bar with the name.
            Row(
                Modifier.fillMaxWidth().background(P.bgPrimary.copy(alpha = bar)).statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FrostedButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), solid = bar, onClick = onClose)
                Spacer(Modifier.width(12.dp))
                Text(
                    person.title(), style = AppType.title, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).graphicsLayer { alpha = bar; translationY = (1f - bar) * 12.dp.toPx() }
                )
                if (relation != Relation.ME && !Feed.demo) Box {
                    FrostedButton(Icons.Rounded.MoreVert, tr("More"), solid = bar) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = P.bgElevated) {
                        if (relation == Relation.FRIEND) DropdownMenuItem(
                            text = { Text(tr("Remove friend"), style = AppType.body) },
                            onClick = {
                                menu = false
                                scope.launch {
                                    Friends.unfollow(person.uid)
                                    Friends.removeFollower(person.uid)
                                    onClose()
                                }
                            }
                        )
                        DropdownMenuItem(text = { Text(tr("Report"), style = AppType.body) }, onClick = { menu = false; reporting = ReportTarget(person) })
                        if (Friends.isBlocked(person.uid)) DropdownMenuItem(
                            text = { Text(tr("Unblock"), style = AppType.body) },
                            onClick = { menu = false; scope.launch { Friends.unblock(person.uid) } }
                        ) else DropdownMenuItem(
                            text = { Text(tr("Block"), style = AppType.body, color = P.dangerText) },
                            onClick = { menu = false; blocking = true }
                        )
                    }
                }
            }

            // Their posts, full screen, from the one tapped.
            viewing?.let { start ->
                val list = posts.orEmpty()
                if (start < list.size) Box(Modifier.fillMaxSize().background(Color.Black)) {
                    val pager = rememberPagerState(initialPage = start) { list.size + 1 }
                    FeedPager(list, Feed.people, onFriends = {}, onNewPost = {}, pager = pager, endPage = false)
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrostedButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back")) { viewing = null }
                        Spacer(Modifier.width(12.dp))
                        Text(person.title(), style = AppType.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A round button: frosted over the banner, the page's control colour as [solid] goes to 1. */
@Composable
private fun FrostedButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, solid: Float = 0f, onClick: () -> Unit) {
    val P = LocalPalette.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(lerp(Color.Black.copy(alpha = 0.28f), P.bgControl, solid)).pressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = lerp(Color.White, P.textPrimary, solid), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Header(person: Friends.Person, relation: Relation, photo: androidx.compose.ui.graphics.ImageBitmap?, hue: Color, stats: FriendStats?, shift: Float) {
    val P = LocalPalette.current
    val density = LocalDensity.current
    val bannerHeight = 190.dp
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            // The banner: their colour, deep, with two soft rings; it lags the scroll for depth.
            Box(
                Modifier.fillMaxWidth().height(bannerHeight + WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .graphicsLayer { translationY = shift * 0.5f; alpha = (1f - shift / with(density) { 260.dp.toPx() }).coerceIn(0f, 1f); clip = true }
                    .background(Brush.linearGradient(listOf(lerp(hue, Color.White, 0.08f), lerp(hue, Color.Black, 0.55f)), start = Offset.Zero, end = Offset(1200f, 900f)))
            ) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 70.dp, y = (-50).dp).size(240.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
                Box(Modifier.align(Alignment.BottomStart).offset(x = (-60).dp, y = 70.dp).size(200.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)))
            }
            // The photo overlapping the banner's edge, ringed in the page colour.
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 20.dp).offset(y = 52.dp)
                    .size(108.dp).background(P.bgPrimary, CircleShape).padding(4.dp)
            ) {
                if (relation == Relation.ME) EditablePhoto { PersonAvatar(person, 100.dp, image = photo) } else PersonAvatar(person, 100.dp, image = photo)
            }
        }
        Spacer(Modifier.height(60.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(person.title(), style = AppType.display, color = P.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (person.name.isNotBlank() && person.handle.isNotBlank()) Text("@${person.handle}", style = AppType.body, color = P.textSecondary)
            val bio = if (relation == Relation.ME) Friends.myBio else person.bio
            if (bio.isNotBlank()) Text(bio, style = AppType.body, color = P.textPrimary, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // When they last trained (the streak has its own tile below).
                if (stats?.lastTitle != null) Pill(
                    tr("Trained {0}", relativeDay(stats.lastAt)),
                    icon = Icons.Rounded.FitnessCenter, color = P.accentPrimary, container = P.accentSoft
                )
                when (relation) {
                    Relation.FRIEND -> Pill(tr("Friends"), icon = Icons.Rounded.Check)
                    Relation.ME -> Pill(tr("You"))
                    else -> {}
                }
            }
        }
    }
}

/** Your own page: Edit profile, Friends and settings, side by side. */
@Composable
private fun MyActions(onEdit: () -> Unit, onFriends: () -> Unit, onSettings: () -> Unit) {
    val P = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun Button(text: String, modifier: Modifier, onClick: () -> Unit) {
            Box(modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(P.bgControl).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
                Text(text, style = AppType.label, color = P.textPrimary)
            }
        }
        Button(tr("Edit profile"), Modifier.weight(1f), onEdit)
        val n = Friends.circle?.friends?.size ?: 0
        Button(if (n == 1) tr("1 friend") else tr("{0} friends", n), Modifier.weight(1f), onFriends)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(P.bgControl).pressable(onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Settings, tr("Social settings"), tint = P.textPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Numbers(s: FriendStats?, hue: Color, loaded: Boolean) {
    val P = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun Tile(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
            Column(
                modifier.height(96.dp).clip(RoundedCornerShape(20.dp)).background(P.bgCard).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content
            )
        }
        val placeholder = if (loaded) "–" else "·"
        Tile(Modifier.weight(1f)) {
            CountUpText(s?.total, placeholder)
            Text(if (s?.total == 1) tr("workout") else tr("workouts"), style = AppType.caption, color = P.textSecondary)
        }
        Tile(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocalFireDepartment, null, tint = P.accentPrimary, modifier = Modifier.size(22.dp))
                CountUpText(s?.streak, placeholder)
            }
            Text(tr("weeks in a row"), style = AppType.caption, color = P.textSecondary, maxLines = 1)
        }
        Tile(Modifier.weight(1f)) {
            val goal = s?.goal ?: 0
            val progress by animateFloatAsState(if (s != null && goal > 0) s.thisWeek.toFloat() / goal else 0f, tween(900), label = "week_ring")
            ProgressRing(progress, Modifier.size(52.dp), strokeWidth = 5.dp, color = hue) {
                Text(if (s == null) placeholder else if (goal > 0) "${s.thisWeek}/$goal" else "${s.thisWeek}", style = AppType.label.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
            }
            Text(tr("this week"), style = AppType.caption, color = P.textSecondary)
        }
    }
}

/** A number that counts up when it arrives. */
@Composable
private fun CountUpText(value: Int?, placeholder: String) {
    val P = LocalPalette.current
    val shown by animateFloatAsState((value ?: 0).toFloat(), tween(700), label = "count")
    Text(
        if (value == null) placeholder else "${shown.toInt()}",
        style = AppType.title.copy(fontFeatureSettings = "tnum", fontSize = 26.sp), color = P.textPrimary
    )
}

@Composable
private fun Week(s: FriendStats, hue: Color) {
    val P = LocalPalette.current
    val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).clip(RoundedCornerShape(20.dp)).background(P.bgCard).padding(16.dp)) {
        Text(tr("This week"), style = AppType.bodyStrong, color = P.textPrimary)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (Calendar.SUNDAY..Calendar.SATURDAY).zip(weekdayLetters()).forEach { (d, letter) ->
                val done = d in s.days
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(letter, style = AppType.caption, color = if (d == today) P.textPrimary else P.textTertiary)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (done) hue else P.bgControl)
                            .then(if (d == today && !done) Modifier.border(1.5.dp, P.textTertiary, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center
                    ) { if (done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

/** Posts | Training, with a sliding underline. */
@Composable
private fun Tabs(selected: Int, postCount: Int?, onSelect: (Int) -> Unit) {
    val P = LocalPalette.current
    val labels = listOf(if (postCount != null && postCount > 0) tr("Posts · {0}", postCount) else tr("Posts"), tr("Training"))
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        val w = maxWidth / 2
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.85f), label = "tab_x")
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).pressable { onSelect(i) }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (i == 0) Icons.Rounded.PhotoLibrary else Icons.Rounded.FitnessCenter, null, tint = if (i == selected) P.textPrimary else P.textTertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = AppType.label, color = if (i == selected) P.textPrimary else P.textTertiary)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(P.borderSubtle))
        Box(Modifier.align(Alignment.BottomStart).offset(x = x + w * 0.2f).width(w * 0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(P.textPrimary))
    }
}

/** A post in the grid: the photo, or the workout / words on the poster's colour. */
@Composable
private fun PostTile(p: Feed.Post, onOpen: () -> Unit) {
    val img = Feed.image(p.photo)
    val hue = hueFor(p.author.uid)
    Box(Modifier.fillMaxWidth().aspectRatio(0.8f).pressable(pressedScale = 0.97f, onClick = onOpen)) {
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (p.workout != null) Icon(Icons.Rounded.FitnessCenter, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(16.dp))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(hue, Color.Black, 0.2f), lerp(hue, Color.Black, 0.7f)), start = Offset.Zero, end = Offset.Infinite)))
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                val w = p.workout
                if (w != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FitnessCenter, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
                        if (w.bests > 0) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.EmojiEvents, null, tint = Color(0xFFFFC53D), modifier = Modifier.size(14.dp))
                        }
                    }
                    Column {
                        Text(shown(w.title), style = AppType.bodyStrong, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (w.kg > 0) formatVolume(w.kg) else formatDuration(w.seconds), style = AppType.caption, color = Color.White.copy(alpha = 0.8f))
                    }
                } else {
                    Text(p.text, style = AppType.label, color = Color.White, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (p.likes.isNotEmpty()) Row(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text("${p.likes.size}", style = AppType.caption.copy(fontSize = 11.sp), color = Color.White)
        }
    }
}

@Composable
private fun NoPosts(text: String) {
    val P = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).border(2.dp, P.borderStrong, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PhotoLibrary, null, tint = P.textTertiary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(text, style = AppType.body, color = P.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Training(s: FriendStats?) {
    val P = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s == null) {
            Text(tr("Nothing shared yet. Their stats appear after their app syncs."), style = AppType.body, color = P.textSecondary)
            return@Column
        }
        if (s.lastTitle != null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.bgCard).padding(16.dp)) {
            Text(tr("Last workout"), style = AppType.overline, color = P.textSecondary)
            Spacer(Modifier.height(6.dp))
            Text(shown(s.lastTitle), style = AppType.title, color = P.textPrimary)
            Text(relativeDay(s.lastAt).replaceFirstChar { it.uppercase() }, style = AppType.caption, color = P.textSecondary)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.lastSeconds > 0) Pill(formatDuration(s.lastSeconds), icon = Icons.Rounded.Schedule)
                if (s.lastSets > 0) Pill(if (s.lastSets == 1) tr("1 set") else tr("{0} sets", s.lastSets))
                if (s.lastKg > 0) Pill(formatVolume(s.lastKg))
            }
        }
        if (s.bests.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.bgCard).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.EmojiEvents, null, tint = P.warningText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Best sets"), style = AppType.overline, color = P.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            s.bests.forEachIndexed { i, (name, set) ->
                if (i > 0) Box(Modifier.padding(start = 54.dp).fillMaxWidth().height(1.dp).background(P.borderSubtle))
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ExerciseThumb(name, size = 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(shown(name), style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(set, style = AppType.bodyStrong.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                }
            }
        }
    }
}

/** Not friends (yet): what they'd see, and the one thing to do about it. */
@Composable
private fun Locked(person: Friends.Person, relation: Relation) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(P.bgControl), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, tint = P.textSecondary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (relation == Relation.BLOCKED) tr("You blocked {0}", person.title()) else tr("Follow each other to see their training"),
            style = AppType.title, color = P.textPrimary, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (relation) {
                Relation.WANTS_YOU -> tr("{0} wants to be friends. Accept to see each other's workouts, streaks and posts.", person.title())
                Relation.REQUESTED -> tr("You asked. When {0} follows you back, you'll see each other's workouts, streaks and posts.", person.title())
                Relation.BLOCKED -> tr("They can't follow you or see your training, and their comments are hidden from you.")
                else -> tr("Friends see each other's workouts, streaks and posts.")
            },
            style = AppType.body, color = P.textSecondary, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        when (relation) {
            Relation.REQUESTED -> Pill(tr("Requested"))
            Relation.BLOCKED -> SecondaryButton(tr("Unblock"), { scope.launch { Friends.unblock(person.uid) } }, Modifier.fillMaxWidth())
            else -> PrimaryButton(
                if (relation == Relation.WANTS_YOU) tr("Accept") else tr("Follow"),
                { scope.launch { if (!Friends.follow(person.uid)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) } },
                Modifier.fillMaxWidth(), icon = Icons.Rounded.PersonAdd
            )
        }
    }
}
