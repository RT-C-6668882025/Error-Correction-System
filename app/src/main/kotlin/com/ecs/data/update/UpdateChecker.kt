package com.ecs.data.update

import android.content.Context
import android.content.pm.PackageManager
import com.ecs.core.update.Release
import com.ecs.core.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 对比 GitHub 上最新 Release 的版本号。公开仓库，免鉴权。
 *
 * 当前版本走 PackageManager 而不是 BuildConfig：AGP 8 默认不生成 BuildConfig，
 * 走它就得额外开一个构建开关，没必要。
 */
class UpdateChecker(private val context: Context) {

    data class Result(
        val installed: String,
        val release: Release?,
        val hasUpdate: Boolean,
        val error: String? = null,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun installedVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "未知"

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val installed = installedVersion()
        val request = Request.Builder()
            .url(UpdateCheck.LATEST_RELEASE_API)
            .addHeader("Accept", "application/vnd.github+json")
            .get()
            .build()

        runCatching {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use Result(installed, null, false, "检查失败 ${resp.code}")
                }
                val release = UpdateCheck.parseLatest(body)
                    ?: return@use Result(installed, null, false, "响应解析不出版本号")
                Result(
                    installed = installed,
                    release = release,
                    hasUpdate = UpdateCheck.isNewer(installed, release.tagName),
                )
            }
        }.getOrElse { e ->
            // 离线是常态，不该崩，也不该只留一个空白
            Result(installed, null, false, e.message ?: "网络不可用")
        }
    }
}
