package com.example.gymtracker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar
import java.util.Date

/**
 * A nudge on the days you train, at the time you pick: "Time for Push", with Start. Not when
 * you've already trained that day or a workout is running.
 */
object WorkoutReminders {
    private const val CHANNEL_ID = "workout_reminders"
    private const val NOTIFICATION_ID = 7301
    private const val REQUEST_ALARM = 7300
    private const val ACTION_FIRE = "com.vanyaevtushenko.gymtracker.REMINDER"

    /** The next reminder after [nowMillis]: [minutes] past midnight on one of [days] (Calendar.SUNDAY…). */
    internal fun nextAt(days: Set<Int>, minutes: Int, nowMillis: Long): Long? {
        if (days.isEmpty()) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (cal.timeInMillis > nowMillis && cal.get(Calendar.DAY_OF_WEEK) in days) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_ALARM,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Sets the next reminder from the saved settings, or clears it when reminders are off. */
    fun schedule(context: Context) {
        val settings = LocalStorage.loadSettings(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        alarms.cancel(pending)
        val at = nextAt(settings.reminderDays, settings.reminderMinutes, System.currentTimeMillis()) ?: return
        // On time when exact alarms are allowed (the rest timer asks for them); otherwise Android may
        // run it up to an hour late, still fine for a nudge.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    internal fun fire(context: Context) {
        schedule(context)
        if (LocalStorage.loadActiveSession(context) != null) return
        val history = LocalStorage.loadHistory(context)
        if (history.any { it.dateKey == DateUtils.formatDayKey(Date()) }) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val next = computeWeekSummary(history, 0, LocalStorage.loadUserProfile(context).splitProgram).recommendedSplit
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("Workout reminders"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = tr("On the days you picked, at the time you picked")
                }
            )
        }
        fun open(action: String?, request: Int) = PendingIntent.getActivity(
            context, request,
            Intent(context, MainActivity::class.java).setAction(action ?: Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(if (next.isBlank()) tr("Time to train") else tr("Time for {0}", shown(next)))
            .setContentText(tr("Your workout is ready when you are."))
            .setContentIntent(open(null, 7302))
            .addAction(0, tr("Start"), open(AppShortcuts.START_NEXT, 7303))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** Started (or saved) a workout: the reminder has done its job. */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    internal const val FIRE = ACTION_FIRE
}

/** The reminder going off, and the events that clear alarms (restart, update, clock change). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (intent?.action == WorkoutReminders.FIRE) WorkoutReminders.fire(app) else WorkoutReminders.schedule(app)
    }
}
