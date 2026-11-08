package com.example.gymtracker

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

/** Profile → Account: sign in with Google or email, or who is signed in. Hidden without Firebase. */
@Composable
fun AccountSection() {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Account.start(context) }
    val user = Account.user
    var busy by remember { mutableStateOf(false) }
    var emailSheet by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    if (user == null) {
        Text(
            tr("Sign in to keep your training in your account, so it follows you to a new phone. Without one, everything stays on this phone."),
            style = AppType.body, color = P.textSecondary
        )
        Spacer(Modifier.height(14.dp))
        SecondaryButton(
            tr("Continue with Google"),
            onClick = {
                if (busy) return@SecondaryButton
                busy = true
                scope.launch {
                    Account.signInWithGoogle(context)?.let { AppSnackbar.show(it) }
                    busy = false
                }
            },
            icon = Icons.Rounded.AccountCircle,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(tr("Use email"), { emailSheet = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Email, enabled = !busy)
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
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Sign out"), style = AppType.label, color = P.textSecondary,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { confirmSignOut = true }.padding(vertical = 10.dp)
        )
    }

    if (emailSheet) EmailSignInSheet(onDismiss = { emailSheet = false })
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
