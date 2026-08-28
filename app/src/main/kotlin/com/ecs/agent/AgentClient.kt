package com.ecs.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
 */
class AgentClient(
    private val apiKeyProvider: suspend () -> String,
    private val modelProvider: suspend () -> String,
) {

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
    ): String = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isBlank()) throw AgentException("未配置 API Key")

        val body = buildJsonObject {
            put("model", modelProvider())
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

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AgentException("调用失败 ${resp.code}：${text.take(300)}")
            val parsed = json.parseToJsonElement(text).jsonObject
            parsed["content"]?.jsonArray
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
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
