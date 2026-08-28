package com.ecs.core.model

/**
 * 可选模型清单。分文本与视觉两档：识别走视觉模型，标注/抽检/报告/树维护走文本模型。
 *
 * 模型 ID 会随厂商更新漂移，所以这份清单只是预设，[resolve] 允许用户填任意 ID 并
 * 自己指定厂商——ID 变了不需要重新发版。
 */
object ModelCatalog {

    enum class Provider(
        val label: String,
        val endpoint: String,
        val keyHint: String,
        /** OpenAI 兼容协议 vs Anthropic Messages 协议。 */
        val openAiCompatible: Boolean,
    ) {
        ANTHROPIC(
            label = "Anthropic",
            endpoint = "https://api.anthropic.com/v1/messages",
            keyHint = "console.anthropic.com 获取",
            openAiCompatible = false,
        ),
        ZHIPU(
            label = "智谱 GLM",
            endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            keyHint = "open.bigmodel.cn 获取，Flash 系列免费",
            openAiCompatible = true,
        );

        companion object {
            fun fromName(name: String?): Provider? = entries.firstOrNull { it.name == name }
        }
    }

    data class ModelSpec(
        val id: String,
        val label: String,
        val provider: Provider,
        val vision: Boolean,
        val note: String = "",
    )

    /** 智谱的 temperature 取值区间不含 0，贴着下界走以保证可复现。 */
    const val ZHIPU_MIN_TEMPERATURE = 0.01

    val MODELS: List<ModelSpec> = listOf(
        ModelSpec("claude-opus-5", "Claude Opus 5", Provider.ANTHROPIC, vision = true, note = "判断最稳"),
        ModelSpec("claude-sonnet-5", "Claude Sonnet 5", Provider.ANTHROPIC, vision = true, note = "均衡"),
        ModelSpec("claude-haiku-4-5", "Claude Haiku 4.5", Provider.ANTHROPIC, vision = true, note = "最便宜"),
        ModelSpec("glm-4.6v-flash", "GLM-4.6V Flash", Provider.ZHIPU, vision = true, note = "免费"),
        ModelSpec(
            "glm-4.1v-thinking-flash", "GLM-4.1V Thinking Flash", Provider.ZHIPU,
            vision = true, note = "免费，带思考",
        ),
        ModelSpec(
            "glm-4.1v-thinking-flashx", "GLM-4.1V Thinking FlashX", Provider.ZHIPU,
            vision = true, note = "付费，更快",
        ),
    )

    val TEXT_MODELS: List<ModelSpec> = MODELS
    val VISION_MODELS: List<ModelSpec> = MODELS.filter { it.vision }

    /** 标注、抽检、报告、树生成与维护都吃这个。 */
    const val DEFAULT_TEXT = "claude-opus-5"

    /** 识别吃这个。默认挑免费的：录入频率是系统生命线，识别不该按次心疼。 */
    const val DEFAULT_VISION = "glm-4.6v-flash"

    fun byId(id: String): ModelSpec? = MODELS.firstOrNull { it.id == id }

    /**
     * 把「用户存下来的模型 ID」解析成可调用的规格。
     * 清单里没有的 ID 按 [fallbackProvider] 当自定义模型处理，视觉能力由调用方声明。
     */
    fun resolve(
        id: String,
        fallbackProvider: Provider = Provider.ANTHROPIC,
        vision: Boolean = false,
    ): ModelSpec = byId(id) ?: ModelSpec(
        id = id,
        label = id,
        provider = fallbackProvider,
        vision = vision,
        note = "自定义",
    )
}
