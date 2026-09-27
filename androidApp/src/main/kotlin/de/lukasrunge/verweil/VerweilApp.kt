package de.lukasrunge.verweil

import android.app.Application
import de.lukasrunge.verweil.core.createDatabase
import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.journal.Journal
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.place.SqlPlaceStore
import de.lukasrunge.verweil.core.upload.Outbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** What the tracking service is doing right now; for the main screen and diagnostics. */
data class TrackingStatus(
    val running: Boolean = false,
    val mode: Mode? = null,
    val activity: Activity = Activity.UNKNOWN,
    val lastEventMs: Long? = null,
    val lastFixMs: Long? = null,
    val lastFixAccuracyM: Double? = null,
    /** Location is switched on in the system settings. */
    val locationEnabled: Boolean = true,
    /** Play Services currently gets any location at all. */
    val locationAvailable: Boolean = true,
    /** How precisely and how often location is requested, see `LocationProfile`. */
    val locationProfile: String? = null,
    val geofenceActive: Boolean = false,
    val lastWifiScanMs: Long? = null,
    val lastWifiAccessPoints: Int? = null,
)

/** Manual wiring of the few app-wide objects. */
class VerweilApp : Application() {
    val database: VerweilDatabase by lazy { createDatabase(this) }
    val outbox: Outbox by lazy { Outbox(database) }
    val journal: Journal by lazy { Journal(database) }
    val places: SqlPlaceStore by lazy { SqlPlaceStore(database) }
    val settings: Settings by lazy { Settings(this) }

    /** For work that must outlive a screen or the service, like saving a setting while stopping. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Events from broadcast receivers (activity transitions, geofence) on their way to the tracking service. */
    val sensorEvents = MutableSharedFlow<SensorEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val status = MutableStateFlow(TrackingStatus())

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
