package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncTest {

    private fun h(s: String) = syncHash(s)

    // --- Pull ---

    @Test
    fun pull_takesWhatIsNew() {
        val (apply, synced) = pullPlan(emptyMap(), listOf(RemoteDoc("1", "a", 5)), emptyMap())
        assertEquals(mapOf("1" to "a"), apply)
        assertEquals(h("a"), synced["1"])
    }

    @Test
    fun pull_skipsWhatIsAlreadyHere() {
        val (apply, synced) = pullPlan(mapOf("1" to "a"), listOf(RemoteDoc("1", "a", 5)), emptyMap())
        assertTrue(apply.isEmpty())
        assertEquals(h("a"), synced["1"])
    }

    @Test
    fun pull_firstSync_accountWinsOverTheSameRecordHere() {
        val (apply, _) = pullPlan(mapOf("1" to "phone"), listOf(RemoteDoc("1", "account", 5)), emptyMap())
        assertEquals(mapOf("1" to "account"), apply)
    }

    @Test
    fun pull_editHereSinceLastSync_keepsThisPhonesVersion() {
        val (apply, synced) = pullPlan(mapOf("1" to "edited"), listOf(RemoteDoc("1", "other", 5)), mapOf("1" to h("old")))
        assertTrue(apply.isEmpty())
        assertEquals(h("old"), synced["1"]) // still differs from here, so the upload sends it
    }

    @Test
    fun pull_tombstoneDeletesHere() {
        val (apply, synced) = pullPlan(mapOf("1" to "a"), listOf(RemoteDoc("1", null, 5)), mapOf("1" to h("a")))
        assertEquals(mapOf<String, String?>("1" to null), apply)
        assertNull(synced["1"])
    }

    // --- Push ---

    @Test
    fun push_sendsChangesAndDeletes() {
        val (upload, synced) = pushPlan(mapOf("1" to "a", "2" to "b2"), mapOf("2" to h("b"), "3" to h("c"), "1" to h("a")))
        assertEquals(mapOf("2" to "b2", "3" to null), upload)
        assertEquals(mapOf("1" to h("a"), "2" to h("b2")), synced)
    }

    // --- Two phones through one account ---

    /** A tiny account: id → payload (null = tombstone), with a clock for "changed since". */
    private class Account {
        val docs = HashMap<String, Pair<String?, Long>>()
        var clock = 0L
        fun since(t: Long) = docs.filter { it.value.second >= t }.map { RemoteDoc(it.key, it.value.first, it.value.second) }
    }

    private class Phone(val local: MutableMap<String, String>) {
        var synced: Map<String, String> = emptyMap()
        var lastPull = 0L

        fun sync(account: Account) {
            val remote = account.since(lastPull)
            val (apply, afterPull) = pullPlan(local, remote, synced)
            apply.forEach { (id, p) -> if (p == null) local.remove(id) else local[id] = p }
            val (upload, afterPush) = pushPlan(local, afterPull)
            account.clock++
            upload.forEach { (id, p) -> account.docs[id] = p to account.clock }
            synced = afterPush
            lastPull = remote.maxOfOrNull { it.updatedAtMillis } ?: lastPull
        }
    }

    @Test
    fun twoPhones_endUpWithEverything() {
        val account = Account()
        val old = Phone(mutableMapOf("w1" to "bench", "w2" to "squat"))
        val new = Phone(mutableMapOf("w3" to "row"))
        old.sync(account)
        new.sync(account)
        old.sync(account)
        assertEquals(mapOf("w1" to "bench", "w2" to "squat", "w3" to "row"), old.local)
        assertEquals(old.local, new.local)

        // A delete and an edit on one phone reach the other.
        new.local.remove("w1")
        new.local["w2"] = "squat, edited"
        new.sync(account)
        old.sync(account)
        assertEquals(mapOf("w2" to "squat, edited", "w3" to "row"), old.local)

        // Nothing changed: nothing more is sent.
        val before = account.docs.toMap()
        old.sync(account)
        new.sync(account)
        assertEquals(before.mapValues { it.value.first }, account.docs.mapValues { it.value.first })
    }

    // --- Payloads ---

    @Test
    fun workoutPayload_isStableAndLeavesPhoneOnlyThingsOut() {
        val w = CompletedWorkout(
            id = 1_700_000_000_000, splitTitle = "Push", dateKey = "2026-11-08", displayDate = "Nov 8", durationSeconds = 3600,
            bodyWeightKg = 80f, photoPath = "/data/photo.jpg", skippedExercises = listOf("Cable Fly"),
            exercises = listOf(
                ExerciseEntry(
                    "Bench Press", "Chest", checkIn = true, planNote = "note", target = ExerciseTarget(3, 6, 10),
                    sets = listOf(SetEntry(1, "60", "8", isCompleted = true, rir = 2), SetEntry(2, "62.5", "7", isCompleted = true))
                )
            ),
            totalVolumeKg = 917, prsHit = 1
        )
        val payload = workoutPayload(w, "felt strong")
        val (back, note) = parseWorkoutPayload(payload)!!
        assertEquals("felt strong", note)
        assertNull(back.photoPath)
        assertEquals(payload, workoutPayload(back, note))
        assertEquals(2, back.exercises.single().sets.first().rir)
    }

    @Test
    fun weighInPayload_roundTrips() {
        val w = WeightLogEntry(5L, "2026-11-08", "Nov 8", 81.65f)
        assertEquals(w, parseWeighInPayload(weighInPayload(w)))
    }
}
