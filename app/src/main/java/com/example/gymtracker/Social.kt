package com.example.gymtracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * The social extras, after TikTok and Hevy: an Activity page (requests and what happened, kept on
 * the phone so it can be looked back at), Edit profile (photo, name, username, bio), Social
 * settings (which notifications, blocked people, the rules), and who liked a post.
 */

/** Which friends notifications to show (all on by default). */
object SocialPrefs {
    private const val PREFS = "gym_tracker_social"
    const val REQUESTS = "requests"
    const val LIKES = "likes"
    const val COMMENTS = "comments"

    fun on(context: Context, kind: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(kind, true)
    fun set(context: Context, kind: String, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(kind, on).apply()

    /** The switch a note's type belongs to. */
    fun kindOf(type: String?) = when (type) {
        "follow" -> REQUESTS
        "like", "clike" -> LIKES
        else -> COMMENTS
    }
}

/** What friends did, newest first, kept on this phone (the notes themselves are deleted once read). */
object ActivityLog {
    private const val PREFS = "gym_tracker_activity"
    private const val MAX = 80

    data class Item(
        val type: String, val from: String, val text: String, val owner: String, val post: String, val at: Long,
        /** For follows: they accepted (you already followed them). */
        val accepted: Boolean = false
    )

    /** How many items came in since the Activity page was last opened, for the bell's dot. */
    var unread by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun items(context: Context, uid: String): List<Item> = parse(prefs(context).getString("$uid.items", null))

    internal fun parse(raw: String?): List<Item> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(o.optString("type"), o.optString("from"), o.optString("text"), o.optString("owner"), o.optString("post"), o.optLong("at"), o.optBoolean("accepted"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    internal fun encode(items: List<Item>): String = JSONArray(items.map {
        JSONObject().put("type", it.type).put("from", it.from).put("text", it.text).put("owner", it.owner).put("post", it.post).put("at", it.at).put("accepted", it.accepted)
    }).toString()

    /** Adds new items on top, keeping the newest [MAX]. */
    internal fun merged(old: List<Item>, new: List<Item>): List<Item> = (new.sortedByDescending { it.at } + old).take(MAX)

    fun add(context: Context, uid: String, new: List<Item>) {
        if (new.isEmpty()) return
        val p = prefs(context)
        p.edit().putString("$uid.items", encode(merged(items(context, uid), new))).apply()
        refreshUnread(context, uid)
    }

    fun refreshUnread(context: Context, uid: String) {
        val seen = prefs(context).getLong("$uid.seen", 0L)
        val n = items(context, uid).count { it.at > seen }
        android.os.Handler(android.os.Looper.getMainLooper()).post { unread = n }
    }

    /** When the Activity page was last opened. */
    fun seenAt(context: Context, uid: String) = prefs(context).getLong("$uid.seen", 0L)

    /** One row: [item] (the newest) and who else did the same (likes on the same post or comment). */
    data class Group(val item: Item, val others: List<String> = emptyList())

    /** Likes on the same post (or the same comment) become one row, as Instagram shows them; newest first. */
    internal fun grouped(items: List<Item>): List<Group> {
        val out = ArrayList<Group>()
        val index = HashMap<String, Int>()
        for (it in items) {
            val key = when (it.type) {
                "like" -> "like|${it.owner}|${it.post}"
                "clike" -> "clike|${it.owner}|${it.post}|${it.text}"
                else -> null
            }
            val at = key?.let(index::get)
            if (at != null) {
                val g = out[at]
                if (it.from != g.item.from && it.from !in g.others) out[at] = g.copy(others = g.others + it.from)
            } else {
                if (key != null) index[key] = out.size
                out += Group(it)
            }
        }
        return out
    }

    /** Today, This week, Earlier: the heading a row at [at] goes under. */
    internal fun bucketOf(at: Long, now: Long = System.currentTimeMillis()): Int {
        val startOfDay = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            at >= startOfDay -> 0
            at >= startOfDay - 6 * 86_400_000L -> 1
            else -> 2
        }
    }

    fun markSeen(context: Context, uid: String) {
        prefs(context).edit().putLong("$uid.seen", System.currentTimeMillis()).apply()
        unread = 0
    }
}

/** The Activity page: find friends, requests to answer, then what happened. */
@Composable
fun ActivityPage(onClose: () -> Unit, onFriends: () -> Unit, onPerson: (Friends.Person) -> Unit, onPost: (owner: String, post: String, comments: Boolean) -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uid = Feed.me() ?: return
    var items by remember { mutableStateOf(ActivityLog.items(context, uid)) }
    // What came in since you last looked gets a dot (read before this visit marks it all seen).
    val seenBefore = remember { ActivityLog.seenAt(context, uid) }
    var people by remember { mutableStateOf<Map<String, Friends.Person>>(emptyMap()) }
    LaunchedEffect(Unit) {
        // Anything waiting comes in now (without popping notifications), then names for everyone.
        Inbox.check(context, notify = false)
        Friends.refresh()
        items = ActivityLog.items(context, uid)
        ActivityLog.markSeen(context, uid)
        val known = (Friends.circle?.let { it.friends + it.requests + it.sent }.orEmpty()).associateBy { it.uid }
        people = known + kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            items.map { it.from }.distinct().filter { it !in known }.associateWith { Friends.personNow(it) }
        }
    }
    val requests = Friends.circle?.requests.orEmpty()

    FullPage(tr("Activity"), onClose = onClose) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(50)).background(P.bgControl)
                        .pressable(onClick = onFriends).padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = P.textSecondary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tr("Find friends"), style = AppType.body, color = P.textSecondary, modifier = Modifier.weight(1f))
                    val n = Friends.circle?.friends?.size ?: 0
                    if (n > 0) Text(if (n == 1) tr("1 friend") else tr("{0} friends", n), style = AppType.caption, color = P.textTertiary)
                }
            }
            if (requests.isNotEmpty()) {
                item { Header(tr("Friend requests")) }
                items(requests, key = { "r" + it.uid }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.bgCard).pressable { onPerson(p) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PersonAvatar(p, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title(), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tr("Wants to be friends"), style = AppType.caption, color = P.textSecondary)
                        }
                        Box(Modifier.size(36.dp).clip(CircleShape).background(P.bgControl).pressable { scope.launch { Friends.removeFollower(p.uid) } }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, tr("Remove"), tint = P.textSecondary, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(P.accentPrimary).pressable { scope.launch { Friends.follow(p.uid) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Check, null, tint = P.onAccentPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(tr("Accept"), style = AppType.label, color = P.onAccentPrimary)
                        }
                    }
                }
            }
            if (items.isEmpty()) item {
                Header(tr("Activity"))
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(P.bgControl), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Notifications, null, tint = P.textTertiary, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(tr("Nothing yet"), style = AppType.bodyStrong, color = P.textPrimary)
                    Text(tr("Likes, comments and friend requests show up here."), style = AppType.body, color = P.textSecondary, textAlign = TextAlign.Center)
                }
            }
            val groups = ActivityLog.grouped(items)
            groups.forEachIndexed { i, g ->
                val bucket = ActivityLog.bucketOf(g.item.at)
                if (i == 0 || ActivityLog.bucketOf(groups[i - 1].item.at) != bucket) item(key = "h$bucket") {
                    Header(listOf(tr("Today"), tr("This week"), tr("Earlier"))[bucket])
                }
                val it = g.item
                item(key = it.type + it.from + it.at + it.post) {
                    val who = people[it.from] ?: Friends.Person(it.from, "", "")
                    ActivityRow(g, who, g.others.map { o -> people[o] ?: Friends.Person(o, "", "") }, isNew = it.at > seenBefore, onClick = {
                        if (it.type == "follow" || it.post.isBlank()) onPerson(who) else onPost(it.owner.ifBlank { uid }, it.post, it.type != "like")
                    })
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    val P = LocalPalette.current
    Text(text, style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun ActivityRow(group: ActivityLog.Group, who: Friends.Person, others: List<Friends.Person>, isNew: Boolean, onClick: () -> Unit) {
    val P = LocalPalette.current
    val item = group.item
    val first = who.title().ifBlank { tr("Someone") }
    val name = when (others.size) {
        0 -> first
        1 -> tr("{0} and {1}", first, others[0].title().ifBlank { tr("someone") })
        else -> tr("{0} and {1} others", first, others.size)
    }
    val (badge, color) = when (item.type) {
        "like", "clike" -> Icons.Rounded.Favorite to Color(0xFFFF4D5E)
        "follow" -> Icons.Rounded.PersonAdd to P.accentPrimary
        else -> Icons.Rounded.ChatBubble to Color(0xFF1C7ED6)
    }
    val what = when (item.type) {
        "like" -> tr("liked your workout")
        "clike" -> tr("liked your comment: {0}", item.text)
        "comment" -> tr("commented: {0}", item.text)
        "reply" -> tr("replied: {0}", item.text)
        else -> if (item.accepted) tr("accepted your friend request") else tr("wants to be friends")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (isNew) P.accentSoft.copy(alpha = 0.45f) else Color.Transparent)
            .pressable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(50.dp)) {
            if (others.isNotEmpty()) {
                // Two faces, overlapping, when several people did it.
                Box(Modifier.align(Alignment.TopEnd)) { PersonAvatar(others[0], 34.dp) }
                Box(Modifier.align(Alignment.BottomStart).border(2.dp, P.bgPrimary, CircleShape)) { PersonAvatar(who, 34.dp) }
            } else Box(Modifier.align(Alignment.Center)) { PersonAvatar(who, 46.dp) }
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(20.dp).clip(CircleShape).background(P.bgPrimary).padding(2.dp)
                    .clip(CircleShape).background(color),
                contentAlignment = Alignment.Center
            ) { Icon(badge, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    append(name)
                    pop()
                    append(" ")
                    append(what)
                },
                style = AppType.body, color = P.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(postAge(item.at), style = AppType.caption, color = P.textTertiary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
    }
}

/** Edit profile: photo, the name and bio friends see, and the username. */
@Composable
fun EditProfilePage(onClose: () -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    val me = Friends.meAsPerson() ?: return
    var name by remember { mutableStateOf(me.name) }
    var bio by remember { mutableStateOf(me.bio) }
    var handle by remember { mutableStateOf(me.handle) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val changed = name.trim() != me.name || bio.trim() != me.bio || cleanHandle(handle) != me.handle
    val valid = isValidHandle(cleanHandle(handle)) && name.isNotBlank()
    FullPage(
        tr("Edit profile"), onClose = onClose,
        actions = {
            val can = changed && valid && !busy
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (can) P.accentPrimary else P.bgControl).pressable(enabled = can) {
                    busy = true
                    error = null
                    scope.launch {
                        val h = cleanHandle(handle)
                        error = (if (h != me.handle) Friends.claimHandle(h, name.trim()) else null) ?: Friends.saveProfile(name, bio)
                        busy = false
                        if (error == null) { AppSnackbar.show(tr("Saved.")); onClose() }
                    }
                }.padding(horizontal = 20.dp, vertical = 10.dp)
            ) { Text(if (busy) tr("Saving…") else tr("Save"), style = AppType.label, color = if (can) P.onAccentPrimary else P.textTertiary) }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EditablePhoto { PersonAvatar(me, 96.dp, image = ProfilePhoto.mine) }
                Text(tr("Tap to change your photo"), style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(top = 8.dp))
            }
            Field(tr("Name"), name, 40, single = true) { name = it }
            Field(tr("Username"), handle, 20, single = true, prefix = "@") { handle = cleanHandle(it); error = null }
            Text(tr("3 to 20 letters, numbers, dots or underscores."), style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            Field(tr("Bio"), bio, 150, single = false, hint = tr("A line about you and your training")) { bio = it }
            error?.let { Text(it, style = AppType.caption, color = P.dangerText, modifier = Modifier.padding(top = 10.dp)) }
            Text(tr("Anyone signed in to Kilonaut can see your name, username, photo and bio."), style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String, max: Int, single: Boolean, prefix: String? = null, hint: String? = null, onChange: (String) -> Unit) {
    val P = LocalPalette.current
    Text(label, style = AppType.label, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.bgControl).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = if (single) Alignment.CenterVertically else Alignment.Top
    ) {
        if (prefix != null) Text(prefix, style = AppType.body, color = P.textTertiary, modifier = Modifier.padding(end = 2.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && hint != null) Text(hint, style = AppType.body, color = P.textTertiary)
            BasicTextField(
                value = value, onValueChange = { onChange(it.take(max)) }, singleLine = single,
                textStyle = AppType.body.copy(color = P.textPrimary), cursorBrush = SolidColor(P.accentPrimary),
                keyboardOptions = KeyboardOptions(capitalization = if (prefix == null) KeyboardCapitalization.Sentences else KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth().then(if (single) Modifier else Modifier.heightIn(min = 66.dp))
            )
        }
        if (!single || value.length > max - 10) Text("${value.length}/$max", style = AppType.caption, color = P.textTertiary, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Social settings: notifications, blocked people, the rules and the privacy policy. */
@Composable
fun SocialSettingsSection() {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (Account.user != null) Friends.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Group(tr("Notifications")) {
            listOf(
                SocialPrefs.REQUESTS to tr("Friend requests"),
                SocialPrefs.LIKES to tr("Likes"),
                SocialPrefs.COMMENTS to tr("Comments and replies")
            ).forEach { (kind, label) ->
                var on by remember { mutableStateOf(SocialPrefs.on(context, kind)) }
                Row(Modifier.fillMaxWidth().pressable { on = !on; SocialPrefs.set(context, kind, on) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f))
                    Switch(on, { on = it; SocialPrefs.set(context, kind, it) }, colors = SwitchDefaults.colors(checkedTrackColor = P.accentPrimary))
                }
            }
            Text(
                tr("Kilonaut checks about every 15 minutes while you're online."), style = AppType.caption, color = P.textTertiary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            )
        }
        Group(tr("Blocked people")) {
            val blocked = Friends.blocked
            if (blocked.isEmpty()) Text(
                tr("Nobody. Block someone from their profile, a post or a comment."), style = AppType.body, color = P.textSecondary,
                modifier = Modifier.padding(16.dp)
            )
            blocked.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(p, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(p.title(), style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        tr("Unblock"), style = AppType.label, color = P.accentPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { scope.launch { Friends.unblock(p.uid) } }.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Group(tr("Safety")) {
            LinkRowSimple(tr("Community rules")) { open(context, CommunityRules.URL) }
            LinkRowSimple(tr("Privacy policy")) { open(context, PRIVACY_POLICY_URL) }
        }
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val P = LocalPalette.current
    Column {
        Text(title, style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.bgCard).padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun LinkRowSimple(text: String, onClick: () -> Unit) {
    val P = LocalPalette.current
    Row(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
    }
}

/** Everyone who liked a post. */
@Composable
fun LikersSheet(post: Feed.Post, people: Map<String, Friends.Person>, onPerson: (Friends.Person) -> Unit, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(if (post.likes.size == 1) tr("1 like") else tr("{0} likes", post.likes.size), style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(8.dp))
            post.likes.forEach { uid ->
                val p = if (uid == Feed.me() || uid == "demo-me") (Friends.meAsPerson() ?: Friends.Person(uid, tr("You"), "")) else people[uid] ?: Friends.Person(uid, "", tr("someone"))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable { onDismiss(); onPerson(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        PersonAvatar(p, 42.dp, image = if (p.uid == Feed.me()) ProfilePhoto.mine else ProfilePhoto.decode(p.photo))
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(P.bgElevated).padding(2.dp).clip(CircleShape).background(Color(0xFFFF4D5E)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(10.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title(), style = AppType.bodyStrong, color = P.textPrimary)
                        if (p.name.isNotBlank() && p.handle.isNotBlank()) Text("@${p.handle}", style = AppType.caption, color = P.textSecondary)
                    }
                }
            }
        }
    }
}
