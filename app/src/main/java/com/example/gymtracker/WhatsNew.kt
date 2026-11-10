package com.example.gymtracker

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Once after an update, what it brought: GitHub installs otherwise only learn it from the release
 * page. Never on a fresh install (everything is new then), and only for versions with notes.
 */
object WhatsNew {
    private const val PREFS = "gym_tracker_whats_new"
    private const val KEY_SEEN = "seen_version"
    private const val KEY_SEEN_UPDATE = "seen_update_time"

    class Item(val icon: ImageVector, val title: String, val text: String)

    /** The highlights of [version], or none. Words through [tr] when shown, so they follow the language. */
    /** Versions with notes, newest first. */
    private val VERSIONS = listOf("1.0.0-beta.3", "1.0.0-beta.2", "1.0.0-beta.1")

    /**
     * [current]'s notes first, then those of versions newer than [since] that weren't seen (an update
     * can skip some); [since] null: every version up to [current].
     */
    internal fun sections(current: String, since: String?): List<Pair<String, List<Item>>> {
        val older = VERSIONS.filter { v -> v != current && !AppUpdate.isNewer(v, current) && (since == null || AppUpdate.isNewer(v, since)) }
        return (listOf(current) + older).map { it to notesFor(it) }.filter { it.second.isNotEmpty() }
    }

    internal fun notesFor(version: String): List<Item> = when (version) {
        "1.0.0-beta.3" -> listOf(
            Item(Icons.Rounded.Group, tr("Friends"), tr("Pick a username, find friends by it and follow each other. Only friends see what you share.")),
            Item(Icons.Rounded.DynamicFeed, tr("A feed to swipe through"), tr("Share a workout, a photo or a few words, and swipe up through your friends' posts. Double-tap to like.")),
            Item(Icons.Rounded.Forum, tr("Comments that talk back"), tr("Reply to a comment, like it, mention someone with @, or answer with one tap of an emoji.")),
            Item(Icons.Rounded.FitnessCenter, tr("Do a friend's workout"), tr("Open the workout on a friend's post and start it yourself, with weights from your own training.")),
            Item(Icons.Rounded.NotificationsActive, tr("Activity"), tr("Likes, comments and friend requests in one place, grouped, with a dot on Feed when something's new. Choose which ones notify you.")),
            Item(Icons.Rounded.AccountCircle, tr("Profiles"), tr("Your page and your friends': a photo, a short bio, weeks in a row, posts and best lifts. Share yours with a tap.")),
            Item(Icons.Rounded.Shield, tr("Safe by design"), tr("Report or block anyone, community rules to agree to, and delete your account with everything in it from Profile → Account."))
        )
        "1.0.0-beta.2" -> listOf(
            Item(Icons.Rounded.EmojiEvents, tr("A better finish"), tr("You celebrate with a small jump, and the screen shows your week and what's next. Timed workouts show the time you held.")),
            Item(Icons.Rounded.Timer, tr("Today follows your workout"), tr("While a workout runs, Today shows its clock, the rest left and the exercise you're on.")),
            Item(Icons.Rounded.CloudSync, tr("Sign in on another phone, safely"), tr("If your account and the phone have different plans, you choose which to keep. A phone with someone else's training asks before adding it to your account.")),
            Item(Icons.Rounded.Schedule, tr("Short on time, clearer"), tr("Every exercise of the day is listed, with what changes for the time you pick.")),
            Item(Icons.Rounded.Face, tr("Setup that looks like you"), tr("The figure in setup changes with the age you enter.")),
            Item(Icons.Rounded.Build, tr("Fixes"), tr("Long exercise names no longer split the plan line, and custom exercises keep their type when your account syncs."))
        )
        "1.0.0-beta.1" -> listOf(
            Item(Icons.Rounded.CloudSync, tr("Your training in your account"), tr("Sign in with Google or email and your workouts, programs and settings are kept in step on every phone you use.")),
            Item(Icons.Rounded.AccountCircle, tr("A new Profile"), tr("You and your totals at the top, your plan below, and every setting on its own page.")),
            Item(Icons.Rounded.Badge, tr("GymTracker is now Kilonaut"), tr("Same app, same data, a name of its own.")),
            Item(Icons.Rounded.NotificationsActive, tr("Reminders"), tr("A nudge on your training days and a morning weigh-in, set on a new day dial. Profile → Reminders.")),
            Item(Icons.Rounded.TouchApp, tr("Shortcuts on the app icon"), tr("Long-press Kilonaut to start your next workout or log your weight.")),
            Item(Icons.AutoMirrored.Rounded.ShowChart, tr("A better body-weight chart"), tr("Ranges, a smoothed trend, and drag to see any day.")),
            Item(Icons.Rounded.SwapHoriz, tr("Machine taken? Short on time?"), tr("Swap an exercise for one that trains the same muscle, or fit the day into 20–75 minutes.")),
            Item(Icons.Rounded.SelfImprovement, tr("A coach across weeks"), tr("Lighter weeks when you need them, a set more or less where it helps, and swaps for exercises you keep skipping. With effort per set on, it checks how well you judge reps left.")),
            Item(Icons.Rounded.EditNote, tr("More in a workout"), tr("Notes, warm-up sets in one tap, effort per set if you want it, percentages on every lift, and Do it again from History.")),
            Item(Icons.Rounded.Widgets, tr("Widgets for any wallpaper"), tr("They follow light and dark mode or your wallpaper's colours. New: a body-weight widget.")),
            Item(Icons.Rounded.Watch, tr("On the watch"), tr("Your heart rate while you rest, and one more set from your wrist."))
        )
        else -> emptyList()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The version to show notes for now, if any; a fresh install is marked seen instead. */
    fun pending(context: Context): String? {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return null
        val current = info.versionName ?: return null
        if (prefs(context).getLong(KEY_SEEN_UPDATE, 0L) == info.lastUpdateTime) return null
        if (info.firstInstallTime == info.lastUpdateTime) {
            markSeen(context, current)
            return null
        }
        return current.takeIf { notesFor(it).isNotEmpty() }
    }

    /** The last version whose notes were seen, so an update shows what came since. */
    fun lastSeen(context: Context): String? = prefs(context).getString(KEY_SEEN, null)

    /** The installed version, for Profile → What's new. */
    fun currentVersion(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    fun markSeen(context: Context, version: String) {
        val updated = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        prefs(context).edit().putString(KEY_SEEN, version).putLong(KEY_SEEN_UPDATE, updated).apply()
    }
}

/**
 * What's new: the newest version's notes as the main list, and any versions skipped since [since]
 * (null: every version, for Profile → What's new) as shorter lists below. "Got it" stays in view.
 */
@Composable
fun WhatsNewSheet(version: String, since: String? = null, onDone: () -> Unit) {
    val P = LocalPalette.current
    val sections = WhatsNew.sections(version, since)
    AppBottomSheet(onDismiss = onDone) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(tr("What's new"), style = AppType.overline, color = P.accentPrimary)
                Text("Kilonaut ${AppUpdate.shownVersion(version)}", style = AppType.display, color = P.textPrimary)
                Spacer(Modifier.height(20.dp))
                sections.firstOrNull()?.second?.forEachIndexed { i, item ->
                    Row(Modifier.padding(bottom = 18.dp).enterAnimation(i + 1), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
                            Icon(item.icon, null, tint = P.accentPrimary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = AppType.bodyStrong, color = P.textPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(item.text, style = AppType.body, color = P.textSecondary)
                        }
                    }
                }
                // Versions skipped on the way here (or, from Profile, the ones before): the titles in a card.
                sections.drop(1).forEach { (v, items) ->
                    Spacer(Modifier.height(6.dp))
                    Text(tr("Also in {0}", AppUpdate.shownVersion(v)), style = AppType.overline, color = P.textTertiary)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.bgControl).padding(horizontal = 14.dp, vertical = 6.dp)) {
                        items.forEach { item ->
                            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(item.icon, null, tint = P.textSecondary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(item.title, style = AppType.body, color = P.textPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(tr("Got it"), onDone, Modifier.fillMaxWidth())
        }
    }
}
