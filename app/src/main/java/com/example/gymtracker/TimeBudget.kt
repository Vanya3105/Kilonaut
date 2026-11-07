package com.example.gymtracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Time under the bar per set, and getting to the next exercise (moving, loading, the first setup).
private const val WORK_SET_SECONDS = 45
private const val WARMUP_SET_SECONDS = 30
private const val WARMUP_REST_SECONDS = 60
private const val CHANGEOVER_SECONDS = 90

/** About how long [exercises] take: every set, the rests between them, and moving between exercises. */
internal fun estimateSeconds(exercises: List<ExerciseEntry>): Int = exercises.filter { !it.isSkipped }.sumOf { ex ->
    val sets = ex.sets
    sets.withIndex().sumOf { (i, s) ->
        val work = when {
            s.secondCount > 0 -> s.secondCount
            s.isWarmupSet -> WARMUP_SET_SECONDS
            else -> WORK_SET_SECONDS
        }
        val rest = if (i == sets.lastIndex) 0 else if (s.isWarmupSet) minOf(s.restSeconds.coerceAtLeast(0), WARMUP_REST_SECONDS)
        else (s.restSeconds.takeIf { it >= 0 } ?: ex.restSeconds).coerceAtLeast(0)
        work + rest
    } + CHANGEOVER_SECONDS
}

internal fun estimateMinutes(exercises: List<ExerciseEntry>): Int = (estimateSeconds(exercises) / 60f).roundToInt()

/** What a shorter session changed about one exercise, for the sheet to show. */
internal sealed class Cut(val name: String) {
    class Dropped(name: String) : Cut(name)
    class FewerSets(name: String, val from: Int, val to: Int) : Cut(name)
    class NoWarmups(name: String) : Cut(name)
}

/**
 * [exercises] cut to fit [minutes]: warm-ups first (the first exercise keeps one), then sets from
 * isolation work and the later exercises (to two each), then the main lifts' sets (to two), then
 * whole exercises from the end, isolation first. The main lifts stay as long as anything can go.
 */
internal fun trimToMinutes(exercises: List<ExerciseEntry>, minutes: Int): Pair<List<ExerciseEntry>, List<Cut>> {
    val budget = minutes * 60
    val list = exercises.filter { !it.isSkipped }.toMutableList()
    fun fits() = estimateSeconds(list) <= budget
    fun work(ex: ExerciseEntry) = ex.sets.count { !it.isWarmupSet }

    // 1. Warm-ups: all but one, on the first exercise only.
    if (!fits()) {
        list.forEachIndexed { i, ex ->
            val warmups = ex.sets.filter { it.isWarmupSet }
            val keep = if (i == 0) warmups.takeLast(1) else emptyList()
            if (warmups.size != keep.size) list[i] = ex.copy(sets = (keep + ex.sets.filter { !it.isWarmupSet }).mapIndexed { n, s -> s.copy(setNumber = n + 1) })
        }
    }
    // Isolation and later exercises give up sets before the first two (the program's main lifts).
    fun isMain(i: Int) = i < 2 && !isIsolation(list[i].name)
    fun takeSets(main: Boolean, floor: Int) {
        while (!fits()) {
            val i = list.indices.filter { isMain(it) == main && work(list[it]) > floor }
                .maxWithOrNull(compareBy<Int> { work(list[it]) }.thenBy { it }) ?: return
            list[i] = withWorkSets(list[i], work(list[i]) - 1)
        }
    }
    takeSets(main = false, floor = 2)
    takeSets(main = true, floor = 2)
    // Whole exercises, from the end, isolation first; at least two stay.
    while (!fits() && list.size > 2) {
        val i = list.indices.lastOrNull { isIsolation(list[it].name) && !isMain(it) } ?: list.indices.last { !isMain(it) || it == list.lastIndex }
        list.removeAt(i)
    }
    takeSets(main = false, floor = 1)
    takeSets(main = true, floor = 1)

    val kept = list.associateBy { exerciseKey(it.name) }
    val cuts = exercises.filter { !it.isSkipped }.mapNotNull { before ->
        val after = kept[exerciseKey(before.name)] ?: return@mapNotNull Cut.Dropped(before.name)
        when {
            work(after) < work(before) -> Cut.FewerSets(before.name, work(before), work(after))
            after.sets.count { it.isWarmupSet } < before.sets.count { it.isWarmupSet } -> Cut.NoWarmups(before.name)
            else -> null
        }
    }
    return list to cuts
}

/** "How long do you have?": pick a time, see what changes, start the shorter version. */
@Composable
fun TimeBudgetSheet(split: WorkoutSplit, plan: List<ExerciseEntry>, onDismiss: () -> Unit, onStart: (List<ExerciseEntry>) -> Unit) {
    val P = LocalPalette.current
    val full = estimateMinutes(plan)
    val options = listOf(20, 30, 45, 60, 75).filter { it < full }
    var minutes by remember { mutableIntStateOf(options.lastOrNull { it <= full * 2 / 3 } ?: options.firstOrNull() ?: full) }
    val (trimmed, cuts) = remember(minutes) { trimToMinutes(plan, minutes) }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(tr("How long do you have?"), style = AppType.title, color = P.textPrimary)
            Text(tr("{0} usually takes about {1} min.", shown(split.title), full), style = AppType.caption, color = P.textSecondary)
            Spacer(Modifier.height(16.dp))
            if (options.isEmpty()) {
                Text(tr("It's already short: no need to cut anything."), style = AppType.body, color = P.textSecondary)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(tr("Start workout"), { onStart(plan) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
                return@Column
            }
            ChoiceChips(options = options.map { it to tr("{0} min", it) }, selected = minutes, onSelect = { minutes = it })
            Spacer(Modifier.height(18.dp))
            AnimatedContent(minutes, transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "trim") { m ->
                val (list, changes) = if (m == minutes) trimmed to cuts else trimToMinutes(plan, m)
                Column {
                    Text(
                        tr("About {0} min: {1} exercises, {2} sets", estimateMinutes(list), list.size, list.sumOf { ex -> ex.sets.count { !it.isWarmupSet } }),
                        style = AppType.bodyStrong, color = P.textPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    if (changes.isEmpty()) Text(tr("Nothing to cut."), style = AppType.caption, color = P.textSecondary)
                    changes.forEach { c ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                shown(c.name), style = AppType.body.copy(textDecoration = if (c is Cut.Dropped) TextDecoration.LineThrough else null),
                                color = if (c is Cut.Dropped) P.textTertiary else P.textPrimary, modifier = Modifier.weight(1f)
                            )
                            Text(
                                when (c) {
                                    is Cut.Dropped -> tr("left out")
                                    is Cut.FewerSets -> tr("{0} → {1} sets", c.from, c.to)
                                    is Cut.NoWarmups -> tr("fewer warm-ups")
                                },
                                style = AppType.caption, color = P.textSecondary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(tr("Start {0} min {1}", minutes, shown(split.title)), { onStart(trimmed) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
        }
    }
}
