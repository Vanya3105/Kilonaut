package com.example.gymtracker.wear

import com.example.gymtracker.watchlink.WatchState
import java.util.Locale

/**
 * The watch's own words, in the phone app's language (sent with every state), or the watch's
 * until the phone has answered. Names of workouts and exercises come from the phone already
 * translated; only the labels around them are here.
 */
object WatchText {
    @Volatile
    var hebrew: Boolean = Locale.getDefault().language.let { it == "he" || it == "iw" }
        private set

    /** Follows [state]'s language; nothing changes without one. */
    fun follow(state: WatchState?) {
        if (state != null) hebrew = state.language == "he"
    }

    internal fun set(hebrew: Boolean) {
        this.hebrew = hebrew
    }

    private val he = mapOf(
        "Phone not connected" to "הטלפון לא מחובר",
        "Waiting for phone…" to "ממתין לטלפון…",
        "Connecting…" to "מתחבר…",
        "Keep your phone nearby with Kilonaut installed" to "שמרו את הטלפון קרוב, עם Kilonaut מותקנת",
        "No workout" to "אין אימון",
        "Start one on your phone" to "התחילו אחד בטלפון",
        "Check your phone" to "הסתכלו בטלפון",
        "Open on phone" to "פתיחה בטלפון",
        "Start a workout" to "התחלת אימון",
        "Starting…" to "מתחיל…",
        "Up next" to "הבא בתור",
        "Workout overview" to "סקירת האימון",
        "Set done" to "סט בוצע",
        "Set {0} of {1}" to "סט {0} מתוך {1}",
        "Set {0}" to "סט {0}",
        "Warm-up" to "חימום",
        "Drop set" to "דרופ סט",
        "{0} left" to "נשארו {0}",
        "Turn the bezel to change {0}" to "סובבו את הבזל כדי לשנות {0}",
        "Next" to "הבא",
        "Last set done" to "הסט האחרון בוצע",
        "Undo set" to "ביטול הסט",
        "Skip" to "דלג",
        "All sets done" to "כל הסטים בוצעו",
        "Finish workout" to "סיום האימון",
        "Undo last set" to "ביטול הסט האחרון",
        "Done" to "סיום",
        "{0} of {1} sets" to "{0} מתוך {1} סטים",
        "Tick a set to finish here, or discard on your phone" to "סמנו סט כדי לסיים כאן, או בטלו בטלפון",
        "Finish {0}?" to "לסיים את {0}?",
        "1 set isn't ticked. It's saved as it is." to "סט אחד לא סומן. האימון נשמר כמו שהוא.",
        "{0} sets aren't ticked. It's saved as it is." to "{0} סטים לא סומנו. האימון נשמר כמו שהוא.",
        "It's saved to your history." to "הוא יישמר בהיסטוריה.",
        "Keep going" to "להמשיך",
        "Finish and save" to "סיום ושמירה",
        "Add 15 seconds" to "הוספת 15 שניות",
        "+15" to "‎+15",
        "Skip rest" to "דילוג על המנוחה",
        "Heart rate" to "דופק",
        "bpm" to "פעימות",
        "One more set" to "עוד סט",
        "Add a set" to "הוספת סט",
        "Workout saved" to "האימון נשמר",
        "set" to "סט",
        "sets" to "סטים",
        "volume" to "נפח",
        "1 new best" to "שיא חדש אחד",
        "{0} new bests" to "{0} שיאים חדשים",
        "{0} min" to "{0} דק׳",
        "{0} h {1} min" to "{0} שע׳ {1} דק׳",
        "Resting" to "מנוחה",
        "Rest" to "מנוחה",
        "Until {0}" to "עד {0}",
        "Next: {0}" to "הבא: {0}",
        "Open to finish it" to "פתחו כדי לסיים",
        "Tap Start to begin" to "הקישו על התחלה",
        "Start" to "התחלה",
        "Open" to "פתיחה",
        "Workout in progress" to "אימון בתהליך",
        "+15s" to "\u200E+15",
        "Rest #time#" to "מנוחה #time#"
    )

    /** [english] in the current language, with {0}, {1}… filled from [args]. */
    fun t(english: String, vararg args: Any?): String {
        var s = if (hebrew) he[english] ?: english else english
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }

    /** Every English text the watch uses, for the test that each has Hebrew. */
    internal val english: Set<String> get() = he.keys
}

/** Shorthand for [WatchText.t]. */
internal fun t(english: String, vararg args: Any?): String = WatchText.t(english, *args)
