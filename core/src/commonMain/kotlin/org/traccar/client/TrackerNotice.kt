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
    val sent = status.lastSentTime
    val recent = sent != null && now - sent <= limit
    fun at(template: String, time: Long) = template.replace("{time}", clock(time))

    val noAnswer = status.noAnswerSince?.let { now - it >= texts.noAnswerSeconds * 1_000L } == true
    if ((status.pending ?: 0) > 0 && (status.waitingForNetwork || noAnswer)) {
        return texts.noInternetTitle to (sent?.let { at(texts.noInternetText, it) } ?: texts.noInternetTextNone)
    }
    val fix = status.lastFixTime
    if (recent && fix != null && now - fix > limit) return at(texts.noGpsTitle, fix) to texts.noGpsText
    if (sent == null) return texts.waitingTitle to texts.waitingText
    if (!recent) {
        val code = status.lastUploadCode
        val refused = code != null && code != 0 && code !in 200..299 && (status.lastUploadTime ?: 0) > sent
        return if (refused) texts.refusedTitle to texts.refusedText else at(texts.silentTitle, sent) to texts.silentText
    }
    return at(texts.okTitle, sent) to texts.okText
}
