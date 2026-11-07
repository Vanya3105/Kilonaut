package com.example.gymtracker

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import java.util.Locale

// ============================================================================
// ROOM SCHEMA
// ============================================================================
// History, body weight, split templates and per-exercise notes live here. Small key-value
// state that changes on every tap (settings, profile, active session, widgets) stays in the
// JSON store. Everything is reached through LocalStorage, which keeps an in-memory copy.

/** One row per exercise ever used. History points at the id, so a rename keeps the history. */
@Entity(tableName = "exercises", indices = [Index(value = ["nameKey"], unique = true)])
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameKey: String,
    val muscle: String,
    val note: String = "",
    val defaultRestSeconds: Int? = null,
    /** [ExerciseType] name. */
    @ColumnInfo(defaultValue = "WEIGHT_REPS") val type: String = ExerciseType.WEIGHT_REPS.name,
    /** Training max for percentage programs, and when it was set. */
    val trainingMaxKg: Float? = null,
    val trainingMaxSetAt: Long? = null
)

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val id: Long,
    val splitTitle: String,
    val dateKey: String,
    val displayDate: String,
    val durationSeconds: Long,
    val bodyWeightKg: Float,
    val photoPath: String?,
    val skippedExercises: List<String>,
    val totalVolumeKg: Int,
    val prsHit: Int
)

@Entity(
    tableName = "workout_exercises",
    foreignKeys = [
        ForeignKey(entity = WorkoutEntity::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"])
    ],
    indices = [Index("workoutId"), Index("exerciseId")]
)
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val position: Int,
    val targetMuscle: String,
    val restSeconds: Int,
    val isSkipped: Boolean,
    val isSuperset: Boolean
)

@Entity(
    tableName = "workout_sets",
    foreignKeys = [ForeignKey(entity = WorkoutExerciseEntity::class, parentColumns = ["id"], childColumns = ["workoutExerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutExerciseId")]
)
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val position: Int,
    val setNumber: Int,
    // Kept as typed so a half-filled set round-trips exactly; parsing happens in the model layer.
    val weight: String,
    val reps: String,
    val intensityType: String,
    val isWarmup: Boolean,
    val isCompleted: Boolean,
    val restSeconds: Int,
    @ColumnInfo(defaultValue = "") val seconds: String = "",
    @ColumnInfo(defaultValue = "") val distanceM: String = "",
    @ColumnInfo(defaultValue = "-1") val rir: Int = -1
)

@Entity(tableName = "body_weight")
data class BodyWeightEntity(
    @PrimaryKey val id: Long,
    val dateKey: String,
    val displayDate: String,
    val weightKg: Float
)

@Entity(
    tableName = "split_template_items",
    primaryKeys = ["splitTitle", "position"],
    foreignKeys = [ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"])],
    indices = [Index("exerciseId")]
)
data class SplitTemplateItemEntity(
    val splitTitle: String,
    val position: Int,
    val exerciseId: Long,
    val muscle: String,
    /** Optional target; all three are set together or not at all. */
    val targetSets: Int? = null,
    val repMin: Int? = null,
    val repMax: Int? = null
)

/** A program the user built or customized. Built-in programs live in code ([SPLIT_PROGRAMS]). */
@Entity(tableName = "programs")
data class ProgramEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tagline: String,
    val weeklyGoal: Int,
    val position: Int
)

/** A day of a program. Days are identified by title, which history and exercise lists use too. */
@Entity(
    tableName = "program_splits",
    primaryKeys = ["programId", "position"],
    foreignKeys = [ForeignKey(entity = ProgramEntity::class, parentColumns = ["id"], childColumns = ["programId"], onDelete = ForeignKey.CASCADE)]
)
data class ProgramSplitEntity(
    val programId: String,
    val position: Int,
    val title: String,
    val subtitle: String
)

class Converters {
    @TypeConverter
    fun fromStringList(list: List<String>): String = JSONArray(list).toString()

    @TypeConverter
    fun toStringList(raw: String): List<String> {
        val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).map { arr.optString(it) }
    }
}

@Dao
interface GymDao {
    @Query("SELECT * FROM exercises") fun exercises(): List<ExerciseEntity>
    @Query("SELECT * FROM exercises WHERE nameKey = :key") fun exerciseByKey(key: String): ExerciseEntity?
    @Insert fun insertExercise(e: ExerciseEntity): Long
    @Query("UPDATE exercises SET note = :note WHERE id = :id") fun setExerciseNote(id: Long, note: String)
    @Query("UPDATE exercises SET defaultRestSeconds = :sec WHERE id = :id") fun setExerciseRest(id: Long, sec: Int?)
    @Query("UPDATE exercises SET name = :name, nameKey = :key WHERE id = :id") fun renameExercise(id: Long, name: String, key: String)
    @Query("UPDATE exercises SET type = :type WHERE id = :id") fun setExerciseType(id: Long, type: String)
    @Query("UPDATE exercises SET trainingMaxKg = :kg, trainingMaxSetAt = :setAt WHERE id = :id") fun setTrainingMax(id: Long, kg: Float?, setAt: Long?)
    @Query("SELECT COUNT(*) FROM workout_exercises WHERE exerciseId = :id") fun loggedCount(id: Long): Int
    @Query("SELECT COUNT(*) FROM workout_sets s JOIN workout_exercises e ON s.workoutExerciseId = e.id WHERE e.exerciseId = :id AND CAST(s.weight AS REAL) > 0")
    fun weightedSetCount(id: Long): Int

    // Merging two exercises
    @Query("SELECT * FROM workout_exercises WHERE exerciseId = :id") fun entriesOf(id: Long): List<WorkoutExerciseEntity>
    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId AND exerciseId = :exerciseId LIMIT 1")
    fun entryIn(workoutId: Long, exerciseId: Long): WorkoutExerciseEntity?
    @Query("UPDATE workout_exercises SET exerciseId = :into WHERE id = :entryId") fun pointEntryAt(entryId: Long, into: Long)
    @Query("SELECT COUNT(*) FROM workout_sets WHERE workoutExerciseId = :entryId") fun setCount(entryId: Long): Int
    @Query("SELECT COALESCE(MAX(position), -1) FROM workout_sets WHERE workoutExerciseId = :entryId") fun lastSetPosition(entryId: Long): Int
    @Query("UPDATE workout_sets SET workoutExerciseId = :to, position = position + :positionShift, setNumber = setNumber + :numberShift WHERE workoutExerciseId = :from")
    fun moveSets(from: Long, to: Long, positionShift: Int, numberShift: Int)
    @Query("DELETE FROM workout_exercises WHERE id = :entryId") fun deleteEntry(entryId: Long)
    @Query("SELECT * FROM split_template_items WHERE exerciseId = :id") fun templateItemsOf(id: Long): List<SplitTemplateItemEntity>
    @Query("UPDATE split_template_items SET exerciseId = :into WHERE splitTitle = :title AND position = :position")
    fun pointTemplateItemAt(title: String, position: Int, into: Long)
    @Query("DELETE FROM split_template_items WHERE splitTitle = :title AND position = :position") fun deleteTemplateItem(title: String, position: Int)
    @Update fun updateExercise(e: ExerciseEntity)
    @Query("DELETE FROM exercises WHERE id = :id") fun deleteExercise(id: Long)
    @Query("UPDATE workouts SET skippedExercises = :skipped WHERE id = :id") fun setSkipped(id: Long, skipped: List<String>)

    @Query("SELECT * FROM workouts") fun workouts(): List<WorkoutEntity>
    @Query("SELECT * FROM workout_exercises") fun workoutExercises(): List<WorkoutExerciseEntity>
    @Query("SELECT * FROM workout_sets") fun workoutSets(): List<WorkoutSetEntity>
    @Insert fun insertWorkout(w: WorkoutEntity)
    @Insert fun insertWorkoutExercise(e: WorkoutExerciseEntity): Long
    @Insert fun insertSets(sets: List<WorkoutSetEntity>)
    @Query("DELETE FROM workouts WHERE id = :id") fun deleteWorkout(id: Long)

    @Query("SELECT * FROM body_weight") fun bodyWeights(): List<BodyWeightEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertBodyWeight(w: BodyWeightEntity)
    @Query("DELETE FROM body_weight WHERE id = :id") fun deleteBodyWeight(id: Long)

    @Query("SELECT * FROM split_template_items ORDER BY splitTitle, position") fun templateItems(): List<SplitTemplateItemEntity>
    @Insert fun insertTemplateItems(items: List<SplitTemplateItemEntity>)
    @Query("DELETE FROM split_template_items WHERE splitTitle = :splitTitle") fun deleteTemplate(splitTitle: String)

    @Query("SELECT * FROM programs ORDER BY position") fun programs(): List<ProgramEntity>
    @Query("SELECT * FROM program_splits ORDER BY programId, position") fun programSplits(): List<ProgramSplitEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertProgram(p: ProgramEntity)
    @Insert fun insertProgramSplits(splits: List<ProgramSplitEntity>)
    @Query("DELETE FROM program_splits WHERE programId = :id") fun deleteProgramSplits(id: String)
    @Query("DELETE FROM programs WHERE id = :id") fun deleteProgram(id: String)
    @Query("UPDATE workouts SET splitTitle = :new WHERE splitTitle = :old") fun renameWorkoutSplit(old: String, new: String)
    @Query("UPDATE split_template_items SET splitTitle = :new WHERE splitTitle = :old") fun renameTemplate(old: String, new: String)
    @Query("SELECT * FROM split_template_items WHERE splitTitle = :title ORDER BY position") fun templateFor(title: String): List<SplitTemplateItemEntity>
    @Query("DELETE FROM programs") fun clearPrograms()

    @Query("DELETE FROM workouts") fun clearWorkouts()
    @Query("DELETE FROM body_weight") fun clearBodyWeights()
    @Query("DELETE FROM split_template_items") fun clearTemplates()
    @Query("DELETE FROM exercises") fun clearExercises()
}

@Database(
    entities = [
        ExerciseEntity::class, WorkoutEntity::class, WorkoutExerciseEntity::class, WorkoutSetEntity::class,
        BodyWeightEntity::class, SplitTemplateItemEntity::class, ProgramEntity::class, ProgramSplitEntity::class
    ],
    version = 6,
    exportSchema = true,
    // v3 only adds tables, v4 and v5 only add nullable columns: Room migrates those by itself.
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)]
)
@TypeConverters(Converters::class)
abstract class GymDatabase : RoomDatabase() {
    abstract fun dao(): GymDao

    companion object {
        @Volatile private var instance: GymDatabase? = null

        fun get(context: Context): GymDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, "gymtracker.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }

        internal fun closeForTests() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}

/** v2: exercise types, plus time and distance on sets. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE exercises ADD COLUMN type TEXT NOT NULL DEFAULT 'WEIGHT_REPS'")
        db.execSQL("ALTER TABLE workout_sets ADD COLUMN seconds TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE workout_sets ADD COLUMN distanceM TEXT NOT NULL DEFAULT ''")
        val rows = ArrayList<Pair<Long, ExerciseType>>()
        db.query("SELECT id, name FROM exercises").use { c ->
            while (c.moveToNext()) {
                val libraryType = ExerciseDatabase.find(c.getString(1))?.type ?: continue
                rows += c.getLong(0) to libraryType
            }
        }
        for ((id, libraryType) in rows) {
            val logged = db.countFor("SELECT COUNT(*) FROM workout_exercises WHERE exerciseId = ?", id) > 0
            val weighted = db.countFor(
                "SELECT COUNT(*) FROM workout_sets s JOIN workout_exercises e ON s.workoutExerciseId = e.id " +
                    "WHERE e.exerciseId = ? AND CAST(s.weight AS REAL) > 0", id
            ) > 0
            val type = typeForExistingExercise(libraryType, logged, weighted)
            if (type != ExerciseType.WEIGHT_REPS) db.execSQL("UPDATE exercises SET type = ? WHERE id = ?", arrayOf<Any>(type.name, id))
        }
    }

    private fun SupportSQLiteDatabase.countFor(sql: String, id: Long): Long =
        query(sql, arrayOf<Any>(id)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
}

/**
 * The type an exercise logged before types existed should get. Everything was "weight & reps"
 * then, so the library's type is only applied when no logged data would be hidden by it.
 */
fun typeForExistingExercise(libraryType: ExerciseType, hasHistory: Boolean, hasWeightedSets: Boolean): ExerciseType = when (libraryType) {
    ExerciseType.WEIGHT_REPS, ExerciseType.WEIGHTED_BODYWEIGHT -> libraryType // same data shape
    ExerciseType.BODYWEIGHT -> if (hasWeightedSets) ExerciseType.WEIGHT_REPS else libraryType
    ExerciseType.ASSISTED, ExerciseType.DURATION, ExerciseType.DISTANCE_TIME -> if (hasHistory) ExerciseType.WEIGHT_REPS else libraryType
}

// ============================================================================
// MODEL <-> ROOM MAPPING
// ============================================================================

/** Everything that lives in Room, in model form. Also the shape of the backup's data sections. */
data class StoreData(
    val history: List<CompletedWorkout> = emptyList(),
    val weights: List<WeightLogEntry> = emptyList(),
    val templates: Map<String, List<Pair<String, String>>> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    val restDefaults: Map<String, Int> = emptyMap(),
    /**
     * Exercise type by [exerciseKey]. Null means unknown (stores and backups from before types),
     * in which case library types are applied only where no logged data would be hidden.
     */
    val types: Map<String, ExerciseType>? = emptyMap(),
    /** The user's own programs, in display order. */
    val programs: List<SplitProgramInfo> = emptyList(),
    /** Day title → [exerciseKey] → target, for exercises on that day's list that have one. */
    val targets: Map<String, Map<String, ExerciseTarget>> = emptyMap(),
    /** [exerciseKey] → training max, for lifts that have one. */
    val trainingMaxes: Map<String, TrainingMax> = emptyMap()
)

fun exerciseKey(name: String): String = name.trim().lowercase(Locale.US)

/**
 * Blocking Room operations in model terms. Callers run these off the main thread
 * (LocalStorage uses its own database thread); tests call them directly.
 */
object RoomStore {

    /** Notes and rest defaults are keyed by [exerciseKey], newest workouts and weigh-ins first. */
    fun load(db: GymDatabase): StoreData {
        val dao = db.dao()
        val exercises = dao.exercises().associateBy { it.id }
        val setsByExercise = dao.workoutSets().groupBy { it.workoutExerciseId }
        val exercisesByWorkout = dao.workoutExercises().groupBy { it.workoutId }

        val history = dao.workouts().map { w ->
            CompletedWorkout(
                id = w.id, splitTitle = w.splitTitle, dateKey = w.dateKey, displayDate = w.displayDate,
                durationSeconds = w.durationSeconds, bodyWeightKg = w.bodyWeightKg, photoPath = w.photoPath,
                skippedExercises = w.skippedExercises,
                exercises = exercisesByWorkout[w.id].orEmpty().sortedBy { it.position }.map { we ->
                    ExerciseEntry(
                        name = exercises[we.exerciseId]?.name.orEmpty(),
                        targetMuscle = we.targetMuscle,
                        restSeconds = we.restSeconds,
                        isSkipped = we.isSkipped,
                        isSuperset = we.isSuperset,
                        type = ExerciseType.fromKey(exercises[we.exerciseId]?.type),
                        sets = setsByExercise[we.id].orEmpty().sortedBy { it.position }.map { s ->
                            SetEntry(
                                setNumber = s.setNumber, weight = s.weight, reps = s.reps, isWarmup = s.isWarmup,
                                intensityType = s.intensityType, isCompleted = s.isCompleted, restSeconds = s.restSeconds,
                                seconds = s.seconds, distanceM = s.distanceM, rir = s.rir
                            )
                        }
                    )
                },
                totalVolumeKg = w.totalVolumeKg, prsHit = w.prsHit
            )
        }.sortedByDescending { it.id }

        val weights = dao.bodyWeights()
            .map { WeightLogEntry(it.id, it.dateKey, it.displayDate, it.weightKg) }
            .sortedByDescending { it.id }

        val templateRows = dao.templateItems().groupBy { it.splitTitle }
        val templates = templateRows.mapValues { (_, items) ->
            items.map { (exercises[it.exerciseId]?.name.orEmpty()) to it.muscle }
        }
        val targets = templateRows.mapValues { (_, items) ->
            items.mapNotNull { row ->
                val target = row.toTarget() ?: return@mapNotNull null
                val key = exercises[row.exerciseId]?.nameKey ?: return@mapNotNull null
                key to target
            }.toMap()
        }.filterValues { it.isNotEmpty() }

        return StoreData(
            history = history,
            weights = weights,
            templates = templates,
            notes = exercises.values.filter { it.note.isNotBlank() }.associate { it.nameKey to it.note },
            restDefaults = exercises.values.mapNotNull { e -> e.defaultRestSeconds?.let { e.nameKey to it } }.toMap(),
            types = exercises.values.associate { it.nameKey to ExerciseType.fromKey(it.type) },
            programs = loadPrograms(db),
            targets = targets,
            trainingMaxes = exercises.values.mapNotNull { e ->
                val kg = e.trainingMaxKg ?: return@mapNotNull null
                e.nameKey to TrainingMax(kg, e.trainingMaxSetAt ?: 0L)
            }.toMap()
        )
    }

    /** Wipes Room and writes [data] in one transaction. Used by the first-run migration and backup restore. */
    fun replaceAll(db: GymDatabase, data: StoreData) = db.runInTransaction {
        val dao = db.dao()
        dao.clearTemplates()
        dao.clearPrograms() // cascades to their days
        dao.clearWorkouts() // cascades to exercises-in-workout and sets
        dao.clearBodyWeights()
        dao.clearExercises()

        // The first spelling of a name wins for differently-cased duplicates, so go templates
        // first, then oldest workouts: that keeps how the user originally typed it.
        data.templates.forEach { (split, items) -> saveTemplate(db, split, items, data.targets[split].orEmpty()) }
        data.history.sortedBy { it.id }.forEach { insertWorkout(db, it) }
        data.weights.forEach { dao.insertBodyWeight(BodyWeightEntity(it.id, it.dateKey, it.displayDate, it.weightKg)) }
        data.notes.forEach { (key, note) -> setNote(db, key, note) }
        data.restDefaults.forEach { (key, sec) -> setDefaultRest(db, key, sec) }
        data.programs.forEachIndexed { i, p -> saveProgram(db, p, i) }
        data.trainingMaxes.forEach { (key, tm) -> setTrainingMax(db, key, tm) }
        val types = data.types
        if (types != null) {
            types.forEach { (key, type) -> setType(db, key, type) }
        } else {
            // Rows were just created with library types; undo any that would hide logged data.
            for (e in dao.exercises()) {
                val libraryType = ExerciseDatabase.find(e.name)?.type ?: continue
                val type = typeForExistingExercise(libraryType, dao.loggedCount(e.id) > 0, dao.weightedSetCount(e.id) > 0)
                if (type.name != e.type) dao.setExerciseType(e.id, type.name)
            }
        }
    }

    /**
     * Finds the exercise by name (case-insensitive) or creates it, returning its id. A new row
     * takes the library's type, or [type] for a custom exercise.
     */
    fun exerciseId(db: GymDatabase, name: String, muscle: String = "", type: ExerciseType? = null): Long {
        val dao = db.dao()
        val key = exerciseKey(name)
        dao.exerciseByKey(key)?.let { return it.id }
        // Notes arrive keyed by lowercase name, so take spelling and muscle from the library when we can.
        val library = ExerciseDatabase.find(name)
        return dao.insertExercise(
            ExerciseEntity(
                name = library?.name ?: name.trim(), nameKey = key, muscle = library?.muscle ?: muscle,
                type = (type ?: library?.type ?: ExerciseType.WEIGHT_REPS).name
            )
        )
    }

    /** Inserts the workout, replacing any stored workout with the same id. */
    fun insertWorkout(db: GymDatabase, workout: CompletedWorkout) = db.runInTransaction {
        val dao = db.dao()
        dao.deleteWorkout(workout.id)
        dao.insertWorkout(
            WorkoutEntity(
                id = workout.id, splitTitle = workout.splitTitle, dateKey = workout.dateKey, displayDate = workout.displayDate,
                durationSeconds = workout.durationSeconds, bodyWeightKg = workout.bodyWeightKg, photoPath = workout.photoPath,
                skippedExercises = workout.skippedExercises, totalVolumeKg = workout.totalVolumeKg, prsHit = workout.prsHit
            )
        )
        workout.exercises.forEachIndexed { i, ex ->
            val rowId = dao.insertWorkoutExercise(
                WorkoutExerciseEntity(
                    workoutId = workout.id, exerciseId = exerciseId(db, ex.name, ex.targetMuscle, ex.type), position = i,
                    targetMuscle = ex.targetMuscle, restSeconds = ex.restSeconds, isSkipped = ex.isSkipped, isSuperset = ex.isSuperset
                )
            )
            dao.insertSets(ex.sets.mapIndexed { j, s ->
                WorkoutSetEntity(
                    workoutExerciseId = rowId, position = j, setNumber = s.setNumber, weight = s.weight, reps = s.reps,
                    intensityType = s.intensityType, isWarmup = s.isWarmup, isCompleted = s.isCompleted, restSeconds = s.restSeconds,
                    seconds = s.seconds, distanceM = s.distanceM, rir = s.rir
                )
            })
        }
    }

    fun deleteWorkout(db: GymDatabase, id: Long) = db.dao().deleteWorkout(id)

    fun addBodyWeight(db: GymDatabase, entry: WeightLogEntry) =
        db.dao().insertBodyWeight(BodyWeightEntity(entry.id, entry.dateKey, entry.displayDate, entry.weightKg))

    fun deleteBodyWeight(db: GymDatabase, id: Long) = db.dao().deleteBodyWeight(id)

    /** Replaces a day's exercise list. [targets] is keyed by [exerciseKey]. */
    fun saveTemplate(
        db: GymDatabase,
        splitTitle: String,
        items: List<Pair<String, String>>,
        targets: Map<String, ExerciseTarget> = emptyMap()
    ) = db.runInTransaction {
        val dao = db.dao()
        dao.deleteTemplate(splitTitle)
        dao.insertTemplateItems(items.mapIndexed { i, (name, muscle) ->
            val t = targets[exerciseKey(name)]
            SplitTemplateItemEntity(splitTitle, i, exerciseId(db, name, muscle), muscle, t?.sets, t?.repMin, t?.repMax)
        })
    }

    private fun SplitTemplateItemEntity.toTarget(): ExerciseTarget? {
        val sets = targetSets ?: return null
        val min = repMin ?: return null
        val max = repMax ?: return null
        return ExerciseTarget(sets, min, max)
    }

    fun setNote(db: GymDatabase, name: String, note: String) =
        db.dao().setExerciseNote(exerciseId(db, name), note.trim())

    fun setDefaultRest(db: GymDatabase, name: String, sec: Int) =
        db.dao().setExerciseRest(exerciseId(db, name), sec)

    private fun loadPrograms(db: GymDatabase): List<SplitProgramInfo> {
        val dao = db.dao()
        val splits = dao.programSplits().groupBy { it.programId }
        return dao.programs().map { p ->
            SplitProgramInfo(
                id = p.id, name = p.name, tagline = p.tagline, weeklyGoal = p.weeklyGoal,
                splits = splits[p.id].orEmpty().map { WorkoutSplit(it.title, it.subtitle, "%02d".format(it.position + 1)) }
            )
        }
    }

    /** Inserts or replaces a program and its days. */
    fun saveProgram(db: GymDatabase, program: SplitProgramInfo, position: Int) = db.runInTransaction {
        val dao = db.dao()
        dao.insertProgram(ProgramEntity(program.id, program.name, program.tagline, program.weeklyGoal, position))
        dao.deleteProgramSplits(program.id)
        dao.insertProgramSplits(program.splits.mapIndexed { i, s -> ProgramSplitEntity(program.id, i, s.title, s.subtitle) })
    }

    fun deleteProgram(db: GymDatabase, id: String) = db.dao().deleteProgram(id)

    /**
     * Gives a day a new title. Its exercise list moves with it, or is copied when [keepTemplate]
     * (another program still uses the old title). Logged workouts move too, so suggestions and
     * "last time" keep working, unless [keepHistory].
     */
    fun renameSplit(db: GymDatabase, old: String, new: String, keepTemplate: Boolean, keepHistory: Boolean) = db.runInTransaction {
        val dao = db.dao()
        val items = dao.templateFor(old)
        if (items.isNotEmpty()) {
            dao.deleteTemplate(new)
            if (keepTemplate) dao.insertTemplateItems(items.map { it.copy(splitTitle = new) }) else dao.renameTemplate(old, new)
        }
        if (!keepHistory) dao.renameWorkoutSplit(old, new)
    }

    fun setTrainingMax(db: GymDatabase, name: String, tm: TrainingMax?) =
        db.dao().setTrainingMax(exerciseId(db, name), tm?.kg, tm?.setAtMillis)

    fun setType(db: GymDatabase, name: String, type: ExerciseType) =
        db.dao().setExerciseType(exerciseId(db, name, type = type), type.name)

    /**
     * Renames an exercise everywhere (history, templates, notes). Returns false if another
     * exercise already has that name, since merging two histories is a separate decision.
     */
    /**
     * Makes [fromName] part of [intoName]: its sessions, places in day lists and settings move to
     * [intoName], and [fromName] is removed. A workout that logged both keeps one entry, [intoName]'s
     * sets first. [intoName]'s note, rest, type and training max win; [fromName]'s fill any it lacks.
     * Returns false if either is missing or they're the same exercise.
     */
    fun mergeExercise(db: GymDatabase, fromName: String, intoName: String): Boolean {
        val dao = db.dao()
        var ok = false
        db.runInTransaction {
            val from = dao.exerciseByKey(exerciseKey(fromName)) ?: return@runInTransaction
            val into = dao.exerciseByKey(exerciseKey(intoName)) ?: return@runInTransaction
            if (from.id == into.id) return@runInTransaction

            for (entry in dao.entriesOf(from.id)) {
                val kept = dao.entryIn(entry.workoutId, into.id)
                if (kept == null) {
                    dao.pointEntryAt(entry.id, into.id)
                } else {
                    dao.moveSets(entry.id, kept.id, dao.lastSetPosition(kept.id) + 1, dao.setCount(kept.id))
                    dao.deleteEntry(entry.id)
                }
            }
            for (item in dao.templateItemsOf(from.id)) {
                val dayHasBoth = dao.templateFor(item.splitTitle).any { it.exerciseId == into.id }
                if (dayHasBoth) dao.deleteTemplateItem(item.splitTitle, item.position)
                else dao.pointTemplateItemAt(item.splitTitle, item.position, into.id)
            }
            dao.workouts().filter { w -> w.skippedExercises.any { exerciseKey(it) == from.nameKey } }.forEach { w ->
                dao.setSkipped(w.id, w.skippedExercises.map { if (exerciseKey(it) == from.nameKey) into.name else it }.distinct())
            }
            dao.updateExercise(
                into.copy(
                    note = into.note.ifBlank { from.note },
                    defaultRestSeconds = into.defaultRestSeconds ?: from.defaultRestSeconds,
                    muscle = into.muscle.ifBlank { from.muscle },
                    trainingMaxKg = into.trainingMaxKg ?: from.trainingMaxKg,
                    trainingMaxSetAt = if (into.trainingMaxKg != null) into.trainingMaxSetAt else from.trainingMaxSetAt
                )
            )
            dao.deleteExercise(from.id)
            ok = true
        }
        return ok
    }

    fun renameExercise(db: GymDatabase, oldName: String, newName: String): Boolean {
        val dao = db.dao()
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        var ok = false
        db.runInTransaction {
            val existing = dao.exerciseByKey(exerciseKey(oldName)) ?: return@runInTransaction
            val clash = dao.exerciseByKey(exerciseKey(trimmed))
            if (clash != null && clash.id != existing.id) return@runInTransaction
            dao.renameExercise(existing.id, trimmed, exerciseKey(trimmed))
            ok = true
        }
        return ok
    }
}
