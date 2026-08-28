package com.ecs.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** GitHub Releases 的最新一条，解析后只留用得上的字段。 */
data class Release(
    val tagName: String,
    val title: String,
    val notes: String,
    /** APK 资产的直链；这一版没传 APK 时为 null。 */
    val apkUrl: String?,
    val pageUrl: String?,
)

object UpdateCheck {

    const val LATEST_RELEASE_API =
        "https://api.github.com/repos/RT-C-6668882025/Error-Correction-System/releases/latest"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 语义化版本比较。容忍 `v` 前缀、位数不等（2.1 == 2.1.0）、
     * 以及末尾的预发布后缀（2.2.0-beta1 < 2.2.0）。
     */
    fun compareVersions(a: String, b: String): Int {
        val (numsA, preA) = split(a)
        val (numsB, preB) = split(b)
        val width = maxOf(numsA.size, numsB.size)
        for (i in 0 until width) {
            val x = numsA.getOrElse(i) { 0 }
            val y = numsB.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // 数字相同：带预发布后缀的更旧
        return when {
            preA.isEmpty() && preB.isEmpty() -> 0
            preA.isEmpty() -> 1
            preB.isEmpty() -> -1
            else -> preA.compareTo(preB)
        }
    }

    fun isNewer(installed: String, latest: String): Boolean =
        compareVersions(latest, installed) > 0

    private fun split(raw: String): Pair<List<Int>, String> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val dash = cleaned.indexOfFirst { it == '-' || it == '+' }
        val core = if (dash >= 0) cleaned.substring(0, dash) else cleaned
        val pre = if (dash >= 0) cleaned.substring(dash + 1) else ""
        val nums = core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return nums to pre
    }

    /** 解析 GitHub Releases API 的响应。字段缺失时返回 null，不抛。 */
    fun parseLatest(body: String): Release? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val apk = root["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content.orEmpty().endsWith(".apk", true) }
            ?.get("browser_download_url")?.jsonPrimitive?.content
        Release(
            tagName = tag,
            title = root["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: tag,
            notes = root["body"]?.jsonPrimitive?.content.orEmpty(),
            apkUrl = apk,
            pageUrl = root["html_url"]?.jsonPrimitive?.content,
        )
    }.getOrNull()
}
