package com.example.gymtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar

/*
 * Friends on Today, like Instagram's stories row: who trained lately, a ring on whoever trained
 * today, and the newest workout in one line. A face opens that friend's page; + finds friends.
 * Friends were four tabs away before; this is where the app opens.
 */

/** One friend in the row, with their last workout if they share one. */
internal data class FriendActivity(val person: Friends.Person, val lastTitle: String?, val lastAt: Long)

/** Shown once you have a username (or the sample feed is on): the row needs friends to mean something. */
internal fun showTodayFriends(): Boolean = Feed.demo || (Account.user != null && Friends.myHandle != null)

/** Friends, most recently trained first; the sample feed's people when it's on. */
internal fun todayFriends(): List<FriendActivity> {
    val list = if (Feed.demo) {
        val posts = Feed.posts.orEmpty()
        Feed.people.values.filter { it.uid != "demo-me" }.map { p ->
            val last = posts.filter { it.author.uid == p.uid && it.workout != null }.maxByOrNull { it.createdAt }
            FriendActivity(p, last?.workout?.title, last?.createdAt ?: 0L)
        }
    } else {
        val c = Friends.circle ?: return emptyList()
        c.friends.map { p -> c.stats[p.uid].let { s -> FriendActivity(p, s?.lastTitle?.takeIf { it.isNotBlank() }, s?.lastAt ?: 0L) } }
    }
    return list.sortedByDescending { it.lastAt }
}

private fun isToday(at: Long, now: Long = System.currentTimeMillis()): Boolean {
    if (at <= 0L) return false
    val a = Calendar.getInstance().apply { timeInMillis = at }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

@Composable
fun TodayFriends(modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    var person by remember { mutableStateOf<Friends.Person?>(null) }
    var friendsOpen by remember { mutableStateOf(false) }
    val friends = todayFriends()
    // The newest workout, if it's from the last two days: older news isn't news.
    val latest = friends.firstOrNull { it.lastTitle != null && System.currentTimeMillis() - it.lastAt < 2 * 24 * 3_600_000L }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            friends.forEach { f -> FriendBubble(f) { person = f.person } }
            AddBubble { friendsOpen = true }
        }
        if (latest != null) {
            val f = latest
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).pressable(pressedScale = 0.98f) { person = f.person }.padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (isToday(f.lastAt)) P.accentPrimary else P.textTertiary))
                Spacer(Modifier.width(8.dp))
                Text(
                    tr("{0} finished {1}", f.person.firstName(), shown(f.lastTitle!!)) + " · " + postAge(f.lastAt),
                    style = AppType.label, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        } else if (friends.isEmpty()) Text(
            tr("Add friends to see their workouts here."),
            style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(top = 8.dp)
        )
    }

    person?.let { PersonPage(it, onClose = { person = null }) }
    if (friendsOpen) FullPage(tr("Friends"), onClose = { friendsOpen = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) { FriendsSection() }
    }
}

private fun Friends.Person.firstName(): String = name.substringBefore(' ').ifBlank { "@$handle" }

@Composable
private fun FriendBubble(f: FriendActivity, onClick: () -> Unit) {
    val P = LocalPalette.current
    val trainedToday = isToday(f.lastAt)
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.94f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Accent ring: trained today. A hairline otherwise, so every face sits the same size.
        Box(
            Modifier.size(60.dp).border(if (trainedToday) 2.5.dp else 1.dp, if (trainedToday) P.accentPrimary else P.borderStrong, CircleShape).padding(4.dp),
            contentAlignment = Alignment.Center
        ) { PersonAvatar(f.person, 52.dp) }
        Spacer(Modifier.height(4.dp))
        Text(
            f.person.firstName(), style = AppType.caption, color = if (trainedToday) P.textPrimary else P.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AddBubble(onClick: () -> Unit) {
    val P = LocalPalette.current
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.94f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(60.dp).padding(4.dp).clip(CircleShape).background(P.bgControl), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PersonAdd, null, tint = P.textSecondary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(tr("Add"), style = AppType.caption, color = P.textSecondary, maxLines = 1, textAlign = TextAlign.Center)
    }
}
