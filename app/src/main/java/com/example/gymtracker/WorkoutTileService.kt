package com.example.gymtracker

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A Quick Settings tile: the next workout from the pulled-down panel, or back into the one that's
 * running (the tile is lit then). Opens the app through the same action as the icon shortcut.
 */
class WorkoutTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val running = LocalStorage.loadActiveSession(applicationContext)?.first
        tile.state = if (running != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running != null) tr("Resume") else tr("Start workout")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = running?.let(::shown) ?: nextDay(applicationContext)?.let(::shown)
        }
        tile.updateTile()
    }

    private fun nextDay(context: Context): String? {
        val history = LocalStorage.loadHistory(context)
        return computeWeekSummary(history, 0, LocalStorage.loadUserProfile(context).splitProgram).recommendedSplit.takeIf { it.isNotBlank() }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).setAction(AppShortcuts.START_NEXT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7400, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** A workout started or ended: the tile shows it next time the panel is pulled down. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, WorkoutTileService::class.java)) }
        }
    }
}
