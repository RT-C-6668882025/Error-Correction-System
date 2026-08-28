package com.ecs.core.model

import kotlinx.serialization.Serializable

/** 决定请求体形状与响应取值路径。中转站基本都实现其中之一。 */
@Serializable
enum class Protocol(val label: String, val path: String) {
    OPENAI("OpenAI 兼容", "/v1/chat/completions"),
    ANTHROPIC("Anthropic Messages", "/v1/messages");

    companion object {
        fun fromName(name: String?): Protocol? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 一个可调用的 API 端点。厂商不写死在代码里：GLM、Kimi、MiniMax、
 * 海内外中转站，只要说这两种协议之一就能接。
 */
@Serializable
data class ApiEndpoint(
    val id: String,
    val name: String,
    /** 用户原样粘贴的地址，展示时不改写；实际请求走 [url]。 */
    val baseUrl: String,
    val protocol: Protocol,
    val apiKey: String = "",
    /** 预置项可改 URL 与 Key，不可删。 */
    val builtIn: Boolean = false,
    val note: String = "",
) {
    val url: String get() = normalizeUrl(baseUrl, protocol)
    val configured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()
}

/**
 * 把用户粘进来的地址归一成完整 endpoint。
 *
 * 中转站的地址写法五花八门：裸域名、带尾斜杠、只到 `/v1`、已经是完整路径。
 * 差一个字符就是 404，而用户看到的只有「调用失败」，所以这里宁可多容错。
 *
 *   https://x.com                      → https://x.com/v1/chat/completions
 *   https://x.com/                     → https://x.com/v1/chat/completions
 *   https://x.com/v1                   → https://x.com/v1/chat/completions
 *   https://x.com/v1/chat/completions  → 原样
 *   https://x.com/api/paas/v4          → https://x.com/api/paas/v4/chat/completions
 */
fun normalizeUrl(baseUrl: String, protocol: Protocol): String {
    var s = baseUrl.trim()
        .substringBefore('#')
        .substringBefore('?')
        .trim()
    if (s.isEmpty()) return ""
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
        s = "https://$s"
    }
    s = s.trimEnd('/')

    val tail = protocol.path.trimStart('/')          // v1/chat/completions
    val leaf = tail.substringAfterLast('/')          // completions / messages

    // 已经指到了终点
    if (s.endsWith("/$leaf", ignoreCase = true)) return s

    return when (protocol) {
        Protocol.OPENAI ->
            // 已经带了版本段（/v1、/api/paas/v4 …）就只补方法路径，否则整段补上
            if (hasVersionSegment(s)) "$s/chat/completions" else "$s/$tail"
        Protocol.ANTHROPIC ->
            if (hasVersionSegment(s)) "$s/messages" else "$s/$tail"
    }
}

private val VERSION_SEGMENT = Regex("^v\\d+$", RegexOption.IGNORE_CASE)

private fun hasVersionSegment(url: String): Boolean =
    VERSION_SEGMENT.matches(url.substringAfterLast('/'))

/** 当前档位在用的端点，配上它承担的角色。 */
data class ActiveSlot(val endpoint: ApiEndpoint, val roles: List<String>) {
    val label: String get() = roles.joinToString(" / ")
    val configured: Boolean get() = endpoint.configured
}

const val ROLE_VISION = "视觉"
const val ROLE_TEXT = "文本"

/**
 * 真正在用的端点：视觉档 + 文本档，去重，视觉在前。
 *
 * 预置了四个端点，但生效的只有选中的这两个。设置页顶部只列它们——
 * 让新用户一眼看到该填哪个 Key，而不是面对四个不知道从哪下手。
 * 两档选同一个端点时合并成一条，免得同一个 Key 在页面上出现两次。
 */
fun activeEndpoints(
    endpoints: List<ApiEndpoint>,
    visionId: String,
    textId: String,
): List<ActiveSlot> {
    val byId = endpoints.associateBy { it.id }
    val out = LinkedHashMap<String, MutableList<String>>()
    // 视觉在前：录入是第一步，识别先跑起来
    listOf(visionId to ROLE_VISION, textId to ROLE_TEXT).forEach { (id, role) ->
        if (byId.containsKey(id)) out.getOrPut(id) { mutableListOf() }.add(role)
    }
    return out.map { (id, roles) -> ActiveSlot(byId.getValue(id), roles) }
}

/** 预置端点。URL 也可改——写错了在应用里改一行，不必等发版。 */
object BuiltInEndpoints {

    const val ANTHROPIC = "anthropic"
    const val ZHIPU = "zhipu"
    const val MOONSHOT = "moonshot"
    const val MINIMAX = "minimax"
    const val DEEPSEEK = "deepseek"
    const val LOCAL = "local"

    val ALL: List<ApiEndpoint> = listOf(
        ApiEndpoint(
            id = ANTHROPIC,
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            protocol = Protocol.ANTHROPIC,
            builtIn = true,
            note = "console.anthropic.com 获取 Key",
        ),
        ApiEndpoint(
            id = ZHIPU,
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            protocol = Protocol.OPENAI,
            builtIn = true,
            note = "open.bigmodel.cn，Flash 系列免费",
        ),
        ApiEndpoint(
            id = MOONSHOT,
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            protocol = Protocol.OPENAI,
            builtIn = true,
            note = "platform.moonshot.cn，地址若有变动可直接改",
        ),
        ApiEndpoint(
            id = DEEPSEEK,
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            protocol = Protocol.OPENAI,
            builtIn = true,
            note = "platform.deepseek.com，模型 deepseek-chat / deepseek-reasoner",
        ),
        ApiEndpoint(
            id = LOCAL,
            name = "本地 / 局域网",
            baseUrl = "http://192.168.1.100:11434/v1",
            protocol = Protocol.OPENAI,
            builtIn = true,
            note = "电脑上用 Ollama 跑 GLM-OCR 之类的模型，手机填局域网地址，外网断了也能用",
        ),
        ApiEndpoint(
            id = MINIMAX,
            name = "MiniMax",
            baseUrl = "https://api.minimax.chat/v1",
            protocol = Protocol.OPENAI,
            builtIn = true,
            note = "platform.minimaxi.com，地址若有变动可直接改",
        ),
    )

    fun byId(id: String): ApiEndpoint? = ALL.firstOrNull { it.id == id }

    /**
     * 存下来的列表与预置项合并：预置项永远在（保留用户改过的 URL / Key），
     * 自定义项跟在后面。这样升级新增预置项时老用户也能看到。
     */
    fun merge(stored: List<ApiEndpoint>): List<ApiEndpoint> {
        val byId = stored.associateBy { it.id }
        val builtIn = ALL.map { preset ->
            byId[preset.id]?.copy(builtIn = true, name = preset.name, note = preset.note) ?: preset
        }
        val custom = stored.filter { it.id !in ALL.map { p -> p.id } }.map { it.copy(builtIn = false) }
        return builtIn + custom
    }
}
