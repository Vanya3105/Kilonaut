package com.example.gymtracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class DataImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LocalStorage.resetForTests()
        context.deleteDatabase("gymtracker.db")
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() = LocalStorage.resetForTests()

    private fun at(text: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(text)!!.time
    private fun workout(time: String, vararg names: String) = CompletedWorkout(
        id = at(time), splitTitle = "Push", dateKey = time.take(10), displayDate = time, durationSeconds = 3600, bodyWeightKg = 80f,
        photoPath = null, skippedExercises = emptyList(),
        exercises = names.map { ExerciseEntry(it, "Chest", sets = listOf(SetEntry(1, "60", "8", isCompleted = true))) }
    )

    // --- Combining backups ---

    @Test
    fun twoBackups_giveEverythingFromBoth() {
        // The owner's case: a backup from two weeks ago with 2 exercises, one from a week ago with 1.
        LocalStorage.saveCompletedWorkout(context, workout("2026-10-15 18:00", "Bench Press", "Overhead Press"))
        val older = LocalStorage.exportJsonBackupString(context)
        LocalStorage.resetForTests(); context.deleteDatabase("gymtracker.db"); context.filesDir.listFiles()?.forEach { it.delete() }
        LocalStorage.saveCompletedWorkout(context, workout("2026-10-22 18:00", "Lateral Raise"))

        val result = LocalStorage.addBackupJsonString(context, older)!!
        assertEquals(1, result.addedWorkouts)
        val names = LocalStorage.loadHistory(context).flatMap { w -> w.exercises.map { it.name } }.toSet()
        assertEquals(setOf("Bench Press", "Overhead Press", "Lateral Raise"), names)

        // The same file again adds nothing.
        val again = LocalStorage.addBackupJsonString(context, older)!!
        assertEquals(0, again.addedWorkouts)
        assertEquals(1, again.alreadyHere)
        assertEquals(2, LocalStorage.loadHistory(context).size)
    }

    @Test
    fun combine_keepsWhatsHereAndOnlyFillsGaps() {
        val here = StoreData(
            history = listOf(workout("2026-10-22 18:00", "Bench Press")),
            notes = mapOf("bench press" to "mine"),
            restDefaults = mapOf("bench press" to 120)
        )
        val edited = workout("2026-10-22 18:00", "Bench Press", "Dips") // same workout, different copy
        val file = StoreData(
            history = listOf(edited, workout("2026-10-01 18:00", "Squat")),
            notes = mapOf("bench press" to "theirs", "squat" to "belt"),
            restDefaults = mapOf("squat" to 180)
        )
        val (combined, result) = combineData(here, file)
        assertEquals(1, result.addedWorkouts)
        assertEquals(1, result.alreadyHere)
        assertEquals(listOf("Bench Press"), combined.history.first { it.id == at("2026-10-22 18:00") }.exercises.map { it.name })
        assertEquals("mine", combined.notes["bench press"])
        assertEquals("belt", combined.notes["squat"])
        assertEquals(mapOf("bench press" to 120, "squat" to 180), combined.restDefaults)
    }

    @Test
    fun badFile_isRefused() {
        assertNull(LocalStorage.addBackupJsonString(context, "{\"something\": 1}"))
        assertNull(LocalStorage.addBackupJsonString(context, "not json"))
    }

    // --- Strong ---

    private val strong = """
        Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
        2026-10-20 18:51:52,Evening Workout,1h 8m,Squat (Barbell),W,95.0,8,0,0,,,
        2026-10-20 18:51:52,Evening Workout,1h 8m,Squat (Barbell),1,225.0,5,0,0,,,
        2026-10-20 18:51:52,Evening Workout,1h 8m,Squat (Barbell),2,225.0,5,0,0,"Felt good, deep",,
        2026-10-20 18:51:52,Evening Workout,1h 8m,Squat (Barbell),Rest Timer,0,0,0,90,,,
        2026-10-20 18:51:52,Evening Workout,1h 8m,Bench Press (Barbell),1,185.0,6,0,0,,,
        2026-10-20 18:51:52,Evening Workout,1h 8m,Bench Press (Barbell),D,135.0,10,0,0,,,
        2026-10-20 18:51:52,Evening Workout,1h 8m,Pull Up,1,0,10,0,0,,,
        2026-10-20 18:51:52,Evening Workout,1h 8m,Plank,1,0,0,0,60,,,
        2026-10-22 07:00:00,Morning Run,45m,Running,1,0,0,5.0,1800,,,
    """.trimIndent()

    @Test
    fun strong_inPounds() {
        val h = parseWorkoutExport(strong, WeightUnit.LB, knownNames = listOf("Back Squat"), bodyWeightKg = 80f)!!
        assertEquals(ImportSource.STRONG, h.source)
        assertEquals(false, h.unitInFile)
        assertEquals(2, h.workouts.size)
        val lift = h.workouts.first { it.splitTitle == "Evening Workout" }
        assertEquals(68 * 60L, lift.durationSeconds)
        assertEquals(listOf("Back Squat", "Barbell Bench Press", "Pull-Up", "Plank"), lift.exercises.map { it.name })

        val squat = lift.exercises[0]
        assertEquals(3, squat.sets.size) // the rest timer row isn't a set
        assertTrue(squat.sets[0].isWarmupSet)
        assertEquals(102.06f, squat.sets[1].weightKg, 0.01f) // 225 lb
        assertEquals("DROP", lift.exercises[1].sets[1].intensityType)
        assertEquals(ExerciseType.BODYWEIGHT, lift.exercises[2].type)
        assertEquals(ExerciseType.DURATION, lift.exercises[3].type)
        assertEquals(60, lift.exercises[3].sets.single().secondCount)

        val run = h.workouts.first { it.splitTitle == "Morning Run" }.exercises.single()
        assertEquals(ExerciseType.DISTANCE_TIME, run.type)
        assertEquals(8046.7f, run.sets.single().distanceMeters, 1f) // 5 miles
        assertEquals(mapOf("Squat (Barbell)" to "Back Squat", "Bench Press (Barbell)" to "Barbell Bench Press", "Pull Up" to "Pull-Up"), h.renamed)
        assertTrue(lift.totalVolumeKg > 0)
    }

    @Test
    fun strong_oldSemicolonExportWithUnits() {
        val old = """
            Date;Workout Name;Exercise Name;Set Order;Weight;Weight Unit;Reps;Distance;Distance Unit;Seconds;Notes;Workout Notes;Duration
            2019-03-02 10:00:00;Push;Bench Press (Barbell);1;80;kg;5;;;0;;;3600
        """.trimIndent()
        val h = parseWorkoutExport(old, WeightUnit.LB, emptyList(), 80f)!!
        assertTrue(h.unitInFile)
        assertEquals(80f, h.workouts.single().exercises.single().sets.single().weightKg, 0.001f)
        assertEquals(3600L, h.workouts.single().durationSeconds)
    }

    // --- Hevy ---

    @Test
    fun hevy_kgAndItsDates() {
        val hevy = """
            "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
            "Push Day","15 Jul 2026, 09:52","15 Jul 2026, 10:58","","Bench Press (Barbell)","","","0","warmup","40","10","","",""
            "Push Day","15 Jul 2026, 09:52","15 Jul 2026, 10:58","","Bench Press (Barbell)","","","1","normal","80","8","","","8"
            "Push Day","15 Jul 2026, 09:52","15 Jul 2026, 10:58","","Lateral Raise (Dumbbell)","","","0","dropset","8","15","","",""
        """.trimIndent()
        val h = parseWorkoutExport(hevy, WeightUnit.LB, emptyList(), 80f)!!
        assertEquals(ImportSource.HEVY, h.source)
        val w = h.workouts.single()
        assertEquals("Push Day", w.splitTitle)
        assertEquals(66 * 60L, w.durationSeconds)
        assertEquals(at("2026-07-15 09:52"), w.id)
        val bench = w.exercises.first()
        assertEquals("Barbell Bench Press", bench.name)
        assertEquals(80f, bench.sets[1].weightKg, 0.001f)
        assertTrue(bench.sets[0].isWarmupSet)
        assertEquals("DROP", w.exercises[1].sets.single().intensityType)
    }

    @Test
    fun hevy_inPounds() {
        val hevy = """
            title,start_time,end_time,description,exercise_title,superset_id,exercise_notes,set_index,set_type,weight_lbs,reps,distance_miles,duration_seconds,rpe
            Legs,2026-07-16T18:00:00Z,2026-07-16T19:00:00Z,,Squat (Barbell),,,0,normal,225,5,,,
        """.trimIndent()
        val w = parseWorkoutExport(hevy, WeightUnit.KG, emptyList(), 80f)!!.workouts.single()
        assertEquals(102.06f, w.exercises.single().sets.single().weightKg, 0.01f)
        assertEquals(3600L, w.durationSeconds)
    }

    @Test
    fun importedHistory_isAddedLikeABackup() {
        LocalStorage.saveCompletedWorkout(context, workout("2026-10-25 18:00", "Back Squat"))
        val h = parseWorkoutExport(strong, WeightUnit.LB, LocalStorage.loadHistory(context).flatMap { w -> w.exercises.map { it.name } }, 80f)!!
        val result = LocalStorage.addData(context, h.asStoreData())!!
        assertEquals(2, result.addedWorkouts)
        assertEquals(3, LocalStorage.loadHistory(context).size)
        assertEquals(ExerciseType.DISTANCE_TIME, LocalStorage.exerciseType(context, "Running"))
        assertEquals(ExerciseType.DURATION, LocalStorage.exerciseType(context, "Plank"))
    }

    @Test
    fun notAnExport() {
        assertNull(parseWorkoutExport("a,b,c\n1,2,3", WeightUnit.KG, emptyList(), 80f))
        assertNotNull(parseExportDate("2020-12-30 18:51:52"))
        assertNull(parseExportDate("yesterday"))
        assertEquals(158 * 60L, parseStrongDuration("2h 38m"))
    }

    @Test
    fun strong_equipmentDecidesTheExercise() {
        val csv = """
            Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
            2026-10-20 18:00:00,Push,1h,Incline Bench Press (Dumbbell),1,22,10,0,0,,,
            2026-10-20 18:00:00,Push,1h,Incline Bench Press (Barbell),1,50,8,0,0,,,
            2026-10-20 18:00:00,Push,1h,Bicep Curl (Dumbbell),1,12,10,0,0,,,
            2026-10-20 18:00:00,Push,1h,Hammer Curl (Dumbbell),1,12,10,0,0,,,
            2026-10-20 18:00:00,Push,1h,Bench Press (Barbell),1,60,8,0,0,,,
        """.trimIndent()
        val names = parseWorkoutExport(csv, WeightUnit.KG, knownNames = listOf("Bench Press"), bodyWeightKg = 80f)!!
            .workouts.single().exercises.map { it.name }
        // The dumbbell press isn't the barbell one; what's logged here already keeps its name.
        assertEquals(listOf("Incline Dumbbell Press", "Incline Barbell Bench Press", "Dumbbell Curl", "Hammer Curl", "Bench Press"), names)
    }
}
