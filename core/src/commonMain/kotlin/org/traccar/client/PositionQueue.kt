package org.traccar.client

interface PositionQueue {
    suspend fun enqueue(position: Position)
    suspend fun peek(): Position?
    suspend fun removeFirst()

    /** Up to [limit] positions, oldest first (a queue without its own query gives one). */
    suspend fun peek(limit: Int): List<Position> = listOfNotNull(peek())

    /** Removes the [count] oldest positions. */
    suspend fun remove(count: Int) = repeat(count) { removeFirst() }

    /** How many positions wait; 0 when the queue cannot tell. */
    suspend fun count(): Int = 0
}
