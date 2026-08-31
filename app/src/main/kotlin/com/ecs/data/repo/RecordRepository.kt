package com.ecs.data.repo

import android.content.Context
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
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
    private val directionStore: DirectionStore,
    private val settings: Settings,
    private val backup: BackupManager,
) {

    val all: Flow<List<ErrorRecord>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun snapshot(): List<ErrorRecord> = dao.all().map { it.toModel() }

    /**
     * 入库。收的是确认页上标过的那些题——不标的既不入库也不分析。
     * 先保证记录不丢，再保证记录完整：这里不做任何会阻断的校验。
     */
    suspend fun add(
        paper: String,
        marks: List<SlotSpec.Mark>,
        srcRef: String? = null,
        now: Long = System.currentTimeMillis(),
        /** 识别流程带来的题干 / 提示词 / 答案，键是 (题号, 空序)。 */
        details: Map<Pair<Int, Int>, Detail> = emptyMap(),
    ): AddResult {
        val batch = SlotSpec.batchLabel(settings.currentBatch())
        val existing = dao.allUids().toSet()

        val records = marks.map { m ->
            ErrorRecord(
                id = m.id,
                src = Src(paper, m.no, m.slot, batch),
                srcRef = srcRef,
                stem = details[m.no to m.slot]?.stem,
                given = details[m.no to m.slot]?.given,
                answer = details[m.no to m.slot]?.answer,
                confidence = m.confidence,
                createdAt = now,
                status = RecordStatus.PENDING,
            )
        }
        val fresh = records.filter { it.uid !in existing }
        dao.insertAll(fresh.map { it.toEntity() })
        maybeBackup()
        return AddResult(
            records = fresh,
            inserted = fresh.size,
            skippedDuplicates = records.size - fresh.size,
        )
    }

    data class Detail(val stem: String?, val given: String?, val answer: String?)

    data class AddResult(
        val records: List<ErrorRecord>,
        val inserted: Int,
        val skippedDuplicates: Int,
    )

    /** 批量导入走同一条状态推导路径。 */
    suspend fun importAll(records: List<ErrorRecord>): Int {
        val existing = dao.allUids().toSet()
        val fresh = records.filter { it.uid !in existing }
            .map { it.copy(status = Validation.deriveStatus(it)) }
        dao.insertAll(fresh.map { it.toEntity() })
        maybeBackup()
        return fresh.size
    }

    /** 保存分析产出。状态由字段现状推导，不由调用方指定。 */
    suspend fun saveAnalysis(record: ErrorRecord): ErrorRecord {
        val settled = record.copy(status = Validation.deriveStatus(record))
        dao.update(settled.toEntity())
        return settled
    }

    suspend fun delete(uid: String) = dao.delete(uid)

    /**
     * 批量删。删之前先导一份备份——一次点掉几十条是不可撤销的，
     * 而备份目录里多一份的代价近乎为零。
     */
    suspend fun deleteAll(uids: List<String>): Int {
        if (uids.isEmpty()) return 0
        backup.export(snapshot(), directionStore.all(), prune = true)
        return uids.chunked(SQLITE_VARS).sumOf { dao.deleteAll(it) }
    }

    // ---------- 导出与备份 ----------

    suspend fun exportNow(): File = backup.export(snapshot(), directionStore.all(), prune = false)

    fun backups(): List<File> = backup.listBackups()

    /** 打成一个 zip 好走系统分享——带不走的备份不叫备份。 */
    suspend fun zipFor(dir: File): File = backup.zipFor(dir)

    private suspend fun maybeBackup() {
        val count = dao.count()
        val last = settings.lastBackupCount()
        if (BackupManager.shouldBackup(last, count)) {
            backup.export(snapshot(), directionStore.all(), prune = true)
            settings.setLastBackupCount(count)
        }
    }

    companion object {

        /** SQLite 一条语句最多 999 个变量，超了会直接抛。 */
        private const val SQLITE_VARS = 900

        fun create(context: Context): RecordRepository {
            val db = AppDatabase.get(context)
            return RecordRepository(
                dao = db.records(),
                directionStore = DirectionStore(context),
                settings = Settings(context),
                backup = BackupManager(context),
            )
        }
    }
}
