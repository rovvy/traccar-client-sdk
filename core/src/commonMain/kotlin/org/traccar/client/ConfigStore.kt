package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.traccar.client.db.Database

class ConfigStore(driver: SqlDriver) {

    private val lenient = Json { ignoreUnknownKeys = true }

    private val queries = Database(driver).configQueries

    suspend fun save(config: Config) = withContext(Dispatchers.IO) {
        queries.saveConfig(Json.encodeToString(config))
    }

    suspend fun load(): Config? = withContext(Dispatchers.IO) {
        // a key this version does not know (a newer SDK saved it) is skipped, so the tracker still starts after a
        // downgrade instead of failing to read its config
        queries.selectConfig().executeAsOneOrNull()?.let { lenient.decodeFromString(it) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        queries.clearConfig()
    }
}
