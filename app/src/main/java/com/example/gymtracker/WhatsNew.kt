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
    internal fun notesFor(version: String): List<Item> = when (version) {
        "1.0.0-beta.1" -> listOf(
            Item(Icons.Rounded.CloudSync, tr("Your training in your account"), tr("Sign in with Google or email and your workouts, programs and settings are kept in step on every phone you use.")),
            Item(Icons.Rounded.AccountCircle, tr("A new Profile"), tr("You and your totals at the top, your plan below, and every setting on its own page.")),
            Item(Icons.Rounded.Badge, tr("GymTracker is now Kilonaut"), tr("Same app, same data, a name of its own.")),
            Item(Icons.Rounded.NotificationsActive, tr("Reminders"), tr("A nudge on your training days and a morning weigh-in, set on a new day dial. Profile → Reminders.")),
            Item(Icons.Rounded.TouchApp, tr("Shortcuts on the app icon"), tr("Long-press Kilonaut to start your next workout or log your weight.")),
            Item(Icons.AutoMirrored.Rounded.ShowChart, tr("A better body-weight chart"), tr("Ranges, a smoothed trend, and drag to see any day.")),
            Item(Icons.Rounded.SwapHoriz, tr("Machine taken? Short on time?"), tr("Swap an exercise for one that trains the same muscle, or fit the day into 20–60 minutes.")),
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

    /** The installed version, for Profile → What's new. */
    fun currentVersion(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    fun markSeen(context: Context, version: String) {
        val updated = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        prefs(context).edit().putString(KEY_SEEN, version).putLong(KEY_SEEN_UPDATE, updated).apply()
    }
}

@Composable
fun WhatsNewSheet(version: String, onDone: () -> Unit) {
    val P = LocalPalette.current
    val items = WhatsNew.notesFor(version)
    AppBottomSheet(onDismiss = onDone) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(tr("What's new"), style = AppType.label, color = P.accentPrimary)
            Text("Kilonaut ${AppUpdate.shownVersion(version)}", style = AppType.display, color = P.textPrimary)
            Spacer(Modifier.height(20.dp))
            items.forEachIndexed { i, item ->
                Row(Modifier.padding(bottom = 16.dp).enterAnimation(i + 1), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(item.icon, null, tint = P.accentPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = AppType.bodyStrong, color = P.textPrimary)
                        Text(item.text, style = AppType.caption, color = P.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(tr("Got it"), onDone, Modifier.fillMaxWidth())
        }
    }
}
