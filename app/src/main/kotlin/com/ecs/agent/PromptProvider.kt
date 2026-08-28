package com.ecs.agent

import com.ecs.core.prompt.PromptSlot

/**
 * 取一段提示词的生效全文。实现由 data 层给（读用户覆盖值），
 * 默认实现直接用内置正文——测试和无存储场景下不必再搭一层。
 */
fun interface PromptProvider {

    suspend fun text(slot: PromptSlot): String

    companion object {
        val DEFAULT = PromptProvider { it.render(null) }
    }
}
