package com.ecs.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「厂商有哪些模型」这件事，问厂商自己。
 *
 * 原来的做法是把模型 ID 写死在 [ModelCatalog] 里，加上一个自由输入框：厂商上新、
 * 改名、下线，用户就得自己去官网翻 ID 再手抄进来，抄错了只会看到一句调用失败。
 * 两种协议都有 `/models`，返回的就是这个 Key 实际能用的清单——拉一次就不用猜了。
 *
 * 这里只做纯计算（URL 归一、解析、挑选），网络在 AgentClient.listModels。
 * 拉不到时上层退回 [ModelCatalog] 的静态清单，功能不回退。
 */
object ModelDiscovery {

    data class RemoteModel(
        val id: String,
        val label: String,
        val vision: Boolean,
        val note: String = "",
    )

    /**
     * 模型清单地址。和 [normalizeUrl] 一样容忍各种粘法，包括直接粘了聊天接口的
     * 完整地址——那种情况下把方法路径换成 models，而不是拼出一个 404。
     */
    fun modelsUrl(baseUrl: String, protocol: Protocol): String {
        var s = baseUrl.trim().substringBefore('#').substringBefore('?').trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        s = s.trimEnd('/')

        // 粘的是聊天接口就退回到版本段
        listOf("/chat/completions", "/completions", "/messages").forEach { tail ->
            if (s.endsWith(tail, ignoreCase = true)) s = s.dropLast(tail.length)
        }
        if (s.endsWith("/models", ignoreCase = true)) return s

        return if (hasVersionSegment(s)) "$s/models" else "$s/v1/models"
    }

    /**
     * 依次要试的清单地址。
     *
     * 厂商把清单接口挂在哪儿并不统一：多数是版本段下的 /models，也有只认根下
     * /v1/models 的（中转站尤其乱）。原来只试一个地址，一个 404 就报「拉不到」，
     * 而用户看到的只是「视觉模型一直拉不下来」。多试一个几乎不花时间。
     */
    fun modelsUrls(baseUrl: String, protocol: Protocol): List<String> {
        val primary = modelsUrl(baseUrl, protocol)
        if (primary.isEmpty()) return emptyList()
        val root = rootOf(primary)
        val fallback = if (root.isEmpty()) "" else "$root/v1/models"
        return listOfNotNull(primary, fallback.takeIf { it.isNotEmpty() && it != primary })
    }

    /** scheme://host[:port]，取不出来就返回空串。 */
    private fun rootOf(url: String): String {
        val mark = url.indexOf("://")
        if (mark < 0) return ""
        val afterScheme = url.indexOf('/', mark + 3)
        return if (afterScheme < 0) url else url.substring(0, afterScheme)
    }

    /**
     * OpenAI 与 Anthropic 的清单响应都是 `{"data":[{"id":…}]}`；中转站偶尔写成
     * `{"models":[…]}` 或裸数组，一并吃掉。解析不出就返回空，交给上层退回静态清单。
     */
    fun parse(raw: String): List<RemoteModel> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val array: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray
            root is JsonObject && root["models"] is JsonArray -> root["models"]!!.jsonArray
            else -> return emptyList()
        }

        return array.mapNotNull { element ->
            val id = when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> element["id"]?.jsonPrimitive?.content
                    ?: element["name"]?.jsonPrimitive?.content
                else -> null
            }?.trim().orEmpty()
            if (id.isBlank() || !isChatModel(id)) return@mapNotNull null

            val display = (element as? JsonObject)
                ?.get("display_name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val known = ModelCatalog.byId(id)
            RemoteModel(
                id = id,
                label = display ?: known?.label ?: id,
                vision = isVision(id),
                note = known?.note.orEmpty(),
            )
        }.distinctBy { it.id }
    }

    /**
     * 拉到的清单与内置候选合并，拉到的在前。
     *
     * 厂商的 /models 不一定是全集：智谱把视觉档（glm-4v 系列）留在文档里、
     * 清单接口只回文本档，中转站更是想回什么回什么。原来「拉到了就整份换掉」，
     * 于是拉一次清单反而把本来能用的视觉模型从候选里抹掉了——页面上写着
     * 「这个厂商没有能看图的模型」，可 glm-4.6v-flash 明明调得通。
     *
     * 所以内置的那几个永远补在后面：清单里已有的不重复，没有的标出来是内置候选，
     * 用户知道它没在厂商清单里出现过，但照样可以点。
     */
    fun merge(fetched: List<RemoteModel>, builtIn: List<RemoteModel>): List<RemoteModel> {
        if (fetched.isEmpty()) return builtIn
        val seen = fetched.map { it.id }.toSet()
        return fetched + builtIn.filterNot { it.id in seen }
            .map { it.copy(note = it.note.ifBlank { SUGGESTED_NOTE }) }
    }

    /** 清单里认识的以清单为准，不认识的看 ID：厂商命名里视觉标记相当稳定。 */
    fun isVision(id: String): Boolean {
        ModelCatalog.byId(id)?.let { return it.vision }
        val s = id.lowercase()
        if (VISION_TOKEN.containsMatchIn(s)) return true
        return VISION_HINTS.any { s.contains(it) }
    }

    /**
     * 聊天以外的模型（向量、重排、语音、画图、审核）也在同一张清单里返回，
     * 选到它们只会得到一个看不懂的报错。
     */
    fun isChatModel(id: String): Boolean {
        val s = id.lowercase()
        return NON_CHAT.none { s.contains(it) }
    }

    /**
     * 自动挑一个。清单里推荐过的优先，顺序就是 [ModelCatalog.MODELS] 的顺序；
     * 陌生 ID 按命名里的档位关键字排：识别天天要跑，挑便宜的；判断决定分析质量，挑强的。
     */
    fun pick(models: List<RemoteModel>, vision: Boolean): RemoteModel? {
        // 视觉档必须真能看图；文本档不挑，视觉模型也能纯文本用
        val pool = if (vision) models.filter { it.vision } else models
        if (pool.isEmpty()) return null

        val preferred = ModelCatalog.MODELS
            .filter { !vision || it.vision }
            .map { it.id }

        return pool.minByOrNull { model ->
            val known = preferred.indexOf(model.id)
            if (known >= 0) known else PREFERRED_CEILING + rank(model.id, vision)
        }
    }

    private fun rank(id: String, vision: Boolean): Int {
        val s = id.lowercase()
        val cheap = CHEAP_HINTS.any { s.contains(it) }
        val strong = STRONG_HINTS.any { s.contains(it) }
        return when {
            vision && cheap -> 0
            vision && strong -> 3
            vision -> 2
            strong -> 0
            cheap -> 3
            else -> 2
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 比清单里任何一项都靠后。 */
    private const val PREFERRED_CEILING = 1000

    /** 内置补进来的候选：厂商清单里没有它，但它调得通。 */
    const val SUGGESTED_NOTE = "内置候选"

    /** glm-4v、glm-4.6v、qwen2-vl、cogvlm 这类：数字后面一个 v，或独立的 v / vl / vlm 段。 */
    private val VISION_TOKEN = Regex("""\d(\.\d+)?v($|[-_.])|(^|[-_.])vl(m)?($|[-_.])|(^|[-_.])v($|[-_.])""")

    private val VISION_HINTS = listOf("vision", "omni", "multimodal", "ocr", "claude-")

    private val NON_CHAT = listOf(
        "embed", "rerank", "tts", "asr", "whisper", "audio", "speech", "voice",
        "image", "cogview", "video", "cogvideo", "moderation", "guard", "safety",
    )

    private val CHEAP_HINTS = listOf("flash", "mini", "lite", "small", "air", "nano", "haiku")

    private val STRONG_HINTS = listOf("opus", "max", "-pro", "plus", "ultra", "large", "sonnet")
}
