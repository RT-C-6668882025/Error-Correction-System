package com.ecs.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("ecs_settings")

class Settings(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val ENDPOINTS = stringPreferencesKey("endpoints")
    private val TEXT_MODEL = stringPreferencesKey("text_model")
    private val TEXT_ENDPOINT = stringPreferencesKey("text_endpoint")
    private val VISION_MODEL = stringPreferencesKey("vision_model")
    private val VISION_ENDPOINT = stringPreferencesKey("vision_endpoint")
    private val BATCH = intPreferencesKey("batch")
    private val LAST_BACKUP_AT = intPreferencesKey("last_backup_count")
    private val MICRO_DEPTH = intPreferencesKey("micro_depth")
    private val MACRO_DEPTH = intPreferencesKey("macro_depth")

    /** 预置项与存下来的合并：升级新增预置项时老用户也能看到。 */
    val endpoints: Flow<List<ApiEndpoint>> = context.dataStore.data.map { prefs ->
        BuiltInEndpoints.merge(decode(prefs[ENDPOINTS]))
    }

    val textModel: Flow<String> = context.dataStore.data.map { it[TEXT_MODEL] ?: ModelCatalog.DEFAULT_TEXT }
    val visionModel: Flow<String> = context.dataStore.data.map { it[VISION_MODEL] ?: ModelCatalog.DEFAULT_VISION }
    val textEndpoint: Flow<String> =
        context.dataStore.data.map { it[TEXT_ENDPOINT] ?: ModelCatalog.DEFAULT_TEXT_ENDPOINT }
    val visionEndpoint: Flow<String> =
        context.dataStore.data.map { it[VISION_ENDPOINT] ?: ModelCatalog.DEFAULT_VISION_ENDPOINT }

    val batch: Flow<Int> = context.dataStore.data.map { it[BATCH] ?: 1 }
    val microDepth: Flow<Int> = context.dataStore.data.map { it[MICRO_DEPTH] ?: 3 }
    val macroDepth: Flow<Int> = context.dataStore.data.map { it[MACRO_DEPTH] ?: 2 }

    private fun decode(raw: String?): List<ApiEndpoint> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching {
            json.decodeFromString(ListSerializer(ApiEndpoint.serializer()), raw)
        }.getOrDefault(emptyList())

    /** 整表覆盖写回。端点不多，没必要做增量。 */
    suspend fun saveEndpoints(list: List<ApiEndpoint>) {
        context.dataStore.edit {
            it[ENDPOINTS] = json.encodeToString(ListSerializer(ApiEndpoint.serializer()), list)
        }
    }

    suspend fun upsertEndpoint(endpoint: ApiEndpoint) {
        val current = endpoints.first()
        val next = if (current.any { it.id == endpoint.id }) {
            current.map { if (it.id == endpoint.id) endpoint else it }
        } else {
            current + endpoint
        }
        saveEndpoints(next)
    }

    /** 预置项不可删，只能改。 */
    suspend fun deleteEndpoint(id: String) {
        if (BuiltInEndpoints.byId(id) != null) return
        saveEndpoints(endpoints.first().filterNot { it.id == id })
    }

    suspend fun endpointById(id: String): ApiEndpoint? = endpoints.first().firstOrNull { it.id == id }

    suspend fun setTextModel(v: String) { context.dataStore.edit { it[TEXT_MODEL] = v } }
    suspend fun setVisionModel(v: String) { context.dataStore.edit { it[VISION_MODEL] = v } }
    suspend fun setTextEndpoint(v: String) { context.dataStore.edit { it[TEXT_ENDPOINT] = v } }
    suspend fun setVisionEndpoint(v: String) { context.dataStore.edit { it[VISION_ENDPOINT] = v } }
    suspend fun setMicroDepth(v: Int) { context.dataStore.edit { it[MICRO_DEPTH] = v } }
    suspend fun setMacroDepth(v: Int) { context.dataStore.edit { it[MACRO_DEPTH] = v } }

    suspend fun nextBatch(): Int {
        var out = 1
        context.dataStore.edit {
            out = (it[BATCH] ?: 0) + 1
            it[BATCH] = out
        }
        return out
    }

    suspend fun currentBatch(): Int = batch.first()

    suspend fun lastBackupCount(): Int = context.dataStore.data.map { it[LAST_BACKUP_AT] ?: 0 }.first()
    suspend fun setLastBackupCount(v: Int) { context.dataStore.edit { it[LAST_BACKUP_AT] = v } }
}
