package com.example.gymtracker

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Calendar
import kotlinx.coroutines.launch

// Friends, in the spirit of Hevy's social screens: colourful initials for people, one big search,
// cards that say how each friend is training at a glance, and a full page per friend.

/** A colour per person, the same everywhere they appear (from their uid). */
private val AVATAR_HUES = listOf(
    Color(0xFFE8590C), Color(0xFF1C7ED6), Color(0xFF2F9E44), Color(0xFF7048E8), Color(0xFFD6336C), Color(0xFF0C8599)
)

private fun hueFor(uid: String) = AVATAR_HUES[Math.floorMod(uid.hashCode(), AVATAR_HUES.size)]

@Composable
private fun PersonAvatar(p: Friends.Person, size: Dp, ring: Boolean = false) {
    val P = LocalPalette.current
    val hue = hueFor(p.uid)
    Box(
        Modifier.size(size).clip(CircleShape).background(lerp(P.bgCard, hue, 0.16f))
            .then(if (ring) Modifier.border(2.dp, hue, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (p.name.ifBlank { p.handle }).take(1).uppercase(),
            style = if (size >= 64.dp) AppType.display else AppType.bodyStrong,
            color = hue
        )
    }
}

private fun Friends.Person.title() = name.ifBlank { "@$handle" }

/** Profile's way in: friends' faces, how many, and a badge for requests. */
@Composable
fun FriendsCard(onOpen: () -> Unit) {
    val P = LocalPalette.current
    val signedIn = Account.user != null
    LaunchedEffect(signedIn) { if (signedIn) { Friends.loadMe(); Friends.refresh() } }
    val circle = Friends.circle
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val faces = circle?.friends.orEmpty().take(3)
            if (faces.isEmpty()) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Group, null, tint = P.accentPrimary, modifier = Modifier.size(22.dp))
                }
            } else {
                // Overlapping faces, like a group.
                Box(Modifier.width(40.dp + 18.dp * (faces.size - 1)).height(40.dp)) {
                    faces.forEachIndexed { i, f ->
                        Box(Modifier.padding(start = 18.dp * i).zIndex(-i.toFloat()).clip(CircleShape).background(P.bgCard).padding(2.dp)) {
                            PersonAvatar(f, 36.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Friends"), style = AppType.bodyStrong, color = P.textPrimary)
                Text(
                    when {
                        !signedIn -> tr("Sign in to add friends")
                        circle == null -> tr("See how your friends train")
                        circle.friends.isEmpty() -> tr("Add friends by their username")
                        circle.friends.size == 1 -> tr("1 friend")
                        else -> tr("{0} friends", circle.friends.size)
                    },
                    style = AppType.caption, color = P.textSecondary
                )
            }
            if (Friends.requestCount > 0) {
                Pill(
                    if (Friends.requestCount == 1) tr("1 request") else tr("{0} requests", Friends.requestCount),
                    color = P.onAccentPrimary, container = P.accentPrimary
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** The Friends page: you, search, requests, friends with how they're doing, and requests you sent. */
@Composable
fun FriendsSection() {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Account.user
    if (user == null) {
        FriendsIntro(tr("Sign in so friends can find you, and you them.")) { SignInChoices() }
        return
    }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(user.uid) {
        Friends.loadMe()
        Friends.refresh()
        loaded = true
    }
    val handle = Friends.myHandle
    var open by remember { mutableStateOf<Friends.Person?>(null) }
    val share = { h: String ->
        val text = tr("Add me on Kilonaut: @{0}", h)
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
        Unit
    }

    if (!loaded && handle == null) {
        Text(tr("Loading…"), style = AppType.body, color = P.textSecondary)
        return
    }
    if (handle == null) {
        // The name friends see is the Google name, never part of an email address.
        HandlePicker(user.name.orEmpty(), suggestion = user.name ?: user.email?.substringBefore('@') ?: "")
        return
    }
    val me = Friends.Person(user.uid, user.name.orEmpty(), handle)
    val c = Friends.circle

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // You: face, name, username, counts, and the invite.
        AppCard(Modifier.fillMaxWidth(), color = P.accentSoft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(me, 56.dp, ring = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(me.title(), style = AppType.title, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (me.name.isNotBlank()) Text("@$handle", style = AppType.body, color = P.textSecondary)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val friends = c?.friends?.size ?: 0
                val requests = c?.requests?.size ?: 0
                Count(friends, if (friends == 1) tr("friend") else tr("friends"))
                Spacer(Modifier.width(20.dp))
                Count(requests, if (requests == 1) tr("request") else tr("requests"))
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(P.accentPrimary).pressable { share(handle) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.IosShare, null, tint = P.onAccentPrimary, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Invite"), style = AppType.label, color = P.onAccentPrimary)
                }
            }
        }

        FindPeople(handle)

        if (c != null && c.requests.isNotEmpty()) Section(tr("Want to be friends")) {
            c.requests.forEach { p ->
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(p, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title(), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.name.isNotBlank()) Text("@${p.handle}", style = AppType.caption, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        CircleIconButton(Icons.Rounded.Close, tr("Remove"), {
                            scope.launch { if (!Friends.removeFollower(p.uid)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) }
                        }, size = 36.dp, tint = P.textSecondary)
                        Spacer(Modifier.width(8.dp))
                        SmallButton(tr("Accept"), Icons.Rounded.Check) {
                            scope.launch { if (!Friends.follow(p.uid)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) }
                        }
                    }
                }
            }
        }

        if (c != null && c.friends.isNotEmpty()) Section(tr("Friends")) {
            c.friends.forEach { p -> FriendCard(p, c.stats[p.uid]) { open = p } }
        }

        if (c != null && c.friends.isEmpty() && c.requests.isEmpty()) {
            FriendsIntro(tr("Share your username, or search for theirs. When you follow each other, you'll see each other's workouts, streaks and best sets.")) {
                SecondaryButton(tr("Share your username"), { share(handle) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.IosShare)
            }
        }

        if (c != null && c.sent.isNotEmpty()) Section(tr("Waiting for them")) {
            AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                c.sent.forEach { p ->
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(p, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title(), style = AppType.body, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tr("Requested"), style = AppType.caption, color = P.textTertiary)
                        }
                        Text(
                            tr("Cancel"), style = AppType.label, color = P.textSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { scope.launch { Friends.unfollow(p.uid) } }.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        if (c == null && loaded) Text(tr("Couldn't load your friends. Check your connection."), style = AppType.body, color = P.textSecondary)
        Text(
            tr("Friends see your workouts, streak and best sets. Never your body weight, notes or photos."),
            style = AppType.caption, color = P.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
    }

    open?.let { p -> FriendProfile(p, Friends.circle?.stats?.get(p.uid), onClose = { open = null }) }
}

@Composable
private fun Count(n: Int, label: String) {
    val P = LocalPalette.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$n", style = AppType.title.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
        Spacer(Modifier.width(5.dp))
        Text(label, style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val P = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp))
        content()
    }
}

@Composable
private fun SmallButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(P.accentPrimary).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = P.onAccentPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = AppType.label, color = P.onAccentPrimary)
    }
}

/** A picture-free explainer with an action under it (signed out, or no friends yet). */
@Composable
private fun FriendsIntro(body: String, action: @Composable () -> Unit) {
    val P = LocalPalette.current
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Three faces in a row, the middle one yours-to-be.
            Row(horizontalArrangement = Arrangement.spacedBy((-10).dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("a" to 40.dp, "b" to 52.dp, "c" to 40.dp).forEachIndexed { i, (seed, size) ->
                    Box(Modifier.zIndex(if (i == 1) 1f else 0f).clip(CircleShape).background(P.bgCard).padding(3.dp)) {
                        val hue = AVATAR_HUES[i * 2 % AVATAR_HUES.size]
                        Box(Modifier.size(size).clip(CircleShape).background(lerp(P.bgCard, hue, 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(if (i == 1) Icons.Rounded.Group else Icons.Rounded.PersonAdd, null, tint = hue, modifier = Modifier.size(size * 0.48f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(tr("Train with friends"), style = AppType.title, color = P.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(body, style = AppType.body, color = P.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
private fun HandlePicker(name: String, suggestion: String) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(cleanHandle(suggestion).filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(20)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val save = {
        if (!busy && isValidHandle(text)) {
            busy = true
            scope.launch {
                error = Friends.claimHandle(text, name)
                busy = false
                if (error == null) Friends.refresh()
            }
        }
    }
    FriendsIntro(tr("Pick a username. Friends find you by it, and you can change it later.")) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.bgControl)
                .border(1.5.dp, if (error != null) P.dangerText else Color.Transparent, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("@", style = AppType.title, color = P.textTertiary)
            Spacer(Modifier.width(4.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = cleanHandle(it).take(20); error = null },
                singleLine = true,
                textStyle = AppType.title.copy(color = P.textPrimary),
                cursorBrush = SolidColor(P.accentPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            error ?: tr("3 to 20 letters, numbers, dots or underscores."),
            style = AppType.caption, color = if (error != null) P.dangerText else P.textTertiary,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp)
        )
        Spacer(Modifier.height(14.dp))
        PrimaryButton(if (busy) tr("Saving…") else tr("Save"), { save() }, Modifier.fillMaxWidth(), enabled = !busy && isValidHandle(text))
    }
}

/** One big search by username; the person found appears under it with Follow. */
@Composable
private fun FindPeople(myHandle: String) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Friends.Person?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val search = {
        val h = cleanHandle(query)
        when {
            h == myHandle -> { result = null; message = tr("That's you.") }
            !isValidHandle(h) -> { result = null; message = tr("Type their username.") }
            else -> {
                searching = true
                scope.launch {
                    result = Friends.find(h)
                    message = if (result == null) tr("Nobody has that username.") else null
                    searching = false
                }
            }
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(P.bgControl).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = P.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(tr("Find friends by username"), style = AppType.body, color = P.textTertiary)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(21); message = null; result = null },
                    singleLine = true,
                    textStyle = AppType.body.copy(color = P.textPrimary),
                    cursorBrush = SolidColor(P.accentPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) Text(
                if (searching) tr("Looking…") else tr("Search"), style = AppType.label, color = P.accentPrimary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { search() }.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        message?.let { Text(it, style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(top = 8.dp, start = 16.dp)) }
        result?.let { p ->
            Spacer(Modifier.height(10.dp))
            AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(p, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title(), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${p.handle}", style = AppType.caption, color = P.textSecondary)
                    }
                    val c = Friends.circle
                    when {
                        c?.friends?.any { it.uid == p.uid } == true -> Pill(tr("Friends"), icon = Icons.Rounded.Check)
                        c?.sent?.any { it.uid == p.uid } == true -> Pill(tr("Requested"))
                        else -> SmallButton(if (c?.requests?.any { it.uid == p.uid } == true) tr("Accept") else tr("Follow"), Icons.Rounded.PersonAdd) {
                            scope.launch {
                                if (Friends.follow(p.uid)) {
                                    val nowFriends = Friends.circle?.friends?.any { it.uid == p.uid } == true
                                    AppSnackbar.show(if (nowFriends) tr("You're friends now.") else tr("Sent. You'll be friends when they follow you back."))
                                } else AppSnackbar.show(tr("Couldn't do that. Check your connection."))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A friend at a glance: their last workout, and a ring for the week against their plan. */
@Composable
private fun FriendCard(p: Friends.Person, s: FriendStats?, onOpen: () -> Unit) {
    val P = LocalPalette.current
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(p, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title(), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        s == null -> "@${p.handle}"
                        s.lastTitle != null -> tr("{0} · {1}", shown(s.lastTitle), relativeDay(s.lastAt))
                        else -> tr("No workouts yet")
                    },
                    style = AppType.caption, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (s != null && s.streak >= 2) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = P.accentPrimary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(tr("{0} weeks in a row", s.streak), style = AppType.caption, color = P.textSecondary)
                }
            }
            if (s != null && s.goal > 0) {
                ProgressRing(s.thisWeek.toFloat() / s.goal, Modifier.size(46.dp), strokeWidth = 4.dp, color = hueFor(p.uid)) {
                    Text("${s.thisWeek}/${s.goal}", style = AppType.caption.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                }
            }
        }
    }
}

/** A friend's page over the Friends page: who they are, their week, last workout and best sets. */
@Composable
private fun FriendProfile(p: Friends.Person, cached: FriendStats?, onClose: () -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(cached) }
    var loading by remember { mutableStateOf(cached == null) }
    LaunchedEffect(p.uid) {
        Friends.stats(p.uid)?.let { stats = it }
        loading = false
    }
    var menu by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    BackHandler(onBack = onClose)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // The dialog's own window: dark status-bar icons on the light theme, as on every other page.
        val view = androidx.compose.ui.platform.LocalView.current
        val lightBackground = P.bgPrimary.luminance() > 0.5f
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = lightBackground
            }
        }
        AnimatedVisibility(visible, enter = slideInHorizontally { it / 3 } + fadeIn(), exit = slideOutHorizontally() + fadeOut()) {
            Box(Modifier.fillMaxSize().background(P.bgPrimary)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                        .padding(horizontal = 20.dp).padding(bottom = 28.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), onClose)
                        Spacer(Modifier.weight(1f))
                        Box {
                            CircleIconButton(Icons.Rounded.MoreVert, tr("More"), { menu = true })
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = P.bgElevated) {
                                DropdownMenuItem(
                                    text = { Text(tr("Remove friend"), style = AppType.body, color = P.dangerText) },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            // Both halves, so it ends for both of you at once.
                                            Friends.unfollow(p.uid)
                                            Friends.removeFollower(p.uid)
                                            onClose()
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        PersonAvatar(p, 92.dp, ring = true)
                        Spacer(Modifier.height(12.dp))
                        Text(p.title(), style = AppType.display, color = P.textPrimary, textAlign = TextAlign.Center)
                        Text("@${p.handle}", style = AppType.body, color = P.textSecondary)
                    }
                    Spacer(Modifier.height(22.dp))
                    val s = stats
                    when {
                        s == null && loading -> Text(tr("Loading…"), style = AppType.body, color = P.textSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
                        s == null -> Text(
                            tr("Nothing shared yet. Their stats appear after their app syncs."),
                            style = AppType.body, color = P.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        )
                        else -> FriendStatsBody(p, s)
                    }
                }
                StatusBarScrim()
            }
        }
    }
}

@Composable
private fun FriendStatsBody(p: Friends.Person, s: FriendStats) {
    val P = LocalPalette.current
    val hue = hueFor(p.uid)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("${s.total}", if (s.total == 1) tr("workout") else tr("workouts"), Modifier.weight(1f))
        StatTile(tr("{0} wk", s.streak), tr("in a row"), Modifier.weight(1f))
        StatTile(if (s.goal > 0) "${s.thisWeek}/${s.goal}" else "${s.thisWeek}", tr("this week"), Modifier.weight(1f))
    }

    // Their week, a dot per day like Today's.
    Spacer(Modifier.height(12.dp))
    AppCard(Modifier.fillMaxWidth()) {
        Text(tr("This week"), style = AppType.bodyStrong, color = P.textPrimary)
        Spacer(Modifier.height(12.dp))
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (Calendar.SUNDAY..Calendar.SATURDAY).zip(weekdayLetters()).forEach { (d, letter) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(letter, style = AppType.caption, color = if (d == today) P.textPrimary else P.textTertiary)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (d in s.days) hue else P.bgControl),
                        contentAlignment = Alignment.Center
                    ) {
                        if (d in s.days) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }

    if (s.lastTitle != null) {
        Spacer(Modifier.height(20.dp))
        Text(tr("Last workout"), style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(8.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shown(s.lastTitle), style = AppType.title, color = P.textPrimary)
                    Text(relativeDay(s.lastAt).replaceFirstChar { it.uppercase() }, style = AppType.caption, color = P.textSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.lastSeconds > 0) Pill(formatDuration(s.lastSeconds), icon = Icons.Rounded.Schedule)
                if (s.lastSets > 0) Pill(if (s.lastSets == 1) tr("1 set") else tr("{0} sets", s.lastSets))
                if (s.lastKg > 0) Pill(formatVolume(s.lastKg))
            }
        }
    }

    if (s.bests.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text(tr("Best sets"), style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(8.dp))
        AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
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

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    val P = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(P.bgCard).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = AppType.title.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
        Text(label, style = AppType.caption, color = P.textSecondary)
    }
}
