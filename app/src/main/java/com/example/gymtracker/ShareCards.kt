package com.example.gymtracker

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// WHAT A SHARE IMAGE SAYS
// ============================================================================

/** Clear is a transparent PNG to lay over your own photo or video, like Strava's stats sticker. */
enum class ShareBackground(val label: String) { DARK(tr("Dark")), LIGHT(tr("Light")), CLEAR(tr("Clear")), PHOTO(tr("Photo")) }

enum class ShareCard(val label: String) { SUMMARY(tr("Summary")), BESTS(tr("New bests")), MUSCLES(tr("Muscles")), STREAK(tr("Streak")) }

@Immutable
data class ShareLift(val name: String, val set: String, val isBest: Boolean)

@Immutable
data class ShareBest(val name: String, val set: String, /** The best before today; null the first time. */ val before: String?)

/** Everything the cards show, worked out once per share. */
@Immutable
data class ShareData(
    val title: String,
    val whenMillis: Long,
    val durationSeconds: Long,
    val workSets: Int,
    val volumeKg: Int,
    /** Reps of the work sets, shown instead of volume for bodyweight workouts. */
    val reps: Int = 0,
    /** Seconds held in timed work sets (planks, hangs), shown when there's no volume or reps. */
    val heldSeconds: Int = 0,
    val lifts: List<ShareLift>,
    val bests: List<ShareBest>,
    /** Sets per muscle group, most first; assisting muscles count half, as recovery counts them. */
    val muscles: List<Pair<String, Float>>,
    /** Volume against the last workout of the same day, in percent; null without one. */
    val volumeChangePct: Int?,
    val workoutNumber: Int,
    val workoutsThisMonth: Int,
    /** Weeks in a row with at least one workout, ending with this one's week. */
    val weekStreak: Int,
    /** Days of this workout's week with a workout (Calendar.DAY_OF_WEEK), this one included. */
    val daysThisWeek: Set<Int>,
    val photoPath: String?
) {
    /** Only cards with something to say: no "0 new bests" card. */
    val cards: List<ShareCard>
        get() = buildList {
            add(ShareCard.SUMMARY)
            if (bests.isNotEmpty()) add(ShareCard.BESTS)
            if (muscles.isNotEmpty()) add(ShareCard.MUSCLES)
            add(ShareCard.STREAK)
        }

    val hasPhoto: Boolean get() = photoPath?.let { it.isNotBlank() && File(it).exists() } == true
}

/** [historyBefore] is every workout logged before this one, as all share buttons pass it. */
fun buildShareData(
    splitTitle: String,
    durationSeconds: Long,
    exercises: List<ExerciseEntry>,
    historyBefore: List<CompletedWorkout>,
    whenMillis: Long,
    photoPath: String?
): ShareData {
    val done = performedExercises(exercises)
    val stats = computeWorkoutStats(done, historyBefore)
    val byExercise = historyByExercise(historyBefore)
    val lifts = ArrayList<ShareLift>()
    val bests = ArrayList<ShareBest>()
    val loads = FloatArray(RECOVERY_GROUPS.size)
    for (ex in done) {
        val records = computeExerciseRecords(ex.name, byExercise[ignoreCaseKey(ex.name)].orEmpty())
        val best = bestSetOf(ex.sets.filter { isSetPersonalRecord(it, records, ex.type) }, ex.type)
        if (best != null) bests += ShareBest(ex.name, formatSet(best, ex.type), records.bestSet?.let { formatSet(it, ex.type) })
        bestSetOf(ex.type, ex.workSets)?.let { lifts += ShareLift(ex.name, formatSet(it, ex.type), best != null) }
        val n = ex.workSets.size
        if (n > 0) muscleLoads(splitTitle, ex.name, ex.targetMuscle).forEachIndexed { i, share -> loads[i] += share * n }
    }
    val muscles = RECOVERY_GROUPS.indices.filter { loads[it] >= 0.5f }.map { RECOVERY_GROUPS[it] to loads[it] }.sortedByDescending { it.second }

    val previous = historyBefore.filter { it.splitTitle == splitTitle }.maxByOrNull { it.id }?.totalVolumeKg ?: 0
    val change = if (previous > 0 && stats.volumeKg > 0) ((stats.volumeKg - previous) * 100f / previous).roundToInt() else null

    val all = historyBefore.map { it.id } + whenMillis
    val thisWeek = weekStart(whenMillis)
    val weeks = all.mapTo(HashSet()) { weekStart(it) }
    var streak = 0
    val cursor = Calendar.getInstance().apply { timeInMillis = thisWeek }
    while (cursor.timeInMillis in weeks) {
        streak++
        cursor.add(Calendar.WEEK_OF_YEAR, -1)
    }
    val month = Calendar.getInstance().apply { timeInMillis = whenMillis }
    fun sameMonth(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }
        .let { it.get(Calendar.YEAR) == month.get(Calendar.YEAR) && it.get(Calendar.MONTH) == month.get(Calendar.MONTH) }

    return ShareData(
        title = shown(splitTitle),
        whenMillis = whenMillis,
        durationSeconds = durationSeconds,
        workSets = stats.workSets,
        volumeKg = stats.volumeKg,
        reps = totalWorkReps(done),
        heldSeconds = done.sumOf { ex -> ex.workSets.sumOf { it.secondCount } },
        lifts = lifts,
        bests = bests,
        muscles = muscles,
        volumeChangePct = change,
        workoutNumber = historyBefore.size + 1,
        workoutsThisMonth = all.count(::sameMonth),
        weekStreak = streak,
        daysThisWeek = all.filter { weekStart(it) == thisWeek }
            .mapTo(HashSet()) { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_WEEK) },
        photoPath = photoPath
    )
}

private fun weekStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    while (get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) add(Calendar.DAY_OF_MONTH, -1)
}.timeInMillis

/**
 * Total volume as something you can picture, like Hevy's "you lifted a truck": the biggest
 * everyday thing the volume adds up to at least one of. Null under one piano.
 */
internal fun volumeComparison(kg: Int): String? {
    val things = listOf(
        Triple(tr("a city bus"), tr("city buses"), 12_000f),
        Triple(tr("an elephant"), tr("elephants"), 6_000f),
        Triple(tr("a small car"), tr("small cars"), 1_200f),
        Triple(tr("a grand piano"), tr("grand pianos"), 480f)
    )
    val (one, many, each) = things.firstOrNull { kg >= it.third } ?: return null
    val n = kg / each
    return if (n < 1.5f) tr("about {0}", one) else tr("about {0} {1}", n.roundToInt(), many)
}

// ============================================================================
// DRAWING (1080 × 1920, the Stories format)
// ============================================================================

private const val W = 1080f
private const val H = 1920f
private const val L = 96f
private const val R = W - 96f

/**
 * Colours and type for one background. Instagram covers roughly the top 250 px (profile) and the
 * bottom 250 px (reply bar), so the cards keep their content between those.
 */
private class Ink(private val context: Context, val bg: ShareBackground) {
    private val palette = if (bg == ShareBackground.LIGHT) LightPalette else DarkPalette
    val clear = bg == ShareBackground.CLEAR
    val text = if (clear) Color.WHITE else palette.textPrimary.toArgb()
    val muted = if (clear) Color.argb(225, 255, 255, 255) else palette.textSecondary.toArgb()
    val faint = if (clear) Color.argb(170, 255, 255, 255) else palette.textTertiary.toArgb()
    val accent = palette.accentPrimary.toArgb()
    /** Accent for text: on Clear, orange text disappears into light photos, so it's white there. */
    val accentText = if (clear) Color.WHITE else accent
    val onAccent = palette.onAccentPrimary.toArgb()
    val track = when {
        clear -> Color.argb(70, 255, 255, 255)
        bg == ShareBackground.LIGHT -> palette.borderStrong.toArgb()
        else -> palette.bgControl.toArgb()
    }
    val line = if (clear) Color.argb(90, 255, 255, 255) else palette.borderSubtle.toArgb()
    val background = palette.bgPrimary.toArgb()
    val good = if (clear) Color.WHITE else palette.successText.toArgb()
    private val faces = HashMap<Int, Typeface>()
    private fun face(res: Int): Typeface = faces.getOrPut(res) {
        runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: Typeface.DEFAULT
    }

    /** The app's Inter files: the display cut for big type, the nearest weight to [weight]. */
    private fun interFor(weight: Int, display: Boolean): Typeface = face(
        if (display) when {
            weight <= 500 -> com.example.gymtracker.R.font.inter_display_regular
            weight <= 650 -> com.example.gymtracker.R.font.inter_display_semibold
            weight <= 750 -> com.example.gymtracker.R.font.inter_display_bold
            else -> com.example.gymtracker.R.font.inter_display_extrabold
        } else when {
            weight <= 450 -> com.example.gymtracker.R.font.inter_regular
            weight <= 550 -> com.example.gymtracker.R.font.inter_medium
            weight <= 650 -> com.example.gymtracker.R.font.inter_semibold
            else -> com.example.gymtracker.R.font.inter_bold
        }
    )

    /** Inter at a weight; display sizes use its display cut and tighter spacing. */
    fun type(color: Int, size: Float, weight: Int = 500, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            val display = size >= 64f
            typeface = interFor(weight, display)
            textSize = size
            this.color = color
            textAlign = align
            fontFeatureSettings = "tnum"
            if (display) letterSpacing = -0.02f
            // Over someone's photo the text has to stand on its own.
            if (clear) setShadowLayer(16f, 0f, 3f, Color.argb(130, 0, 0, 0))
        }

    fun fill(color: Int, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha }
}

private fun Paint.fit(text: String, maxWidth: Float): Paint = apply {
    while (measureText(text) > maxWidth && textSize > 24f) textSize *= 0.94f
}

private fun Paint.ellipsize(text: String, maxWidth: Float): String {
    if (measureText(text) <= maxWidth) return text
    var t = text
    while (t.isNotEmpty() && measureText("$t…") > maxWidth) t = t.dropLast(1)
    return "$t…"
}

private fun shortDate(millis: Long) = (if (hebrewOn()) SimpleDateFormat("EEE, d בMMM", Locale.forLanguageTag("he")) else SimpleDateFormat("EEE, MMM d", Locale.US)).format(Date(millis)).uppercase(Locale.US)

private fun setsText(sets: Float): String {
    val shown = if (sets % 1f == 0f) "${sets.toInt()}" else String.format(Locale.US, "%.1f", sets)
    return if (shown == "1") tr("1 set") else tr("{0} sets", shown)
}

/** Draws one card. [scale] < 1 makes a smaller copy for the preview. */
fun renderShareCard(context: Context, data: ShareData, card: ShareCard, background: ShareBackground, scale: Float = 1f): Bitmap {
    val bmp = Bitmap.createBitmap((W * scale).roundToInt(), (H * scale).roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.scale(scale, scale)
    val bg = if (background == ShareBackground.PHOTO && !data.hasPhoto) ShareBackground.DARK else background
    val ink = Ink(context, bg)
    val onPhoto = bg == ShareBackground.PHOTO
    drawBackground(canvas, ink, data, scale)
    when (card) {
        ShareCard.SUMMARY -> drawSummary(canvas, ink, data, onPhoto)
        ShareCard.BESTS -> drawBests(canvas, ink, data)
        ShareCard.MUSCLES -> drawMuscles(canvas, ink, data)
        ShareCard.STREAK -> drawStreak(canvas, ink, data)
    }
    drawBrand(canvas, ink)
    return bmp
}

private fun drawBackground(canvas: Canvas, ink: Ink, data: ShareData, scale: Float) {
    when (ink.bg) {
        ShareBackground.CLEAR -> Unit
        ShareBackground.PHOTO -> {
            val opts = BitmapFactory.Options().apply { inSampleSize = if (scale < 0.75f) 2 else 1 }
            val photo = data.photoPath?.let { BitmapFactory.decodeFile(it, opts) }
            if (photo == null) {
                canvas.drawColor(ink.background)
                return
            }
            val s = maxOf(W / photo.width, H / photo.height)
            val m = Matrix().apply {
                postScale(s, s)
                postTranslate((W - photo.width * s) / 2f, (H - photo.height * s) / 2f)
            }
            canvas.drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
            photo.recycle()
            canvas.drawRect(0f, 0f, W, H, Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, H,
                    intArrayOf(Color.argb(140, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(190, 10, 10, 12), Color.argb(245, 10, 10, 12)),
                    floatArrayOf(0f, 0.28f, 0.52f, 1f),
                    Shader.TileMode.CLAMP
                )
            })
        }
        else -> {
            canvas.drawColor(ink.background)
            val a = ink.accent
            val strength = if (ink.bg == ShareBackground.LIGHT) 45 else 80
            canvas.drawRect(0f, 0f, W, H, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(W * 0.9f, H * 0.06f, 1000f, Color.argb(strength, Color.red(a), Color.green(a), Color.blue(a)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            canvas.drawRect(0f, 0f, W, H, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(0f, H, 900f, Color.argb(strength / 3, Color.red(a), Color.green(a), Color.blue(a)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
        }
    }
}

private fun drawOverline(canvas: Canvas, ink: Ink, text: String, y: Float, color: Int = ink.accentText) {
    canvas.drawText(text, L, y, ink.type(color, 34f, 650).apply { letterSpacing = 0.1f }.fit(text, R - L))
}

/** The small mark at the bottom: present, not loud. */
private fun drawBrand(canvas: Canvas, ink: Ink) {
    val paint = ink.type(ink.faint, 34f, 600)
    val label = "Kilonaut"
    val width = 28f + 16f + paint.measureText(label)
    val x = (W - width) / 2
    val y = 1720f
    canvas.drawCircle(x + 14f, y - 12f, 14f, ink.fill(ink.accent))
    canvas.drawText(label, x + 44f, y, paint)
}

/** Three numbers side by side with hairlines between them. */
private fun drawStatRow(canvas: Canvas, ink: Ink, top: Float, items: List<Triple<String, String, Boolean>>) {
    val colW = (R - L) / items.size
    items.forEachIndexed { i, (value, label, highlight) ->
        val x = L + i * colW + if (i == 0) 0f else 40f
        if (i > 0) canvas.drawRect(L + i * colW, top, L + i * colW + 2f, top + 120f, ink.fill(ink.line))
        canvas.drawText(value, x, top + 66f, ink.type(if (highlight) ink.accentText else ink.text, 64f, 750).fit(value, colW - 48f))
        canvas.drawText(label, x, top + 116f, ink.type(ink.muted, 34f, 450))
    }
}

private fun drawSummary(canvas: Canvas, ink: Ink, d: ShareData, onPhoto: Boolean) {
    val unit = Units.current
    // Over a photo the picture is the hero: everything sits in the lower half, without the list.
    var y = if (onPhoto) 1000f else 330f
    drawOverline(canvas, ink, tr("{0}  ·  WORKOUT {1}", shortDate(d.whenMillis), d.workoutNumber), y)
    y += if (onPhoto) 120f else 150f
    canvas.drawText(d.title, L, y, ink.type(ink.text, 132f, 800).fit(d.title, R - L))
    val focus = d.muscles.take(3).joinToString(", ") { shown(it.first) }
    if (focus.isNotEmpty()) {
        y += 70f
        canvas.drawText(focus, L, y, ink.type(ink.muted, 42f, 450).fit(focus, R - L))
    }

    y += if (onPhoto) 190f else 250f
    if (d.volumeKg > 0) {
        val number = String.format(Locale.US, "%,d", unit.fromKg(d.volumeKg.toFloat()).roundToInt())
        val big = ink.type(ink.text, if (onPhoto) 170f else 220f, 800)
        canvas.drawText(number, L, y, big)
        canvas.drawText(unit.label, L + big.measureText(number) + 20f, y, ink.type(ink.muted, 64f, 600))
        val compare = volumeComparison(d.volumeKg)
        y += 64f
        canvas.drawText(listOfNotNull(tr("lifted"), compare).joinToString(", "), L, y, ink.type(ink.muted, 42f, 450))
    } else {
        canvas.drawText("${d.workSets}", L, y, ink.type(ink.text, 220f, 800))
        y += 64f
        canvas.drawText(tr("sets done"), L, y, ink.type(ink.muted, 42f, 450))
    }
    d.volumeChangePct?.takeIf { it != 0 }?.let { pct ->
        y += 40f
        val up = pct > 0
        val label = tr("{0} {1}% vs last {2}", if (up) "↑" else "↓", kotlin.math.abs(pct), d.title)
        val paint = ink.type(if (up) ink.good else ink.muted, 36f, 600).fit(label, R - L - 48f)
        val w = paint.measureText(label) + 48f
        canvas.drawRoundRect(RectF(L, y, L + w, y + 64f), 32f, 32f, ink.fill(if (up && !ink.clear) ink.good else ink.text, if (ink.clear) 50 else 36))
        canvas.drawText(label, L + 24f, y + 44f, paint)
        y += 64f
    }

    y += 80f
    drawStatRow(
        canvas, ink, y,
        listOf(
            Triple(formatDuration(d.durationSeconds), tr("Time"), false),
            Triple("${d.workSets}", tr("Sets"), false),
            // No "0 new bests": without one, the slot counts exercises instead.
            if (d.bests.isNotEmpty()) Triple("${d.bests.size}", if (d.bests.size == 1) tr("New best") else tr("New bests"), true)
            else Triple("${d.lifts.size}", if (d.lifts.size == 1) tr("Exercise") else tr("Exercises"), false)
        )
    )
    if (onPhoto) return

    y += 210f
    val rowH = 72f
    val maxRows = ((1630f - y) / rowH).toInt().coerceIn(0, 6)
    if (d.lifts.isEmpty() || maxRows == 0) return
    drawOverline(canvas, ink, tr("TOP SETS"), y, ink.faint)
    val shown = if (d.lifts.size > maxRows) d.lifts.take(maxRows - 1) else d.lifts
    shown.forEach { lift ->
        y += rowH
        val setPaint = ink.type(if (lift.isBest) ink.accentText else ink.text, 42f, 650, Paint.Align.RIGHT)
        val namePaint = ink.type(ink.text, 42f, 450)
        val indent = if (lift.isBest) 40f else 0f
        if (lift.isBest) canvas.drawCircle(L + 12f, y - 14f, 10f, ink.fill(ink.accent))
        canvas.drawText(namePaint.ellipsize(shown(lift.name), R - L - indent - setPaint.measureText(lift.set) - 32f), L + indent, y, namePaint)
        canvas.drawText(lift.set, R, y, setPaint)
    }
    if (shown.size < d.lifts.size) {
        y += rowH
        canvas.drawText(tr("+{0} more", d.lifts.size - shown.size), L, y, ink.type(ink.faint, 38f, 450))
    }
}

/** A round "PR" medal, so the card reads as a record before any text does. */
private fun drawMedal(canvas: Canvas, ink: Ink, cx: Float, cy: Float, r: Float) {
    canvas.drawCircle(cx, cy, r, ink.fill(ink.accent))
    canvas.drawCircle(cx, cy, r - 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = ink.onAccent; alpha = 90 })
    val p = ink.type(ink.onAccent, r * 0.72f, 850, Paint.Align.CENTER).apply { clearShadowLayer() }
    canvas.drawText("PR", cx, cy + p.textSize * 0.36f, p)
}

private fun drawBests(canvas: Canvas, ink: Ink, d: ShareData) {
    drawOverline(canvas, ink, tr("NEW BESTS  ·  {0}", shortDate(d.whenMillis)), 330f)
    drawMedal(canvas, ink, L + 95f, 545f, 95f)
    val count = d.bests.size
    canvas.drawText("$count", L + 240f, 610f, ink.type(ink.text, 240f, 800))
    val label = if (count == 1) tr("new best") else tr("new bests")
    canvas.drawText(label, L + 240f + ink.type(ink.text, 240f, 800).measureText("$count") + 24f, 610f, ink.type(ink.muted, 56f, 600))
    canvas.drawText(tr("in {0}", d.title), L + 240f, 680f, ink.type(ink.muted, 42f, 450).fit(tr("in {0}", d.title), R - L - 240f))

    var y = 830f
    val maxRows = 4
    val shown = if (d.bests.size > maxRows) d.bests.take(maxRows - 1) else d.bests
    // Fewer bests get bigger rows rather than an empty bottom half.
    val big = shown.size <= 2
    val blockH = if (big) 300f else 190f
    shown.forEachIndexed { i, best ->
        if (i > 0) canvas.drawRect(L, y - 40f, R, y - 38f, ink.fill(ink.line))
        val name = ink.type(ink.muted, if (big) 48f else 42f, 550)
        canvas.drawText(name.ellipsize(shown(best.name), R - L), L, y + 30f, name)
        canvas.drawText(best.set, L, y + if (big) 140f else 106f, ink.type(ink.text, if (big) 104f else 72f, 800).fit(best.set, R - L))
        val before = best.before?.let { tr("Before: {0}", it) } ?: tr("First time logged")
        canvas.drawText(before, L, y + if (big) 200f else 150f, ink.type(ink.faint, if (big) 40f else 34f, 450).fit(before, R - L))
        y += blockH
    }
    if (shown.size < d.bests.size) canvas.drawText(tr("+{0} more", d.bests.size - shown.size), L, y + 20f, ink.type(ink.faint, 38f, 450))
}

private fun drawMuscles(canvas: Canvas, ink: Ink, d: ShareData) {
    drawOverline(canvas, ink, tr("MUSCLES WORKED  ·  {0}", d.title.uppercase(Locale.US)), 330f)
    val (topKey, topSets) = d.muscles.first()
    val top = shown(topKey)
    canvas.drawText(top, L, 500f, ink.type(ink.text, 150f, 800).fit(top, R - L))
    canvas.drawText(tr("worked most, {0}", setsText(topSets)), L, 570f, ink.type(ink.muted, 42f, 450))

    var y = 730f
    val max = topSets
    val rows = d.muscles.take(7)
    // Fewer muscles get taller rows rather than an empty bottom half.
    val rowH = ((1560f - y) / rows.size).coerceIn(124f, 210f)
    val barH = if (rowH > 160f) 34f else 22f
    rows.forEachIndexed { i, (muscle, sets) ->
        canvas.drawText(shown(muscle), L, y, ink.type(ink.text, if (rowH > 160f) 52f else 44f, 600))
        canvas.drawText(setsText(sets), R, y, ink.type(ink.muted, if (rowH > 160f) 44f else 38f, 500, Paint.Align.RIGHT))
        val barTop = y + 28f
        canvas.drawRoundRect(RectF(L, barTop, R, barTop + barH), barH / 2, barH / 2, ink.fill(ink.track))
        val w = (R - L) * (sets / max).coerceIn(0.04f, 1f)
        // The top muscle in full accent, the rest a step quieter.
        canvas.drawRoundRect(RectF(L, barTop, L + w, barTop + barH), barH / 2, barH / 2, ink.fill(ink.accent, if (i == 0) 255 else 170))
        y += rowH
    }
    val note = tr("Helping muscles count as half a set")
    canvas.drawText(note, L, minOf(y + 10f, 1620f), ink.type(ink.faint, 32f, 450))
}

private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
    val path = Path().apply {
        moveTo(cx - size * 0.45f, cy)
        lineTo(cx - size * 0.12f, cy + size * 0.32f)
        lineTo(cx + size * 0.48f, cy - size * 0.34f)
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = size * 0.16f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
    })
}

private fun drawStreak(canvas: Canvas, ink: Ink, d: ShareData) {
    drawOverline(canvas, ink, tr("CONSISTENCY  ·  {0}", shortDate(d.whenMillis)), 330f)
    val streaking = d.weekStreak >= 2
    val hero = if (streaking) "${d.weekStreak}" else "${d.workoutNumber}"
    val heroPaint = ink.type(ink.text, 300f, 800)
    canvas.drawText(hero, L, 640f, heroPaint)
    canvas.drawText(if (streaking) tr("weeks in a row") else if (d.workoutNumber == 1) tr("workout logged") else tr("workouts logged"), L, 730f, ink.type(ink.text, 60f, 650))
    val sub = if (streaking) {
        val since = Calendar.getInstance().apply { timeInMillis = weekStart(d.whenMillis); add(Calendar.WEEK_OF_YEAR, -(d.weekStreak - 1)) }
        tr("Training every week since {0}", DateUtils.formatShortDay(since.time))
    } else tr("One workout at a time")
    canvas.drawText(sub, L, 800f, ink.type(ink.muted, 42f, 450).fit(sub, R - L))

    // This week, day by day, starting on the locale's first day.
    drawOverline(canvas, ink, tr("THIS WEEK"), 980f, ink.faint)
    val first = Calendar.getInstance().firstDayOfWeek
    val today = Calendar.getInstance().apply { timeInMillis = d.whenMillis }.get(Calendar.DAY_OF_WEEK)
    val step = (R - L) / 7f
    for (i in 0 until 7) {
        val day = ((first - 1 + i) % 7) + 1
        val cx = L + step * i + step / 2
        val cy = 1090f
        val r = 46f
        when {
            day in d.daysThisWeek -> {
                canvas.drawCircle(cx, cy, r, ink.fill(ink.accent))
                drawCheck(canvas, cx, cy, 44f, ink.onAccent)
            }
            else -> canvas.drawCircle(cx, cy, r, ink.fill(ink.track))
        }
        if (day == today) {
            canvas.drawCircle(cx, cy, r + 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = ink.text; alpha = 160 })
        }
        canvas.drawText("SMTWTFS"[day - 1].toString(), cx, cy + 110f, ink.type(if (day == today) ink.text else ink.faint, 36f, 600, Paint.Align.CENTER))
    }

    drawStatRow(
        canvas, ink, 1330f,
        listOf(
            Triple("${d.workoutsThisMonth}", tr("This month"), false),
            Triple("${d.workoutNumber}", tr("All time"), false),
            Triple(if (d.weekStreak == 1) tr("1 week") else tr("{0} weeks", d.weekStreak), tr("Streak"), streaking)
        )
    )
}

// ============================================================================
// THE SHARE SHEET
// ============================================================================

/** Set by any Share button; [ShareSheetHost] shows the sheet over whatever is on screen. */
object ShareRequests {
    var pending by mutableStateOf<ShareData?>(null)
}

/**
 * Opens the share sheet for a workout. [historyBefore] holds the workouts logged before it;
 * [whenMillis] is when it was done (now, for the one just finished).
 */
fun shareWorkoutCardImage(
    splitTitle: String,
    durationSeconds: Long,
    exercises: List<ExerciseEntry>,
    historyBefore: List<CompletedWorkout>,
    photoPath: String? = null,
    whenMillis: Long = System.currentTimeMillis()
) {
    ShareRequests.pending = buildShareData(splitTitle, durationSeconds, exercises, historyBefore, whenMillis, photoPath)
}

@Composable
fun ShareSheetHost() {
    val data = ShareRequests.pending ?: return
    ShareWorkoutSheet(data, onDismiss = { ShareRequests.pending = null })
}

@Composable
private fun ShareWorkoutSheet(data: ShareData, onDismiss: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cards = remember(data) { data.cards }
    val pager = rememberPagerState { cards.size }
    var background by remember { mutableStateOf(if (data.hasPhoto) ShareBackground.PHOTO else ShareBackground.DARK) }
    var busy by remember { mutableStateOf(false) }
    val options = ShareBackground.entries.filter { it != ShareBackground.PHOTO || data.hasPhoto }

    fun export(save: Boolean) {
        if (busy) return
        busy = true
        val card = cards[pager.currentPage]
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = renderShareCard(context, data, card, background)
                    try {
                        if (save) saveToPictures(context, bmp, background == ShareBackground.CLEAR) else writeShareFile(context, bmp, background == ShareBackground.CLEAR)
                    } finally {
                        bmp.recycle()
                    }
                }.getOrNull()
            }
            busy = false
            when {
                ok == null -> AppSnackbar.show(if (save) tr("Couldn't save the image") else tr("Couldn't create the image"))
                save -> AppSnackbar.show(tr("Saved to Pictures/Kilonaut"))
                ok is File -> openShareChooser(context, ok, data.title, background == ShareBackground.CLEAR)
            }
        }
    }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(bottom = 20.dp).navigationBarsPadding()) {
            Text(tr("Share workout"), style = AppType.title, color = P.textPrimary, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                tr("Swipe for more cards. {0} card.", cards[pager.currentPage].label),
                style = AppType.caption,
                color = P.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(14.dp))
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 72.dp),
                pageSpacing = 14.dp,
                modifier = Modifier.fillMaxWidth().height(400.dp)
            ) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SharePreview(data, cards[page], background)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                cards.indices.forEach { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) P.textPrimary else P.borderStrong)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(16.dp))
                ChoiceChips(options = options.map { it to it.label }, selected = background, onSelect = { background = it })
                if (background == ShareBackground.CLEAR) {
                    Text(
                        tr("No background: save it, then add it as a sticker over your own photo or video."),
                        style = AppType.caption,
                        color = P.textTertiary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(tr("Share"), { export(save = false) }, Modifier.weight(1f), icon = Icons.Rounded.IosShare, enabled = !busy)
                    // Saving to Pictures without a storage permission needs Android 10's MediaStore.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        SecondaryButton(tr("Save"), { export(save = true) }, Modifier.weight(0.6f), icon = Icons.Rounded.FileDownload, enabled = !busy, height = 54.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SharePreview(data: ShareData, card: ShareCard, background: ShareBackground) {
    val P = LocalPalette.current
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, data, card, background) {
        value = withContext(Dispatchers.Default) { renderShareCard(context, data, card, background, scale = 0.4f).asImageBitmap() }
    }
    val checker = if (P.isDark) P.bgControl else P.borderSubtle
    Box(
        Modifier
            .fillMaxHeight()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(18.dp))
            .background(P.bgCard)
            .then(
                // A checkerboard shows that Clear really has no background.
                if (background == ShareBackground.CLEAR) Modifier.drawBehind {
                    val cell = 12.dp.toPx()
                    for (row in 0..(size.height / cell).toInt()) for (col in 0..(size.width / cell).toInt()) {
                        if ((row + col) % 2 == 0) drawRect(checker, Offset(col * cell, row * cell), Size(cell, cell))
                    }
                } else Modifier
            )
    ) {
        image?.let { Image(it, contentDescription = tr("{0} card", card.label), modifier = Modifier.fillMaxSize()) }
    }
}

private fun writeShareFile(context: Context, bmp: Bitmap, png: Boolean): File {
    // Share images are throwaway: keep them in cache and replace the previous one.
    val folder = File(context.cacheDir, "share_cards").apply { mkdirs() }
    folder.listFiles()?.forEach { it.delete() }
    val file = File(folder, "workout_${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
    FileOutputStream(file).use { out ->
        if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }
    return file
}

private fun openShareChooser(context: Context, file: File, title: String, png: Boolean) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (png) "image/png" else "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, tr("{0} done. Logged with Kilonaut.", title))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, tr("Share workout")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun saveToPictures(context: Context, bmp: Bitmap, png: Boolean) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Kilonaut ${SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())}.${if (png) "png" else "jpg"}")
        put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kilonaut")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No place to save")
    try {
        resolver.openOutputStream(uri)?.use { out ->
            if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: error("Couldn't write")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
