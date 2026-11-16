package org.traccar.client

import kotlinx.serialization.Serializable

/**
 * A battery profile: fewer location fixes while the battery is low. The app gives the profiles in [Config.batteryProfiles];
 * the SDK picks one by itself from the battery level, charging and battery saver ([chooseBatteryProfile]) and rebuilds the
 * tracker with that profile's interval and heartbeat ([Config.applied]). No profile active means the config as given.
 */
@Serializable
data class BatteryProfile(
    /** Shown in the status ([TrackerStatus.profile]) and the log, e.g. "eco". */
    val name: String,
    /** Comes on at this battery level or lower, when the phone is not charging. */
    val enterPercent: Int,
    /** Goes off once the level is this or higher (above [enterPercent], so a level going 30, 31, 30 does not switch back
     *  and forth). */
    val leavePercent: Int,
    val intervalSeconds: Int,
    val heartbeatIntervalSeconds: Int,
    /** Comes on as well while the phone's battery saver is on (and not charging), whatever the level. */
    val withBatterySaver: Boolean = false,
)

/** What the choice reads from the phone. [level] null: not known (no change). */
data class BatteryReading(val level: Int?, val charging: Boolean, val batterySaver: Boolean)

/**
 * The profile to use now: [current] the active one (null: none), [sinceMs] when it last changed, [holdMs] the shortest
 * time between two changes. Rules:
 * - charging: none, at once (no hold);
 * - else the lowest-level profile that applies: its level reached ([BatteryProfile.enterPercent]), its battery saver, or
 *   the active one whose [BatteryProfile.leavePercent] is not reached yet; a profile with a lower [enterPercent] wins;
 * - a change waits until [holdMs] passed since the last one, so a level reading up and down does not rebuild the tracker
 *   every minute.
 * Returns the profile's name, or null for none.
 */
fun chooseBatteryProfile(
    profiles: List<BatteryProfile>,
    current: String?,
    reading: BatteryReading,
    sinceMs: Long,
    nowMs: Long,
    holdMs: Long,
): String? {
    if (profiles.isEmpty()) return null
    if (reading.charging) return null
    val level = reading.level ?: return current?.takeIf { name -> profiles.any { it.name == name } }
    val target = profiles.sortedBy { it.enterPercent }.firstOrNull { p ->
        level <= p.enterPercent || (p.withBatterySaver && reading.batterySaver) || (p.name == current && level < p.leavePercent)
    }?.name
    if (target == current) return current
    // the active profile was removed from the config: no wait
    if (current != null && profiles.none { it.name == current }) return target
    return if (nowMs - sinceMs >= holdMs) target else current
}

/** The config the tracker runs with: the active profile's interval and heartbeat in place of the config's own. */
fun Config.applied(): Config {
    val p = batteryProfiles.firstOrNull { it.name == activeProfile } ?: return this
    return copy(location = location.copy(intervalSeconds = p.intervalSeconds, heartbeatIntervalSeconds = p.heartbeatIntervalSeconds))
}
