package de.lukasrunge.verweil.ui

import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.core.timeline.TravelMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.roundToInt

/** Clock time in the user's 12 or 24 hour format. */
fun formatTime(context: Context, epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

/** Date and time, short, e.g. for when something was last fetched. */
@Composable
fun dateTime(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, LocalResources.current.configuration.locales[0])
        .format(Date(epochMs))

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

/** "Today", "Yesterday", or the day with its weekday: "Wed, 16 Sep", with the year only for other years. */
@Composable
fun dayLabel(day: LocalDate, today: LocalDate): String {
    val resources = LocalResources.current
    val locale = resources.configuration.locales[0]
    return when (day) {
        today -> resources.getString(R.string.day_today)
        today.minusDays(1) -> resources.getString(R.string.day_yesterday)
        else -> {
            val skeleton = if (day.year == today.year) "EEEdMMM" else "EEEdMMMyyyy"
            day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
        }
    }
}

/** How the user moved, as a short phrase: "On foot", "By bike". */
fun TravelMode.label(): Int = when (this) {
    TravelMode.WALKING -> R.string.travel_walking
    TravelMode.RUNNING -> R.string.travel_running
    TravelMode.CYCLING -> R.string.travel_cycling
    TravelMode.DRIVING -> R.string.travel_driving
    TravelMode.MOTORCYCLE -> R.string.travel_motorcycle
    TravelMode.BUS -> R.string.travel_bus
    TravelMode.TRAIN -> R.string.travel_train
    TravelMode.FLYING -> R.string.travel_flying
    TravelMode.BOAT -> R.string.travel_boat
    TravelMode.VEHICLE -> R.string.travel_vehicle
    TravelMode.UNKNOWN -> R.string.travel_unknown
}

fun TravelMode.icon(): Int = when (this) {
    TravelMode.WALKING -> R.drawable.ic_directions_walk
    TravelMode.RUNNING -> R.drawable.ic_directions_run
    TravelMode.CYCLING -> R.drawable.ic_directions_bike
    TravelMode.DRIVING, TravelMode.VEHICLE -> R.drawable.ic_directions_car
    TravelMode.MOTORCYCLE -> R.drawable.ic_two_wheeler
    TravelMode.BUS -> R.drawable.ic_directions_bus
    TravelMode.TRAIN -> R.drawable.ic_train
    TravelMode.FLYING -> R.drawable.ic_flight
    TravelMode.BOAT -> R.drawable.ic_directions_boat
    TravelMode.UNKNOWN -> R.drawable.ic_route
}
