package com.example.gymtracker

import android.app.Activity
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The first screen on a new phone: pick the language, then sign in with Google (backups to the
 * user's own Drive, and a restore if there's one there already) or carry on without an account.
 * [onContinue] goes on to the profile questions; [onRestored] means a backup was restored.
 */
@Composable
fun WelcomeScreen(activity: Activity, haptics: Haptics, onContinue: () -> Unit, onRestored: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language = remember { AppLanguage.current(context) }
    var signingIn by remember { mutableStateOf(false) }
    // The newest Drive backup, once signed in on a phone with nothing logged yet.
    var found by remember { mutableStateOf<DriveBackup.DriveBackupFile?>(null) }
    var restoring by rememberSaveable { mutableStateOf(false) }

    fun afterSignIn(step: DriveBackup.SignInStep) {
        when (step) {
            is DriveBackup.SignInStep.NeedsUser -> Unit
            is DriveBackup.SignInStep.Failed -> {
                signingIn = false
                if (step.message.isNotEmpty()) AppSnackbar.show(step.message)
            }
            is DriveBackup.SignInStep.Done -> scope.launch {
                val newest = withContext(Dispatchers.IO) { DriveBackup.listBackups(context) }.orEmpty().maxByOrNull { it.modifiedMillis }
                signingIn = false
                if (newest != null && LocalStorage.loadHistory(context).isEmpty()) found = newest else onContinue()
            }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        DriveBackup.finishSignIn(context, result.data, ::afterSignIn)
    }
    fun signIn() {
        haptics.tick()
        signingIn = true
        DriveBackup.startSignIn(context) { step ->
            if (step is DriveBackup.SignInStep.NeedsUser) consent.launch(IntentSenderRequest.Builder(step.intent).build())
            else afterSignIn(step)
        }
    }
    // Signed in to a Kilonaut account here: bring its data in before deciding whether setup is needed.
    val accounts = remember { Account.available(context).also { if (it) Account.start(context) } }
    var fetching by remember { mutableStateOf(false) }
    val signedIn = Account.user != null
    LaunchedEffect(signedIn) {
        if (!signedIn || fetching) return@LaunchedEffect
        fetching = true
        val synced = CloudSync.syncAndWait(context)
        fetching = false
        val profile = LocalStorage.loadUserProfile(context)
        if (!synced) AppSnackbar.show(tr("Couldn't reach your account just now. Your training will come in once you're online."))
        if (profile.hasCompletedOnboarding || LocalStorage.loadHistory(context).isNotEmpty()) onRestored() else onContinue()
    }
    val couldNotRestore = stringResource(R.string.welcome_restore_failed)
    fun restore(file: DriveBackup.DriveBackupFile) {
        haptics.tick()
        restoring = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                DriveBackup.download(context, file.file)?.let { LocalStorage.importJsonBackupString(context, it) } == true
            }
            restoring = false
            if (ok) onRestored() else AppSnackbar.show(couldNotRestore)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Language in the corner, as a choice you rarely make.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LanguageChip(language) { haptics.tick(); AppLanguage.set(activity, it) }
        }
        Spacer(Modifier.weight(1f))
        WelcomePreview()
        Spacer(Modifier.height(32.dp))
        Text(
            tr("Every rep counted.") + "\n" + tr("Smarter next time."),
            style = AppType.display,
            color = P.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.welcome_tagline),
            style = AppType.body,
            color = P.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))

        AnimatedContent(
            targetState = found,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            label = "welcome_step"
        ) { backup ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (backup == null && accounts) {
                    if (fetching) {
                        Text(tr("Getting your training…"), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(vertical = 48.dp))
                    } else {
                        SignInChoices(primary = true, enabled = !signingIn)
                        TextLink(stringResource(R.string.welcome_no_account), enabled = !signingIn) { haptics.tick(); onContinue() }
                        Text(
                            tr("Privacy policy"),
                            style = AppType.caption,
                            color = P.textTertiary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .pressable {
                                    try {
                                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_POLICY_URL)))
                                    } catch (_: Exception) {}
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else if (backup == null) {
                    // A build without accounts: backups to the user's own Drive, as before.
                    PrimaryButton(
                        text = stringResource(if (signingIn) R.string.welcome_signing_in else R.string.welcome_google),
                        onClick = ::signIn,
                        icon = Icons.Rounded.CloudSync,
                        enabled = !signingIn,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextLink(stringResource(R.string.welcome_no_account), enabled = !signingIn) { haptics.tick(); onContinue() }
                    Text(stringResource(R.string.welcome_google_why), style = AppType.caption, color = P.textTertiary, textAlign = TextAlign.Center)
                } else {
                    val date = DateUtils.formatDateTime(
                        context, backup.modifiedMillis,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
                    )
                    Text(stringResource(R.string.welcome_found_title), style = AppType.title, color = P.textPrimary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.welcome_found_body, date),
                        style = AppType.body,
                        color = P.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton(
                        text = stringResource(if (restoring) R.string.welcome_restoring else R.string.welcome_restore),
                        onClick = { restore(backup) },
                        icon = Icons.Rounded.CloudDownload,
                        enabled = !restoring,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextLink(stringResource(R.string.welcome_start_fresh), enabled = !restoring) { haptics.tick(); onContinue() }
                }
            }
        }
    }
}

@Composable
private fun TextLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val P = LocalPalette.current
    Text(
        text,
        style = AppType.label,
        color = if (enabled) P.textSecondary else P.textTertiary,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun LanguageChip(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    val P = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(P.bgControl)
                .pressable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Language, null, tint = P.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(current.label, style = AppType.label, color = P.textPrimary)
            Icon(Icons.Rounded.ExpandMore, null, tint = P.textSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = P.bgElevated) {
            AppLanguage.entries.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.label, style = AppType.body, color = P.textPrimary) },
                    onClick = { open = false; if (lang != current) onSelect(lang) }
                )
            }
        }
    }
}

/**
 * What the app looks like, drawn with its own parts: today's workout card, and behind it an
 * exercise with its sets and the weight for next time.
 */
@Composable
private fun WelcomePreview() {
    val P = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(272.dp)) {
        AppCard(
            Modifier.align(Alignment.TopEnd).padding(end = 4.dp).width(230.dp).rotate(4f),
            elevated = true
        ) {
            Text(shown("Bench Press"), style = AppType.bodyStrong, color = P.textPrimary)
            Text(tr("Next time: {0}", formatWeightKg(62.5f)), style = AppType.caption, color = P.accentPrimary)
            Spacer(Modifier.height(8.dp))
            listOf(8, 8, 7).forEachIndexed { i, reps ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = AppType.caption, color = P.textTertiary, modifier = Modifier.width(18.dp))
                    Text("${formatWeightKg(60f)} × $reps", style = AppType.body.copy(fontFeatureSettings = "tnum"), color = P.textPrimary, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.CheckCircle, null, tint = P.accentPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
        AppCard(
            Modifier.align(Alignment.BottomStart).padding(start = 4.dp).width(240.dp).rotate(-3f),
            elevated = true
        ) {
            Text(tr("Up next"), style = AppType.overline, color = P.accentPrimary)
            Spacer(Modifier.height(4.dp))
            Text(shown("Push"), style = AppType.title, color = P.textPrimary)
            Text(tr("Chest, shoulders, triceps"), style = AppType.caption, color = P.textSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(tr("{0} exercises", 6), container = P.bgControl)
                Pill(tr("~{0} min", 55), icon = Icons.Rounded.Schedule, container = P.bgControl)
            }
        }
    }
}
