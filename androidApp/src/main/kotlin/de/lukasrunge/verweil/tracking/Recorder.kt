package de.lukasrunge.verweil.tracking

import android.content.Context
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.replay.EventLog
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Appends raw sensor events to one JSONL file per day in the app's external files directory
 * (Android/data/de.lukasrunge.verweil/files/recordings). Pull them with adb for replays.
 */
class Recorder(context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    fun write(event: SensorEvent) {
        val day = Instant.ofEpochMilli(event.timeMs).atZone(ZoneId.systemDefault()).toLocalDate()
        File(dir, "$day.jsonl").appendText(EventLog.encode(event) + "\n")
    }
}
