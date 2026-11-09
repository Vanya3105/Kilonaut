package com.example.gymtracker

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language, chosen on the welcome screen or in Profile; until then it follows the phone.
 * Android 13+ keeps a per-app language itself (it also shows in the phone's app settings); older
 * versions keep the choice here and apply it to each activity in [wrap].
 */
enum class AppLanguage(val tag: String, val label: String) {
    English("en", "English"),
    Hebrew("he", "עברית");

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_TAG = "tag"

        fun current(context: Context): AppLanguage {
            val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
            } else prefs(context).getString(KEY_TAG, null)
            return of(chosen ?: Locale.getDefault().language)
        }

        /** Switches the app to [language]; the app restarts in it. */
        fun set(activity: Activity, language: AppLanguage) {
            if (language == current(activity)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
            } else {
                prefs(activity).edit().putString(KEY_TAG, language.tag).commit()
            }
            // Some text is made once per run (tab names, labels), so start the app afresh in the new language.
            val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return activity.recreate()
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Runtime.getRuntime().exit(0)
        }

        /**
         * Before Android 13 the chosen language is applied when the activity starts; work that runs
         * without it (notifications in the background) calls this first so its text matches.
         */
        fun applyInBackground(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            prefs(context).getString(KEY_TAG, null)?.let { Locale.setDefault(Locale.forLanguageTag(it)) }
        }

        /** For attachBaseContext: before Android 13, gives the activity the chosen language. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = prefs(base).getString(KEY_TAG, null) ?: return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            return base.createConfigurationContext(config)
        }

        // Java still calls Hebrew "iw" in places.
        private fun of(language: String) = if (language == "he" || language == "iw") Hebrew else English

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

/** The activity behind a Compose context, which may be wrapped (for the theme or the language). */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Hebrew is on: chosen in the app, or the phone's language when nothing was chosen. */
internal fun hebrewOn(): Boolean = Locale.getDefault().language.let { it == "he" || it == "iw" }

/**
 * The app's text in the current language. The code keeps the English, with {0}, {1}… where values
 * go; the Hebrew comes from [HebrewText]. Text with no Hebrew entry shows in English.
 */
fun tr(text: String, vararg args: Any?): String {
    var out = if (hebrewOn()) HebrewText.of(text) ?: text else text
    args.forEachIndexed { i, a -> out = out.replace("{$i}", a.toString()) }
    return out
}

/** One letter per day, Sunday first. Not through [tr]: in English two days share each of S and T. */
internal fun weekdayLetters(): List<String> =
    if (hebrewOn()) listOf("א", "ב", "ג", "ד", "ה", "ו", "ש") else listOf("S", "M", "T", "W", "T", "F", "S")

/**
 * A name saved in English (exercise, muscle, equipment, a built-in day or program) shown in the
 * app's language. Day names the split builder makes from muscles ("Chest, Back & Biceps", "Day 2",
 * "Push B") are put together from their parts. Names the user typed have no Hebrew and stay as they are.
 */
fun shown(name: String): String {
    if (!hebrewOn()) return name
    HebrewText.ofName(name)?.let { return it }
    Regex("""Day (\d+)""").matchEntire(name)?.let { return tr("Day {0}", it.groupValues[1]) }
    Regex("""(.+) ([A-Z]|\+\d+)""").matchEntire(name)?.let { m ->
        val base = shown(m.groupValues[1])
        if (base != m.groupValues[1]) return "$base ${m.groupValues[2]}"
    }
    val parts = name.split(", ", " & ")
    if (parts.size > 1 && parts.all { HebrewText.ofName(it) != null }) {
        val he = parts.map { HebrewText.ofName(it)!! }
        return if (he.size == 2) "${he[0]} ו${he[1]}" else he.dropLast(1).joinToString(", ") + " ו" + he.last()
    }
    return name
}
