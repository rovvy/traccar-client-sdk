package org.traccar.client

import kotlinx.serialization.Serializable

/**
 * The tracking notification's wording for each state, from the app (its language). `{time}` is replaced by a clock
 * time (the last position sent, or the last fix for no GPS). The SDK picks the state from its own status
 * ([noticeFor]), so the notification stays current while the app sleeps or is closed.
 */
@Serializable
data class NoticeTexts(
    /** A position was sent within [limitMinutes]: title with `{time}` (last sent). */
    val okTitle: String,
    val okText: String,
    /** Tracking on, nothing sent yet. */
    val waitingTitle: String,
    val waitingText: String,
    /** Positions wait and the phone has no network, or sends got no answer for [noAnswerSeconds]. */
    val noInternetTitle: String,
    /** With `{time}` (last sent). */
    val noInternetText: String,
    /** Nothing sent yet. */
    val noInternetTextNone: String,
    /** Sending works but no location fix for over [limitMinutes]: title with `{time}` (last fix). */
    val noGpsTitle: String,
    val noGpsText: String,
    /** Nothing sent for over [limitMinutes]: title with `{time}` (last sent). */
    val silentTitle: String,
    val silentText: String,
    /** The server answered, but not 2xx, since the last position it took, which is over [limitMinutes] old. */
    val refusedTitle: String,
    val refusedText: String,
    val limitMinutes: Int = 10,
    val noAnswerSeconds: Int = 30,
)

/**
 * The notification's title and text for [status] at [now], or null when tracking is off. The order is the order of
 * what the rider should know first: no internet (positions wait), no GPS, refused, nothing sent, all well.
 */
fun noticeFor(status: TrackerStatus, texts: NoticeTexts, now: Long, clock: (Long) -> String): Pair<String, String>? {
    if (!status.enabled) return null
    val limit = texts.limitMinutes * 60_000L
    // what happened before the latest start belongs to the run before (the status is one per process)
    val started = status.startedAt ?: 0L
    val sent = status.lastSentTime?.takeIf { it >= started }
    val recent = sent != null && now - sent <= limit
    fun at(template: String, time: Long) = template.replace("{time}", clock(time))

    val noAnswer = status.noAnswerSince?.let { now - it >= texts.noAnswerSeconds * 1_000L } == true
    if ((status.pending ?: 0) > 0 && (status.waitingForNetwork || noAnswer)) {
        return texts.noInternetTitle to (sent?.let { at(texts.noInternetText, it) } ?: texts.noInternetTextNone)
    }
    val fix = status.lastFixTime
    if (recent && fix != null && now - fix > limit) return at(texts.noGpsTitle, fix) to texts.noGpsText
    if (recent) return at(texts.okTitle, sent!!) to texts.okText
    // nothing taken for over the limit since the last one, or since the start: refused when the server answered
    // otherwise since then, else nothing sent; a fresh start waits for its first one
    val since = sent ?: status.startedAt?.takeIf { now - it > limit } ?: return texts.waitingTitle to texts.waitingText
    val code = status.lastUploadCode
    val refused = code != null && code != 0 && code !in 200..299 && (status.lastUploadTime ?: 0) > since
    return if (refused) texts.refusedTitle to texts.refusedText else at(texts.silentTitle, since) to texts.silentText
}
