package com.example.gymtracker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class RemindersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Before
    fun setUp() {
        LocalStorage.resetForTests()
        context.deleteDatabase("gymtracker.db")
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() = LocalStorage.resetForTests()

    @Test
    fun fires_withTheNextWorkout_butNotWhileOneIsRunning() {
        LocalStorage.saveActiveSession(context, "Push", listOf(ExerciseEntry("Bench Press", sets = listOf(SetEntry(1, "80", "8")))))
        WorkoutReminders.fire(context)
        assertEquals("a workout is going", 0, notifications.size)

        LocalStorage.saveActiveSession(context, null, emptyList())
        WorkoutReminders.fire(context)
        assertEquals(1, notifications.size)
        val title = notifications[0].extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty()
        assertEquals(true, title.startsWith("Time for") || title == "Time to train")
        assertEquals("Start", notifications[0].actions[0].title.toString())
    }

    private fun at(day: Int, hour: Int, minute: Int = 0) = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun next_isTodayIfStillAhead_elseTheNextChosenDay() {
        // Nov 2, 2026 is a Monday.
        val mwf = setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY)
        assertEquals(at(2, 18), WorkoutReminders.nextAt(mwf, 18 * 60, at(2, 9)))
        assertEquals("past today's time: Wednesday", at(4, 18), WorkoutReminders.nextAt(mwf, 18 * 60, at(2, 18, 30)))
        assertEquals("Friday evening: next Monday", at(9, 7, 30), WorkoutReminders.nextAt(mwf, 7 * 60 + 30, at(6, 20)))
        assertNull("no days: off", WorkoutReminders.nextAt(emptySet(), 18 * 60, at(2, 9)))
    }

    @Test
    fun defaultDays_spreadOverTheWeek() {
        assertEquals(setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY), defaultReminderDays(3))
        assertEquals(5, defaultReminderDays(5).size)
    }
}
