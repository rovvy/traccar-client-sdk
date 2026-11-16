package org.traccar.client

interface Uploader {
    suspend fun upload(position: Position): Boolean

    /** Sends [positions] (oldest first) in one request where it can. -> how many from the front the server took. */
    suspend fun upload(positions: List<Position>): Int {
        for ((i, p) in positions.withIndex()) if (!upload(p)) return i
        return positions.size
    }
}
