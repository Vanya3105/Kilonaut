package com.example.gymtracker

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Profile's way in: how many friends, and a badge when someone wants to be one. */
@Composable
fun FriendsCard(onOpen: () -> Unit) {
    val P = LocalPalette.current
    val signedIn = Account.user != null
    LaunchedEffect(signedIn) { if (signedIn) { Friends.loadMe(); Friends.refresh() } }
    val circle = Friends.circle
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Group, null, tint = P.textSecondary, modifier = Modifier.size(22.dp))
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
                Box(Modifier.clip(CircleShape).background(P.accentPrimary).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("${Friends.requestCount}", style = AppType.label, color = P.onAccentPrimary)
                }
                Spacer(Modifier.width(6.dp))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** The Friends page: your username, finding people, requests, friends and requests you sent. */
@Composable
fun FriendsSection() {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Account.user
    if (user == null) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(tr("Sign in so friends can find you, and you them."), style = AppType.body, color = P.textSecondary)
            Spacer(Modifier.height(14.dp))
            SignInChoices()
        }
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

    if (!loaded && handle == null) {
        Text(tr("Loading…"), style = AppType.body, color = P.textSecondary)
        return
    }
    if (handle == null) {
        HandlePicker(user.name ?: user.email?.substringBefore('@') ?: "")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // You, and a way to tell friends where to find you.
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Your username"), style = AppType.caption, color = P.textSecondary)
                    Text("@$handle", style = AppType.title, color = P.textPrimary)
                }
                CircleIconButton(Icons.Rounded.IosShare, tr("Share your username"), {
                    val text = tr("Add me on Kilonaut: @{0}", handle)
                    runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                    }
                })
            }
            Text(tr("Friends see your workouts, streak and best sets. Never your body weight, notes or photos."), style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(top = 8.dp))
        }

        AddFriend(handle)

        val c = Friends.circle
        if (c != null) {
            if (c.requests.isNotEmpty()) PeopleGroup(tr("Want to be friends")) {
                c.requests.forEach { p ->
                    PersonRow(p) {
                        TextButton(onClick = { scope.launch { if (!Friends.removeFollower(p.uid)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) } }) {
                            Text(tr("Remove"), style = AppType.label, color = P.textSecondary)
                        }
                        TextButton(onClick = { scope.launch { if (!Friends.follow(p.uid)) AppSnackbar.show(tr("Couldn't do that. Check your connection.")) } }) {
                            Text(tr("Accept"), style = AppType.label, color = P.accentPrimary)
                        }
                    }
                }
            }
            PeopleGroup(tr("Friends")) {
                if (c.friends.isEmpty()) Text(
                    tr("No friends yet. When someone you follow follows you back, you can see each other's training."),
                    style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(vertical = 4.dp)
                )
                c.friends.forEach { p ->
                    PersonRow(p, onClick = { open = p }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (c.sent.isNotEmpty()) PeopleGroup(tr("Waiting for them")) {
                c.sent.forEach { p ->
                    PersonRow(p) {
                        TextButton(onClick = { scope.launch { Friends.unfollow(p.uid) } }) {
                            Text(tr("Cancel"), style = AppType.label, color = P.textSecondary)
                        }
                    }
                }
            }
        } else if (loaded) {
            Text(tr("Couldn't load your friends. Check your connection."), style = AppType.body, color = P.textSecondary)
        }
    }

    open?.let { p -> FriendStatsSheet(p, onDismiss = { open = null }) }
}

@Composable
private fun HandlePicker(name: String) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(cleanHandle(name).filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(20)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth()) {
        Text(tr("Pick a username"), style = AppType.title, color = P.textPrimary)
        Text(tr("Friends find you by it. You can change it later."), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(14.dp))
        val save = {
            if (!busy) {
                busy = true
                scope.launch {
                    error = Friends.claimHandle(text, name)
                    busy = false
                    if (error == null) Friends.refresh()
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = cleanHandle(it).take(20); error = null },
            prefix = { Text("@", color = P.textSecondary) },
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: tr("3 to 20 letters, numbers, dots or underscores."), color = if (error != null) P.dangerText else P.textTertiary) },
            textStyle = AppType.body.copy(color = P.textPrimary),
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = P.accentPrimary, unfocusedBorderColor = P.borderStrong),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(if (busy) tr("Saving…") else tr("Save"), { save() }, Modifier.fillMaxWidth(), enabled = !busy && isValidHandle(text))
    }
}

@Composable
private fun AddFriend(myHandle: String) {
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
    AppCard(Modifier.fillMaxWidth()) {
        Text(tr("Add a friend"), style = AppType.bodyStrong, color = P.textPrimary)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(21); message = null; result = null },
            placeholder = { Text(tr("Their username"), color = P.textTertiary) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = P.textSecondary) },
            singleLine = true,
            textStyle = AppType.body.copy(color = P.textPrimary),
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = P.accentPrimary, unfocusedBorderColor = P.borderStrong),
            modifier = Modifier.fillMaxWidth()
        )
        if (searching) Text(tr("Looking…"), style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(top = 8.dp))
        message?.let { Text(it, style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(top = 8.dp)) }
        result?.let { p ->
            Spacer(Modifier.height(8.dp))
            val c = Friends.circle
            PersonRow(p) {
                when {
                    c?.friends?.any { it.uid == p.uid } == true -> Pill(tr("Friends"))
                    c?.sent?.any { it.uid == p.uid } == true -> Pill(tr("Requested"))
                    else -> TextButton(onClick = {
                        scope.launch {
                            if (Friends.follow(p.uid)) {
                                val nowFriends = Friends.circle?.friends?.any { it.uid == p.uid } == true
                                AppSnackbar.show(if (nowFriends) tr("You're friends now.") else tr("Sent. You'll be friends when they follow you back."))
                            } else AppSnackbar.show(tr("Couldn't do that. Check your connection."))
                        }
                    }) {
                        Icon(Icons.Rounded.PersonAdd, null, tint = P.accentPrimary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Follow"), style = AppType.label, color = P.accentPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val P = LocalPalette.current
    Column {
        Text(title, style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun PersonRow(p: Friends.Person, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit) {
    val P = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Initial(p, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name.ifBlank { "@${p.handle}" }, style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.name.isNotBlank()) Text("@${p.handle}", style = AppType.caption, color = P.textSecondary, maxLines = 1)
        }
        trailing()
    }
}

@Composable
private fun Initial(p: Friends.Person, size: Dp) {
    val P = LocalPalette.current
    Box(Modifier.size(size).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
        Text((p.name.ifBlank { p.handle }).take(1).uppercase(), style = AppType.bodyStrong, color = P.accentPrimary)
    }
}

/** A friend's training: the totals, their last workout and their best sets (in your units). */
@Composable
private fun FriendStatsSheet(p: Friends.Person, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<FriendStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(p.uid) {
        stats = Friends.stats(p.uid)
        loading = false
    }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Initial(p, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifBlank { "@${p.handle}" }, style = AppType.title, color = P.textPrimary)
                    Text("@${p.handle}", style = AppType.caption, color = P.textSecondary)
                }
            }
            Spacer(Modifier.height(18.dp))
            val s = stats
            when {
                loading -> Text(tr("Loading…"), style = AppType.body, color = P.textSecondary)
                s == null -> Text(tr("Nothing shared yet. Their stats appear after their app syncs."), style = AppType.body, color = P.textSecondary)
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("${s.total}", if (s.total == 1) tr("workout") else tr("workouts"), Modifier.weight(1f))
                        StatTile(if (s.goal > 0) "${s.thisWeek}/${s.goal}" else "${s.thisWeek}", tr("this week"), Modifier.weight(1f))
                        StatTile(tr("{0} wk", s.streak), tr("in a row"), Modifier.weight(1f))
                    }
                    if (s.lastTitle != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(tr("Last workout"), style = AppType.overline, color = P.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.bgControl).padding(14.dp)) {
                            Text(shown(s.lastTitle), style = AppType.bodyStrong, color = P.textPrimary)
                            Text(
                                listOfNotNull(
                                    relativeDay(s.lastAt),
                                    formatDuration(s.lastSeconds).takeIf { s.lastSeconds > 0 },
                                    (if (s.lastSets == 1) tr("1 set") else tr("{0} sets", s.lastSets)).takeIf { s.lastSets > 0 },
                                    formatVolume(s.lastKg).takeIf { s.lastKg > 0 }
                                ).joinToString(" · "),
                                style = AppType.caption, color = P.textSecondary
                            )
                        }
                    }
                    if (s.bests.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(tr("Best sets"), style = AppType.overline, color = P.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        s.bests.forEach { (name, set) ->
                            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(shown(name), style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(set, style = AppType.bodyStrong.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = {
                scope.launch {
                    // Both halves, so it ends for both of you at once.
                    Friends.unfollow(p.uid)
                    Friends.removeFollower(p.uid)
                    onDismiss()
                }
            }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(tr("Remove friend"), style = AppType.label, color = P.dangerText)
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    val P = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(P.bgControl).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = AppType.title.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
        Text(label, style = AppType.caption, color = P.textSecondary)
    }
}
