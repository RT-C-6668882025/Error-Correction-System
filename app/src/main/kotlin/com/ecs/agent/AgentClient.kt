package com.ecs.agent

import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.ModelCatalog
import com.ecs.core.model.ModelDiscovery
import com.ecs.core.model.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * 四个内置任务共用的调用层。每次调用相互独立，不共用上下文——
 * 这是 F7.2 Top-5 约束成立的前提。
 *
 * 两种协议：Anthropic Messages 与 OpenAI 兼容（智谱 GLM）。
 * 差异只在请求体与取值路径上，上层任务感知不到。
 */
open class AgentClient(
    private val resolver: suspend (Role) -> Config,
) {

    /** 识别走视觉模型，其余走文本模型。 */
    enum class Role { TEXT, VISION }

    data class Config(val endpoint: ApiEndpoint, val modelId: String)

    class AgentException(message: String) : Exception(message)

    /**
     * 四个超时都要显式设。识别请求的正文是整张图的 base64，
     * OkHttp 默认的 writeTimeout 只有 10 秒，手机上传根本发不完——
     * 这是「识别超时」最常见的那一种。
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Image(val mediaType: String, val base64: String)

    /** open 是为了测试能脱网替换掉这一层，正式路径不覆盖它。 */
    open suspend fun complete(
        system: String,
        user: String,
        images: List<Image> = emptyList(),
        maxTokens: Int = 4096,
        temperature: Double = 0.0,
        role: Role = Role.TEXT,
    ): String = withContext(Dispatchers.IO) {
        val config = resolver(role)
        call(config.endpoint, config.modelId, system, user, images, maxTokens, temperature)
    }

    private fun call(
        endpoint: ApiEndpoint,
        modelId: String,
        system: String,
        user: String,
        images: List<Image>,
        maxTokens: Int,
        temperature: Double,
    ): String {
        if (endpoint.baseUrl.isBlank()) throw AgentException("${endpoint.name} 未填地址")
        if (endpoint.apiKey.isBlank()) throw AgentException("${endpoint.name} 未配置 API Key")
        if (modelId.isBlank()) throw AgentException("${endpoint.name} 未指定模型 ID")

        val body = when (endpoint.protocol) {
            Protocol.OPENAI -> openAiBody(modelId, system, user, images, maxTokens, temperature)
            Protocol.ANTHROPIC -> anthropicBody(modelId, system, user, images, maxTokens, temperature)
        }

        val builder = Request.Builder()
            .url(endpoint.url)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
        when (endpoint.protocol) {
            Protocol.OPENAI -> builder.addHeader("Authorization", "Bearer ${endpoint.apiKey}")
            Protocol.ANTHROPIC -> {
                builder.addHeader("x-api-key", endpoint.apiKey)
                builder.addHeader("anthropic-version", ANTHROPIC_VERSION)
            }
        }

        return try {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 中转站配错时这条信息是唯一线索，原样带回状态码与响应片段
                    throw AgentException("$modelId 调用失败 ${resp.code}：${text.take(300)}")
                }
                extractText(text, endpoint.protocol)
            }
        } catch (e: InterruptedIOException) {
            // 超时冒到 UI 上是一句 SocketTimeoutException，看不出卡在哪一步
            throw AgentException(timeoutMessage(modelId, images.size, e))
        }
    }

    /** 连不上 / 图片没发完 / 模型没回，三种超时给的建议不一样。 */
    private fun timeoutMessage(modelId: String, imageCount: Int, e: InterruptedIOException): String {
        val detail = e.message.orEmpty()
        val stage = when {
            detail.contains("connect", ignoreCase = true) -> "连不上服务器，检查端点地址和网络"
            imageCount > 0 -> "图片还没传完。少选几张（现在 $imageCount 张），或换到 Wi-Fi"
            else -> "模型迟迟没有返回，可以换一个更快的模型"
        }
        return "$modelId 超时：$stage"
    }

    /**
     * 测试连接：发一次最小请求。中转站地址、协议、Key 三者错任意一个，
     * 报错都长得一样，这里把原始状态码和响应片段直接抛出来。
     */
    suspend fun ping(endpoint: ApiEndpoint, modelId: String): String = withContext(Dispatchers.IO) {
        val reply = call(
            endpoint = endpoint,
            modelId = modelId,
            system = "回答要极短。",
            user = "回一个字：好",
            images = emptyList(),
            maxTokens = 16,
            temperature = 0.0,
        )
        "连通：${endpoint.url}　模型回了「${reply.trim().take(20)}」"
    }

    /**
     * 拉这个 Key 实际能用的模型清单。厂商上新、改名、下线都不用再等发版，
     * 用户也不用去官网抄 ID。拉不到就抛，上层退回静态清单，不清掉已有配置。
     */
    open suspend fun listModels(endpoint: ApiEndpoint): List<ModelDiscovery.RemoteModel> =
        withContext(Dispatchers.IO) {
            if (endpoint.baseUrl.isBlank()) throw AgentException("${endpoint.name} 未填地址")
            if (endpoint.apiKey.isBlank()) throw AgentException("${endpoint.name} 未配置 API Key")

            val urls = ModelDiscovery.modelsUrls(endpoint.baseUrl, endpoint.protocol)
            var failure: AgentException? = null
            urls.forEach { url ->
                // 一个地址不通就换下一个：厂商把清单挂在哪儿并不统一
                val result = runCatching { fetchModels(endpoint, url) }
                result.getOrNull()?.let { return@withContext it }
                failure = result.exceptionOrNull() as? AgentException
                    ?: AgentException("拉模型清单失败（$url）")
            }
            throw failure ?: AgentException("${endpoint.name} 没有可用的模型清单地址")
        }

    private fun fetchModels(endpoint: ApiEndpoint, url: String): List<ModelDiscovery.RemoteModel> {
        val builder = Request.Builder().url(url).get()
        when (endpoint.protocol) {
            Protocol.OPENAI -> builder.addHeader("Authorization", "Bearer ${endpoint.apiKey}")
            Protocol.ANTHROPIC -> {
                builder.addHeader("x-api-key", endpoint.apiKey)
                builder.addHeader("anthropic-version", ANTHROPIC_VERSION)
            }
        }
        return try {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 有的厂商压根没开放 /models，这里要说清楚是清单接口不通，不是 Key 不对
                    throw AgentException(
                        "拉模型清单失败 ${resp.code}（$url）：${text.take(200)}　$LIST_OPTIONAL"
                    )
                }
                ModelDiscovery.parse(text).ifEmpty {
                    throw AgentException("$url 返回的清单里没有可用模型　$LIST_OPTIONAL")
                }
            }
        } catch (e: InterruptedIOException) {
            throw AgentException("拉模型清单超时（$url），检查地址和网络")
        }
    }

    // ---------- 请求体 ----------

    private fun anthropicBody(
        model: String,
        system: String,
        user: String,
        images: List<Image>,
        maxTokens: Int,
        temperature: Double,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("temperature", temperature)
        put("system", system)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        images.forEach { img ->
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", img.mediaType)
                                        put("data", img.base64)
                                    }
                                }
                            )
                        }
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", user)
                            }
                        )
                    }
                }
            )
        }
    }

    private fun openAiBody(
        model: String,
        system: String,
        user: String,
        images: List<Image>,
        maxTokens: Int,
        temperature: Double,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        // OpenAI 兼容端的 temperature 区间不含 0（智谱如此，多数中转站跟随），传 0 会被拒
        put("temperature", temperature.coerceAtLeast(ModelCatalog.OPENAI_MIN_TEMPERATURE))
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", system)
                }
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        images.forEach { img ->
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:${img.mediaType};base64,${img.base64}")
                                    }
                                }
                            )
                        }
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", user)
                            }
                        )
                    }
                }
            )
        }
    }

    // ---------- 取值 ----------

    /**
     * 两种协议的正文路径不同。
     *
     * 推理型模型（DeepSeek 的 pro 档、各家的 thinking 系列）把思考放在 `reasoning_content`，
     * 而 `content` 可能是空串——上层拿到空字符串去找 JSON，报出来的是一句
     * 「响应中没有 JSON」，看不出真正发生了什么。所以这里 content 空了就回退去读思考，
     * 两处都空则直接说清楚是模型没给正文，而不是让错误顺着流到解析层。
     */
    fun extractText(raw: String, protocol: Protocol): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val cut = truncated(root, protocol)
        if (protocol != Protocol.OPENAI) {
            return root["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("\n")
                ?.ifBlank { throw AgentException(if (cut) TRUNCATED else NO_BODY) }
                ?: throw AgentException("响应缺少 content")
        }

        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AgentException("响应缺少 choices[0].message")
        fun field(name: String) = message[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        field("content")?.let { return it }
        // 有的中转站把结构化输出放在 content 之外的字段里，一并认了
        val thinking = field("reasoning_content") ?: field("reasoning")
        // 思考里没有 JSON、而且这次回复是被额度截断的：正文根本没轮到写。
        // 把思考原样交给解析层只会得到一句「响应中没有 JSON」，看不出该改什么
        if (thinking == null || (cut && !looksLikeJson(thinking))) {
            throw AgentException(if (cut) TRUNCATED else NO_BODY)
        }
        return thinking
    }

    /** 这次回复是不是写到一半被 max_tokens 掐断的。 */
    private fun truncated(root: JsonObject, protocol: Protocol): Boolean = when (protocol) {
        Protocol.OPENAI -> root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "length"
        Protocol.ANTHROPIC -> root["stop_reason"]?.jsonPrimitive?.contentOrNull == "max_tokens"
    }

    private fun looksLikeJson(text: String): Boolean = text.any { it == '{' || it == '[' }

    /** 模型偶尔会在 JSON 外包一层解释或围栏，这里只取第一个完整对象/数组。 */
    fun extractJson(raw: String): String {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)?.trim()
        val text = fenced ?: raw.trim()
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) throw AgentException("响应中没有 JSON：${raw.take(200)}")
        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        throw AgentException("JSON 未闭合：${raw.take(200)}")
    }

    fun <T> decode(raw: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T =
        json.decodeFromString(deserializer, extractJson(raw))

    fun obj(raw: String): JsonObject = json.parseToJsonElement(extractJson(raw)).jsonObject

    fun arr(raw: String): JsonArray = json.parseToJsonElement(extractJson(raw)).jsonArray

    companion object {
        /** 模型只吐了思考、或者干脆什么都没吐。说清楚该往哪儿改，别让它变成一句「没有 JSON」。 */
        const val NO_BODY = "模型没有返回正文（只有思考或空响应）：多半是 max_tokens 不够被截断，" +
            "或这个模型不适合结构化输出——换一个非推理档的模型试试"

        /**
         * 推理档把额度烧在思考上、正文一个字都没轮到写。这跟「模型没回」不是一回事：
         * 上层会自动用更大的额度再要一次，所以这里要说清楚是截断，别再表现成「没有 JSON」。
         */
        const val TRUNCATED = "模型把 token 额度用在思考上，正文被截断了。已按更大的额度重试；" +
            "如果总是这样，换一个非推理档的模型（分析这一步不需要长思考，也会快很多）"

        /** 清单拉不到不影响使用——这句要跟着每一条清单报错走，否则看着像 Key 不对。 */
        const val LIST_OPTIONAL = "（清单不是必须的：下面的候选照样能选，也可以直接手填模型 ID）"

        private const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
