package com.example.gymtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Report and block, required for anything people post to each other (Google Play's user-generated
// content policy). Reports go to the app's makers (Firebase console); blocking ends every follow
// between the two of you and hides their comments.

/** What's being reported: a person, one of their posts, or one comment. */
data class ReportTarget(val person: Friends.Person, val postPath: String? = null, val commentId: String? = null, val excerpt: String = "")

private val REASONS = listOf(
    "spam" to "Spam",
    "harassment" to "Harassment or bullying",
    "nudity" to "Nudity or sexual content",
    "violence" to "Violence or dangerous acts",
    "hate" to "Hate speech",
    "other" to "Something else"
)

/** Pick a reason; optionally block them too. */
@Composable
fun ReportSheet(target: ReportTarget, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var alsoBlock by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    AppBottomSheet(onDismiss = { if (!sending) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(
                when {
                    target.commentId != null -> tr("Report comment")
                    target.postPath != null -> tr("Report post")
                    else -> tr("Report {0}", target.person.title())
                },
                style = AppType.title, color = P.textPrimary
            )
            Text(tr("What's wrong with it? They won't know who reported them."), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            REASONS.forEach { (key, label) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(enabled = !sending) {
                        sending = true
                        scope.launch {
                            val ok = Friends.report(target.person.uid, key, target.postPath, target.commentId, target.excerpt)
                            if (ok && alsoBlock) Friends.block(target.person.uid)
                            sending = false
                            if (ok) {
                                onDismiss()
                                AppSnackbar.show(if (alsoBlock) tr("Thanks for telling us. You've blocked them.") else tr("Thanks for telling us. We'll look at it."))
                            } else AppSnackbar.show(tr("Couldn't send it. Check your connection."))
                        }
                    }.padding(horizontal = 6.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr(label), style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.textTertiary, modifier = Modifier.size(20.dp))
                }
            }
            if (!Friends.isBlocked(target.person.uid)) Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(P.bgControl)
                    .pressable { alsoBlock = !alsoBlock }.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(alsoBlock, { alsoBlock = it }, colors = CheckboxDefaults.colors(checkedColor = P.accentPrimary))
                Text(tr("Also block {0}", target.person.title()), style = AppType.body, color = P.textPrimary)
            }
            if (sending) Text(tr("Sending…"), style = AppType.caption, color = P.textSecondary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** "Block @x?" and what it does. */
@Composable
fun BlockDialog(person: Friends.Person, onDismiss: () -> Unit, onBlocked: () -> Unit = {}) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = P.bgElevated,
        title = { Text(tr("Block {0}?", person.title()), style = AppType.headline, color = P.textPrimary) },
        text = {
            Text(
                tr("You won't be friends anymore, they can't follow you or see your training and posts, and you won't see their comments. They aren't told. You can unblock them in Friends."),
                style = AppType.body, color = P.textSecondary
            )
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val ok = Friends.block(person.uid)
                    busy = false
                    onDismiss()
                    if (ok) { onBlocked(); AppSnackbar.show(tr("Blocked.")) } else AppSnackbar.show(tr("Couldn't do that. Check your connection."))
                }
            }) { Text(if (busy) tr("Blocking…") else tr("Block"), color = P.dangerText, style = AppType.label) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
    )
}
