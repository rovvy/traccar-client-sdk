package org.traccar.client

interface PositionQueue {
    suspend fun enqueue(position: Position)
    suspend fun peek(): Position?
    suspend fun removeFirst()

    /** How many positions wait; 0 when the queue cannot tell. */
    suspend fun count(): Int = 0
}
