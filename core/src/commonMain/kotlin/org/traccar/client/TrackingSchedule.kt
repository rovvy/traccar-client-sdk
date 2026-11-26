package org.traccar.client

import kotlinx.serialization.Serializable

/**
 * Tracking that starts and stops by itself at set times (an event's tracking times): [startAt] and [stopAt] in
 * milliseconds since the epoch, null for none. The app sets it ([setTrackingSchedule] on Android); the SDK keeps it on
 * the phone, so it holds after the app is closed, an app update or a reboot.
 *
 * A start happens once: after it, only the stop is kept, so a rider who then stops tracking by hand is not started
 * again (after a reboot either). A stop ends the schedule.
 */
@Serializable
data class TrackingSchedule(val startAt: Long? = null, val stopAt: Long? = null) {

    enum class Step { START, STOP, NONE }

    /**
     * What is due at [now] with tracking [enabled] or not: the stop once its time has come (nothing when already off);
     * else the start once its time has come (nothing when already on). A start whose stop time has passed is never
     * due, so a phone that was off for the whole window does not start afterwards.
     */
    fun stepAt(now: Long, enabled: Boolean): Step = when {
        stopAt != null && now >= stopAt -> if (enabled) Step.STOP else Step.NONE
        startAt != null && now >= startAt -> if (enabled) Step.NONE else Step.START
        else -> Step.NONE
    }

    /** What is left once the step due at [now] was handled: a start or stop time that has come is dropped. */
    fun after(now: Long): TrackingSchedule =
        if (stopAt != null && now >= stopAt) TrackingSchedule()
        else copy(startAt = startAt?.takeIf { now < it })

    /** The next time the phone has to wake for this schedule after [now], null when none. */
    fun nextAt(now: Long): Long? = listOfNotNull(startAt, stopAt).filter { it > now }.minOrNull()

    val isEmpty: Boolean get() = startAt == null && stopAt == null
}
