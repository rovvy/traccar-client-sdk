package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.traccar.client.db.Database
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Batching (Config.batchSize): positions that waited go out several to a request, and leave the queue only once taken. */
class BatchUploadTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private fun fix(i: Int) = Position(latitude = 45.8 + i / 1000.0, longitude = 24.15, accuracy = 5.0, time = 1_700_000_000_000 + i * 60_000L)

    private class Setup(val positions: MutableSharedFlow<Position>, val online: MutableStateFlow<Boolean>, val queue: DatabaseQueue)

    private suspend fun TestScope.engine(batch: Int, uploader: Uploader): Setup {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        Database.Schema.create(driver)
        val store = StateStore.create(driver)
        store.update { State(enabled = true) }
        val setup = Setup(MutableSharedFlow(extraBufferCapacity = 200), MutableStateFlow(false), DatabaseQueue(driver))
        TrackerEngine(
            stateStore = store,
            queue = setup.queue,
            network = object : NetworkMonitor {
                override val isOnline = setup.online
            },
            locationSource = object : LocationSource {
                override val positions = setup.positions
                override suspend fun fetchOnce(): Position? = null
            },
            signalSources = listOf(object : SignalSource {
                override val signals = emptyFlow<Signal>()
            }),
            processors = emptyList(),
            uploader = uploader,
            buffer = true,
            scope = backgroundScope,
            batchSize = batch,
        )
        settle()
        return setup
    }

    /** The queue and state store write on Dispatchers.IO (real threads): run, wait a little real time, run again. */
    private fun TestScope.settle() {
        repeat(15) {
            runCurrent()
            Thread.sleep(20)
        }
        runCurrent()
    }

    /** 120 positions waited offline: back online they go in three requests of 50, 50 and 20, oldest first, and the queue is empty. */
    @Test
    fun waitingPositionsGoInBatches() = runTest {
        val calls = mutableListOf<List<Position>>()
        val s = engine(50, object : Uploader {
            override suspend fun upload(position: Position) = error("single upload not expected")
            override suspend fun upload(positions: List<Position>): Int {
                calls += positions
                return positions.size
            }
        })
        repeat(120) { s.positions.emit(fix(it)) }
        settle()
        assertEquals(120, s.queue.count(), "offline: all wait")
        s.online.value = true
        settle()
        assertEquals(listOf(50, 50, 20), calls.map { it.size })
        assertEquals(fix(0).time, calls[0][0].time, "oldest first")
        assertEquals(fix(119).time, calls[2].last().time)
        assertEquals(0, s.queue.count())
    }

    /** A refused batch stays in the queue, whole, for the next try. */
    @Test
    fun aRefusedBatchStays() = runTest {
        val s = engine(50, object : Uploader {
            override suspend fun upload(position: Position) = false
            override suspend fun upload(positions: List<Position>) = 0
        })
        repeat(7) { s.positions.emit(fix(it)) }
        settle()
        s.online.value = true
        settle()
        assertEquals(7, s.queue.count())
    }

    /** The JSON request: device_id, and each position with ISO time, coords, speed in m/s, battery level 0-1. */
    @Test
    fun theJsonRequest() = runTest {
        var body = ""
        var type = ""
        val client = HttpClient(MockEngine { request ->
            val content = request.body as TextContent
            body = content.text
            type = content.contentType.toString()
            respond("{\"saved\":2}", HttpStatusCode.OK)
        })
        val uploader = HttpUploader(Config(serverUrl = "https://example.org", deviceId = "tt27-1-2", batchSize = 50), client)
        val sent = uploader.upload(listOf(fix(0).copy(speed = 5.0, battery = 80, charging = true), fix(1)))
        assertEquals(2, sent)
        assertTrue(type.startsWith("application/json"), type)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("tt27-1-2", json["device_id"]!!.jsonPrimitive.content)
        val first = json["location"]!!.jsonArray[0].jsonObject
        assertEquals("2023-11-14T22:13:20Z", first["timestamp"]!!.jsonPrimitive.content)
        assertEquals(45.8, first["coords"]!!.jsonObject["latitude"]!!.jsonPrimitive.content.toDouble())
        assertEquals(5.0, first["coords"]!!.jsonObject["speed"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.8, first["battery"]!!.jsonObject["level"]!!.jsonPrimitive.content.toDouble())
        assertEquals(2, json["location"]!!.jsonArray.size)
    }

    /** Batch size 1 (the default): one form request per position, as before. */
    @Test
    fun batchOneSendsForms() = runTest {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            bodies += request.body.contentType.toString()
            respond("{}", HttpStatusCode.OK)
        })
        val uploader = HttpUploader(Config(serverUrl = "https://example.org", deviceId = "x"), client)
        assertEquals(1, uploader.upload(listOf(fix(0))))
        assertTrue(bodies.single().startsWith("application/x-www-form-urlencoded"), bodies.single())
    }
}
