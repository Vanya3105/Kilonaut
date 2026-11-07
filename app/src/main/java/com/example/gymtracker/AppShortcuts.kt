package com.example.gymtracker

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Long-press the app icon: start the next workout (or get back to the running one), an empty
 * workout, or log a weigh-in, without going through Today first.
 */
object AppShortcuts {
    const val START_NEXT = "com.vanyaevtushenko.gymtracker.shortcut.START_NEXT"
    const val EMPTY_WORKOUT = "com.vanyaevtushenko.gymtracker.shortcut.EMPTY_WORKOUT"
    const val LOG_WEIGHT = "com.vanyaevtushenko.gymtracker.shortcut.LOG_WEIGHT"
    private val ALL = setOf(START_NEXT, EMPTY_WORKOUT, LOG_WEIGHT)

    /** The shortcut the app was opened with, until the app has acted on it. */
    var pending by mutableStateOf<String?>(null)

    fun take(intent: Intent?) {
        val action = intent?.action ?: return
        if (action in ALL) pending = action
    }

    /** [next]: the day that's up next; [running]: the workout in progress, which comes first. */
    fun update(context: Context, next: String?, running: String?) {
        fun intent(action: String) = Intent(context, MainActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val shortcuts = buildList {
            val first = when {
                running != null -> tr("Resume") to tr("Resume {0}", shown(running))
                next != null -> tr("Start") to tr("Start {0}", shown(next))
                else -> null
            }
            first?.let { (short, long) ->
                add(
                    ShortcutInfoCompat.Builder(context, "start_next")
                        .setShortLabel(short).setLongLabel(long)
                        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_play))
                        .setIntent(intent(START_NEXT)).setRank(0).build()
                )
            }
            add(
                ShortcutInfoCompat.Builder(context, "empty_workout")
                    .setShortLabel(tr("Empty")).setLongLabel(tr("Empty workout"))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_add))
                    .setIntent(intent(EMPTY_WORKOUT)).setRank(1).build()
            )
            add(
                ShortcutInfoCompat.Builder(context, "log_weight")
                    .setShortLabel(tr("Weigh-in")).setLongLabel(tr("Log body weight"))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_scale))
                    .setIntent(intent(LOG_WEIGHT)).setRank(2).build()
            )
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
