package de.lukasrunge.verweil

import android.app.Application
import de.lukasrunge.verweil.core.createDatabase
import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.upload.Outbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class TrackingStatus(
    val running: Boolean = false,
    val mode: Mode? = null,
    val lastEventMs: Long? = null,
)

data class UploadStatus(
    val lastSuccessMs: Long? = null,
    val error: String? = null,
)

/** Manual wiring of the few app-wide objects. */
class VerweilApp : Application() {
    val database: VerweilDatabase by lazy { createDatabase(this) }
    val outbox: Outbox by lazy { Outbox(database) }
    val settings: Settings by lazy { Settings(this) }

    /** For work that must outlive a screen or the service, like saving a setting while stopping. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Events from broadcast receivers (activity transitions, geofence) on their way to the tracking service. */
    val sensorEvents = MutableSharedFlow<SensorEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val status = MutableStateFlow(TrackingStatus())
    val uploadStatus = MutableStateFlow(UploadStatus())
}
