package com.example.gymtracker.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class WatchActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START = "start"

        /** On screen: the app buzzes at rest's end itself, so [PhoneDataService] doesn't. */
        @Volatile
        var visible = false
            private set
    }

    private lateinit var link: PhoneLink
    private lateinit var heartRate: HeartRate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = PhoneLink(this)
        heartRate = HeartRate(this)
        setContent {
            AskForNotifications(link)
            AskForHeartRate(link.state?.active == true, heartRate)
            WatchApp(link, heartRate)
        }
        // Not again when the activity is recreated (e.g. after a configuration change).
        if (savedInstanceState == null) startFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startFrom(intent)
    }

    /** The tile's Start chip names the day to start; the phone ignores it if a workout is already running. */
    private fun startFrom(intent: Intent?) {
        val day = intent?.getStringExtra(EXTRA_START) ?: return
        intent.removeExtra(EXTRA_START)
        link.startWorkout(day)
    }

    // Listening only while visible: the Data Layer keeps the latest state for the next start,
    // and PhoneDataService handles changes while the app is closed.
    override fun onStart() {
        super.onStart()
        visible = true
        link.start()
        heartRate.setVisible(true)
    }

    override fun onStop() {
        visible = false
        heartRate.setVisible(false)
        link.stop()
        super.onStop()
    }
}

/**
 * Needed for the workout on the watch face (an ongoing notification). Asked from Compose like the
 * phone app does: Activity.registerForActivityResult trips a lint check about the old Fragment
 * library that Play services pulls in, although this activity has no fragments.
 */
@Composable
private fun AskForNotifications(link: PhoneLink) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) link.state?.let { WorkoutOngoing.update(context, it) }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Heart rate is asked for once, when a workout is first open on the watch, where it's clear what
 * it's for; a no isn't asked again (the phone's history still gets it through Health Connect).
 */
@Composable
private fun AskForHeartRate(workout: Boolean, heartRate: HeartRate) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { heartRate.permissionChanged() }
    LaunchedEffect(workout) {
        if (!workout || HeartRate.granted(context)) return@LaunchedEffect
        val prefs = context.getSharedPreferences("watch", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_heart_rate", false)) return@LaunchedEffect
        prefs.edit().putBoolean("asked_heart_rate", true).apply()
        ask.launch(HeartRate.PERMISSION)
    }
}
