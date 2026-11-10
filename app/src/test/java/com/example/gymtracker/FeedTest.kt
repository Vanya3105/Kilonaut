package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a workout post carries, and post times. */
@RunWith(RobolectricTestRunner::class)
class FeedTest {
    private val day = 24 * 60 * 60 * 1000L

    private fun workout(id: Long, kg: String, note: String = "") = CompletedWorkout(
        id, "Push", "", "", 3720, 81.3f, "/photos/private.jpg", emptyList(),
        listOf(
            ExerciseEntry("Bench Press", "Chest", sets = listOf(
                SetEntry(1, "20", "10", isWarmup = true, isCompleted = true),
                SetEntry(2, kg, "8", isCompleted = true), SetEntry(3, kg, "7", isCompleted = true)
            )),
            ExerciseEntry("Plank", "Core", type = ExerciseType.DURATION, sets = listOf(SetEntry(1, "", "", isCompleted = true, seconds = "75"))),
            ExerciseEntry("Cable Fly", "Chest", sets = listOf(SetEntry(1, "15", "12", isCompleted = false)))
        )
    )

    @Test
    fun workoutPostRoundTrips() {
        val before = listOf(workout(1_000_000L, "80"))
        val today = workout(1_000_000L + 7 * day, "85")
        val json = workoutPostJson(today, before).toString()
        assertFalse("body weight stays private", json.contains("81.3"))
        assertFalse("photo path stays private", json.contains("private.jpg"))

        val w = parseWorkoutPost(json)!!
        assertEquals("Push", w.title)
        assertEquals(3720L, w.seconds)
        assertEquals(1, w.bests)
        // Only exercises with done sets; warm-ups don't count.
        assertEquals(listOf("Bench Press", "Plank"), w.exercises.map { it.name })
        val bench = w.exercises.first()
        assertEquals(2, bench.sets)
        assertTrue(bench.isBest)
        assertTrue(formatSet(bench.best!!, bench.type), formatSet(bench.best!!, bench.type).contains("85"))
        assertEquals(ExerciseType.DURATION, w.exercises[1].type)
    }

    @Test
    fun postAges() {
        val now = System.currentTimeMillis()
        assertEquals("now", postAge(now - 20_000, now))
        assertEquals("5m", postAge(now - 5 * 60_000, now))
    }

    @Test
    fun repliesFollowTheirComment() {
        val c = { id: String, at: Long, to: String? -> Feed.Comment(id, "u", id, at, to) }
        val list = listOf(c("a", 1, null), c("b", 2, null), c("a1", 3, "a"), c("b1", 4, "b"), c("a2", 5, "a"), c("orphan", 6, "gone"))
        // Each comment, then its replies; a reply whose comment was deleted stands alone, in time order.
        assertEquals(listOf("a", "a1", "a2", "b", "b1", "orphan"), threaded(list).map { it.id })
    }

    @Test
    fun longThreadsFoldUntilOpened() {
        val c = { id: String, at: Long, to: String? -> Feed.Comment(id, "u", id, at, to) }
        val list = listOf(c("a", 1, null), c("a1", 2, "a"), c("a2", 3, "a"), c("a3", 4, "a"), c("b", 5, null), c("b1", 6, "b"), c("b2", 7, "b"))
        fun show(lines: List<CommentLine>) = lines.map { if (it is CommentLine.One) it.comment.id + (if (it.reply) "^" else "") else "+" + (it as CommentLine.More).hidden }
        // Three replies: the first, then "2 more"; two replies show as they are.
        assertEquals(listOf("a", "a1^", "+2", "b", "b1^", "b2^"), show(commentLines(list, emptySet())))
        assertEquals(listOf("a", "a1^", "a2^", "a3^", "b", "b1^", "b2^"), show(commentLines(list, setOf("a"))))
    }

    @Test
    fun mentionsAreUsernamesNotEmails() {
        val found = MENTION.findAll("@maya.lifts nice, mail me at dan@mail.com or @Dan_L").map { it.groupValues[1] }.toList()
        assertEquals(listOf("maya.lifts", "Dan_L"), found)
    }

    @Test
    fun sharedPostReadsWell() {
        val w = parseWorkoutPost(workoutPostJson(workout(1_000_000L + 7 * day, "85"), listOf(workout(1_000_000L, "80"))).toString())!!
        val post = Feed.Post("p", Friends.Person("u", "Maya Cohen", "maya"), 0L, "Finally", null, w, emptyList(), 0)
        val text = postShareText(post, "https://example.com/get")
        val lines = text.lines()
        assertEquals("Maya Cohen trained Push on Kilonaut", lines[0])
        assertEquals("“Finally”", lines[1])
        assertTrue(lines.any { it.startsWith("• Bench Press") && it.endsWith("🏆") })
        assertEquals("https://example.com/get", lines.last())
        assertFalse("body weight stays private", text.contains("81.3"))
    }

    @Test
    fun aFriendsWorkoutToDoYourself() {
        val w = parseWorkoutPost(workoutPostJson(workout(1_000_000L, "80"), emptyList()).toString())!!
        val mine = postAsWorkout(w, from = "Maya")
        assertEquals("Push from Maya", mine.splitTitle)
        assertEquals(listOf("Bench Press", "Plank"), mine.exercises.map { it.name })
        // As many work sets, no numbers of theirs (yours come from your own history when it starts).
        assertEquals(listOf(2, 1), mine.exercises.map { it.sets.size })
        assertTrue(mine.exercises.flatMap { it.sets }.all { it.weight.isEmpty() && !it.isCompleted })
        assertEquals(ExerciseType.DURATION, mine.exercises[1].type)
        assertEquals("Push", postAsWorkout(w).splitTitle)
    }

    @Test
    fun compactCounts() {
        assertEquals("999", compactCount(999))
        assertEquals("1K", compactCount(1000))
        assertEquals("1.2K", compactCount(1234))
        assertEquals("12K", compactCount(12_400))
    }
}
