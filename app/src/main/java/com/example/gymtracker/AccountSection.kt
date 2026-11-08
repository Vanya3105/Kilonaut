package com.example.gymtracker

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Profile → Account: who is signed in, sync and sign out; a way to sign in when not. Hidden without Firebase. */
@Composable
fun AccountSection() {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Account.start(context) }
    val user = Account.user
    var confirmSignOut by remember { mutableStateOf(false) }

    var signingIn by remember { mutableStateOf(false) }
    if (user == null) {
        // The welcome screen and Today ask; here it's only a way back to it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Not signed in. Your training is only on this phone."), style = AppType.body, color = P.textSecondary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SecondaryButton(tr("Sign in"), { signingIn = true })
        }
        if (signingIn) SignInSheet(onDismiss = { signingIn = false })
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AccountCircle, null, tint = P.textSecondary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.name ?: user.email ?: tr("Signed in"), style = AppType.bodyStrong, color = P.textPrimary)
                Text(
                    if (user.name != null && user.email != null) user.email else if (user.viaGoogle) tr("Google account") else tr("Email account"),
                    style = AppType.caption, color = P.textSecondary
                )
            }
        }
        if (!user.verified) {
            Spacer(Modifier.height(12.dp))
            Text(tr("Open the link we sent to {0} to confirm it's yours.", user.email ?: ""), style = AppType.body, color = P.textSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("I've confirmed"), {
                    scope.launch { if (!Account.checkVerified()) AppSnackbar.show(tr("Not confirmed yet. Check your spam folder too.")) }
                }, Modifier.weight(1f))
                SecondaryButton(tr("Send again"), {
                    scope.launch { AppSnackbar.show(Account.resendVerification() ?: tr("Sent. Check your inbox.")) }
                }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        val sync = CloudSync.status
        Text(
            when {
                sync.running -> tr("Syncing…")
                sync.error != null -> sync.error
                sync.lastSyncAt > 0L -> syncedText(sync.lastSyncAt)
                else -> tr("Your training is copied to your account and kept in step on every phone you sign in on.")
            },
            style = AppType.caption,
            color = if (sync.error != null && !sync.running) P.dangerText else P.textSecondary
        )
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                tr("Sync now"), style = AppType.label, color = if (sync.running) P.textTertiary else P.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(enabled = !sync.running) { CloudSync.request(context, now = true) }
                    .padding(end = 16.dp, top = 10.dp, bottom = 10.dp)
            )
            Text(
                tr("Sign out"), style = AppType.label, color = P.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { confirmSignOut = true }.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = P.bgElevated,
            title = { Text(tr("Sign out?"), style = AppType.headline, color = P.textPrimary) },
            text = { Text(tr("Your training stays on this phone."), style = AppType.body, color = P.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch { Account.signOut(context) }
                }) { Text(tr("Sign out"), color = P.textPrimary, style = AppType.label) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(tr("Cancel"), color = P.textSecondary, style = AppType.label) } }
        )
    }
}

/** Continue with Google and Use email. [primary]: Google is the screen's main button. */
@Composable
fun SignInChoices(primary: Boolean = false, enabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var emailSheet by remember { mutableStateOf(false) }
    val google = {
        if (!busy) {
            busy = true
            scope.launch {
                Account.signInWithGoogle(context)?.let { AppSnackbar.show(it) }
                busy = false
            }
        }
    }
    val googleText = if (busy) tr("One moment…") else tr("Continue with Google")
    if (primary) PrimaryButton(googleText, google, Modifier.fillMaxWidth(), icon = Icons.Rounded.AccountCircle, enabled = enabled && !busy)
    else SecondaryButton(googleText, google, Modifier.fillMaxWidth(), icon = Icons.Rounded.AccountCircle, enabled = enabled && !busy)
    Spacer(Modifier.height(10.dp))
    SecondaryButton(
        tr("Use email"), { emailSheet = true }, Modifier.fillMaxWidth(),
        icon = Icons.Rounded.Email, enabled = enabled && !busy, height = if (primary) 52.dp else 48.dp
    )
    if (emailSheet) EmailSignInSheet(onDismiss = { emailSheet = false })
}

/** Signing in from Today's card: closes once signed in. */
@Composable
fun SignInSheet(onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val signedIn = Account.user != null
    LaunchedEffect(signedIn) { if (signedIn) onDismiss() }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(tr("Sign in to Kilonaut"), style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Your workouts, programs and settings are saved to your account, so they're there on any phone. Everything here stays as it is."),
                style = AppType.body, color = P.textSecondary
            )
            Spacer(Modifier.height(20.dp))
            SignInChoices(primary = true)
        }
    }
}

/** Today's nudge to sign in, until signed in or "Not now". */
@Composable
fun AccountCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    var signingIn by remember { mutableStateOf(false) }
    AppCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AccountCircle, null, tint = P.textSecondary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(tr("Keep your training safe"), style = AppType.bodyStrong, color = P.textPrimary)
        }
        Spacer(Modifier.height(6.dp))
        Text(tr("Sign in and it's saved to your account, so a new phone picks up where this one left off."), style = AppType.body, color = P.textSecondary)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(tr("Sign in"), { signingIn = true })
            Spacer(Modifier.width(8.dp))
            Text(
                tr("Not now"), style = AppType.label, color = P.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
    }
    if (signingIn) SignInSheet(onDismiss = { signingIn = false })
}

/** "Not now" on Today's sign-in card is remembered on this phone. */
object AccountPrompt {
    private const val PREFS = "gym_tracker_account_prompt"
    fun dismissed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dismissed", false)
    fun dismiss(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("dismissed", true).apply()
}

/** Email and password: sign in, create an account, or get a reset link. */
@Composable
private fun EmailSignInSheet(onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val emailOk = android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val canSubmit = emailOk && password.length >= (if (creating) 6 else 1) && !busy

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        scope.launch {
            val failed = Account.withEmail(email, password, create = creating)
            busy = false
            if (failed == null) {
                onDismiss()
                AppSnackbar.show(if (creating) tr("Account created. Check your inbox to confirm your email.") else tr("Signed in"))
            } else error = failed
        }
    }

    AppBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
            Text(if (creating) tr("Create an account") else tr("Sign in with email"), style = AppType.title, color = P.textPrimary)
            Spacer(Modifier.height(16.dp))
            SegmentedControl(
                options = listOf(false to tr("Sign in"), true to tr("New account")),
                selected = creating,
                onSelect = { creating = it; error = null }
            )
            Spacer(Modifier.height(16.dp))
            val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = P.accentPrimary, unfocusedBorderColor = P.borderStrong)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(200); error = null },
                label = { Text(tr("Email")) },
                singleLine = true,
                textStyle = AppType.body.copy(color = P.textPrimary),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                colors = colors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(128); error = null },
                label = { Text(tr("Password")) },
                supportingText = if (creating) ({ Text(tr("At least 6 characters")) }) else null,
                singleLine = true,
                textStyle = AppType.body.copy(color = P.textPrimary),
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (showPassword) tr("Hide password") else tr("Show password"),
                            tint = P.textSecondary
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                colors = colors,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = AppType.caption, color = P.dangerText)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                if (busy) tr("One moment…") else if (creating) tr("Create account") else tr("Sign in"),
                ::submit,
                Modifier.fillMaxWidth(),
                enabled = canSubmit
            )
            if (!creating) {
                Text(
                    tr("Forgot password?"),
                    style = AppType.label,
                    color = P.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .pressable {
                            if (!emailOk) {
                                error = tr("Type your email first, then tap this again.")
                                return@pressable
                            }
                            scope.launch {
                                val failed = Account.resetPassword(email)
                                if (failed == null) AppSnackbar.show(tr("If there's an account for {0}, a reset link is on its way.", email.trim()))
                                else error = failed
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun syncedText(at: Long): String {
    val minutes = ((System.currentTimeMillis() - at) / 60_000L).toInt()
    return when {
        minutes < 1 -> tr("Synced just now")
        minutes < 60 -> tr("Synced {0} min ago", minutes)
        android.text.format.DateUtils.isToday(at) -> tr("Synced at {0}", java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)))
        else -> tr("Synced {0}", DateUtils.formatDisplayDate(java.util.Date(at)))
    }
}
