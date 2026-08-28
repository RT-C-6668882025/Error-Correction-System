package com.ecs.data.repo

import android.content.Context
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Section
import com.ecs.core.model.Src
import com.ecs.core.parse.SlotSpec
import com.ecs.core.rules.Validation
import com.ecs.data.backup.BackupManager
import com.ecs.data.db.AppDatabase
import com.ecs.data.db.RecordDao
import com.ecs.data.db.toEntity
import com.ecs.data.db.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

class RecordRepository(
    private val dao: RecordDao,
    private val treeStore: TreeStore,
    private val settings: Settings,
    private val backup: BackupManager,
) {

    val all: Flow<List<ErrorRecord>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    val conflicts: Flow<List<ErrorRecord>> = dao.observeConflicts().map { rows -> rows.map { it.toModel() } }

    fun byStatus(status: RecordStatus): Flow<List<ErrorRecord>> =
        dao.observeByStatus(status.label).map { rows -> rows.map { it.toModel() } }

    suspend fun snapshot(): List<ErrorRecord> = dao.all().map { it.toModel() }

    suspend fun get(uid: String): ErrorRecord? = dao.byUid(uid)?.toModel()

    /**
     * F1.2 极简录入：卷名 + 错题号，其余留空，status = 不完整。
     * 先保证记录不丢，再保证记录完整——这里不做任何会阻断的校验。
     */
    suspend fun quickAdd(
        paper: String,
        section: Section,
        spec: String,
        totalInSection: Int? = null,
        srcRef: String? = null,
        now: Long = System.currentTimeMillis(),
        /** 识别流程带来的题干 / 提示词 / 答案，键是 (题号, 空序)。 */
        details: Map<Pair<Int, Int>, Detail> = emptyMap(),
    ): QuickAddResult {
        val parsed = SlotSpec.parse(spec, section)
        val batch = SlotSpec.batchLabel(settings.currentBatch())
        val existing = dao.allUids().toSet()

        val records = parsed.entries.map { e ->
            ErrorRecord(
                id = e.id(section),
                src = Src(paper, section, e.no, e.slot, batch, totalInSection),
                srcRef = srcRef,
                stem = details[e.no to e.slot]?.stem,
                given = details[e.no to e.slot]?.given,
                answer = details[e.no to e.slot]?.answer,
                confidence = e.confidence,
                createdAt = now,
                status = RecordStatus.INCOMPLETE,
            )
        }
        val fresh = records.filter { it.uid !in existing }
        dao.insertAll(fresh.map { it.toEntity() })
        maybeBackup()
        return QuickAddResult(
            records = fresh,
            inserted = fresh.size,
            skippedDuplicates = records.size - fresh.size,
            unparsed = parsed.errors,
        )
    }

    data class Detail(val stem: String?, val given: String?, val answer: String?)

    data class QuickAddResult(
        val records: List<ErrorRecord>,
        val inserted: Int,
        val skippedDuplicates: Int,
        val unparsed: List<String>,
    )

    /** 批量导入（F1.5）走同一条校验路径。 */
    suspend fun importAll(records: List<ErrorRecord>): Int {
        val tree = treeStore.tree.value ?: treeStore.load()
        val existing = dao.allUids().toSet()
        val fresh = records.filter { it.uid !in existing }
            .map { it.copy(status = Validation.deriveStatus(it, tree)) }
        dao.insertAll(fresh.map { it.toEntity() })
        maybeBackup()
        return fresh.size
    }

    /**
     * 保存派生字段。form_rule 永远由考点树带出，调用方给的值被忽略——
     * 一致性靠结构保证，不靠事后校验。
     */
    suspend fun saveAnnotation(record: ErrorRecord): ErrorRecord {
        val tree = treeStore.tree.value ?: treeStore.load()
        val rule = record.kaodian?.let { tree?.formRuleOf(it) }
        val withRule = record.copy(formRule = rule, treeVersion = tree?.version ?: record.treeVersion)
        val settled = withRule.copy(status = Validation.deriveStatus(withRule, tree))
        dao.update(settled.toEntity())
        return settled
    }

    suspend fun fillAnswer(uid: String, answer: String) {
        val current = get(uid) ?: return
        saveAnnotation(current.copy(answer = answer))
    }

    suspend fun delete(uid: String) = dao.delete(uid)

    // ---------- 导出与备份 ----------

    suspend fun exportNow(): File {
        val tree = treeStore.tree.value ?: treeStore.load()
        return backup.export(snapshot(), tree, prune = false)
    }

    fun backups(): List<File> = backup.listBackups()

    private suspend fun maybeBackup() {
        val count = dao.count()
        val last = settings.lastBackupCount()
        if (BackupManager.shouldBackup(last, count)) {
            val tree = treeStore.tree.value ?: treeStore.load()
            backup.export(snapshot(), tree, prune = true)
            settings.setLastBackupCount(count)
        }
    }

    companion object {
        fun create(context: Context): RecordRepository {
            val db = AppDatabase.get(context)
            return RecordRepository(
                dao = db.records(),
                treeStore = TreeStore(context),
                settings = Settings(context),
                backup = BackupManager(context),
            )
        }
    }
}
