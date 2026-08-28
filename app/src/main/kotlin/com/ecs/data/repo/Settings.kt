package com.ecs.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ecs.core.model.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("ecs_settings")

class Settings(private val context: Context) {

    private val ANTHROPIC_KEY = stringPreferencesKey("api_key")
    private val ZHIPU_KEY = stringPreferencesKey("zhipu_key")
    private val TEXT_MODEL = stringPreferencesKey("text_model")
    private val VISION_MODEL = stringPreferencesKey("vision_model")
    private val BATCH = intPreferencesKey("batch")
    private val LAST_BACKUP_AT = intPreferencesKey("last_backup_count")
    private val MICRO_DEPTH = intPreferencesKey("micro_depth")
    private val MACRO_DEPTH = intPreferencesKey("macro_depth")

    val anthropicKey: Flow<String> = context.dataStore.data.map { it[ANTHROPIC_KEY].orEmpty() }
    val zhipuKey: Flow<String> = context.dataStore.data.map { it[ZHIPU_KEY].orEmpty() }
    val textModel: Flow<String> = context.dataStore.data.map { it[TEXT_MODEL] ?: ModelCatalog.DEFAULT_TEXT }
    val visionModel: Flow<String> = context.dataStore.data.map { it[VISION_MODEL] ?: ModelCatalog.DEFAULT_VISION }
    val batch: Flow<Int> = context.dataStore.data.map { it[BATCH] ?: 1 }
    val microDepth: Flow<Int> = context.dataStore.data.map { it[MICRO_DEPTH] ?: 3 }
    val macroDepth: Flow<Int> = context.dataStore.data.map { it[MACRO_DEPTH] ?: 2 }

    suspend fun setAnthropicKey(v: String) { context.dataStore.edit { it[ANTHROPIC_KEY] = v } }
    suspend fun setZhipuKey(v: String) { context.dataStore.edit { it[ZHIPU_KEY] = v } }
    suspend fun setTextModel(v: String) { context.dataStore.edit { it[TEXT_MODEL] = v } }
    suspend fun setVisionModel(v: String) { context.dataStore.edit { it[VISION_MODEL] = v } }
    suspend fun setMicroDepth(v: Int) { context.dataStore.edit { it[MICRO_DEPTH] = v } }
    suspend fun setMacroDepth(v: Int) { context.dataStore.edit { it[MACRO_DEPTH] = v } }

    /** 按模型所属厂商取对应的 Key，缺哪个报哪个。 */
    suspend fun keyFor(provider: ModelCatalog.Provider): String = when (provider) {
        ModelCatalog.Provider.ANTHROPIC -> anthropicKey.first()
        ModelCatalog.Provider.ZHIPU -> zhipuKey.first()
    }

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
