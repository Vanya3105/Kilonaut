package com.example.gymtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Today's coach card: one suggestion from [coachTip], answered right here. */
@Composable
fun CoachCard(
    tip: CoachTip,
    history: List<CompletedWorkout>,
    equipment: Equipment?,
    modifier: Modifier = Modifier
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    var replacing by remember { mutableStateOf<SkippedOften?>(null) }

    @Composable
    fun Body(title: String, text: String, actions: @Composable () -> Unit) {
        AppCard(modifier.fillMaxWidth()) {
            Text(tr("Coach"), style = AppType.overline, color = P.textSecondary)
            Spacer(Modifier.height(4.dp))
            Text(title, style = AppType.bodyStrong, color = P.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(text, style = AppType.body, color = P.textSecondary)
            Spacer(Modifier.height(14.dp))
            actions()
        }
    }

    @Composable
    fun Actions(main: String, onMain: () -> Unit, other: String?, onOther: () -> Unit = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(main, onMain, Modifier.weight(1f, fill = false))
            if (other != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    other, style = AppType.label, color = P.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onOther).padding(horizontal = 12.dp, vertical = 12.dp)
                )
            }
        }
    }

    when (tip) {
        is CoachTip.CheckResult -> {
            val c = tip.check
            val result = tr("{0} reps on the all-out set of {1}; your rating of the set before pointed to about {2}.", c.reps, shown(c.exercise), c.expected)
            val meaning = when {
                tip.bias >= 2 -> tr("You keep more in reserve than you think, so coaching now counts {0} more reps left than you rate.", tip.bias)
                tip.bias == 1 -> tr("You keep a little more in reserve than you think, so coaching now counts 1 more rep left than you rate.")
                tip.bias < 0 -> tr("You had a little less left than you thought, so coaching now counts 1 rep fewer than you rate.")
                else -> tr("Your reps-left ratings are accurate, so coaching takes them as they are.")
            }
            Body(tr("Reps-left check"), "$result $meaning") {
                Actions(tr("Got it"), { Coach.checkSeen(context) }, null)
            }
        }
        is CoachTip.LighterWeek -> {
            val until = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(tip.endsAt))
            Body(tr("Lighter week"), tr("Until {0}: about half the sets and 10% lighter. Your weights pick up where they were after it.", until)) {
                Actions(tr("End it now"), { Coach.endLighterWeek(context) }, null)
            }
        }
        is CoachTip.DeloadDue -> {
            Body(
                tr("Time for a lighter week"),
                tr("{0} hard weeks in a row. A week at half the sets and about 10% lighter lets fatigue clear, and you come back stronger.", tip.weeks)
            ) {
                Actions(tr("Start lighter week"), {
                    Coach.startLighterWeek(context)
                    AppSnackbar.show(tr("Lighter week started"), tr("Undo")) { Coach.endLighterWeek(context) }
                }, tr("Not now"), { Coach.snoozeLighterWeek(context) })
            }
        }
        is CoachTip.Skipped -> {
            val s = tip.s
            Body(
                tr("Skipping {0}?", shown(s.exercise)),
                tr("You skipped it in {0} of your last {1} {2} workouts. Swap it for something you'd rather do, or keep it.", s.skipped, s.of, shown(s.day))
            ) {
                Actions(tr("Swap it"), { replacing = s }, tr("Keep it"), { Coach.keepSkipped(context, s.day, s.exercise) })
            }
        }
        is CoachTip.Swapped -> {
            val s = tip.s
            Body(
                tr("Make {0} part of {1}?", shown(s.to), shown(s.day)),
                tr("You swapped {0} for it on {1} different days.", shown(s.from), s.times)
            ) {
                Actions(tr("Make it part of {0}", shown(s.day)), {
                    val before = LocalStorage.daySnapshot(context, s.day)
                    LocalStorage.replaceInSplitTemplate(context, s.day, s.from, s.to, ExerciseDatabase.find(s.to)?.muscle ?: s.muscle)
                    Coach.declineSwap(context, s.day, s.from, s.to)
                    AppSnackbar.show(tr("{0} is part of {1}", shown(s.to), shown(s.day)), tr("Undo")) { LocalStorage.restoreDay(context, before) }
                }, tr("No"), { Coach.declineSwap(context, s.day, s.from, s.to) })
            }
        }
        is CoachTip.Volume -> {
            val v = tip.v
            val more = v.toSets > v.fromSets
            Body(
                if (more) tr("One more set for {0}", shown(v.muscle)) else tr("One set fewer for {0}", shown(v.muscle)),
                if (more) tr("{0} gets about {1} sets a week and {2} hasn't gone up in 4 weeks. {3} instead of {4} sets on {5} gives it more to grow from.", shown(v.muscle), v.weeklySets, shown(v.exercise), v.toSets, v.fromSets, shown(v.day))
                else tr("{0} gets about {1} sets a week and {2} has slipped over 4 weeks. {3} instead of {4} sets on {5} leaves more to recover from.", shown(v.muscle), v.weeklySets, shown(v.exercise), v.toSets, v.fromSets, shown(v.day))
            ) {
                Actions(if (more) tr("Add the set") else tr("Take a set off"), {
                    val before = LocalStorage.daySnapshot(context, v.day)
                    val old = LocalStorage.getTarget(context, v.day, v.exercise)
                    val (min, max) = old?.let { it.repMin to it.repMax } ?: defaultRepRange(v.exercise)
                    LocalStorage.setTarget(context, v.day, v.exercise, ExerciseTarget(v.toSets, min, max))
                    Coach.volumeAnswered(context)
                    AppSnackbar.show(tr("{0}: {1} sets on {2}", shown(v.exercise), v.toSets, shown(v.day)), tr("Undo")) { LocalStorage.restoreDay(context, before) }
                }, tr("Not now"), { Coach.volumeAnswered(context) })
            }
        }
    }

    replacing?.let { s ->
        ReplaceExerciseSheet(s, history, equipment, onDismiss = { replacing = null }) { info ->
            replacing = null
            val before = LocalStorage.daySnapshot(context, s.day)
            LocalStorage.replaceInSplitTemplate(context, s.day, s.exercise, info.name, info.muscle)
            AppSnackbar.show(tr("{0} replaces {1} on {2}", shown(info.name), shown(s.exercise), shown(s.day)), tr("Undo")) { LocalStorage.restoreDay(context, before) }
        }
    }
}

/** Alternatives for an exercise that keeps being skipped; the pick replaces it in the day. */
@Composable
private fun ReplaceExerciseSheet(
    s: SkippedOften,
    history: List<CompletedWorkout>,
    equipment: Equipment?,
    onDismiss: () -> Unit,
    onPick: (ExerciseInfo) -> Unit
) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val candidates = remember(s) {
        val day = LocalStorage.loadTemplateForSplit(context, s.day).map { it.first }
        swapCandidates(ExerciseEntry(s.exercise, s.muscle, type = LocalStorage.exerciseType(context, s.exercise)), day, history, equipment, limit = 4)
    }
    val doneBefore = remember(history) { history.flatMap { w -> w.exercises.map { exerciseKey(it.name) } }.toSet() }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(tr("Instead of {0}", shown(s.exercise)), style = AppType.title, color = P.textPrimary)
            Text(tr("Same muscle. Your pick takes its place on {0}.", shown(s.day)), style = AppType.caption, color = P.textSecondary)
            Spacer(Modifier.height(16.dp))
            if (candidates.isEmpty()) {
                Text(tr("Nothing similar fits your equipment."), style = AppType.body, color = P.textSecondary)
            }
            candidates.forEachIndexed { i, info ->
                AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).enterAnimation(i), onClick = { onPick(info) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExerciseThumb(info.name, size = 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(shown(info.name), style = AppType.bodyStrong, color = P.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(shown(info.equipment), if (exerciseKey(info.name) in doneBefore) tr("You've done it") else tr("New to you")).joinToString(" · "),
                                style = AppType.caption, color = P.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
