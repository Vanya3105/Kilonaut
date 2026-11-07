package com.example.gymtracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for adding exercises: search the built-in library (name, muscle, equipment or
 * nickname like "rdl"), filter by muscle. The + adds straight away; tapping a row opens it to show
 * how the exercise is done, with its own Add. Several can be added before closing.
 */
@Composable
fun ExercisePickerSheet(
    existingNames: Set<String>,
    onPick: (ExerciseInfo) -> Unit,
    onCreateCustom: (String, ExerciseType) -> Unit,
    onDismiss: () -> Unit,
    /** Shown under exercises that are already in the list being edited. */
    addedLabel: String = tr("In this workout")
) {
    val P = LocalPalette.current
    var query by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val results = remember(query, muscle) { ExerciseDatabase.search(query, muscle) }
    val lowerExisting = remember(existingNames) { existingNames.map { it.lowercase() }.toSet() }
    val trimmed = query.trim()
    // A custom exercise is created once its type is chosen.
    var pendingCustom by remember { mutableStateOf<String?>(null) }
    val canCreate = trimmed.length >= 2 &&
        results.none { it.name.equals(trimmed, ignoreCase = true) } &&
        trimmed.lowercase() !in lowerExisting

    AppBottomSheet(onDismiss = onDismiss) {
        // The sheet is its own window: only its focus manager can put the keyboard away.
        val focus = LocalFocusManager.current
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Add exercise"), style = AppType.title, color = P.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    tr("Done"),
                    style = AppType.label,
                    color = P.accentPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick = onDismiss).padding(8.dp)
                )
            }
            Spacer(Modifier.height(12.dp))

            // Search field
            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(P.bgControl)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = P.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(tr("Search exercises"), style = AppType.body, color = P.textTertiary)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = AppType.body.copy(color = P.textPrimary),
                        cursorBrush = SolidColor(P.accentPrimary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    CircleIconButton(Icons.Rounded.Close, tr("Clear search"), { query = "" }, size = 28.dp, container = Color.Transparent, tint = P.textSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))

            // Muscle filter
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf<String?>(null) + ExerciseDatabase.muscleGroups) { group ->
                    val selected = muscle == group
                    val bg by animateColorAsState(if (selected) P.textPrimary else P.bgControl, label = "filter_bg")
                    val fg by animateColorAsState(if (selected) P.bgPrimary else P.textSecondary, label = "filter_fg")
                    Text(
                        group?.let(::shown) ?: tr("All"),
                        style = AppType.label,
                        color = fg,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(bg)
                            .pressable(pressedScale = 0.94f) { muscle = group }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(results, key = { it.name }) { info ->
                    val added = info.name.lowercase() in lowerExisting
                    val isOpen = open == info.name
                    PickerRow(
                        title = shown(info.name),
                        addedLabel = addedLabel,
                        subtitle = listOfNotNull(shown(info.muscle), shown(info.equipment), info.type.takeIf { it != ExerciseType.WEIGHT_REPS }?.label).joinToString(" · "),
                        added = added,
                        onClick = { if (!added) onPick(info) },
                        onRowClick = { focus.clearFocus(); open = if (isOpen) null else info.name },
                        thumbName = info.name,
                        modifier = Modifier.animateItem()
                    ) {
                        AnimatedVisibility(
                            isOpen,
                            enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                            exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                        ) {
                            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)) {
                                ExerciseGuideBody(info.name, info.muscle)
                                Spacer(Modifier.height(16.dp))
                                if (added) {
                                    SecondaryButton(addedLabel, {}, Modifier.fillMaxWidth(), icon = Icons.Rounded.Check, enabled = false)
                                } else {
                                    PrimaryButton(tr("Add"), { onPick(info); open = null }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, height = 48.dp)
                                }
                            }
                        }
                    }
                }
                if (canCreate) {
                    item(key = "create") {
                        PickerRow(
                            title = tr("Create \"{0}\"", trimmed),
                            subtitle = tr("Add your own exercise"),
                            added = false,
                            leadingAccent = true,
                            onClick = { pendingCustom = trimmed }
                        )
                    }
                }
                if (results.isEmpty() && !canCreate) {
                    item { EmptyState(Icons.Rounded.Search, tr("No matches"), tr("Try a different word, like \"row\" or \"cable\".")) }
                }
            }
        }
    }

    pendingCustom?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingCustom = null },
            containerColor = P.bgElevated,
            title = { Text(tr("How do you log {0}?", name), style = AppType.headline, color = P.textPrimary) },
            text = {
                ExerciseTypeOptions(selected = null) { type ->
                    onCreateCustom(name, type)
                    pendingCustom = null
                    query = ""
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingCustom = null }) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
        )
    }
}

/** Every [ExerciseType] as a tappable row with an example, the current one checked. */
@Composable
fun ExerciseTypeOptions(selected: ExerciseType?, onPick: (ExerciseType) -> Unit) {
    val P = LocalPalette.current
    Column {
        ExerciseType.entries.forEach { type ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .pressable(pressedScale = 0.98f) { onPick(type) }
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(type.label, style = AppType.bodyStrong, color = if (type == selected) P.accentPrimary else P.textPrimary)
                    Text(type.example, style = AppType.caption, color = P.textSecondary)
                }
                if (type == selected) Icon(Icons.Rounded.Check, tr("Current"), tint = P.accentPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * "Logged as: Weight & reps ▾". Changing it keeps every logged number, so switching back
 * shows them again.
 */
@Composable
fun ExerciseTypeRow(current: ExerciseType, onChange: (ExerciseType) -> Unit, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    var choosing by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(P.bgControl)
            .pressable(pressedScale = 0.99f) { choosing = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("Logged as"), style = AppType.caption, color = P.textSecondary)
            Text(current.label, style = AppType.bodyStrong, color = P.textPrimary)
        }
        Icon(Icons.Rounded.UnfoldMore, tr("Change"), tint = P.textSecondary, modifier = Modifier.size(20.dp))
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            containerColor = P.bgElevated,
            title = { Text(tr("Log this exercise as"), style = AppType.headline, color = P.textPrimary) },
            text = {
                Column {
                    ExerciseTypeOptions(selected = current) { type ->
                        choosing = false
                        if (type != current) onChange(type)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Numbers you've already logged are kept. Switch back any time to see them again."),
                        style = AppType.caption,
                        color = P.textTertiary
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
        )
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    added: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tapping the row itself; without it, the whole row is the + . */
    onRowClick: (() -> Unit)? = null,
    thumbName: String? = null,
    leadingAccent: Boolean = false,
    addedLabel: String = "",
    expanded: @Composable () -> Unit = {}
) {
    val P = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .pressable(enabled = onRowClick != null || !added, pressedScale = 0.98f, onClick = onRowClick ?: onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbName != null) {
                ExerciseThumb(thumbName, size = 48.dp)
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    title,
                    style = AppType.bodyStrong,
                    color = when {
                        added -> P.textSecondary
                        leadingAccent -> P.accentPrimary
                        else -> P.textPrimary
                    }
                )
                Text(if (added) addedLabel else subtitle, style = AppType.caption, color = P.textSecondary)
            }
            AnimatedContent(
                targetState = added,
                transitionSpec = { scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium), initialScale = 0.6f).togetherWith(scaleOut()) },
                label = "picker_check"
            ) { isAdded ->
                // A 44dp target around the 30dp circle, so the + is easy to hit apart from the row.
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .then(if (onRowClick != null) Modifier.pressable(enabled = !isAdded, pressedScale = 0.9f, onClick = onClick) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (isAdded) P.successSurface else P.accentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isAdded) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = if (isAdded) tr("Added") else tr("Add"),
                            tint = if (isAdded) P.successText else P.accentPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        expanded()
    }
}
