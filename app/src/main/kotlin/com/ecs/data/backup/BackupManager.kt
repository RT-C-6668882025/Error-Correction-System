package com.ecs.data.backup

import android.content.Context
import com.ecs.core.export.Exporter
import com.ecs.core.model.ErrorRecord
import com.ecs.core.tree.KaodianTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F5.3 自动备份：每新增 50 条导出一次，保留最近 5 份。
 * 本地 SQLite 丢失等于全部积累归零，这一份是唯一的兜底。
 */
class BackupManager(private val context: Context) {

    private val root: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    suspend fun export(records: List<ErrorRecord>, tree: KaodianTree?, prune: Boolean): File =
        withContext(Dispatchers.IO) {
            val pkg = Exporter.build(records, tree, stamp())
            val dir = File(root, pkg.dirName).apply { mkdirs() }
            File(dir, "README.md").writeText(pkg.readme)
            File(dir, "data.json").writeText(pkg.dataJson)
            File(dir, "data.csv").writeText(pkg.dataCsv)
            File(dir, "倒推表.md").writeText(pkg.reverseTable)
            if (prune) prune()
            dir
        }

    fun listBackups(): List<File> =
        root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    private fun prune(keep: Int = KEEP) {
        listBackups().drop(keep).forEach { it.deleteRecursively() }
    }

    companion object {
        const val EVERY = 50
        const val KEEP = 5

        /** 跨过 50 的整数倍就备份一次。 */
        fun shouldBackup(previousCount: Int, currentCount: Int): Boolean =
            currentCount / EVERY > previousCount / EVERY
    }
}
