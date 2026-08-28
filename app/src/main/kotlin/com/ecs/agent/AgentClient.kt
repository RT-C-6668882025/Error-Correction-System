package com.ecs.agent

import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.ModelCatalog
import com.ecs.core.model.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
import java.util.concurrent.TimeUnit

/**
 * 四个内置任务共用的调用层。每次调用相互独立，不共用上下文——
 * 这是 F7.2 Top-5 约束成立的前提。
 *
 * 两种协议：Anthropic Messages 与 OpenAI 兼容（智谱 GLM）。
 * 差异只在请求体与取值路径上，上层任务感知不到。
 */
class AgentClient(
    private val resolver: suspend (Role) -> Config,
) {

    /** 识别走视觉模型，其余走文本模型。 */
    enum class Role { TEXT, VISION }

    data class Config(val endpoint: ApiEndpoint, val modelId: String)

    class AgentException(message: String) : Exception(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Image(val mediaType: String, val base64: String)

    suspend fun complete(
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

        return http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 中转站配错时这条信息是唯一线索，原样带回状态码与响应片段
                throw AgentException("$modelId 调用失败 ${resp.code}：${text.take(300)}")
            }
            extractText(text, endpoint.protocol)
        }
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

    /** 两种协议的正文路径不同；思考型模型的推理过程不在 content 里，取到的就是答案。 */
    fun extractText(raw: String, protocol: Protocol): String {
        val root = json.parseToJsonElement(raw).jsonObject
        return if (protocol == Protocol.OPENAI) {
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                ?: throw AgentException("响应缺少 choices[0].message.content")
        } else {
            root["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("\n")
                ?: throw AgentException("响应缺少 content")
        }
    }

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
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
