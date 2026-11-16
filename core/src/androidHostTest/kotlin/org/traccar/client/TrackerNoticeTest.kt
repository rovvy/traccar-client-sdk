package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The notification's state from the tracker's status alone, so it stays right while the app sleeps. */
class TrackerNoticeTest {

    private val texts = NoticeTexts(
        okTitle = "Tracking on · last sent {time}", okText = "On the map.",
        waitingTitle = "Waiting for the first position", waitingText = "On the map.",
        noInternetTitle = "No internet", noInternetText = "Last one sent at {time}.", noInternetTextNone = "Sent later.",
        noGpsTitle = "No GPS since {time}", noGpsText = "Keep riding.",
        silentTitle = "Nothing sent since {time}", silentText = "Fix settings.",
        refusedTitle = "Not taken", refusedText = "See details.",
    )
    private val min = 60_000L
    private val now = 100 * min
    private fun clock(t: Long) = "m${t / min}"
    private fun on(s: TrackerStatus) = noticeFor(s, texts, now, ::clock)
    private val sending = TrackerStatus(enabled = true, lastSentTime = now - 2 * min, lastFixTime = now - min, pending = 0)

    /** Tracking off: nothing (the config's text). On and sent recently: the time of the last one sent. */
    @Test
    fun offAndOk() {
        assertNull(on(sending.copy(enabled = false)))
        assertEquals("Tracking on · last sent m98" to "On the map.", on(sending))
        assertEquals("Waiting for the first position" to "On the map.", on(TrackerStatus(enabled = true)))
    }

    /** Waiting for the network with positions queued: no internet at once, with the last one sent. */
    @Test
    fun noInternet() {
        assertEquals("No internet" to "Last one sent at m98.", on(sending.copy(waitingForNetwork = true, pending = 2)))
        assertEquals("No internet" to "Sent later.", on(TrackerStatus(enabled = true, waitingForNetwork = true, pending = 1)))
        // nothing waits: nothing is late
        assertEquals("Tracking on · last sent m98", on(sending.copy(waitingForNetwork = true, pending = 0))!!.first)
    }

    /** Sends with no answer count after 30 s, not before (the SDK tries again in 5 s). */
    @Test
    fun noAnswer() {
        assertEquals("No internet", on(sending.copy(noAnswerSince = now - 31_000, pending = 1))!!.first)
        assertEquals("Tracking on · last sent m98", on(sending.copy(noAnswerSince = now - 5_000, pending = 1))!!.first)
    }

    /** Sending works but the last fix is over 10 minutes old: no GPS since that fix. */
    @Test
    fun noGps() {
        assertEquals("No GPS since m85" to "Keep riding.", on(sending.copy(lastFixTime = now - 15 * min)))
        assertEquals("Tracking on · last sent m98", on(sending.copy(lastFixTime = now - 8 * min))!!.first)
        assertEquals("Tracking on · last sent m98", on(sending.copy(lastFixTime = null))!!.first, "not known yet")
    }

    /** Nothing taken for over 10 minutes: refused when the server answered otherwise since, else nothing sent. */
    @Test
    fun silentAndRefused() {
        val old = sending.copy(lastSentTime = now - 20 * min)
        assertEquals("Nothing sent since m80" to "Fix settings.", on(old))
        assertEquals("Not taken" to "See details.", on(old.copy(lastUploadCode = 403, lastUploadTime = now - min)))
        assertEquals("Nothing sent since m80", on(old.copy(lastUploadCode = 0, lastUploadTime = now - min))!!.first)
    }

    /** A new start does not take the run before's uploads: it waits, then counts nothing sent from the start. */
    @Test
    fun aNewStart() {
        val restarted = TrackerStatus(enabled = true, startedAt = now - 3 * min, lastSentTime = now - 90 * min,
            lastUploadCode = 503, lastUploadTime = now - 90 * min, pending = 0)
        assertEquals("Waiting for the first position", on(restarted)!!.first)
        val long = restarted.copy(startedAt = now - 12 * min)
        assertEquals("Nothing sent since m88", on(long)!!.first, "counted from the start")
        assertEquals("Not taken", on(long.copy(lastUploadCode = 403, lastUploadTime = now - min))!!.first)
        assertEquals("Tracking on · last sent m99", on(long.copy(lastSentTime = now - min))!!.first)
    }
}
