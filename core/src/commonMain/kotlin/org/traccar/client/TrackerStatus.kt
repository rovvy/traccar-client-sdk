package org.traccar.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the tracker is doing, for an app to show without reading the log. Times are epoch milliseconds.
 * One per process: a new config (a new tracker) keeps writing to the same status.
 */
data class TrackerStatus(
    /** Tracking is on. */
    val enabled: Boolean = false,
    /** Stop detection paused the location updates (the phone stands still). */
    val paused: Boolean = false,
    /** The newest position the tracker kept (a fix that passed the filter, or a heartbeat). */
    val lastPositionTime: Long? = null,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    /** The newest upload attempt and its HTTP status; 0 when there was no answer (no network, timeout). */
    val lastUploadTime: Long? = null,
    val lastUploadCode: Int? = null,
    /** The newest upload the server took (2xx). */
    val lastSentTime: Long? = null,
    /** Positions kept on the phone that the server has not taken yet; null until the queue was counted. */
    val pending: Int? = null,
)

private val status = MutableStateFlow(TrackerStatus())

/** The tracker's status, updated as it changes. */
val trackerStatus: StateFlow<TrackerStatus> = status.asStateFlow()

internal fun updateStatus(transform: (TrackerStatus) -> TrackerStatus) = status.update(transform)

/** Tests: back to the start. */
internal fun resetStatus() {
    status.value = TrackerStatus()
}
