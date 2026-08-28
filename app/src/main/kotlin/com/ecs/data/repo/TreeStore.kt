package com.ecs.data.repo

import android.content.Context
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.TreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** kaodian_tree.json：path / form_rule / embedding / created_in。 */
class TreeStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val file: File get() = File(context.filesDir, "kaodian_tree.json")

    private val _tree = MutableStateFlow<KaodianTree?>(null)
    val tree: StateFlow<KaodianTree?> = _tree.asStateFlow()

    suspend fun load(): KaodianTree? = withContext(Dispatchers.IO) {
        val t = runCatching {
            if (file.exists()) json.decodeFromString<KaodianTree>(file.readText()) else null
        }.getOrNull()
        _tree.value = t
        t
    }

    suspend fun save(tree: KaodianTree) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(tree))
        _tree.value = tree
    }

    /** 结构变更统一走这里：版本号必然递增，否则会产生一批孤儿记录。 */
    suspend fun mutate(transform: (List<TreeNode>) -> List<TreeNode>): KaodianTree {
        val current = _tree.value ?: load() ?: KaodianTree("v0", emptyList())
        val next = KaodianTree(
            version = KaodianTree.bumpVersion(current.version),
            nodes = transform(current.nodes),
        )
        save(next)
        return next
    }

    fun exists(): Boolean = file.exists()
}
