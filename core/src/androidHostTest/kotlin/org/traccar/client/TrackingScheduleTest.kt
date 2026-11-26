package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tracking that starts and stops by itself: the start is due once its time has come and the stop's has not, the stop
 * once its time has come and tracking is on; a start happens once (a rider who stops by hand is not started again);
 * the phone wakes at the next time still ahead.
 */
class TrackingScheduleTest {

    private val start = 1_000_000L
    private val stop = 5_000_000L
    private val s = TrackingSchedule(start, stop)

    /** Before the start nothing is due; from the start on, a start while off and nothing while on. */
    @Test
    fun startIsDueFromItsTime() {
        assertEquals(TrackingSchedule.Step.NONE, s.stepAt(start - 1, enabled = false))
        assertEquals(TrackingSchedule.Step.START, s.stepAt(start, enabled = false))
        assertEquals(TrackingSchedule.Step.START, s.stepAt(start + 60_000, enabled = false))
        assertEquals(TrackingSchedule.Step.NONE, s.stepAt(start, enabled = true))
    }

    /** From the stop time on: a stop while on, nothing while off, and never a late start (a phone off the whole window). */
    @Test
    fun stopIsDueFromItsTime() {
        assertEquals(TrackingSchedule.Step.STOP, s.stepAt(stop, enabled = true))
        assertEquals(TrackingSchedule.Step.NONE, s.stepAt(stop, enabled = false))
        assertEquals(TrackingSchedule.Step.NONE, s.stepAt(stop + 1, enabled = false))
    }

    /** After the start only the stop is kept, so stopping by hand is not undone; after the stop nothing is left. */
    @Test
    fun aStartHappensOnce() {
        val left = s.after(start)
        assertEquals(TrackingSchedule(null, stop), left)
        assertEquals(TrackingSchedule.Step.NONE, left.stepAt(start + 1, enabled = false))
        assertEquals(s, s.after(start - 1))
        assertTrue(s.after(stop).isEmpty)
    }

    /** The phone wakes at the start, then at the stop, then not at all. */
    @Test
    fun nextWake() {
        assertEquals(start, s.nextAt(0))
        assertEquals(stop, s.nextAt(start))
        assertEquals(null, s.nextAt(stop))
        assertEquals(stop, TrackingSchedule(null, stop).nextAt(0))
        assertEquals(null, TrackingSchedule().nextAt(0))
    }
}
