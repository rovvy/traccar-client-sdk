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
        // a key this version does not know (a newer SDK saved it) is skipped; a config it still cannot read counts as
        // none (logged), so a boot or an update never crashes on it; the app's next init saves a new one
        queries.selectConfig().executeAsOneOrNull()?.let {
            try {
                lenient.decodeFromString<Config>(it)
            } catch (e: kotlinx.serialization.SerializationException) {
                Log.log("Saved config unreadable: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                Log.log("Saved config unreadable: ${e.message}")
                null
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        queries.clearConfig()
    }
}
