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
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(P.accentSoft),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.FitnessCenter, null, tint = P.accentPrimary, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.app_name), style = AppType.display, color = P.textPrimary)
        Spacer(Modifier.height(8.dp))
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
                if (backup == null) {
                    Text(stringResource(R.string.welcome_language), style = AppType.label, color = P.textSecondary, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SegmentedControl(
                        options = AppLanguage.entries.map { it to it.label },
                        selected = language,
                        onSelect = { haptics.tick(); AppLanguage.set(activity, it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(28.dp))
                    if (accounts) {
                        if (fetching) {
                            Text(tr("Getting your training…"), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(vertical = 40.dp))
                        } else {
                            SignInChoices(primary = true, enabled = !signingIn)
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton(
                                text = stringResource(R.string.welcome_no_account),
                                onClick = { haptics.tick(); onContinue() },
                                enabled = !signingIn,
                                modifier = Modifier.fillMaxWidth(),
                                height = 52.dp
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                tr("Sign in and your training is saved to your account, so it's there on any phone. You can also do it later."),
                                style = AppType.caption,
                                color = P.textTertiary,
                                textAlign = TextAlign.Center
                            )
                            // From before accounts: backups in the user's own Drive.
                            Text(
                                if (signingIn) stringResource(R.string.welcome_signing_in) else tr("Restore a Google Drive backup"),
                                style = AppType.label,
                                color = P.textSecondary,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .pressable(enabled = !signingIn, onClick = ::signIn)
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                    PrimaryButton(
                        text = stringResource(if (signingIn) R.string.welcome_signing_in else R.string.welcome_google),
                        onClick = ::signIn,
                        icon = Icons.Rounded.CloudSync,
                        enabled = !signingIn,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = stringResource(R.string.welcome_no_account),
                        onClick = { haptics.tick(); onContinue() },
                        enabled = !signingIn,
                        modifier = Modifier.fillMaxWidth(),
                        height = 52.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.welcome_google_why),
                        style = AppType.caption,
                        color = P.textTertiary,
                        textAlign = TextAlign.Center
                    )
                    }
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
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = stringResource(R.string.welcome_start_fresh),
                        onClick = { haptics.tick(); onContinue() },
                        enabled = !restoring,
                        modifier = Modifier.fillMaxWidth(),
                        height = 52.dp
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
