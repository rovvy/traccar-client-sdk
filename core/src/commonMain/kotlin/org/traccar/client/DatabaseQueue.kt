package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.traccar.client.db.Database

class DatabaseQueue(driver: SqlDriver) : PositionQueue {

    private val queries = Database(driver).positionQueries
    private val mutex = Mutex()

    override suspend fun enqueue(position: Position) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.enqueue(
                    latitude = position.latitude,
                    longitude = position.longitude,
                    accuracy = position.accuracy,
                    time = position.time,
                    altitude = position.altitude,
                    speed = position.speed,
                    bearing = position.bearing,
                    battery = position.battery?.toLong(),
                    charging = position.charging?.let { if (it) 1L else 0L },
                )
            }
        }
    }

    override suspend fun peek(): Position? = peek(1).firstOrNull()

    override suspend fun peek(limit: Int): List<Position> = withContext(Dispatchers.IO) {
        mutex.withLock {
            queries.peekMany(limit.coerceAtLeast(1).toLong()) { latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging ->
                position(latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging)
            }.executeAsList()
        }
    }

    override suspend fun remove(count: Int) {
        if (count <= 0) return
        withContext(Dispatchers.IO) {
            mutex.withLock { queries.removeMany(count.toLong()) }
        }
    }

    private fun position(
        latitude: Double?, longitude: Double?, accuracy: Double?, time: Long, altitude: Double?, speed: Double?,
        bearing: Double?, battery: Long?, charging: Long?,
    ) = Position(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        time = time,
        altitude = altitude,
        speed = speed,
        bearing = bearing,
        battery = battery?.toInt(),
        charging = charging?.let { it != 0L },
    )

    override suspend fun removeFirst() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.removeFirst()
            }
        }
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        mutex.withLock { queries.count().executeAsOne().toInt() }
    }
}
