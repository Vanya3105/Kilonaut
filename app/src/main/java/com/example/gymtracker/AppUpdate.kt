package com.example.gymtracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Friends install the APK from GitHub, so nothing tells them a new version is out. Once a day the
 * app asks GitHub for the latest release and Today offers it. Installs from Google Play are left
 * to Play.
 */
object AppUpdate {
    class Release(val version: String, val apkUrl: String, val pageUrl: String)

    private const val LATEST = "https://api.github.com/repos/Vanya3105/Kilonaut/releases/latest"
    private const val PREFS = "gym_tracker_update_check"
    private const val KEY_CHECKED = "checked_at"
    private const val KEY_DISMISSED = "dismissed_version"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    var available by mutableStateOf<Release?>(null)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun installedFromPlay(context: Context): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer == "com.android.vending"
    } catch (_: Exception) { false }

    /** "3.10.0" > "3.9.1": compared number by number. */
    internal fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun check(context: Context, force: Boolean = false) {
        if (installedFromPlay(context)) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong(KEY_CHECKED, 0L) < DAY_MS) {
            return
        }
        val current = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return
        val release = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(LATEST).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val assets = json.optJSONArray("assets")
                    // The phone APK, not the watch one.
                    val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                        .firstOrNull { val n = it.optString("name"); n.endsWith(".apk") && !n.contains("Watch", ignoreCase = true) }
                    Release(
                        json.optString("tag_name").removePrefix("v"),
                        apk?.optString("browser_download_url") ?: json.optString("html_url"),
                        json.optString("html_url")
                    )
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        } ?: return
        p.edit().putLong(KEY_CHECKED, now).apply()
        if (isNewer(release.version, current) && p.getString(KEY_DISMISSED, null) != release.version) available = release
    }

    fun dismiss(context: Context) {
        available?.let { prefs(context).edit().putString(KEY_DISMISSED, it.version).apply() }
        available = null
    }
}

/** On Today when a newer version is on GitHub: download it, see what's new, or hide it. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val release = AppUpdate.available
    AnimatedVisibility(release != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        val r = release ?: return@AnimatedVisibility
        fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = P.accentPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Kilonaut {0} is out", r.version), style = AppType.bodyStrong, color = P.textPrimary)
                    Text(tr("Your workouts stay as they are."), style = AppType.caption, color = P.textSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(tr("Download"), { open(r.apkUrl) }, Modifier.weight(1f), height = 44.dp)
                SecondaryButton(tr("What's new"), { open(r.pageUrl) }, Modifier.weight(1f), height = 44.dp)
            }
            Text(
                tr("Not now"),
                style = AppType.label,
                color = P.textSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).pressable { AppUpdate.dismiss(context) }.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
