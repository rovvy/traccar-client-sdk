package org.traccar.client

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.round

class HttpUploader(
    private val config: Config,
    private val httpClient: HttpClient,
) : Uploader {

    override suspend fun upload(position: Position): Boolean = try {
        val response: HttpResponse = httpClient.submitForm(
            url = config.serverUrl,
            formParameters = Parameters.build {
                append("id", config.deviceId)
                position.latitude?.let { append("lat", it.toString()) }
                position.longitude?.let { append("lon", it.toString()) }
                append("timestamp", (position.time / 1000).toString())
                position.accuracy?.let { append("accuracy", it.toString()) }
                position.altitude?.let { append("altitude", it.toString()) }
                position.speed?.let { append("speed", it.toKnots().toString()) }
                position.bearing?.let { append("bearing", it.toString()) }
                position.battery?.let { append("batt", it.toString()) }
                position.charging?.let { append("charge", it.toString()) }
                position.alarm?.let { append("alarm", it) }
            },
        ) {
            config.headers.forEach { (name, value) -> headers.append(name, value) }
        }
        Log.log("Upload response ${response.status.value}")
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val ok = response.status.isSuccess()
        updateStatus {
            it.copy(lastUploadTime = now, lastUploadCode = response.status.value, lastSentTime = if (ok) now else it.lastSentTime)
        }
        ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.log("Upload error: ${e.message}")
        updateStatus { it.copy(lastUploadTime = kotlin.time.Clock.System.now().toEpochMilliseconds(), lastUploadCode = 0) }
        false
    }

    /**
     * Several positions in one JSON request (`Config.batchSize` above 1): `{"device_id", "location": [...]}`, each in
     * the Traccar Client JSON form (ISO time, coords in degrees, speed in m/s, battery level 0-1). All or none: the
     * answer is for the whole request. One position goes as form fields, as without batching.
     */
    override suspend fun upload(positions: List<Position>): Int {
        if (positions.isEmpty()) return 0
        if (config.batchSize <= 1 || positions.size == 1) return if (upload(positions[0])) 1 else 0
        return try {
            Log.log("Upload batch of ${positions.size}")
            val body = buildJsonObject {
                put("device_id", config.deviceId)
                put("location", buildJsonArray { positions.forEach { add(it.toJson()) } })
            }
            val response: HttpResponse = httpClient.post(config.serverUrl) {
                config.headers.forEach { (name, value) -> headers.append(name, value) }
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            Log.log("Upload response ${response.status.value}")
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val ok = response.status.isSuccess()
            updateStatus {
                it.copy(lastUploadTime = now, lastUploadCode = response.status.value, lastSentTime = if (ok) now else it.lastSentTime)
            }
            if (ok) positions.size else 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.log("Upload error: ${e.message}")
            updateStatus { it.copy(lastUploadTime = kotlin.time.Clock.System.now().toEpochMilliseconds(), lastUploadCode = 0) }
            0
        }
    }

    private fun Position.toJson(): JsonObject = buildJsonObject {
        put("timestamp", Instant.fromEpochMilliseconds(time).toString())
        putJsonObject("coords") {
            latitude?.let { put("latitude", it) }
            longitude?.let { put("longitude", it) }
            accuracy?.let { put("accuracy", it) }
            speed?.let { put("speed", it) }
            bearing?.let { put("heading", it) }
            altitude?.let { put("altitude", it) }
        }
        if (battery != null || charging != null) {
            putJsonObject("battery") {
                battery?.let { put("level", it / 100.0) }
                charging?.let { put("is_charging", it) }
            }
        }
        alarm?.let { put("event", it) }
    }

    private fun Double.toKnots(): Double = round(this * 1.94384 * 100) / 100
}
