package com.example.gymtracker

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import android.content.ActivityNotFoundException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val EXERCISE_KEY_PREFIX = "ex:"

private data class Banner(val message: String, val icon: ImageVector, val isPr: Boolean)

@Composable
fun ActiveWorkoutScreen(
    split: WorkoutSplit,
    exercises: List<ExerciseEntry>,
    history: List<CompletedWorkout>,
    settings: AppSettings,
    userProfile: UserProfile,
    isEditingPastWorkout: Boolean,
    initialDurationSeconds: Long = 0L,
    initialPhotoPath: String?,
    haptics: Haptics,
    hasUnsavedEdits: Boolean = false,
    /** Goes up when an exercise's page changed its note or training max, so the cards reread them. */
    infoVersion: Int = 0,
    onOpenExercise: (String) -> Unit,
    onChangeExerciseType: (name: String, type: ExerciseType) -> Unit,
    onUpdateExercises: (List<ExerciseEntry>) -> Unit,
    onReorderExercises: (Int, Int) -> Unit,
    onAddExercise: (name: String, muscle: String) -> Unit,
    onRemoveExercisePermanently: (String) -> Unit,
    onMinimize: () -> Unit,
    onDiscardWorkout: () -> Unit,
    onFinishWorkout: (durationSec: Long, photoPath: String?, skipped: List<String>, note: String) -> Unit,
    /** The note a past workout already has. */
    initialNote: String = ""
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val latestExercises by rememberUpdatedState(exercises)
    val startTimeMillis = remember { LocalStorage.getActiveSessionStartTime(context) }

    // An empty workout has nothing to show until something is picked.
    // Saveable: Android may close the app while the camera or gallery is open from the finish sheet.
    var showPicker by rememberSaveable { mutableStateOf(!isEditingPastWorkout && exercises.isEmpty()) }
    var showFinish by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // A set ticked while earlier work sets of the same exercise aren't: (card, set, the earlier ones).
    var tickingAhead by remember { mutableStateOf<Triple<Int, Int, List<Int>>?>(null) }
    var photoPath by rememberSaveable { mutableStateOf(initialPhotoPath) }
    var note by rememberSaveable { mutableStateOf(initialNote) }
    var finalDuration by rememberSaveable { mutableLongStateOf(initialDurationSeconds) }
    var banner by remember { mutableStateOf<Banner?>(null) }
    val editor = remember { SetEditor() }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(2800)
            banner = null
        }
    }

    // A full-size photo into our own file (TakePicturePreview only returns a thumbnail).
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraFile?.let(::File)
        if (taken && file != null) PhotoStorage.copyUriToInternalStorage(context, Uri.fromFile(file))?.let { photoPath = it }
        file?.delete()
        cameraFile = null
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { PhotoStorage.copyUriToInternalStorage(context, it)?.let { path -> photoPath = path } }
    }

    // First workout: the tour. Later, one tip the first time the rest timer or Previous column shows.
    val workoutTour = rememberTipTour(
        Tips.WORKOUT,
        listOf(
            TutorialStepInfo("SET_ROW", tr("Log a set"), tr("Tap a number to change the weight or reps. Tap \u2713 when the set is done and the rest timer starts on its own.")),
            TutorialStepInfo("SUGGESTION", tr("Your next weight"), tr("Worked out from your last sessions and today's sets. Tap Use to fill it in.")),
            TutorialStepInfo("NAME", tr("Everything about an exercise"), tr("Tap its name for your history, chart, records and a setup note like seat height.")),
            TutorialStepInfo("SET_BADGE", tr("Set options"), tr("Tap a set's number to make it a warm-up or drop set, change its rest or see the plates. Hold the dotted handle to move an exercise.")),
            TutorialStepInfo("FINISH", tr("Done for today?"), tr("Finish saves the workout and shows your new bests. You can edit it later in History."))
        ),
        ready = !isEditingPastWorkout && exercises.isNotEmpty() && !showPicker
    )
    var restShowing by remember { mutableStateOf(false) }
    val restTour = rememberTipTour(
        Tips.REST_TIMER,
        listOf(TutorialStepInfo("REST", tr("Rest timer"), tr("Counts down your rest. \u221215 / +15 changes it, \u23ED skips it. It keeps going on the lock screen and your watch, and buzzes when it's time."))),
        ready = restShowing && !workoutTour.active && !isEditingPastWorkout
    )
    val hasPrevious = remember(exercises, history) { exercises.any { ex -> history.any { w -> w.exercises.any { it.name.equals(ex.name, ignoreCase = true) } } } }
    val previousTour = rememberTipTour(
        Tips.PREVIOUS,
        listOf(TutorialStepInfo("PREVIOUS", tr("Last time"), tr("What you did on this set last time. Tap it to copy those numbers in."))),
        ready = hasPrevious && !workoutTour.active && !restTour.active && !restShowing && !isEditingPastWorkout
    )

    fun openFinish() {
        finalDuration = if (isEditingPastWorkout) initialDurationSeconds
        else ((System.currentTimeMillis() - startTimeMillis) / 1000L).coerceAtLeast(0L)
        showFinish = true
    }

    // Unsaved edits ask first, so the page stays put during that gesture.
    val backModifier = rememberPredictiveBack(animate = !(isEditingPastWorkout && hasUnsavedEdits)) {
        when {
            isEditingPastWorkout && hasUnsavedEdits -> confirmDiscard = true
            isEditingPastWorkout -> onDiscardWorkout()
            else -> onMinimize()
        }
    }

    val active = exercises.withIndex().filter { !it.value.isSkipped }
    // The set to do next, across the workout: in the first unfinished exercise, see nextSetIndex.
    val nextUp = active.firstOrNull { iv -> iv.value.sets.any { !it.isCompleted } }?.let { iv -> iv.value.name to iv.value.nextSetIndex() }
    val skipped = exercises.withIndex().filter { it.value.isSkipped }
    val totalSets = active.sumOf { it.value.sets.size }
    val doneSets = active.sumOf { ex -> ex.value.sets.count { it.isCompleted } }
    // The header's running totals count ticked work sets only, as the finished workout will.
    val liftsWeight = active.any { it.value.type.countsVolume }
    val liveTotal = active.sumOf { iv ->
        val ex = iv.value
        val done = ex.sets.filter { it.isCompleted && !it.isWarmupSet }
        if (liftsWeight) { if (ex.type.countsVolume) done.sumOf { (it.weightKg * it.repCount).toDouble() } else 0.0 }
        else if (ex.type.usesReps) done.sumOf { it.repCount.toDouble() } else 0.0
    }.roundToInt()
    // Quick workouts differ every time, so the last one is no template.
    val previousSplitWorkout = remember(split.title, history) {
        if (split.title == QUICK_WORKOUT_TITLE) null
        else history.firstOrNull { it.splitTitle.equals(split.title, ignoreCase = true) }
    }
    // Copying only helps if last time had at least one of today's exercises.
    val canCopyLast = previousSplitWorkout != null && exercises.any { cur ->
        previousSplitWorkout.exercises.any { !it.isSkipped && it.name.equals(cur.name, ignoreCase = true) }
    }

    fun supersetRange(pos: Int): IntRange {
        if (pos !in active.indices) return pos..pos
        var start = pos
        while (start > 0 && active[start - 1].value.isSuperset) start--
        var end = pos
        while (end < active.lastIndex && active[end].value.isSuperset) end++
        return start..end
    }

    // The exercise being swapped for another ("Machine taken?").
    var swapping by remember { mutableStateOf<String?>(null) }

    fun updateAt(exIdx: Int, transform: (ExerciseEntry) -> ExerciseEntry) {
        val list = latestExercises.toMutableList()
        if (exIdx !in list.indices) return
        list[exIdx] = transform(list[exIdx])
        onUpdateExercises(list)
    }

    /**
     * Ticks or unticks a set. Ticking one while earlier work sets of the exercise are unticked asks
     * first whether those were done too ([tickingAhead]): never blocked (supersets, a warm-up left
     * out, logging afterwards), only a nudge so a forgotten set doesn't read as never done.
     * Warm-ups don't count, they're often skipped on purpose. [alsoTick] are earlier sets to tick too.
     */
    fun toggleSet(pos: Int, setIdx: Int, alsoTick: List<Int>? = null) {
        val (exIdx, exercise) = active[pos]
        val setEntry = exercise.sets.getOrNull(setIdx) ?: return
        val nowDone = !setEntry.isCompleted
        if (nowDone && alsoTick == null) {
            val earlier = exercise.sets.indices.filter { it < setIdx && !exercise.sets[it].isCompleted && !exercise.sets[it].isWarmupSet }
            if (earlier.isNotEmpty()) {
                haptics.tick()
                tickingAhead = Triple(pos, setIdx, earlier)
                return
            }
        }
        val updatedSet = setEntry.copy(isCompleted = nowDone)
        val next = latestExercises.toMutableList()
        next[exIdx] = exercise.copy(sets = exercise.sets.toMutableList().also { list ->
            list[setIdx] = updatedSet
            alsoTick.orEmpty().forEach { i -> list[i] = list[i].copy(isCompleted = true) }
        })
        onUpdateExercises(next)
        if (!nowDone) {
            haptics.tick()
            return
        }
        haptics.confirm()

        if (isSetPersonalRecord(updatedSet, computeExerciseRecords(exercise.name, history), exercise.type)) {
            banner = Banner(tr("New best: {0}, {1}", shown(exercise.name), formatSet(updatedSet, exercise.type)), Icons.Rounded.EmojiEvents, isPr = true)
        }
        if (isEditingPastWorkout) return

        val range = supersetRange(pos)
        if (range.first < range.last) {
            // Superset: rest only once every exercise in the group has done this round.
            val waitingOn = range.firstNotNullOfOrNull { p ->
                val ex = next[active[p].index]
                val s = ex.sets.getOrNull(setIdx) ?: ex.sets.lastOrNull()
                if (s != null && !s.isCompleted) ex.name else null
            }
            if (waitingOn == null) {
                val rest = range.maxOf { p ->
                    val ex = next[active[p].index]
                    ex.sets.getOrNull(setIdx)?.restSeconds?.takeIf { it >= 0 } ?: ex.restSeconds.takeIf { it > 0 } ?: settings.restTimerSeconds
                }
                if (rest > 0) LocalStorage.setRestEndTimeMillis(context, System.currentTimeMillis() + rest * 1000L, fromWidget = true)
            } else {
                if (banner?.isPr != true) banner = Banner(tr("Superset: go straight to {0}", waitingOn), Icons.Rounded.Link, isPr = false)
            }
        } else {
            val rest = setEntry.restSeconds.takeIf { it >= 0 } ?: exercise.restSeconds
            if (rest > 0) LocalStorage.setRestEndTimeMillis(context, System.currentTimeMillis() + rest * 1000L, fromWidget = true)
        }
    }

    fun addFromLibrary(name: String, muscle: String) {
        if (latestExercises.any { it.name.equals(name, ignoreCase = true) }) return
        haptics.tick()
        onAddExercise(name, muscle)
        onUpdateExercises(latestExercises + newExerciseEntry(context, split.title, name, muscle, history, settings))
    }

    // Each card's records only need the workouts that have its exercise.
    val byExercise = remember(history) { historyByExercise(history) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragState = rememberDragDropState(
        listState = listState,
        canReorder = { key -> key is String && key.startsWith(EXERCISE_KEY_PREFIX) },
        onMove = { fromKey, toKey ->
            val from = latestExercises.indexOfFirst { EXERCISE_KEY_PREFIX + it.name == fromKey }
            val to = latestExercises.indexOfFirst { EXERCISE_KEY_PREFIX + it.name == toKey }
            if (from >= 0 && to >= 0) onReorderExercises(from, to)
        },
        onSwap = { haptics.tick() },
        onDrop = { haptics.drop() },
        collapsesOnDrag = true
    )
    val reordering = dragState.isReordering

    // The keypad's target can vanish (set deleted, exercise removed) or move under a drag.
    val editing = editor.target
    LaunchedEffect(editing, exercises, reordering) {
        if (editing != null && (reordering || exercises.getOrNull(editing.exIdx)?.sets?.getOrNull(editing.setIdx) == null)) editor.close()
    }
    BackHandler(enabled = editing != null) { editor.close() }

    CompositionLocalProvider(LocalSetEditor provides editor) {
    Box(backModifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WorkoutTopBar(
                title = shown(split.title),
                isEditing = isEditingPastWorkout,
                startTimeMillis = startTimeMillis,
                doneSets = doneSets,
                totalSets = totalSets,
                liftsWeight = liftsWeight,
                liveTotal = liveTotal,
                onClose = {
                    if (isEditingPastWorkout && hasUnsavedEdits) confirmDiscard = true
                    else if (isEditingPastWorkout) onDiscardWorkout()
                    else onMinimize()
                },
                onFinish = { openFinish() },
                finishModifier = Modifier.tipTarget(workoutTour, "FINISH"),
                onDiscard = { confirmDiscard = true }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isEditingPastWorkout && canCopyLast && previousSplitWorkout != null && doneSets == 0 && !reordering) {
                    item(key = "copy_last") {
                        AppCard(
                            Modifier.fillMaxWidth().animateItem(),
                            onClick = {
                                haptics.tick()
                                onUpdateExercises(latestExercises.map { copyFromPrevious(it, previousSplitWorkout, settings) })
                            },
                            contentPadding = PaddingValues(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).clip(CircleShape).background(P.bgControl), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.ContentCopy, null, tint = P.textSecondary, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(tr("Use last time's numbers"), style = AppType.bodyStrong, color = P.textPrimary)
                                    Text(
                                        tr("Fill in weights and reps from {0}", DateUtils.shortDayOf(previousSplitWorkout.displayDate, previousSplitWorkout.id)),
                                        style = AppType.caption,
                                        color = P.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                itemsIndexed(active, key = { _, iv -> EXERCISE_KEY_PREFIX + iv.value.name }) { pos, (exIdx, exercise) ->
                    val key = EXERCISE_KEY_PREFIX + exercise.name
                    val range = supersetRange(pos)
                    val partners = if (range.first < range.last) range.filter { it != pos }.map { active[it].value.name } else emptyList()
                    val records = remember(exercise.name, byExercise) { computeExerciseRecords(exercise.name, byExercise[ignoreCaseKey(exercise.name)].orEmpty()) }
                    // The tips spotlight the first card; only track it while they're showing.
                    // The tips point at the first card; only it reports its parts' positions.
                    val tour = if (pos == 0) workoutTour else null
                    val prevTour = if (pos == 0) previousTour else null
                    val unit = Units.current
                    // Coaching is about the next session ("190 kg for 8, 6, 7 today…"): it means nothing on a past workout.
                    val suggestion = if (isEditingPastWorkout) null else remember(exercise, history, settings.weightStep, unit) {
                        suggestNextWeight(split.title, exercise, history, settings.weightStep)
                    }
                    val previousSets = remember(exercise.name, exercise.type, previousSplitWorkout, byExercise) {
                        lastSessionSets(exercise.name, exercise.type, previousSplitWorkout, byExercise[ignoreCaseKey(exercise.name)].orEmpty())
                    }
                    val note = remember(exercise.name, infoVersion) { LocalStorage.getExerciseNote(context, exercise.name) }

                    DraggableItem(dragState, key) { isDragging ->
                        ExerciseCard(
                            exercise = exercise,
                            exIdx = exIdx,
                            partners = partners,
                            canLinkNext = pos < active.lastIndex || exercise.isSuperset,
                            records = records,
                            suggestion = suggestion,
                            previousSets = previousSets,
                            note = note,
                            isDragging = isDragging,
                            collapsed = reordering,
                            nextSetIdx = if (!isEditingPastWorkout && nextUp?.first == exercise.name) nextUp.second else -1,
                            splitTitle = split.title,
                            settings = settings,
                            headerModifier = Modifier.longPressDragHandle(dragState, key, onStart = { haptics.pickUp() }),
                            handleModifier = Modifier.dragHandle(dragState, key, onStart = { haptics.pickUp() }),
                            suggestionModifier = Modifier.tipTarget(tour, "SUGGESTION"),
                            firstBadgeModifier = Modifier.tipTarget(tour, "SET_BADGE"),
                            nameModifier = Modifier.tipTarget(tour, "NAME"),
                            tipRowModifier = Modifier.tipTarget(tour, "SET_ROW"),
                            tipPreviousModifier = Modifier.tipTarget(prevTour, "PREVIOUS"),
                            haptics = haptics,
                            onUpdate = { transform -> updateAt(exIdx, transform) },
                            // Only before any of its sets is done: a swap would throw those away.
                            onSwap = if (!isEditingPastWorkout && exercise.sets.none { it.isCompleted }) ({ haptics.tick(); swapping = exercise.name }) else null,
                            onToggleSet = { setIdx -> toggleSet(pos, setIdx) },
                            onSetRest = { setIdx, sec ->
                                haptics.tick()
                                if (range.first < range.last) {
                                    val list = latestExercises.toMutableList()
                                    range.forEach { p ->
                                        val i = active[p].index
                                        list[i] = list[i].copy(sets = list[i].sets.mapIndexed { si, s -> if (si == setIdx) s.copy(restSeconds = sec) else s })
                                    }
                                    onUpdateExercises(list)
                                } else {
                                    updateAt(exIdx) { ex -> ex.copy(sets = ex.sets.mapIndexed { si, s -> if (si == setIdx) s.copy(restSeconds = sec) else s }) }
                                }
                            },
                            onSetExerciseRest = { sec ->
                                haptics.tick()
                                val list = latestExercises.toMutableList()
                                range.forEach { p ->
                                    val i = active[p].index
                                    list[i] = list[i].copy(restSeconds = sec, sets = list[i].sets.map { s -> if (s.intensityType == "DROP") s else s.copy(restSeconds = sec) })
                                    LocalStorage.saveExerciseDefaultRest(context, list[i].name, sec)
                                }
                                onUpdateExercises(list)
                            },
                            onToggleSuperset = {
                                haptics.tick()
                                val list = latestExercises.toMutableList()
                                val linking = !exercise.isSuperset
                                list[exIdx] = exercise.copy(isSuperset = linking)
                                if (linking && pos + 1 in active.indices) {
                                    // Partners share one rest timer.
                                    val pi = active[pos + 1].index
                                    list[pi] = list[pi].copy(restSeconds = exercise.restSeconds, sets = list[pi].sets.map { it.copy(restSeconds = exercise.restSeconds) })
                                }
                                onUpdateExercises(list)
                            },
                            onSkip = {
                                haptics.tick()
                                updateAt(exIdx) { it.copy(isSkipped = true, isSuperset = false) }
                            },
                            onOpenDetails = { onOpenExercise(exercise.name) },
                            onRemove = { haptics.tick(); onRemoveExercisePermanently(exercise.name) }
                        )
                    }
                }

                if (skipped.isNotEmpty() && !reordering) {
                    item(key = "skipped") {
                        AppCard(Modifier.fillMaxWidth().animateItem()) {
                            Text(tr("Skipped today"), style = AppType.headline, color = P.textPrimary)
                            skipped.forEach { (idx, ex) ->
                                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(shown(ex.name), style = AppType.body, color = P.textSecondary, modifier = Modifier.weight(1f))
                                    SecondaryButton(tr("Bring back"), {
                                        haptics.tick()
                                        updateAt(idx) { it.copy(isSkipped = false) }
                                    }, icon = Icons.AutoMirrored.Rounded.Undo, height = 36.dp)
                                }
                            }
                        }
                    }
                }

                if (!reordering) item(key = "add") {
                    SecondaryButton(
                        tr("Add exercise"),
                        { haptics.tick(); showPicker = true },
                        Modifier.fillMaxWidth().animateItem(),
                        icon = Icons.Rounded.Add,
                        height = 52.dp
                    )
                }
            }

            // Kept while it slides away, so it doesn't go blank mid-animation.
            var shownRef by remember { mutableStateOf<FieldRef?>(null) }
            var platesFor by remember { mutableStateOf<FieldRef?>(null) }
            platesFor?.let { ref ->
                val set = exercises.getOrNull(ref.exIdx)?.sets?.getOrNull(ref.setIdx)
                if (set == null) platesFor = null
                else PlateCalculatorSheet(
                    storedKg = set.weight,
                    step = settings.weightStep,
                    onChange = { w -> updateAt(ref.exIdx) { ex -> ex.copy(sets = ex.sets.toMutableList().also { it[ref.setIdx] = it[ref.setIdx].copy(weight = w) }) } },
                    onDismiss = { platesFor = null }
                )
            }
            if (editing != null) shownRef = editing
            AnimatedVisibility(
                visible = editing != null,
                enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it },
                exit = slideOutVertically(tween(180)) { it }
            ) {
                val ref = shownRef
                val exercise = ref?.let { exercises.getOrNull(it.exIdx) }
                if (ref != null && exercise != null && ref.setIdx in exercise.sets.indices) {
                    val set = exercise.sets[ref.setIdx]
                    val fieldName = when (ref.field) {
                        SetField.WEIGHT -> Units.current.label
                        SetField.DISTANCE -> Units.current.distanceLabel
                        SetField.REPS -> tr("reps")
                        SetField.TIME -> tr("time")
                    }
                    val setName = when {
                        set.isWarmupSet -> tr("Warm-up")
                        set.intensityType == "DROP" -> tr("Drop set")
                        else -> tr("Set {0}", exercise.sets.take(ref.setIdx + 1).count { !it.isWarmupSet && it.intensityType != "DROP" })
                    }
                    val next = nextField(ref, exercise)
                    SetKeypad(
                        title = "${shown(exercise.name)} · $setName · $fieldName",
                        field = ref.field,
                        stepText = stepLabel(ref.field, exercise.type, settings.weightStep),
                        hasNext = next != null,
                        onKey = { key ->
                            val typed = typeKey(editor.text, key, ref.field, editor.fresh)
                            if (typed != null) {
                                haptics.tick()
                                editor.text = typed
                                editor.fresh = false
                                updateAt(ref.exIdx) { ex -> ex.copy(sets = ex.sets.toMutableList().also { it[ref.setIdx] = withFieldText(it[ref.setIdx], ref.field, typed) }) }
                            }
                        },
                        onStep = { up ->
                            haptics.tick()
                            val stepped = steppedSet(set, ref.field, exercise.type, up, settings.weightStep)
                            updateAt(ref.exIdx) { ex -> ex.copy(sets = ex.sets.toMutableList().also { it[ref.setIdx] = stepped }) }
                            editor.open(ref, stepped, exercise.type)
                        },
                        onNext = {
                            haptics.tick()
                            next?.let { editor.open(it, exercise.sets[it.setIdx], exercise.type) }
                        },
                        onDone = { haptics.tick(); editor.close() },
                        onPlates = if (ref.field == SetField.WEIGHT && usesPlates(exercise.name, exercise.type)) {
                            { haptics.tick(); editor.close(); platesFor = ref }
                        } else null
                    )
                }
            }
        }

        // Transient banner (new best / superset hint)
        AnimatedVisibility(
            visible = banner != null,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) { -it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)
        ) {
            val b = banner
            if (b != null) {
                AppCard(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    elevated = true,
                    color = if (b.isPr) P.warningSurface else P.bgElevated,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(b.icon, null, tint = if (b.isPr) P.warningText else P.textSecondary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(b.message, style = AppType.bodyStrong, color = P.textPrimary)
                    }
                }
            }
        }

        // Hidden while typing: it would sit on the keypad.
        if (!isEditingPastWorkout && editing == null) {
            RestTimerBar(
                exercises = exercises,
                haptics = haptics,
                modifier = Modifier.align(Alignment.BottomCenter),
                cardModifier = Modifier.tipTarget(restTour, "REST"),
                onShowingChange = { restShowing = it }
            )
        }

        TipTourOverlay(workoutTour, onShowStep = { key -> if (key != "FINISH") scope.launch { listState.animateScrollToItem(0) } })
        TipTourOverlay(restTour)
        TipTourOverlay(previousTour)
    }
    }

    if (showPicker) {
        ExercisePickerSheet(
            existingNames = exercises.map { it.name }.toSet(),
            onPick = { info -> addFromLibrary(info.name, info.muscle) },
            onCreateCustom = { name, type ->
                LocalStorage.setExerciseType(context, name, type)
                addFromLibrary(name, "")
            },
            onDismiss = { showPicker = false }
        )
    }


    swapping?.let { name ->
        val idx = latestExercises.indexOfFirst { it.name == name }
        val current = latestExercises.getOrNull(idx)
        if (current == null) {
            swapping = null
        } else {
            val onDay = remember(split.title, name) {
                split.title != QUICK_WORKOUT_TITLE && LocalStorage.loadTemplateForSplit(context, split.title).any { exerciseKey(it.first) == exerciseKey(name) }
            }
            SwapExerciseSheet(
                exercise = current,
                splitTitle = split.title,
                inWorkout = latestExercises.map { it.name },
                history = history,
                settings = settings,
                equipment = userProfile.equipment,
                canKeep = onDay,
                onDismiss = { swapping = null },
                onSwap = { replacement, keep ->
                    swapping = null
                    haptics.confirm()
                    val day = if (keep) LocalStorage.daySnapshot(context, split.title) else null
                    updateAt(idx) { replacement }
                    if (keep) LocalStorage.replaceInSplitTemplate(context, split.title, current.name, replacement.name, replacement.targetMuscle)
                    // Just for today: remembered, so a swap made every time can be offered for good.
                    else if (onDay) Coach.recordSwap(context, split.title, current.name, replacement.name)
                    AppSnackbar.show(tr("Swapped for {0}", shown(replacement.name)), tr("Undo")) {
                        val at = latestExercises.indexOfFirst { it.name == replacement.name }
                        if (at >= 0) updateAt(at) { current }
                        day?.let { LocalStorage.restoreDay(context, it) }
                        if (!keep && onDay) Coach.undoSwap(context, split.title, current.name, replacement.name)
                    }
                }
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = P.bgElevated,
            title = { Text(if (isEditingPastWorkout) tr("Discard changes?") else tr("Discard this workout?"), style = AppType.headline, color = P.textPrimary) },
            text = {
                Text(
                    if (isEditingPastWorkout) tr("Your edits to this workout won't be saved.") else tr("Nothing from today's session will be saved."),
                    style = AppType.body,
                    color = P.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscardWorkout() }) {
                    Text(tr("Discard"), color = P.dangerText, style = AppType.label)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(tr("Keep going"), color = P.textSecondary, style = AppType.label) } }
        )
    }

    tickingAhead?.let { (pos, setIdx, earlier) ->
        val sets = active.getOrNull(pos)?.value?.sets.orEmpty()
        // Sets are numbered without warm-ups, as on the card.
        fun number(i: Int) = sets.take(i + 1).count { !it.isWarmupSet }
        val names = earlier.map(::number)
        val one = names.size == 1
        AlertDialog(
            onDismissRequest = { tickingAhead = null },
            containerColor = P.bgElevated,
            title = {
                Text(
                    if (one) tr("Set {0} isn't ticked", names[0]) else tr("Sets {0} aren't ticked", names.joinToString(", ")),
                    style = AppType.headline,
                    color = P.textPrimary
                )
            },
            text = { Text(if (one) tr("Did you do it too?") else tr("Did you do them too?"), style = AppType.body, color = P.textSecondary) },
            confirmButton = {
                TextButton(onClick = { tickingAhead = null; toggleSet(pos, setIdx, alsoTick = earlier) }) {
                    Text(if (one) tr("Tick both") else tr("Tick them all"), color = P.accentPrimary, style = AppType.label)
                }
            },
            dismissButton = {
                TextButton(onClick = { tickingAhead = null; toggleSet(pos, setIdx, alsoTick = emptyList()) }) {
                    Text(tr("Just this one"), color = P.textSecondary, style = AppType.label)
                }
            }
        )
    }

    if (showFinish) {
        FinishSheet(
            split = split,
            exercises = exercises,
            skipped = skipped.map { it.value },
            history = history,
            userProfile = userProfile,
            isEditing = isEditingPastWorkout,
            durationSeconds = finalDuration,
            photoPath = photoPath,
            note = note,
            onNoteChange = { note = it },
            onUnskipAll = {
                onUpdateExercises(latestExercises.map { it.copy(isSkipped = false) })
                showFinish = false
            },
            onClearPhoto = { photoPath = null },
            onCamera = {
                val file = PhotoStorage.newCameraFile(context)
                cameraFile = file.absolutePath
                try {
                    cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
                } catch (_: ActivityNotFoundException) {
                    cameraFile = null
                    AppSnackbar.show(tr("No camera app on this phone"))
                }
            },
            onGallery = { galleryLauncher.launch("image/*") },
            onSave = {
                showFinish = false
                onFinishWorkout(finalDuration, photoPath, skipped.map { it.value.name }, note)
            },
            onDismiss = { showFinish = false }
        )
    }
}

private fun copyFromPrevious(current: ExerciseEntry, previous: CompletedWorkout, settings: AppSettings): ExerciseEntry {
    val prev = previous.exercises.find { it.name.equals(current.name, ignoreCase = true) } ?: return current
    return current.copy(
        sets = prev.sets.mapIndexed { idx, s ->
            s.copy(
                setNumber = idx + 1,
                isCompleted = false,
                restSeconds = s.restSeconds.takeIf { it >= 0 } ?: current.restSeconds.takeIf { it > 0 } ?: settings.restTimerSeconds
            )
        }
    )
}

// ============================================================================
// TOP BAR
// ============================================================================

@Composable
private fun WorkoutTopBar(
    title: String,
    isEditing: Boolean,
    startTimeMillis: Long,
    doneSets: Int,
    totalSets: Int,
    /** Volume in kg when anything is lifted with weight, otherwise reps. */
    liftsWeight: Boolean,
    liveTotal: Int,
    onClose: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    finishModifier: Modifier = Modifier
) {
    val P = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(P.bgPrimary).statusBarsPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(
                if (isEditing) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.KeyboardArrowDown,
                if (isEditing) tr("Back") else tr("Minimize workout"),
                onClose,
                container = Color.Transparent
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = AppType.title, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isEditing) Text(tr("Editing a logged workout"), style = AppType.caption, color = P.textSecondary)
            }
            PrimaryButton(if (isEditing) tr("Save") else tr("Finish"), onFinish, finishModifier, height = 40.dp)
            Box {
                CircleIconButton(Icons.Rounded.MoreVert, tr("More options"), { menuOpen = true }, container = Color.Transparent, tint = P.textSecondary)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = P.bgElevated) {
                    DropdownMenuItem(
                        text = { Text(if (isEditing) tr("Discard changes") else tr("Discard workout"), style = AppType.body, color = P.dangerText) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = P.dangerText) },
                        onClick = { menuOpen = false; onDiscard() }
                    )
                }
            }
        }
        // Running totals, readable at a glance between sets.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)) {
            if (!isEditing) LiveStat(tr("Duration"), Modifier.weight(1f)) { ElapsedClock(startTimeMillis) }
            LiveStat(if (liftsWeight) tr("Volume") else tr("Reps"), Modifier.weight(1f)) {
                LiveNumber(if (liftsWeight) formatVolume(liveTotal) else "$liveTotal")
            }
            LiveStat(tr("Sets"), Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    LiveNumber("$doneSets")
                    Text("/$totalSets", style = AppType.label.copy(fontFeatureSettings = "tnum"), color = P.textTertiary, modifier = Modifier.padding(bottom = 1.dp))
                }
            }
        }
        AnimatedProgressBar(
            progress = if (totalSets == 0) 0f else doneSets / totalSets.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            height = 2.dp
        )
    }
}

@Composable
private fun LiveStat(label: String, modifier: Modifier, value: @Composable () -> Unit) {
    val P = LocalPalette.current
    Column(modifier) {
        Text(label, style = AppType.caption, color = P.textSecondary)
        value()
    }
}

@Composable
private fun LiveNumber(text: String, color: Color = LocalPalette.current.textPrimary) {
    Text(text, style = AppType.headline.copy(fontFeatureSettings = "tnum"), color = color, maxLines = 1)
}

@Composable
private fun ElapsedClock(startTimeMillis: Long) {
    val P = LocalPalette.current
    var elapsed by remember { mutableLongStateOf(((System.currentTimeMillis() - startTimeMillis) / 1000L).coerceAtLeast(0L)) }
    LaunchedEffect(startTimeMillis) {
        while (true) {
            delay(1000L)
            elapsed = ((System.currentTimeMillis() - startTimeMillis) / 1000L).coerceAtLeast(0L)
        }
    }
    LiveNumber(formatClock(elapsed), P.accentPrimary)
}

// ============================================================================
// EXERCISE CARD
// ============================================================================

@Composable
private fun ExerciseCard(
    exercise: ExerciseEntry,
    exIdx: Int,
    partners: List<String>,
    canLinkNext: Boolean,
    records: ExerciseRecords,
    suggestion: Suggestion?,
    /** Last session's sets of this exercise, for the Previous column; null hides the column. */
    previousSets: List<SetEntry>?,
    note: String,
    isDragging: Boolean,
    /** A card is being dragged: show only the header so cards are easy to move past each other. */
    collapsed: Boolean,
    /** This card holds the workout's next set; -1 if not. */
    nextSetIdx: Int,
    splitTitle: String,
    settings: AppSettings,
    headerModifier: Modifier,
    handleModifier: Modifier,
    suggestionModifier: Modifier,
    firstBadgeModifier: Modifier,
    nameModifier: Modifier = Modifier,
    /** On the set row the tips point at: the next set, or the first. */
    tipRowModifier: Modifier = Modifier,
    tipPreviousModifier: Modifier = Modifier,
    haptics: Haptics,
    onUpdate: ((ExerciseEntry) -> ExerciseEntry) -> Unit,
    onSwap: (() -> Unit)?,
    onToggleSet: (Int) -> Unit,
    onSetRest: (setIdx: Int, sec: Int) -> Unit,
    onSetExerciseRest: (Int) -> Unit,
    onToggleSuperset: () -> Unit,
    onSkip: () -> Unit,
    onOpenDetails: () -> Unit,
    onRemove: () -> Unit
) {
    val P = LocalPalette.current
    val inSuperset = partners.isNotEmpty()
    var menuOpen by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        when {
            isDragging -> P.accentPrimary
            inSuperset -> P.borderStrong
            else -> Color.Transparent
        }, label = "card_border"
    )

    val workSets = exercise.sets.filter { !it.isWarmupSet }
    val allDone = workSets.isNotEmpty() && workSets.all { it.isCompleted }
    val bottomPad by animateDpAsState(if (collapsed) 6.dp else 12.dp, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow), label = "card_pad")

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, CardShape),
        elevated = isDragging,
        contentPadding = PaddingValues(start = 6.dp, end = 10.dp, top = 6.dp, bottom = bottomPad)
    ) {
        AnimatedVisibility(
            visible = inSuperset && !collapsed,
            enter = fadeIn() + expandVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(120)) + shrinkVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
        ) {
            Row(Modifier.padding(start = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Link, null, tint = P.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Superset with {0}", partners.joinToString(", ")), style = AppType.caption, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Header: handle · name · menu. Long-pressing it drags too, like the handle.
        Row(headerModifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                handleModifier.size(36.dp, 44.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.DragIndicator, tr("Drag to reorder"), tint = if (isDragging) P.accentPrimary else P.textTertiary)
            }
            val restPill = @Composable {
                Box {
                    var restOpen by remember { mutableStateOf(false) }
                    Pill(formatRestShort(exercise.restSeconds), icon = Icons.Rounded.Timer, modifier = Modifier.clip(RoundedCornerShape(50)).pressable { restOpen = true })
                    DropdownMenu(expanded = restOpen, onDismissRequest = { restOpen = false }, containerColor = P.bgElevated) {
                        RestChooser(tr("Rest between sets"), exercise.restSeconds) { restOpen = false; onSetExerciseRest(it) }
                    }
                }
            }
            // With large text the pill would squeeze the name to a few letters a line.
            val pillUnderName = isLargeText() && !collapsed
            ExerciseThumb(
                exercise.name,
                Modifier.padding(end = 10.dp).clip(RoundedCornerShape(10.dp)).pressable(pressedScale = 0.95f, onClick = onOpenDetails),
                size = if (collapsed) 32.dp else 40.dp,
                showEmpty = false
            )
            Column(
                Modifier
                    .weight(1f)
                    .then(nameModifier)
                    .clip(RoundedCornerShape(10.dp))
                    .pressable(pressedScale = 0.99f, onClick = onOpenDetails)
                    .padding(vertical = 2.dp, horizontal = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shown(exercise.name),
                        style = AppType.headline,
                        color = P.textPrimary,
                        maxLines = if (collapsed) 1 else if (isLargeText()) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // The name opens the exercise's page: say so without a separate button.
                    if (!collapsed) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
                    AnimatedVisibility(allDone, enter = scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium), initialScale = 0.6f) + fadeIn(), exit = scaleOut() + fadeOut()) {
                        Box(Modifier.padding(start = 6.dp).size(18.dp).clip(CircleShape).background(P.successSurface), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, tr("All sets done"), tint = P.successText, modifier = Modifier.size(12.dp))
                        }
                    }
                }
                val sub = if (collapsed) {
                    val done = exercise.sets.count { it.isCompleted }
                    listOfNotNull(exercise.targetMuscle.ifBlank { null }?.let(::shown), tr("{0} of {1} sets", done, exercise.sets.size)).joinToString(" · ")
                } else buildList {
                    if (exercise.targetMuscle.isNotBlank()) add(shown(exercise.targetMuscle))
                    exercise.target?.let { add(tr("Plan {0}", it.label)) }
                    records.bestSet?.let { add(tr("Best {0}", formatSet(it, exercise.type))) }
                }.joinToString(" · ") { unbroken(it) }
                // Two lines when open: the plan ("Plan 3 × 8–12") was the part cut off.
                if (sub.isNotEmpty()) Text(sub, style = AppType.caption, color = P.textSecondary, maxLines = if (collapsed) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (pillUnderName) Box(Modifier.padding(top = 6.dp)) { restPill() }
            }
            if (!pillUnderName) restPill()
            Box {
                CircleIconButton(Icons.Rounded.MoreVert, tr("Exercise options"), { menuOpen = true }, size = 36.dp, container = Color.Transparent, tint = P.textSecondary)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = P.bgElevated) {
                    if (canLinkNext) {
                        DropdownMenuItem(
                            text = { Text(if (exercise.isSuperset) tr("Unlink from next") else tr("Superset with next"), style = AppType.body) },
                            leadingIcon = { Icon(if (exercise.isSuperset) Icons.Rounded.LinkOff else Icons.Rounded.Link, null) },
                            onClick = { menuOpen = false; onToggleSuperset() }
                        )
                    }
                    if (suggestion != null && !suggestion.forNextTime && exercise.type.hasWeightSuggestions) {
                        DropdownMenuItem(
                            text = { Text(tr("Go lighter for the rest"), style = AppType.body) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TrendingDown, null) },
                            onClick = {
                                menuOpen = false
                                haptics.tick()
                                onUpdate { ex ->
                                    ex.copy(sets = ex.sets.map { s ->
                                        if (s.isCompleted || s.isWarmupSet) s
                                        else s.copy(weight = lighterWeight(s.weight, settings.weightStep))
                                    })
                                }
                            }
                        )
                    }
                    if (exercise.type == ExerciseType.WEIGHT_REPS && exercise.sets.none { it.isWarmupSet && it.isCompleted }) {
                        val context = LocalContext.current
                        DropdownMenuItem(
                            text = { Text(tr("Warm-up sets"), style = AppType.body) },
                            leadingIcon = { Icon(Icons.Rounded.Whatshot, null) },
                            onClick = {
                                menuOpen = false
                                haptics.tick()
                                val bar = if (usesPlates(exercise.name, exercise.type)) LocalStorage.loadPlateSetup(context, Units.current).barWeight else null
                                onUpdate { ex -> withWarmups(ex, settings.weightStep, bar) }
                            }
                        )
                    }
                    if (onSwap != null) {
                        DropdownMenuItem(
                            text = { Text(tr("Machine taken? Swap it"), style = AppType.body) },
                            leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) },
                            onClick = { menuOpen = false; onSwap() }
                        )
                    }
                    HorizontalDivider(color = P.borderSubtle)
                    DropdownMenuItem(
                        text = { Text(tr("Skip for today"), style = AppType.body) },
                        leadingIcon = { Icon(Icons.Rounded.SkipNext, null) },
                        onClick = { menuOpen = false; onSkip() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Remove from {0}", shown(splitTitle)), style = AppType.body, color = P.dangerText) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = P.dangerText) },
                        onClick = { menuOpen = false; onRemove() }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter = fadeIn(tween(220, delayMillis = 80)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(100)) + shrinkVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
        ) {
        Column(Modifier.padding(start = 10.dp).animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))) {
            exercise.planNote?.let {
                Text(it, style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            if (note.isNotBlank()) {
                Row(
                    Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(P.bgControl).pressable(onClick = onOpenDetails).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PushPin, null, tint = P.textSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(note, style = AppType.caption, color = P.textPrimary)
                }
            }

            if (suggestion != null) SuggestionLine(
                suggestion = suggestion,
                alreadyApplied = parseWeightClean(suggestion.suggestedWeight, -1f).let { w ->
                    exercise.sets.filter { !it.isCompleted && !it.isWarmupSet }.let { todo -> todo.isNotEmpty() && todo.all { it.weightKg == w } }
                },
                modifier = suggestionModifier.padding(top = 8.dp),
                onUse = {
                    haptics.tick()
                    onUpdate { ex -> ex.copy(sets = ex.sets.map { s -> if (s.isCompleted || s.isWarmupSet) s else s.copy(weight = suggestion.suggestedWeight) }) }
                }
            )

            Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Set"), style = AppType.caption, color = P.textTertiary, modifier = Modifier.width(40.dp))
                if (previousSets != null) {
                    Text(tr("Previous"), style = AppType.caption, color = P.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.width(PREVIOUS_WIDTH))
                }
                val unit = Units.current
                val (first, second) = when (exercise.type) {
                    ExerciseType.WEIGHT_REPS -> unit.label to tr("Reps")
                    ExerciseType.WEIGHTED_BODYWEIGHT -> tr("Added {0}", unit.label) to tr("Reps")
                    ExerciseType.ASSISTED -> tr("Assist {0}", unit.label) to tr("Reps")
                    ExerciseType.BODYWEIGHT -> null to tr("Reps")
                    ExerciseType.DURATION -> null to tr("Time")
                    ExerciseType.DISTANCE_TIME -> unit.distanceLabel to tr("Time")
                }
                if (first != null) {
                    Text(first, style = AppType.caption, color = P.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.weight(1.25f))
                    Spacer(Modifier.width(8.dp))
                }
                Text(second, style = AppType.caption, color = P.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(52.dp))
            }

            var workNumber = 0
            val tipRow = if (nextSetIdx >= 0) nextSetIdx else 0
            exercise.sets.forEachIndexed { setIdx, set ->
                if (set.intensityType == "NORMAL" && !set.isWarmup) workNumber++
                SetRow(
                    rowModifier = if (setIdx == tipRow) tipRowModifier else Modifier,
                    previousModifier = if (setIdx == tipRow) tipPreviousModifier else Modifier,
                    set = set,
                    refFor = { field -> FieldRef(exIdx, setIdx, field) },
                    isNext = setIdx == nextSetIdx,
                    workNumber = workNumber,
                    previous = previousSets?.let { previousSetFor(exercise.sets, setIdx, it) },
                    showPrevious = previousSets != null,
                    isPr = isSetPersonalRecord(set, records, exercise.type),
                    type = exercise.type,
                    canDelete = exercise.sets.size > 1,
                    badgeModifier = if (setIdx == 0) firstBadgeModifier else Modifier,
                    step = settings.weightStep,
                    usesPlates = usesPlates(exercise.name, exercise.type),
                    onChange = { transform ->
                        onUpdate { ex -> ex.copy(sets = ex.sets.toMutableList().also { it[setIdx] = transform(it[setIdx]) }) }
                    },
                    onSetType = { type ->
                        haptics.tick()
                        onUpdate { ex -> ex.copy(sets = ex.sets.toMutableList().also { it[setIdx] = it[setIdx].copy(intensityType = type, isWarmup = type == "WARMUP") }) }
                    },
                    onSetRest = { sec -> onSetRest(setIdx, sec) },
                    onDelete = {
                        haptics.tick()
                        val removed = exercise.sets[setIdx]
                        onUpdate { ex -> ex.copy(sets = ex.sets.filterIndexed { i, _ -> i != setIdx }.mapIndexed { i, s -> s.copy(setNumber = i + 1) }) }
                        AppSnackbar.show(tr("Set deleted"), tr("Undo")) {
                            onUpdate { ex ->
                                val sets = ex.sets.toMutableList().apply { add(setIdx.coerceAtMost(size), removed) }
                                ex.copy(sets = sets.mapIndexed { i, s -> s.copy(setNumber = i + 1) })
                            }
                        }
                    },
                    onToggle = { onToggleSet(setIdx) },
                    haptics = haptics
                )
                // Effort: asked under the set just ticked, until it's answered.
                if (settings.trackEffort && exercise.type.usesReps) {
                    EffortPicker(
                        visible = setIdx == exercise.sets.indexOfLast { it.isCompleted } && !set.isWarmupSet && set.rir < 0,
                        onPick = { rir ->
                            haptics.tick()
                            onUpdate { ex -> ex.copy(sets = ex.sets.toMutableList().also { it[setIdx] = it[setIdx].copy(rir = rir) }) }
                        }
                    )
                }
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Add set"), {
                    haptics.tick()
                    onUpdate { ex ->
                        val last = ex.sets.lastOrNull()
                        ex.copy(sets = ex.sets + SetEntry(
                            setNumber = ex.sets.size + 1,
                            weight = last?.weight ?: if (ex.type.usesWeight) "20" else "",
                            reps = last?.reps ?: if (ex.type.usesReps) "8" else "",
                            restSeconds = last?.restSeconds?.takeIf { it >= 0 && last.intensityType != "DROP" } ?: ex.restSeconds,
                            seconds = last?.seconds.orEmpty(),
                            distanceM = last?.distanceM.orEmpty()
                        ))
                    }
                }, Modifier.weight(1f), icon = Icons.Rounded.Add, height = 40.dp)
            }
        }
        }
    }
}

@Composable
private fun RestChooser(label: String, current: Int, onPick: (Int) -> Unit) {
    val P = LocalPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = AppType.caption, color = P.textSecondary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            REST_PRESETS.forEach { sec ->
                val sel = sec == current
                Text(
                    formatRestShort(sec),
                    style = AppType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = if (sel) P.onAccentPrimary else P.textPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) P.accentPrimary else P.bgControl)
                        .pressable(pressedScale = 0.9f) { onPick(sec) }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * Coaching advice as one quiet line: a small tone icon, the advice, and "Use …" only when the
 * sets to do aren't already at that weight. "Go lighter" lives in the exercise menu.
 */
@Composable
private fun SuggestionLine(suggestion: Suggestion, alreadyApplied: Boolean, modifier: Modifier, onUse: () -> Unit) {
    val P = LocalPalette.current
    val (_, fg) = toneColors(suggestion.tone, P)
    val icon = when (suggestion.tone) {
        SuggestionTone.GOOD -> Icons.AutoMirrored.Rounded.TrendingUp
        SuggestionTone.CAUTION -> Icons.AutoMirrored.Rounded.TrendingDown
        SuggestionTone.NEUTRAL -> Icons.AutoMirrored.Rounded.TrendingFlat
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(suggestion.message, style = AppType.caption, color = P.textSecondary, modifier = Modifier.weight(1f))
        if (!suggestion.forNextTime && suggestion.suggestedWeight.isNotBlank() && !alreadyApplied) {
            Spacer(Modifier.width(8.dp))
            Text(
                tr("Use {0}", formatWeightKg(parseWeightClean(suggestion.suggestedWeight, 0f))),
                style = AppType.label,
                color = P.textPrimary,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(P.bgControl).pressable(onClick = onUse).padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

/** "Reps left? 0 1 2 3 4+", sliding in under a set just ticked; a tap answers and folds it away. */
@Composable
private fun EffortPicker(visible: Boolean, onPick: (Int) -> Unit) {
    val P = LocalPalette.current
    AnimatedVisibility(visible, enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Reps left?"), style = AppType.caption, color = P.textSecondary, modifier = Modifier.weight(1f))
            (0..4).forEach { n ->
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(P.bgControl)
                        .pressable(pressedScale = 0.88f) { onPick(n) }
                        .semantics { contentDescription = effortText(n) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (n == 4) "4+" else "$n", style = AppType.label, color = P.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun SetRow(
    set: SetEntry,
    refFor: (SetField) -> FieldRef,
    isNext: Boolean,
    workNumber: Int,
    /** The matching set last time, if there was one. */
    previous: SetEntry?,
    showPrevious: Boolean,
    rowModifier: Modifier = Modifier,
    previousModifier: Modifier = Modifier,
    isPr: Boolean,
    canDelete: Boolean,
    badgeModifier: Modifier,
    type: ExerciseType,
    step: Float,
    usesPlates: Boolean,
    onChange: ((SetEntry) -> SetEntry) -> Unit,
    onSetType: (String) -> Unit,
    onSetRest: (Int) -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    haptics: Haptics
) {
    val P = LocalPalette.current
    val rowBg by animateColorAsState(if (set.isCompleted) P.successSurface else Color.Transparent, tween(250), label = "set_row")
    val rowBorder by animateColorAsState(if (isNext && !set.isCompleted) P.accentPrimary.copy(alpha = 0.55f) else Color.Transparent, tween(300), label = "set_next")
    var menuOpen by remember { mutableStateOf(false) }
    var platesOpen by remember { mutableStateOf(false) }
    val (badgeText, badgeFg, badgeBg) = when {
        set.isWarmupSet -> Triple(tr("W"), P.warningText, P.warningSurface)
        set.intensityType == "DROP" -> Triple(tr("D"), P.dangerText, P.dangerSurface)
        set.intensityType == "REST_PAUSE" -> Triple(tr("RP"), P.textSecondary, P.bgControl)
        else -> Triple("$workNumber", P.textPrimary, if (set.isCompleted) Color.Transparent else P.bgControl)
    }

    Row(
        rowModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .border(1.5.dp, rowBorder, RoundedCornerShape(12.dp))
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(badgeModifier.width(40.dp)) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(badgeBg)
                    .pressable(pressedScale = 0.88f) { haptics.tick(); menuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                if (isPr && set.intensityType == "NORMAL") Icon(Icons.Rounded.EmojiEvents, tr("Personal best"), tint = P.warningText, modifier = Modifier.size(16.dp))
                else Text(badgeText, style = AppType.label.copy(fontWeight = FontWeight.SemiBold), color = badgeFg)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = P.bgElevated) {
                // Drop sets and rest-pause only mean something when there's a load to change.
                val kinds = if (type.hasWeightSuggestions) listOf("NORMAL" to tr("Working set"), "WARMUP" to tr("Warm-up"), "DROP" to tr("Drop set"), "REST_PAUSE" to tr("Rest-pause"))
                else listOf("NORMAL" to tr("Working set"), "WARMUP" to tr("Warm-up"))
                kinds.forEach { (kind, label) ->
                    val selected = if (kind == "WARMUP") set.isWarmupSet else set.intensityType == kind && !set.isWarmupSet
                    DropdownMenuItem(
                        text = { Text(label, style = AppType.body) },
                        trailingIcon = { if (selected) Icon(Icons.Rounded.Check, null, tint = P.accentPrimary) },
                        onClick = { menuOpen = false; onSetType(kind) }
                    )
                }
                HorizontalDivider(color = P.borderSubtle)
                RestChooser(tr("Rest after this set"), set.restSeconds) { menuOpen = false; onSetRest(it) }
                if (usesPlates) {
                    HorizontalDivider(color = P.borderSubtle)
                    DropdownMenuItem(
                        text = { Text(tr("Plate calculator"), style = AppType.body) },
                        leadingIcon = { Icon(Icons.Rounded.FitnessCenter, null, tint = P.textSecondary) },
                        onClick = { menuOpen = false; platesOpen = true }
                    )
                }
                if (canDelete) {
                    HorizontalDivider(color = P.borderSubtle)
                    DropdownMenuItem(
                        text = { Text(tr("Delete set"), style = AppType.body, color = P.dangerText) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = P.dangerText) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }

        if (showPrevious) PreviousCell(previous, type, previousModifier) { p ->
            haptics.tick()
            onChange { it.copy(weight = p.weight, reps = p.reps, seconds = p.seconds, distanceM = p.distanceM) }
        }

        // Same widths as the column labels above: the first box is wider (weights have decimals).
        val fields = fieldsFor(type)
        fields.forEachIndexed { i, field ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            SetValueBox(refFor(field), set, type, Modifier.weight(if (i == 0 && fields.size > 1) 1.25f else 1f))
        }
        Spacer(Modifier.width(8.dp))
        CheckButton(done = set.isCompleted, onClick = onToggle)
    }

    if (platesOpen) {
        PlateCalculatorSheet(
            storedKg = set.weight,
            step = step,
            onChange = { w -> onChange { it.copy(weight = w) } },
            onDismiss = { platesOpen = false }
        )
    }
}

private val PREVIOUS_WIDTH = 68.dp

/** Last time's numbers for this set, quiet; a tap copies them into the set. "—" if there was none. */
@Composable
private fun PreviousCell(previous: SetEntry?, type: ExerciseType, modifier: Modifier = Modifier, onCopy: (SetEntry) -> Unit) {
    val P = LocalPalette.current
    val text = previous?.let { formatSetShort(it, type) } ?: "—"
    Box(
        modifier
            .width(PREVIOUS_WIDTH)
            .height(SET_FIELD_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .then(if (previous != null) Modifier.pressable(pressedScale = 0.92f) { onCopy(previous) } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = AppType.caption.copy(fontFeatureSettings = "tnum"),
            color = P.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun CheckButton(done: Boolean, onClick: () -> Unit) {
    val P = LocalPalette.current
    // Done is green, like its row: an orange tick on a green row read as two different meanings.
    val bg by animateColorAsState(if (done) P.successText else P.bgControl, tween(200), label = "check_bg")
    val fg by animateColorAsState(if (done) P.bgCard else P.textTertiary, tween(200), label = "check_fg")
    val pop = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(done) }
    LaunchedEffect(done) {
        if (done && !previous) {
            // A small, firm press: it lands rather than wobbles.
            pop.animateTo(1.08f, spring(stiffness = Spring.StiffnessHigh))
            pop.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium))
        }
        previous = done
    }
    Box(
        Modifier
            .size(44.dp, SET_FIELD_HEIGHT)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .pressable(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Check, if (done) tr("Mark set not done") else tr("Mark set done"), tint = fg, modifier = Modifier.size(22.dp))
    }
}

// ============================================================================
// REST TIMER
// ============================================================================

/** Floating rest countdown. Reads the shared rest end time, so widget and lock-screen changes show here too. */
@Composable
private fun RestTimerBar(
    exercises: List<ExerciseEntry>,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    onShowingChange: (Boolean) -> Unit = {}
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    var endMillis by remember { mutableLongStateOf(0L) }
    var totalMillis by remember { mutableLongStateOf(1L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            val end = LocalStorage.getRestEndTimeMillis(context)
            val t = System.currentTimeMillis()
            if (end != endMillis) {
                // Extending a running rest grows the total, so the ring doesn't jump back to full.
                totalMillis = if (endMillis > t && end > t) totalMillis + (end - endMillis) else (end - t).coerceAtLeast(1L)
                endMillis = end
            }
            now = t
            delay(100)
        }
    }

    val remaining = (endMillis - now).coerceAtLeast(0L)
    val visible = remaining > 0L
    LaunchedEffect(visible) { onShowingChange(visible) }
    val next = remember(exercises) {
        exercises.firstOrNull { ex -> !ex.isSkipped && ex.sets.any { !it.isCompleted } }?.let { ex ->
            "${shown(ex.name)} · ${formatSet(ex.sets[ex.nextSetIndex()], ex.type)}"
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
        modifier = modifier
    ) {
        AppCard(
            Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().then(cardModifier),
            elevated = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    progress = remaining / totalMillis.toFloat(),
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 5.dp,
                    color = P.accentPrimary
                ) {
                    Text(formatClock((remaining + 999) / 1000), style = AppType.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = P.textPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Resting"), style = AppType.bodyStrong, color = P.textPrimary)
                    if (next != null) Text(tr("Next: {0}", next), style = AppType.caption, color = P.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "−15",
                    style = AppType.label,
                    color = P.textPrimary,
                    modifier = Modifier.clip(CircleShape).background(P.bgControl).pressable {
                        haptics.tick()
                        val newEnd = endMillis - 15_000L
                        LocalStorage.setRestEndTimeMillis(context, if (newEnd - System.currentTimeMillis() < 1000) 0L else newEnd, fromWidget = true)
                    }.padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "+15",
                    style = AppType.label,
                    color = P.textPrimary,
                    modifier = Modifier.clip(CircleShape).background(P.bgControl).pressable {
                        haptics.tick()
                        LocalStorage.setRestEndTimeMillis(context, endMillis + 15_000L, fromWidget = true)
                    }.padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(6.dp))
                CircleIconButton(Icons.Rounded.SkipNext, tr("Skip rest"), {
                    haptics.tick()
                    LocalStorage.setRestEndTimeMillis(context, 0L, fromWidget = true)
                }, size = 36.dp, container = P.bgControl, tint = P.textPrimary)
            }
        }
    }
}

// ============================================================================
// SHEETS
// ============================================================================

@Composable
private fun FinishSheet(
    split: WorkoutSplit,
    exercises: List<ExerciseEntry>,
    skipped: List<ExerciseEntry>,
    history: List<CompletedWorkout>,
    userProfile: UserProfile,
    isEditing: Boolean,
    durationSeconds: Long,
    photoPath: String?,
    note: String,
    onNoteChange: (String) -> Unit,
    onUnskipAll: () -> Unit,
    onClearPhoto: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    // Counted like the saved workout will be: the ticked sets only (an edit keeps every set).
    val counted = remember(exercises, isEditing) { if (isEditing) exercises.filter { !it.isSkipped } else performedExercises(exercises) }
    val stats = remember(counted, history) { computeWorkoutStats(counted, history) }
    // Each exercise's best new record, judged like the "New bests" count.
    val byExercise = remember(history) { historyByExercise(history) }
    val bests = remember(counted, history) {
        counted.mapNotNull { ex ->
            val records = computeExerciseRecords(ex.name, byExercise[ignoreCaseKey(ex.name)].orEmpty())
            bestSetOf(ex.sets.filter { isSetPersonalRecord(it, records, ex.type) }, ex.type)?.let { ex to it }
        }
    }
    val previous = remember(history, split.title) {
        if (isEditing) null else history.filter { it.splitTitle == split.title }.maxByOrNull { it.id }
    }
    val celebrate = !isEditing && stats.prCount > 0

    AppBottomSheet(onDismiss = onDismiss) {
        Box {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            isEditing -> tr("Save changes")
                            celebrate -> if (stats.prCount == 1) tr("New personal best") else tr("{0} new personal bests", stats.prCount)
                            else -> tr("Nice work")
                        },
                        style = AppType.display,
                        color = P.textPrimary
                    )
                    Text(
                        if (isEditing) shown(split.title) else tr("That's {0} done.", shown(split.title)),
                        style = AppType.body,
                        color = P.textSecondary
                    )
                }
                if (!isEditing) FinishBadge(trophy = celebrate)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountUpTile(durationSeconds.toFloat(), { formatDuration(it.toLong()) }, tr("Time"), Modifier.weight(1f).enterAnimation(1), delayMillis = 150)
                CountUpTile(stats.workSets.toFloat(), { "${it.roundToInt()}" }, tr("Sets"), Modifier.weight(1f).enterAnimation(2), delayMillis = 200)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stats.volumeKg > 0) CountUpTile(stats.volumeKg.toFloat(), { formatVolume(it.roundToInt()) }, tr("Volume"), Modifier.weight(1f).enterAnimation(3), delayMillis = 250)
                else CountUpTile(totalWorkReps(counted).toFloat(), { "${it.roundToInt()}" }, tr("Reps"), Modifier.weight(1f).enterAnimation(3), delayMillis = 250)
                CountUpTile(
                    stats.prCount.toFloat(), { "${it.roundToInt()}" }, tr("New bests"), Modifier.weight(1f).enterAnimation(4), delayMillis = 300,
                    valueColor = if (stats.prCount > 0) P.warningText else null
                )
            }

            val prevVolume = previous?.totalVolumeKg ?: 0
            if (prevVolume > 0 && stats.volumeKg > 0) {
                val pct = ((stats.volumeKg - prevVolume) * 100f / prevVolume).roundToInt()
                val up = pct > 0
                Row(Modifier.padding(top = 10.dp).enterAnimation(5), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (up) Icons.AutoMirrored.Rounded.TrendingUp else if (pct < 0) Icons.AutoMirrored.Rounded.TrendingDown else Icons.AutoMirrored.Rounded.TrendingFlat,
                        null,
                        tint = if (up) P.successText else P.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            up -> tr("{0}% more volume than your last {1}", pct, shown(split.title))
                            pct < 0 -> tr("{0}% less volume than your last {1}", -pct, shown(split.title))
                            else -> tr("Same volume as your last {0}", shown(split.title))
                        },
                        style = AppType.label,
                        color = if (up) P.successText else P.textSecondary
                    )
                }
            }

            if (bests.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier.fillMaxWidth().enterAnimation(6).clip(RoundedCornerShape(16.dp)).background(P.warningSurface).padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(tr("New bests"), style = AppType.overline, color = P.warningText)
                    bests.forEach { (ex, set) ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.EmojiEvents, null, tint = P.warningText, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(shown(ex.name), style = AppType.body, color = P.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(formatSet(set, ex.type), style = AppType.bodyStrong.copy(fontFeatureSettings = "tnum"), color = P.textPrimary)
                        }
                    }
                }
            }

            if (skipped.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.warningSurface).padding(14.dp)) {
                    Text(tr("You skipped {0}: {1}", skipped.size, skipped.joinToString(", ") { shown(it.name) }), style = AppType.label.copy(fontWeight = FontWeight.Normal), color = P.textPrimary)
                    Text(
                        tr("Do them now"),
                        style = AppType.label,
                        color = P.warningText,
                        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).pressable(onClick = onUnskipAll).padding(vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            NoteField(note, onNoteChange)
            Spacer(Modifier.height(16.dp))
            val preview = remember(photoPath) { PhotoStorage.loadImageBitmap(photoPath) }
            if (preview != null) {
                Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp))) {
                    Image(preview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    CircleIconButton(
                        Icons.Rounded.Close, tr("Remove photo"), onClearPhoto,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        size = 32.dp, container = Color.Black.copy(alpha = 0.55f), tint = Color.White
                    )
                }
            } else {
                Text(tr("Add a photo (optional)"), style = AppType.label, color = P.textSecondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(tr("Camera"), onCamera, Modifier.weight(1f), icon = Icons.Rounded.PhotoCamera)
                    SecondaryButton(tr("Gallery"), onGallery, Modifier.weight(1f), icon = Icons.Rounded.Image)
                }
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(if (isEditing) tr("Save changes") else tr("Save workout"), onSave, Modifier.fillMaxWidth(), icon = Icons.Rounded.Check)
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                tr("Share"),
                {
                    shareWorkoutCardImage(split.title, durationSeconds, exercises, history, photoPath)
                },
                Modifier.fillMaxWidth(),
                icon = Icons.Rounded.IosShare
            )
        }
        }
    }
}

/** A check (or a trophy for new bests) that pops in with a little twist. */
@Composable
private fun FinishBadge(trophy: Boolean) {
    val P = LocalPalette.current
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120)
        pop.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        Modifier
            .size(60.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = (1f - pop.value) * -35f
            }
            .clip(CircleShape)
            .background(if (trophy) P.warningSurface else P.successSurface),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (trophy) Icons.Rounded.EmojiEvents else Icons.Rounded.Check,
            null,
            tint = if (trophy) P.warningText else P.successText,
            modifier = Modifier.size(32.dp)
        )
    }
}


/** How the workout went, in a line or two: shown on its History card. */
@Composable
private fun NoteField(note: String, onChange: (String) -> Unit) {
    val P = LocalPalette.current
    Text(tr("Note (optional)"), style = AppType.label, color = P.textSecondary)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = note,
        onValueChange = { if (it.length <= 500) onChange(it) },
        placeholder = { Text(tr("How did it go? Anything to remember next time?"), style = AppType.body, color = P.textTertiary) },
        textStyle = AppType.body.copy(color = P.textPrimary),
        minLines = 2,
        maxLines = 5,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = P.accentPrimary, unfocusedBorderColor = P.borderStrong),
        modifier = Modifier.fillMaxWidth()
    )
}


/**
 * Replaces the exercise's warm-ups with a ramp to its first work set: the empty bar for bar lifts,
 * then about 50%, 70% and 85% for fewer and fewer reps (a common pattern: warm, not tired).
 * Weights round down to [stepDisplay]; steps that come out at the bar or at the work weight are left out.
 * [barDisplay] is the bar in the display unit, null when there's no bar.
 */
internal fun withWarmups(ex: ExerciseEntry, stepDisplay: Float, barDisplay: Float?): ExerciseEntry {
    val unit = Units.current
    val work = ex.sets.firstOrNull { !it.isWarmupSet && it.intensityType == "NORMAL" } ?: return ex
    val workDisplay = unit.fromKg(work.weightKg)
    if (workDisplay <= 0f) return ex
    fun down(v: Float) = if (stepDisplay > 0f) (v / stepDisplay).toInt() * stepDisplay else v
    val ramp = buildList {
        if (barDisplay != null && barDisplay > 0f && workDisplay > barDisplay * 1.5f) add(barDisplay to 10)
        listOf(0.5f to 5, 0.7f to 3, 0.85f to 1).forEach { (pct, reps) ->
            val w = down(workDisplay * pct)
            if (w > (lastOrNull()?.first ?: 0f) && w < workDisplay && (barDisplay == null || w > barDisplay)) add(w to reps)
        }
    }
    if (ramp.isEmpty()) return ex
    val rest = minOf(60, work.restSeconds.takeIf { it > 0 } ?: ex.restSeconds)
    val warmups = ramp.map { (w, reps) ->
        SetEntry(0, weight = unit.stored(w), reps = "$reps", isWarmup = true, intensityType = "WARMUP", restSeconds = rest)
    }
    // Done warm-ups stay; unticked ones are replaced.
    val kept = ex.sets.filter { !(it.isWarmupSet && !it.isCompleted) }
    return ex.copy(sets = (warmups + kept).mapIndexed { i, s -> s.copy(setNumber = i + 1) })
}

/**
 * [text] that wraps only as a whole: no line break at its spaces or dashes, so "Plan 3 × 8–12"
 * never ends a line at "3 ×" (a long name used to push half of it onto the next line).
 */
internal fun unbroken(text: String): String =
    text.replace(' ', '\u00A0').replace("–", "\u2060–\u2060").replace("-", "\u2060-\u2060")
