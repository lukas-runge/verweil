package de.lukasrunge.verweil.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.replay.EventLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day of raw sensor events. */
data class Recording(val day: LocalDate, val file: File) {
    val sizeBytes: Long get() = file.length()
}

/**
 * Raw sensor events as one JSONL file per day in the app's external files directory
 * (Android/data/de.lukasrunge.verweil/files/recordings), for replays. They contain where the user lives,
 * so they are kept for [KEEP_DAYS] only and leave the phone only when the user shares them.
 */
class Recordings(private val context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "recordings")

    fun write(event: SensorEvent) {
        dir.mkdirs()
        val day = Instant.ofEpochMilli(event.timeMs).atZone(ZoneId.systemDefault()).toLocalDate()
        File(dir, "$day.jsonl").appendText(EventLog.encode(event) + "\n")
    }

    /** Newest first. */
    fun all(): List<Recording> = dir.listFiles().orEmpty()
        .mapNotNull { file -> file.dayOrNull()?.let { Recording(it, file) } }
        .sortedByDescending { it.day }

    fun delete(recording: Recording) {
        recording.file.delete()
    }

    fun deleteAll() = all().forEach { it.file.delete() }

    fun pruneOld(today: LocalDate = LocalDate.now()) =
        all().filter { it.day.isBefore(today.minusDays(KEEP_DAYS)) }.forEach { it.file.delete() }

    /** A share sheet for the recordings; the receiving app gets read access to these files only. */
    fun shareIntent(recordings: List<Recording>): Intent {
        val uris = ArrayList<Uri>(recordings.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it.file) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.setType("application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    private fun File.dayOrNull(): LocalDate? =
        if (extension != "jsonl") null else runCatching { LocalDate.parse(nameWithoutExtension) }.getOrNull()

    companion object {
        const val KEEP_DAYS = 30L
    }
}
