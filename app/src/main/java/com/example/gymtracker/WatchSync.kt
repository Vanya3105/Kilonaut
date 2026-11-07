package com.example.gymtracker

import android.content.Context
import android.util.Log
import com.example.gymtracker.watchlink.WatchAction
import com.example.gymtracker.watchlink.WatchLink
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

// ============================================================================
// WATCH (Wear OS remote)
// ============================================================================
// The phone keeps the only copy of the workout. The watch gets a ready-to-show snapshot after
// every change (same moment widgets redraw) and sends button presses back as messages.

object WatchSync {
    private const val TAG = "WatchSync"
    @Volatile private var lastSent: String? = null

    /** Sends the current state if it changed. Cheap without a watch: the Data Layer call just fails. */
    fun publish(context: Context) {
        val json = watchStateOf(context).toJson()
        if (json == lastSent) return
        lastSent = json
        try {
            val request = PutDataRequest.create(WatchLink.STATE_PATH).setData(json.toByteArray()).setUrgent()
            Wearable.getDataClient(context.applicationContext).putDataItem(request)
                .addOnFailureListener { lastSent = null }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't reach the Wearable API", e)
            lastSent = null
        }
    }

    /** The watch app opened: send the state even if it didn't change. */
    fun resend(context: Context) {
        lastSent = null
        publish(context)
    }

    fun apply(context: Context, action: WatchAction) {
        val restEnd = LocalStorage.getRestEndTimeMillis(context)
        when (action) {
            WatchAction.PRIMARY_MINUS -> GymWidgetProvider.applySetAction(context, GymWidgetProvider.ACTION_WEIGHT_MINUS)
            WatchAction.PRIMARY_PLUS -> GymWidgetProvider.applySetAction(context, GymWidgetProvider.ACTION_WEIGHT_PLUS)
            WatchAction.SECONDARY_MINUS -> GymWidgetProvider.applySetAction(context, GymWidgetProvider.ACTION_REPS_MINUS)
            WatchAction.SECONDARY_PLUS -> GymWidgetProvider.applySetAction(context, GymWidgetProvider.ACTION_REPS_PLUS)
            WatchAction.COMPLETE_SET -> GymWidgetProvider.applySetAction(context, GymWidgetProvider.ACTION_COMPLETE_SET)
            WatchAction.ADD_REST -> if (restEnd > System.currentTimeMillis()) {
                LocalStorage.setRestEndTimeMillis(context, restEnd + 15_000L, fromWidget = true)
            }
            WatchAction.SKIP_REST -> if (restEnd > 0L) LocalStorage.setRestEndTimeMillis(context, 0L, fromWidget = true)
            WatchAction.REMOVE_REST -> if (restEnd > System.currentTimeMillis()) {
                // Less than a second left would flash 0:00: end the rest instead.
                val shorter = restEnd - 15_000L
                LocalStorage.setRestEndTimeMillis(context, if (shorter > System.currentTimeMillis() + 1_000L) shorter else 0L, fromWidget = true)
            }
            WatchAction.UNDO_SET -> undoLastSet(context)
            WatchAction.FINISH_WORKOUT -> finishWorkout(context)
            WatchAction.ADD_SET -> addSet(context)
        }
    }

    /** Starts [splitTitle] like Start on the Today screen. Ignored while a workout runs or for a day not in the program. */
    fun startWorkout(context: Context, splitTitle: String) {
        if (LocalStorage.loadActiveSession(context) != null) return
        if (programFor(LocalStorage.loadUserProfile(context).splitProgram).splits.none { it.title == splitTitle }) return
        val history = LocalStorage.loadHistory(context)
        val settings = LocalStorage.loadSettings(context)
        val entries = LocalStorage.loadTemplateForSplit(context, splitTitle).map { (name, muscle) ->
            newExerciseEntry(context, splitTitle, name, muscle, history, settings)
        }
        LocalStorage.saveActiveSession(context, splitTitle, entries, fromWidget = true)
    }

    /**
     * Un-ticks the set ticked last: the last finished set before the next one to do, so in the
     * current exercise or, if it has none yet, the exercise before. Also ends the rest it started.
     */
    internal fun undoLastSet(context: Context) {
        val (splitTitle, exercises) = LocalStorage.loadActiveSession(context) ?: return
        val current = exercises.indexOfFirst { ex -> !ex.isSkipped && ex.sets.any { !it.isCompleted } }
        val searchUpTo = if (current == -1) exercises.lastIndex else current
        val exIdx = (searchUpTo downTo 0).firstOrNull { i -> !exercises[i].isSkipped && exercises[i].sets.any { it.isCompleted } } ?: return
        val ex = exercises[exIdx]
        val setIdx = ex.sets.indexOfLast { it.isCompleted }
        val sets = ex.sets.toMutableList().also { it[setIdx] = it[setIdx].copy(isCompleted = false) }
        LocalStorage.saveActiveSession(context, splitTitle, exercises.toMutableList().also { it[exIdx] = ex.copy(sets = sets) }, fromWidget = true)
        LocalStorage.setRestEndTimeMillis(context, 0L, fromWidget = true)
    }

    /**
     * Where "Add set" goes: the exercise of the set ticked last (one more of what was just done,
     * also while resting before the next exercise), else the current one, else the last.
     */
    internal fun addSetTarget(exercises: List<ExerciseEntry>): Int {
        val current = exercises.indexOfFirst { ex -> !ex.isSkipped && ex.sets.any { !it.isCompleted } }
        val searchUpTo = if (current == -1) exercises.lastIndex else current
        return (searchUpTo downTo 0).firstOrNull { i -> !exercises[i].isSkipped && exercises[i].sets.any { it.isCompleted } }
            ?: current.takeIf { it >= 0 }
            ?: exercises.indexOfLast { !it.isSkipped && it.sets.isNotEmpty() }
    }

    /** A copy of the target's last set, not done and as a work set; it becomes the next set. */
    internal fun addSet(context: Context) {
        val (splitTitle, exercises) = LocalStorage.loadActiveSession(context) ?: return
        val exIdx = addSetTarget(exercises).takeIf { it >= 0 } ?: return
        val ex = exercises[exIdx]
        val last = ex.sets.lastOrNull() ?: return
        val added = last.copy(setNumber = ex.sets.size + 1, isCompleted = false, isWarmup = false, intensityType = "NORMAL")
        LocalStorage.saveActiveSession(context, splitTitle, exercises.toMutableList().also { it[exIdx] = ex.copy(sets = ex.sets + added) }, fromWidget = true)
    }

    /** Saves the workout like Finish → Save on the phone (no photo), timed from its start to now. */
    internal fun finishWorkout(context: Context) {
        finishActiveWorkout(context, System.currentTimeMillis(), fromWidget = true)
    }
}

/** Receives the watch's messages, also while the app is closed. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val context = applicationContext
        if (event.path == WatchLink.REQUEST_STATE) {
            WatchSync.resend(context)
            return
        }
        if (event.path == WatchLink.START_WORKOUT) {
            WatchSync.startWorkout(context, String(event.data ?: ByteArray(0), Charsets.UTF_8))
        } else {
            val action = WatchLink.actionFromPath(event.path) ?: return
            WatchSync.apply(context, action)
        }
        LocalStorage.flush(context)
        Wearable.getMessageClient(context).sendMessage(event.sourceNodeId, WatchLink.ACK, null)
    }
}
