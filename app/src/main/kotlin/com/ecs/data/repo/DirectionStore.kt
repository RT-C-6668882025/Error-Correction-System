package com.ecs.data.repo

import android.content.Context
import com.ecs.core.direction.Direction
import com.ecs.core.tree.Skeleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * directions.json：每个板块一份小方向，外加一份大方向。
 *
 * 按 scope 覆盖写：重跑某个板块只换那一份，别的板块的汇总结果不受影响——
 * 汇总一次要花模型调用，不该因为动了一支就全部作废。
 */
class DirectionStore(private val context: Context) {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val file: File get() = File(context.filesDir, "directions.json")
    private val serializer = ListSerializer(Direction.serializer())
    private val writeLock = Mutex()

    private val _directions = MutableStateFlow<List<Direction>>(emptyList())
    val directions: StateFlow<List<Direction>> = _directions.asStateFlow()

    suspend fun load(): List<Direction> = withContext(Dispatchers.IO) {
        val list = runCatching {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        _directions.value = list
        list
    }

    /** 已加载的就直接给，导出与备份用得上。 */
    suspend fun all(): List<Direction> = _directions.value.ifEmpty { load() }

    fun of(scope: String): Direction? = _directions.value.firstOrNull { it.scope == scope }

    fun major(): Direction? = of(Direction.ALL)

    suspend fun save(direction: Direction) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            // 大方向是所有小方向的物化视图。任意一棵小方向被重建，旧大方向立刻过期；
            // 否则页面会同时展示新小方向和基于旧小方向生成的大方向。
            val next = _directions.value.filterNot {
                it.scope == direction.scope ||
                    (direction.scope != Direction.ALL && it.scope == Direction.ALL)
            } + direction
            write(next)
        }
    }

    suspend fun delete(scope: String) = withContext(Dispatchers.IO) {
        writeLock.withLock { write(_directions.value.filterNot { it.scope == scope }) }
    }

    /**
     * A minor is a materialized view of analyses; the major is a materialized view of minors.
     * Once an input branch changes, keeping either result would present stale output as current.
     */
    suspend fun invalidate(scopes: Collection<String?>) = withContext(Dispatchers.IO) {
        // 老版本可能保存四层路径；Direction 的 scope 始终是骨架里的两层板块路径。
        // 失效前先归一，不然 `词法/名词/后缀转换/-tion` 匹配不到 `词法/名词`。
        val affected = scopes.filterNotNull().mapTo(hashSetOf()) {
            Skeleton.branchOf(it)?.path ?: it
        }
        if (affected.isEmpty()) return@withContext
        writeLock.withLock {
            write(_directions.value.filterNot { it.scope == Direction.ALL || it.scope in affected })
        }
    }

    private fun write(list: List<Direction>) {
        val sorted = list.sortedBy { if (it.scope == Direction.ALL) "￿" else it.scope }
        file.writeText(json.encodeToString(serializer, sorted))
        _directions.value = sorted
    }
}
