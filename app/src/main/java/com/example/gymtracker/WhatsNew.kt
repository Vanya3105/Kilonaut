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
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.EditNote
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

    class Item(val icon: ImageVector, val title: String, val text: String)

    /** The highlights of [version], or none. Words through [tr] when shown, so they follow the language. */
    internal fun notesFor(version: String): List<Item> = when (version) {
        "3.9.0" -> listOf(
            Item(Icons.Rounded.Badge, tr("GymTracker is now Kilonaut"), tr("Same app, same data, a name of its own.")),
            Item(Icons.Rounded.NotificationsActive, tr("Reminders"), tr("A nudge on your training days and a morning weigh-in, set on a new day dial. Profile → Reminders.")),
            Item(Icons.Rounded.TouchApp, tr("Shortcuts on the app icon"), tr("Long-press Kilonaut to start your next workout or log your weight.")),
            Item(Icons.AutoMirrored.Rounded.ShowChart, tr("A better body-weight chart"), tr("Ranges, a smoothed trend, and drag to see any day.")),
            Item(Icons.Rounded.EditNote, tr("More in a workout"), tr("Notes, warm-up sets in one tap, and effort per set if you want it. Percentages on every lift.")),
            Item(Icons.Rounded.Widgets, tr("Widgets for any wallpaper"), tr("They follow light and dark mode, or take your wallpaper's colours.")),
            Item(Icons.Rounded.Watch, tr("On the watch"), tr("Your heart rate while you rest, and one more set from your wrist."))
        )
        else -> emptyList()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The version to show notes for now, if any; a fresh install is marked seen instead. */
    fun pending(context: Context): String? {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return null
        val current = info.versionName ?: return null
        val seen = prefs(context).getString(KEY_SEEN, null)
        if (seen == current) return null
        if (seen == null && info.firstInstallTime == info.lastUpdateTime) {
            markSeen(context, current)
            return null
        }
        return current.takeIf { notesFor(it).isNotEmpty() }
    }

    fun markSeen(context: Context, version: String) = prefs(context).edit().putString(KEY_SEEN, version).apply()
}

@Composable
fun WhatsNewSheet(version: String, onDone: () -> Unit) {
    val P = LocalPalette.current
    val items = WhatsNew.notesFor(version)
    AppBottomSheet(onDismiss = onDone) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(tr("What's new"), style = AppType.label, color = P.accentPrimary)
            Text("Kilonaut $version", style = AppType.display, color = P.textPrimary)
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
