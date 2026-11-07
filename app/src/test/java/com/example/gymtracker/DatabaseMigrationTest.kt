package com.example.gymtracker

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs the real Room migrations against databases built from the committed schemas. */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    // A full path: with a bare name, Room's helper and its driver disagree about the file under Robolectric.
    private val dbName = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-test.db").absolutePath

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

    @Test
    fun v1ToV2_addsColumns_andAppliesLibraryTypesOnlyWhereNothingIsHidden() {
        helper.createDatabase(dbName, 1).use { db ->
            fun exercise(id: Int, name: String) =
                db.execSQL("INSERT INTO exercises (id, name, nameKey, muscle, note) VALUES ($id, '$name', '${name.lowercase()}', '', '')")
            exercise(1, "Pull-Up")          // bodyweight in the library, logged WITH weight
            exercise(2, "Push-Up")          // bodyweight in the library, logged without weight
            exercise(3, "Plank")            // time in the library, logged as reps
            exercise(4, "Running")          // distance in the library, never logged
            exercise(5, "Weighted Dips")    // weighted bodyweight, logged
            exercise(6, "My Machine Press") // custom
            db.execSQL("INSERT INTO workouts (id, splitTitle, dateKey, displayDate, durationSeconds, bodyWeightKg, photoPath, skippedExercises, totalVolumeKg, prsHit) VALUES (100, 'Pull', '', '', 0, 80, NULL, '[]', 0, 0)")
            val logged = listOf(1 to "20", 2 to "0", 3 to "", 5 to "15", 6 to "40")
            logged.forEachIndexed { i, (exId, weight) ->
                db.execSQL("INSERT INTO workout_exercises (id, workoutId, exerciseId, position, targetMuscle, restSeconds, isSkipped, isSuperset) VALUES (${i + 1}, 100, $exId, $i, '', 90, 0, 0)")
                db.execSQL("INSERT INTO workout_sets (workoutExerciseId, position, setNumber, weight, reps, intensityType, isWarmup, isCompleted, restSeconds) VALUES (${i + 1}, 0, 1, '$weight', '8', 'NORMAL', 0, 1, 90)")
            }
        }

        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).use { db ->
            val types = HashMap<String, String>()
            db.query("SELECT name, type FROM exercises").use { c -> while (c.moveToNext()) types[c.getString(0)] = c.getString(1) }
            assertEquals("WEIGHT_REPS", types["Pull-Up"])               // weight would be hidden
            assertEquals("BODYWEIGHT", types["Push-Up"])
            assertEquals("WEIGHT_REPS", types["Plank"])                 // logged reps would be hidden
            assertEquals("DISTANCE_TIME", types["Running"])
            assertEquals("WEIGHTED_BODYWEIGHT", types["Weighted Dips"])
            assertEquals("WEIGHT_REPS", types["My Machine Press"])

            db.query("SELECT weight, reps, seconds, distanceM FROM workout_sets ORDER BY workoutExerciseId").use { c ->
                c.moveToFirst()
                assertEquals("20", c.getString(0))
                assertEquals("8", c.getString(1))
                assertEquals("", c.getString(2))
                assertEquals("", c.getString(3))
            }
        }
    }

    @Test
    fun v2ToV3_addsProgramTables_andKeepsData() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL("INSERT INTO workouts (id, splitTitle, dateKey, displayDate, durationSeconds, bodyWeightKg, photoPath, skippedExercises, totalVolumeKg, prsHit) VALUES (7, 'Push', '', '', 0, 80, NULL, '[]', 0, 0)")
        }
        helper.runMigrationsAndValidate(dbName, 3, true).use { db ->
            db.query("SELECT splitTitle FROM workouts").use { c -> c.moveToFirst(); assertEquals("Push", c.getString(0)) }
            db.query("SELECT COUNT(*) FROM programs").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM program_splits").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test
    fun v3ToV4_addsTargetColumns_leavingExistingListsWithoutTargets() {
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL("INSERT INTO exercises (id, name, nameKey, muscle, note, type) VALUES (1, 'Row', 'row', '', '', 'WEIGHT_REPS')")
            db.execSQL("INSERT INTO split_template_items (splitTitle, position, exerciseId, muscle) VALUES ('Pull', 0, 1, 'Back')")
        }
        helper.runMigrationsAndValidate(dbName, 4, true).use { db ->
            db.query("SELECT targetSets, repMin, repMax FROM split_template_items").use { c ->
                c.moveToFirst()
                assertTrue(c.isNull(0) && c.isNull(1) && c.isNull(2))
            }
        }
    }

    @Test
    fun v4ToV5_addsTrainingMaxColumns() {
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL("INSERT INTO exercises (id, name, nameKey, muscle, note, type) VALUES (1, 'Back Squat', 'back squat', '', '', 'WEIGHT_REPS')")
        }
        helper.runMigrationsAndValidate(dbName, 5, true).use { db ->
            db.query("SELECT trainingMaxKg, trainingMaxSetAt FROM exercises").use { c ->
                c.moveToFirst()
                assertTrue(c.isNull(0) && c.isNull(1))
            }
        }
    }

    @Test
    fun v5ToV6_addsEffortColumn_unrecordedForOldSets() {
        helper.createDatabase(dbName, 5).use { db ->
            db.execSQL("INSERT INTO workouts (id, splitTitle, dateKey, displayDate, durationSeconds, bodyWeightKg, skippedExercises, totalVolumeKg, prsHit) VALUES (1, 'Push', '2026-11-01', '', 3000, 0, '', 0, 0)")
            db.execSQL("INSERT INTO exercises (id, name, nameKey, muscle, note, type) VALUES (1, 'Bench Press', 'bench press', '', '', 'WEIGHT_REPS')")
            db.execSQL("INSERT INTO workout_exercises (id, workoutId, exerciseId, position, targetMuscle, restSeconds, isSkipped, isSuperset) VALUES (1, 1, 1, 0, '', 90, 0, 0)")
            db.execSQL("INSERT INTO workout_sets (workoutExerciseId, position, setNumber, weight, reps, intensityType, isWarmup, isCompleted, restSeconds) VALUES (1, 0, 1, '80', '8', 'NORMAL', 0, 1, 90)")
        }
        helper.runMigrationsAndValidate(dbName, 6, true).use { db ->
            db.query("SELECT rir FROM workout_sets").use { c ->
                c.moveToFirst()
                assertEquals(-1, c.getInt(0))
            }
        }
    }

    @Test
    fun v1ToV6_inOneGo() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_1_2).close()
    }

    @Test
    fun v1ToV3_inOneGo() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_1_2).close()
    }

    @Test
    fun typeForExistingExercise_rules() {
        val bw = ExerciseType.BODYWEIGHT
        assertEquals(bw, typeForExistingExercise(bw, hasHistory = true, hasWeightedSets = false))
        assertEquals(ExerciseType.WEIGHT_REPS, typeForExistingExercise(bw, hasHistory = true, hasWeightedSets = true))
        for (t in listOf(ExerciseType.DURATION, ExerciseType.DISTANCE_TIME, ExerciseType.ASSISTED)) {
            assertEquals(t, typeForExistingExercise(t, hasHistory = false, hasWeightedSets = false))
            assertEquals(ExerciseType.WEIGHT_REPS, typeForExistingExercise(t, hasHistory = true, hasWeightedSets = false))
        }
        assertEquals(
            ExerciseType.WEIGHTED_BODYWEIGHT,
            typeForExistingExercise(ExerciseType.WEIGHTED_BODYWEIGHT, hasHistory = true, hasWeightedSets = true)
        )
    }
}
