package de.lukasrunge.verweil.ui

import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.core.model.Activity
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlin.math.roundToInt

/** Clock time in the user's 12 or 24 hour format. */
fun formatTime(context: Context, epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

/** "2 h 10 min", "25 min", "< 1 min". */
fun formatDuration(resources: Resources, durationMs: Long): String {
    val minutes = (durationMs / 60_000).toInt()
    return when {
        minutes < 1 -> resources.getString(R.string.duration_under_minute)
        minutes < 60 -> resources.getString(R.string.duration_minutes, minutes)
        minutes % 60 == 0 -> resources.getString(R.string.duration_hours, minutes / 60)
        else -> resources.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
    }
}

/** "850 m", "2.4 km", "12 km"; decimals in the user's locale. */
fun formatDistance(resources: Resources, meters: Double): String {
    val locale = resources.configuration.locales[0]
    return when {
        meters < 1000 -> resources.getString(R.string.distance_meters, ((meters / 10).roundToInt() * 10))
        else -> {
            val km = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (meters < 10_000) 1 else 0 }
            resources.getString(R.string.distance_kilometers, km.format(meters / 1000))
        }
    }
}

@Composable
fun time(epochMs: Long): String = formatTime(LocalContext.current, epochMs)

@Composable
fun duration(durationMs: Long): String = formatDuration(LocalResources.current, durationMs)

@Composable
fun distance(meters: Double): String = formatDistance(LocalResources.current, meters)

/** "Today", "Yesterday", or the date. */
@Composable
fun dayLabel(day: LocalDate, today: LocalDate): String {
    val resources = LocalResources.current
    return when (day) {
        today -> resources.getString(R.string.day_today)
        today.minusDays(1) -> resources.getString(R.string.day_yesterday)
        else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(resources.configuration.locales[0]))
    }
}

/** How the user moved, as a short phrase: "On foot", "By bike". */
fun Activity?.movingLabel(): Int = when (this) {
    Activity.WALKING -> R.string.moving_walking
    Activity.RUNNING -> R.string.moving_running
    Activity.CYCLING -> R.string.moving_cycling
    Activity.VEHICLE -> R.string.moving_vehicle
    else -> R.string.moving_unknown
}

fun Activity?.movingIcon(): Int = when (this) {
    Activity.RUNNING -> R.drawable.ic_directions_run
    Activity.CYCLING -> R.drawable.ic_directions_bike
    Activity.VEHICLE -> R.drawable.ic_directions_car
    else -> R.drawable.ic_directions_walk
}
