package com.ecs.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("ecs_settings")

class Settings(private val context: Context) {

    private val API_KEY = stringPreferencesKey("api_key")
    private val MODEL = stringPreferencesKey("model")
    private val BATCH = intPreferencesKey("batch")
    private val LAST_BACKUP_AT = intPreferencesKey("last_backup_count")
    private val MICRO_DEPTH = intPreferencesKey("micro_depth")
    private val MACRO_DEPTH = intPreferencesKey("macro_depth")

    val apiKey: Flow<String> = context.dataStore.data.map { it[API_KEY].orEmpty() }
    val model: Flow<String> = context.dataStore.data.map { it[MODEL] ?: DEFAULT_MODEL }
    val batch: Flow<Int> = context.dataStore.data.map { it[BATCH] ?: 1 }
    val microDepth: Flow<Int> = context.dataStore.data.map { it[MICRO_DEPTH] ?: 3 }
    val macroDepth: Flow<Int> = context.dataStore.data.map { it[MACRO_DEPTH] ?: 2 }

    suspend fun setApiKey(v: String) = context.dataStore.edit { it[API_KEY] = v }.let { }
    suspend fun setModel(v: String) = context.dataStore.edit { it[MODEL] = v }.let { }
    suspend fun setMicroDepth(v: Int) = context.dataStore.edit { it[MICRO_DEPTH] = v }.let { }
    suspend fun setMacroDepth(v: Int) = context.dataStore.edit { it[MACRO_DEPTH] = v }.let { }
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
    suspend fun setLastBackupCount(v: Int) = context.dataStore.edit { it[LAST_BACKUP_AT] = v }.let { }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-5"
    }
}
