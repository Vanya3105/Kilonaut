package com.example.gymtracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** The feed with made-up posts, drawn to app/build/screenshots/feed-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()

    /** A stand-in "gym photo": a warm gradient with a dark floor, as a stored post photo. */
    private fun photo(): String {
        val bmp = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 1080f, 1350f, 0xFF8A6A55.toInt(), 0xFF2B2522.toInt(), Shader.TileMode.CLAMP) })
        c.drawRect(0f, 1000f, 1080f, 1350f, Paint().apply { color = 0xFF1A1716.toInt() })
        c.drawCircle(540f, 520f, 230f, Paint().apply { color = 0x33FFFFFF })
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private val maya = Friends.Person("maya-uid", "Maya Cohen", "maya.lifts")
    private val dan = Friends.Person("dan-uid", "Dan Levi", "danl")
    private val noa = Friends.Person("noa-uid", "Noa", "noa_runs")

    private val workout = WorkoutPost(
        "Push A", now - 3_600_000, 3720, 18, 6240, 2,
        listOf(
            PostExercise("Barbell Bench Press", ExerciseType.WEIGHT_REPS, 4, SetEntry(1, "85", "6", isCompleted = true), true),
            PostExercise("Overhead Press", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "52.5", "8", isCompleted = true), false),
            PostExercise("Cable Fly", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "15", "12", isCompleted = true), false),
            PostExercise("Triceps Pushdown", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "30", "12", isCompleted = true), true),
            PostExercise("Plank", ExerciseType.DURATION, 2, SetEntry(1, "", "", isCompleted = true, seconds = "75"), false),
            PostExercise("Lateral Raise", ExerciseType.WEIGHT_REPS, 3, SetEntry(1, "10", "15", isCompleted = true), false)
        )
    )

    private fun posts() = listOf(
        Feed.Post("p1", maya, now - 2 * 3_600_000, "New bench best. Finally 85 for six 🔥", photo(), workout, listOf("dan-uid", "noa-uid", "x"), 4),
        Feed.Post("p2", dan, now - 20 * 60_000, "", null, workout.copy(title = "Legs A", bests = 1, kg = 9120), listOf("maya-uid"), 1),
        Feed.Post("p3", noa, now - 26 * 3_600_000, "Rest day. Walked 12k steps and slept 9 hours. Back at it tomorrow.", null, null, emptyList(), 0)
    )

    private fun draw(name: String, page: Int, offset: Float = 0f) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
        val list = posts()
        compose.setContent {
            GymTheme(LightPalette) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    val pager = rememberPagerState(initialPage = page) { list.size + 1 }
                    if (offset != 0f) LaunchedEffect(Unit) { pager.scrollToPage(page, offset) }
                    FeedPager(list, listOf(maya, dan, noa).associateBy { it.uid }, onFriends = {}, onNewPost = {}, pager = pager)
                    FeedTopBar(onPosts = true, canPost = true, onFriends = {}, onNewPost = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        save(name)
    }

    private fun save(name: String) {
        val bitmap = compose.activity.window.decorView.drawToBitmap()
        File("build/screenshots/feed-$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun photoWithWorkout() = draw("1-photo", 0)
    @Test fun workoutOnly() = draw("2-workout", 1)
    @Test fun textOnly() = draw("3-text", 2)
    @Test fun midSwipe() = draw("4-swipe", 0, 0.35f)
    @Test fun caughtUp() = draw("5-end", 3)

    private fun drawCards(name: String, palette: GymPalette) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
        val list = posts()
        compose.setContent {
            GymTheme(palette) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(palette.bgPrimary)) {
                    FeedTopBar(onPosts = false, canPost = true, onFriends = {}, onNewPost = {}, onMe = {}, onFindFriends = {})
                    FeedList(list, listOf(maya, dan, noa).associateBy { it.uid }, onFriends = {}, onNewPost = {}, onOpenPerson = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        save(name)
    }

    /** Today's friends row with the sample feed's people (Maya trained today: accent ring). */
    @Test
    fun todayFriends() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
        Feed.showDemo()
        compose.setContent {
            GymTheme(LightPalette) {
                Box(Modifier.fillMaxSize().background(LightPalette.bgPrimary)) {
                    TodayFriends(Modifier.padding(20.dp))
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        save("9-today-friends")
    }

    /** A History card, Hevy style: stats strip, exercises with pictures and best sets, muscles line. */
    private fun drawHistoryCard(name: String, palette: GymPalette) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
        fun s(w: String, r: String) = SetEntry(setNumber = 1, weight = w, reps = r, isCompleted = true)
        val w = CompletedWorkout(
            id = 1_762_900_000_000L, splitTitle = "Push A", dateKey = "2026-11-11", displayDate = "2026-11-11", durationSeconds = 3120,
            bodyWeightKg = 80f, photoPath = null, skippedExercises = emptyList(), exercises = listOf(
                ExerciseEntry("Barbell Bench Press", "Chest", sets = listOf(s("80", "8"), s("80", "8"), s("80", "7"))),
                ExerciseEntry("Incline Dumbbell Press", "Chest", sets = listOf(s("28", "10"), s("28", "10"), s("28", "9"))),
                ExerciseEntry("Overhead Press", "Shoulders", sets = listOf(s("45", "8"), s("45", "8"))),
                ExerciseEntry("Triceps Pushdown", "Triceps", sets = listOf(s("30", "12"), s("30", "12"))),
                ExerciseEntry("Lateral Raise", "Shoulders", sets = listOf(s("10", "15"), s("10", "15")))
            )
        ).let { it.copy(totalVolumeKg = it.exercises.sumOf { e -> e.sets.sumOf { x -> (x.weightKg * x.repCount).toDouble() } }.toInt()) }
        compose.setContent {
            GymTheme(palette) {
                Box(Modifier.fillMaxSize().background(palette.bgPrimary).padding(16.dp)) {
                    WorkoutHistoryCard(w, listOf(w), onOpen = {}, onDelete = {}, onViewPhoto = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        save(name)
    }

    @Test fun historyCard() = drawHistoryCard("10-history-card", LightPalette)
    @Test fun historyCardDark() = drawHistoryCard("10-history-card-dark", DarkPalette)

    @Test fun cards() = drawCards("8-cards", LightPalette)
    @Test fun cardsDark() = drawCards("8-cards-dark", DarkPalette)

    /** The sample feed's first post with its comments open: threads, likes on comments, quick reactions. */
    @Test
    fun comments() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
        Feed.showDemo()
        val list = Feed.posts!!
        compose.setContent {
            GymTheme(LightPalette) {
                FeedPager(list, Feed.people, onFriends = {}, onNewPost = {}, openComments = true)
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        Thread.sleep(500)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // The sheet is its own window: draw that window.
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val bitmap = dialog.window!!.decorView.drawToBitmap()
        File("build/screenshots/feed-7-comments.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Feed.signedOut()
    }

    @Test
    fun empty() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xxhdpi")
        compose.setContent { GymTheme(LightPalette) { FeedScreen(history = emptyList()) } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        save("6-signed-out")
    }
}
