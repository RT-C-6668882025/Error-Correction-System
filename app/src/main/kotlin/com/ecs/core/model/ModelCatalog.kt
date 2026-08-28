package com.ecs.core.model

/**
 * 模型建议清单。分文本与视觉两档：识别走视觉模型，标注/抽检/报告/树维护走文本模型。
 *
 * 这里只是「常用值」，真正生效的是「端点 + 模型 ID」两段自由输入——
 * 厂商换 ID、换域名、上新模型，都不需要重新发版。
 */
object ModelCatalog {

    data class ModelSpec(
        val id: String,
        val label: String,
        val endpointId: String,
        val vision: Boolean,
        val note: String = "",
    )

    /**
     * OpenAI 兼容协议的 temperature 取值区间不含 0（智谱如此，多数中转站跟随），
     * 传 0 会被拒。贴着下界走，尽量保住可复现性。
     */
    const val OPENAI_MIN_TEMPERATURE = 0.01

    val MODELS: List<ModelSpec> = listOf(
        ModelSpec("claude-opus-5", "Claude Opus 5", BuiltInEndpoints.ANTHROPIC, vision = true, note = "判断最稳"),
        ModelSpec("claude-sonnet-5", "Claude Sonnet 5", BuiltInEndpoints.ANTHROPIC, vision = true, note = "均衡"),
        ModelSpec("claude-haiku-4-5", "Claude Haiku 4.5", BuiltInEndpoints.ANTHROPIC, vision = true, note = "最便宜"),
        ModelSpec("glm-4.6v-flash", "GLM-4.6V Flash", BuiltInEndpoints.ZHIPU, vision = true, note = "免费"),
        ModelSpec(
            "glm-4.1v-thinking-flash", "GLM-4.1V Thinking Flash", BuiltInEndpoints.ZHIPU,
            vision = true, note = "免费，带思考",
        ),
        ModelSpec(
            "glm-4.1v-thinking-flashx", "GLM-4.1V Thinking FlashX", BuiltInEndpoints.ZHIPU,
            vision = true, note = "付费，更快",
        ),
        ModelSpec("glm-4-plus", "GLM-4-Plus", BuiltInEndpoints.ZHIPU, vision = false),
        ModelSpec("kimi-k2-turbo-preview", "Kimi K2 Turbo", BuiltInEndpoints.MOONSHOT, vision = false),
        ModelSpec("moonshot-v1-8k-vision-preview", "Kimi 视觉", BuiltInEndpoints.MOONSHOT, vision = true),
        ModelSpec("MiniMax-Text-01", "MiniMax Text 01", BuiltInEndpoints.MINIMAX, vision = false),
    )

    /** 标注、抽检、报告、树生成与维护都吃这个。 */
    const val DEFAULT_TEXT = "claude-opus-5"

    /** 识别吃这个。默认挑免费的：录入频率是系统生命线，识别不该按次心疼。 */
    const val DEFAULT_VISION = "glm-4.6v-flash"

    const val DEFAULT_TEXT_ENDPOINT = BuiltInEndpoints.ANTHROPIC
    const val DEFAULT_VISION_ENDPOINT = BuiltInEndpoints.ZHIPU

    fun byId(id: String): ModelSpec? = MODELS.firstOrNull { it.id == id }

    /** 某个端点下的建议模型，给 UI 做候选用。清单外的 ID 用户照样能填。 */
    fun suggestionsFor(endpointId: String, vision: Boolean): List<ModelSpec> =
        MODELS.filter { it.endpointId == endpointId && (!vision || it.vision) }
}
