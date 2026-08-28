package com.ecs.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ecs.core.prompt.PromptSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.promptDataStore by preferencesDataStore("ecs_prompts")

/**
 * 提示词覆盖值。没有覆盖就走内置正文。
 *
 * 「还原默认」是删除覆盖值，不是把正文清空——清空后仍会保留只读契约，
 * 任务依旧能跑，只是没了额外指引。
 */
class PromptStore(private val context: Context) {

    private fun key(slot: PromptSlot) = stringPreferencesKey("prompt_${slot.name}")

    fun override(slot: PromptSlot): Flow<String?> =
        context.promptDataStore.data.map { it[key(slot)] }

    suspend fun overrides(): Map<PromptSlot, String> {
        val prefs = context.promptDataStore.data.first()
        return PromptSlot.entries.mapNotNull { slot ->
            prefs[key(slot)]?.let { slot to it }
        }.toMap()
    }

    /** 生效全文：覆盖值（若有）+ 只读契约。 */
    suspend fun text(slot: PromptSlot): String =
        slot.render(context.promptDataStore.data.first()[key(slot)])

    suspend fun save(slot: PromptSlot, body: String) {
        context.promptDataStore.edit { it[key(slot)] = body }
    }

    suspend fun reset(slot: PromptSlot) {
        context.promptDataStore.edit { it.remove(key(slot)) }
    }

    suspend fun resetAll() {
        context.promptDataStore.edit { prefs ->
            PromptSlot.entries.forEach { prefs.remove(key(it)) }
        }
    }
}
