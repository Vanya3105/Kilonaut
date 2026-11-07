package com.example.gymtracker

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * Settings for one placed widget (any of the three kinds): colours, background, how see-through
 * it is and what it shows, with a live preview over different wallpapers and in light or dark
 * mode. Opens when a widget is added or reconfigured.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_CANCELED, resultValue)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val kind = widgetKindFor(this, appWidgetId)
        val appTheme = LocalStorage.loadSettings(this).themeChoice

        setContent {
            GymTheme(paletteFor(this, appTheme)) {
                WidgetSettingsScreen(
                    appWidgetId = appWidgetId,
                    kind = kind,
                    onSave = { settings ->
                        LocalStorage.saveWidgetSettings(this, appWidgetId, settings)
                        LocalStorage.flush(this)
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    },
                    // Closing keeps the widget with its current (or default) look.
                    onClose = {
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    }
                )
            }
        }
    }
}

/** What each kind of widget is for, in one line. */
private fun widgetBlurb(kind: String) = when (kind) {
    WIDGET_KIND_RECOVERY -> tr("How rested each muscle is, least rested first.")
    WIDGET_KIND_WEEKLY -> tr("Your week at a glance: workouts done, the days you trained, what's next.")
    WIDGET_KIND_WEIGHT -> tr("Your weight and its trend. Tap it to log a weigh-in.")
    else -> tr("Your next workout, then the set you're on: tick it off without opening the app.")
}

/** A wallpaper stand-in behind the preview, so the widget can be judged on what it will sit on. */
private enum class Backdrop(val label: String, val brush: Brush) {
    LIGHT(tr("Light"), Brush.linearGradient(listOf(Color(0xFFF3EFE9), Color(0xFFD9E2EC), Color(0xFFEDE3D9)))),
    DARK(tr("Dark"), Brush.linearGradient(listOf(Color(0xFF0E1320), Color(0xFF1F2A3D), Color(0xFF0B0B10)))),
    PHOTO(tr("Photo"), Brush.linearGradient(listOf(Color(0xFF2F5D7C), Color(0xFFE08A5B), Color(0xFFF4C27A), Color(0xFF3A2E4F))))
}

@Composable
private fun WidgetSettingsScreen(appWidgetId: Int, kind: String, onSave: (WidgetSettings) -> Unit, onClose: () -> Unit) {
    val P = LocalPalette.current
    val context = LocalContext.current
    var settings by remember { mutableStateOf(LocalStorage.loadWidgetSettings(context, appWidgetId, kind)) }
    var backdrop by remember { mutableStateOf(Backdrop.PHOTO) }
    // Which of the widget's two looks the preview shows.
    var previewNight by remember { mutableStateOf(isNightMode(context)) }
    val title = when (kind) {
        WIDGET_KIND_RECOVERY -> tr("Recovery widget")
        WIDGET_KIND_WEEKLY -> tr("Weekly widget")
        WIDGET_KIND_WEIGHT -> tr("Body weight widget")
        else -> tr("Workout widget")
    }
    val clear = isClear(settings)

    Column(Modifier.fillMaxSize().background(P.bgPrimary).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.Rounded.Close, tr("Close"), onClose, container = Color.Transparent, tint = P.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                tr("Reset"),
                style = AppType.label,
                color = P.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { settings = defaultWidgetSettings(kind) }.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(title, style = AppType.display, color = P.textPrimary)
            Text(widgetBlurb(kind), style = AppType.body, color = P.textSecondary, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))

            WidgetPreview(kind, settings, backdrop, previewNight)
            Spacer(Modifier.height(10.dp))
            // Preview controls: the wallpaper behind it, and the phone in light or dark mode.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Backdrop.entries.forEach { b ->
                    val isSel = b == backdrop
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(b.brush)
                            .border(if (isSel) 2.dp else 1.dp, if (isSel) P.textPrimary else P.borderStrong, CircleShape)
                            .pressable(pressedScale = 0.9f, role = Role.RadioButton) { backdrop = b }
                            .semantics { selected = isSel; contentDescription = tr("{0} wallpaper", b.label) }
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(P.bgControl)
                        .pressable(pressedScale = 0.95f) { previewNight = !previewNight }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (previewNight) Icons.Rounded.DarkMode else Icons.Rounded.LightMode, null, tint = P.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (previewNight) tr("Dark mode") else tr("Light mode"), style = AppType.caption, color = P.textPrimary)
                }
            }
            Spacer(Modifier.height(22.dp))

            SettingsSection(tr("Look")) {
                Text(tr("Colours"), style = AppType.label, color = P.textSecondary)
                Spacer(Modifier.height(8.dp))
                val themes = buildList {
                    add(THEME_SYSTEM to tr("Auto"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(WIDGET_THEME_WALLPAPER to tr("Wallpaper"))
                    add(THEME_LIGHT to tr("Light"))
                    add(THEME_DARK to tr("Dark"))
                }
                SegmentedControl(options = themes, selected = settings.theme, onSelect = { settings = settings.copy(theme = it) })
                Text(
                    when (settings.theme) {
                        THEME_SYSTEM -> tr("Light or dark with your phone, switching on its own.")
                        WIDGET_THEME_WALLPAPER -> tr("Colours from your wallpaper, light or dark with your phone.")
                        THEME_LIGHT -> tr("Always light.")
                        else -> tr("Always dark.")
                    },
                    style = AppType.caption,
                    color = P.textTertiary,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(tr("Background"), style = AppType.label, color = P.textSecondary)
                Spacer(Modifier.height(8.dp))
                SegmentedControl(
                    options = listOf(WIDGET_STYLE_STANDARD to tr("Plain"), WIDGET_STYLE_TINTED to tr("Tinted")),
                    selected = settings.style,
                    onSelect = { settings = settings.copy(style = it) }
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("See-through"), style = AppType.label, color = P.textSecondary, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            settings.opacityPercent >= 100 -> tr("Solid")
                            clear -> tr("Clear")
                            else -> "${100 - settings.opacityPercent}%"
                        },
                        style = AppType.label.copy(fontFeatureSettings = "tnum"),
                        color = P.textPrimary
                    )
                }
                // Left solid, right clear: "more see-through" as people think of it.
                Slider(
                    value = (100 - settings.opacityPercent).toFloat(),
                    onValueChange = { settings = settings.copy(opacityPercent = 100 - (it / 5f).roundToInt() * 5) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = P.accentPrimary, activeTrackColor = P.accentPrimary, inactiveTrackColor = P.bgControl)
                )
                if (clear) Text(tr("Text turns dark or light to suit your wallpaper."), style = AppType.caption, color = P.textTertiary)
            }
            Spacer(Modifier.height(14.dp))
            SettingsSection(tr("Shows")) {
                widgetOptionLabels(kind).forEach { (key, label) ->
                    ToggleRow(label, widgetOptionHint(key), checked = key in settings.options, onCheckedChange = { on ->
                        settings = settings.copy(options = if (on) settings.options + key else settings.options - key)
                    })
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                tr("To change this later, long-press the widget and choose its settings."),
                style = AppType.caption,
                color = P.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(20.dp))
        }
        PrimaryButton(
            tr("Done"),
            onClick = { onSave(settings) },
            icon = Icons.Rounded.Check,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth()
        )
    }
}

private fun widgetOptionHint(key: String): String? = when (key) {
    "controls" -> tr("−/+ for weight and reps on the widget. Off: the set as text and one Done button.")
    "weekly" -> tr("Workouts done this week, next to Up next.")
    "sets" -> tr("Hard sets each muscle got this week.")
    "hours" -> tr("When each muscle was last trained.")
    "volume" -> tr("Total weight lifted this week.")
    "next" -> tr("The workout that's up next.")
    else -> null
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val P = LocalPalette.current
    Text(title, style = AppType.overline, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    AppCard(Modifier.fillMaxWidth(), content = content)
}

/** The real widget at its usual size, over the chosen wallpaper and in the chosen phone mode. */
@Composable
private fun WidgetPreview(kind: String, settings: WidgetSettings, backdrop: Backdrop, night: Boolean) {
    val context = LocalContext.current
    val (wDp, hDp) = when (kind) {
        WIDGET_KIND_WEEKLY -> 170 to 170
        WIDGET_KIND_WEIGHT -> 300 to 130
        else -> 320 to 150
    }
    // The workout widget takes its colours from the context's mode, as on the home screen.
    val modeContext = remember(night) {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        context.createConfigurationContext(config)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(backdrop.brush),
        contentAlignment = Alignment.Center
    ) {
        if (kind == WIDGET_KIND_WORKOUT) {
            AndroidView(
                factory = { FrameLayout(it) },
                update = { frame ->
                    frame.removeAllViews()
                    frame.addView(buildWorkoutWidgetViews(modeContext, settings, compact = false).apply(modeContext, frame))
                },
                modifier = Modifier.size(wDp.dp, hDp.dp)
            )
        } else {
            val bitmap = remember(settings, kind, night) {
                when (kind) {
                    WIDGET_KIND_RECOVERY -> MuscleFatigueWidgetProvider.render(modeContext, settings, wDp, hDp, night)
                    WIDGET_KIND_WEIGHT -> BodyWeightWidgetProvider.render(modeContext, settings, wDp, hDp, night)
                    else -> SquarePulseWidgetProvider.render(modeContext, settings, wDp, hDp, night)
                }
            }
            Image(bitmap.asImageBitmap(), contentDescription = tr("Widget preview"), contentScale = ContentScale.Fit, modifier = Modifier.size(wDp.dp, hDp.dp))
        }
    }
}
