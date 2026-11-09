package com.example.gymtracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * The rules for Friends and the feed, which people agree to before they post or comment (Google
 * Play asks for this in apps where people share things with each other). Agreeing is remembered
 * on the phone; a new version of the rules ([VERSION]) asks again.
 */
object CommunityRules {
    private const val VERSION = 1
    private const val PREFS = "gym_tracker_community"
    const val URL = "https://vanya3105.github.io/Kilonaut/community-rules.html"

    var agreed by mutableStateOf<Boolean?>(null)
        private set

    fun load(context: Context): Boolean =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("agreed", 0) >= VERSION).also { agreed = it }

    fun agree(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("agreed", VERSION).apply()
        agreed = true
    }

    /** The rules, short enough to read. */
    val items: List<Pair<String, String>>
        get() = listOf(
            tr("Be kind") to tr("No harassment, bullying, threats or hate, in comments or anywhere else."),
            tr("Keep it clean") to tr("No nudity or sexual content, including in profile pictures."),
            tr("No spam") to tr("No ads, selling, or links to get people somewhere else."),
            tr("Be yourself") to tr("Don't pretend to be someone else, and share only your own training."),
            tr("Look out for each other") to tr("Report anything that breaks these rules: long-press a comment, or ⋯ on a post or profile.")
        )
}

/** The rules as a list, for the username step and the one-time check. */
@Composable
fun CommunityRulesList() {
    val P = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CommunityRules.items.forEachIndexed { i, (title, text) ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = AppType.caption, color = P.accentPrimary)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, style = AppType.bodyStrong, color = P.textPrimary)
                    Text(text, style = AppType.caption, color = P.textSecondary)
                }
            }
        }
        Text(
            tr("Breaking them can get your posts and comments removed and your account deleted."),
            style = AppType.caption, color = P.textTertiary
        )
    }
}

/** "I agree to the community rules", with a link to read them on the web. */
@Composable
fun AgreeToRules(checked: Boolean, onChange: (Boolean) -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.bgControl).pressable { onChange(!checked) }.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = P.accentPrimary))
        Text(tr("I agree to the community rules"), style = AppType.body, color = P.textPrimary, modifier = Modifier.weight(1f))
        Text(
            tr("Read"), style = AppType.label, color = P.accentPrimary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CommunityRules.URL))) }
            }.padding(6.dp)
        )
    }
}

/**
 * Shown in place of Friends or the feed until the rules are agreed to (people who picked a
 * username before there were rules).
 */
@Composable
fun CommunityRulesGate(modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val context = LocalContext.current
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, null, tint = P.accentPrimary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(tr("Community rules"), style = AppType.title, color = P.textPrimary)
        Text(
            tr("Before you see and share posts: a few rules that keep Kilonaut a good place to train together."),
            style = AppType.body, color = P.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.bgCard).padding(16.dp)) { CommunityRulesList() }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(tr("I agree"), { CommunityRules.agree(context) }, Modifier.fillMaxWidth())
    }
}
