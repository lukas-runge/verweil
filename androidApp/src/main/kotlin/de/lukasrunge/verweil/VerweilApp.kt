package de.lukasrunge.verweil

import android.app.Application
import de.lukasrunge.verweil.core.createOutbox
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.upload.Outbox
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class TrackingStatus(
    val running: Boolean = false,
    val mode: Mode? = null,
    val lastEventMs: Long? = null,
)

/** Manual wiring of the few app-wide objects. */
class VerweilApp : Application() {
    val outbox: Outbox by lazy { createOutbox(this) }
    val settings: Settings by lazy { Settings(this) }

    /** Events from broadcast receivers (activity transitions) on their way to the tracking service. */
    val sensorEvents = MutableSharedFlow<SensorEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val status = MutableStateFlow(TrackingStatus())
}
