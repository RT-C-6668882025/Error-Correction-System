package com.ecs.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecs.Container
import com.ecs.EcsApp
import com.ecs.agent.AgentClient
import com.ecs.agent.Annotator
import com.ecs.agent.PaperScanner
import com.ecs.agent.TreeMaintainer
import com.ecs.core.agg.Aggregator
import com.ecs.core.export.CsvImporter
import com.ecs.core.export.Exporter
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Section
import com.ecs.core.model.Verified
import com.ecs.core.report.ReportBuilder
import com.ecs.core.tree.KaodianTree
import com.ecs.data.repo.RecordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container: Container = (app as EcsApp).container
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val repo: RecordRepository = container.repository

    val records: StateFlow<List<ErrorRecord>> =
        repo.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tree: StateFlow<KaodianTree?> = container.treeStore.tree

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _microDepth = MutableStateFlow(3)
    val microDepth: StateFlow<Int> = _microDepth.asStateFlow()

    private val _macroDepth = MutableStateFlow(2)
    val macroDepth: StateFlow<Int> = _macroDepth.asStateFlow()

    /** 识别结果暂存，供确认页使用。 */
    private val _scanned = MutableStateFlow<List<PaperScanner.Question>>(emptyList())
    val scanned: StateFlow<List<PaperScanner.Question>> = _scanned.asStateFlow()

    private val _proposals = MutableStateFlow<List<TreeMaintainer.Proposal>>(emptyList())
    val proposals: StateFlow<List<TreeMaintainer.Proposal>> = _proposals.asStateFlow()

    private val _microNarrative = MutableStateFlow<Map<String, ReportBuilder.MicroNarrative>>(emptyMap())
    val microNarrative: StateFlow<Map<String, ReportBuilder.MicroNarrative>> = _microNarrative.asStateFlow()

    private val _macroNarrative = MutableStateFlow(ReportBuilder.MacroNarrative())
    val macroNarrative: StateFlow<ReportBuilder.MacroNarrative> = _macroNarrative.asStateFlow()

    init {
        viewModelScope.launch {
            container.treeStore.load()
            _apiKey.value = runCatching { container.settings.apiKey.first() }.getOrDefault("")
            _microDepth.value = runCatching { container.settings.microDepth.first() }.getOrDefault(3)
            _macroDepth.value = runCatching { container.settings.macroDepth.first() }.getOrDefault(2)
            repo.sweepDormancy()
        }
    }

    fun dismissMessage() { _message.value = null }

    private fun run(label: String, block: suspend () -> Unit) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = label
            try {
                block()
            } catch (e: Exception) {
                _message.value = e.message ?: e.toString()
            } finally {
                _busy.value = null
            }
        }
    }

    // ---------- 设置 ----------

    fun setApiKey(v: String) {
        _apiKey.value = v
        viewModelScope.launch { container.settings.setApiKey(v) }
    }

    fun setMicroDepth(v: Int) {
        _microDepth.value = v
        viewModelScope.launch { container.settings.setMicroDepth(v) }
    }

    fun setMacroDepth(v: Int) {
        _macroDepth.value = v
        viewModelScope.launch { container.settings.setMacroDepth(v) }
    }

    // ---------- F7.1 树生成 ----------

    fun generateTree() = run("正在生成考点树…") {
        val generated = container.treeGenerator.generate()
        container.treeStore.save(generated)
        _message.value = "考点树已生成：${generated.liveNodes.size} 个末端节点（${generated.version}）"
    }

    // ---------- F1.2 极简录入 ----------

    fun quickAdd(paper: String, section: Section, spec: String, total: Int?) = run("录入中…") {
        val r = repo.quickAdd(paper, section, spec, total)
        _message.value = buildString {
            append("已录入 ${r.inserted} 条")
            if (r.skippedDuplicates > 0) append("，跳过重复 ${r.skippedDuplicates} 条")
            if (r.unparsed.isNotEmpty()) append("，无法解析：${r.unparsed.joinToString(" ")}")
        }
    }

    // ---------- F1.1 识别 ----------

    fun scan(images: List<AgentClient.Image>, section: Section) = run("识别中…") {
        _scanned.value = container.scanner.scan(images, section)
        _message.value = "识别到 ${_scanned.value.size} 个空"
    }

    fun clearScan() { _scanned.value = emptyList() }

    /**
     * 确认页提交：识别结果只提供 given / answer / 题干，作答仍由错题号决定。
     * autoAnnotate 打开时紧接着跑一遍 F7.2 标注。
     */
    fun confirmScan(
        paper: String,
        section: Section,
        spec: String,
        total: Int?,
        autoAnnotate: Boolean,
    ) = run("录入中…") {
        val questions = _scanned.value
        val details = questions.associate { q ->
            (q.no to q.slot) to RecordRepository.Detail(q.given, q.answer)
        }
        val result = repo.quickAdd(paper, section, spec, total, details = details)
        val stems = questions.associate { q -> (q.no to q.slot) to q.stem }
        val lowConfidence = questions.filter { it.confidence < 0.8 }.size

        if (autoAnnotate) {
            val t = tree.value
            if (t == null) {
                _message.value = "已录入 ${result.inserted} 条；考点树未生成，标注跳过"
            } else {
                var done = 0
                result.records.forEach { r ->
                    val stem = stems[r.src.no to r.src.slot].orEmpty()
                    if (stem.isBlank()) return@forEach
                    runCatching {
                        val out = container.annotator.annotate(
                            Annotator.Input(stem, r.given, r.answer, section.label), t
                        )
                        repo.saveAnnotation(container.annotator.apply(r, out, t))
                        done++
                    }
                }
                _message.value = "已录入 ${result.inserted} 条，标注 $done 条" +
                    if (lowConfidence > 0) "；$lowConfidence 个空识别置信度偏低，留在待完善" else ""
            }
        } else {
            _message.value = "已录入 ${result.inserted} 条"
        }
        _scanned.value = emptyList()
    }

    fun importJson(text: String) = run("导入中…") {
        val list = json.decodeFromString<List<ErrorRecord>>(text)
        _message.value = "已导入 ${repo.importAll(list)} 条"
    }

    fun importCsv(text: String) = run("导入中…") {
        _message.value = "已导入 ${repo.importAll(CsvImporter.parse(text))} 条"
    }

    // ---------- F7.2 标注 ----------

    fun annotate(record: ErrorRecord, stem: String) = run("标注中…") {
        val t = tree.value ?: throw IllegalStateException("考点树尚未生成")
        val out = container.annotator.annotate(
            Annotator.Input(stem, record.given, record.answer, record.src.section.label), t
        )
        val annotated = container.annotator.apply(record, out, t)
        val saved = repo.saveAnnotation(annotated)
        _message.value = if (out.unmatched) {
            "五个候选都不匹配，已记为待归位"
        } else {
            "已标注：${saved.kaodian}（${saved.status.label}）"
        }
    }

    /** 待完善批量标注：题干缺失时用 given + answer 兜底。 */
    fun annotateBatch(targets: List<ErrorRecord>, stems: Map<String, String> = emptyMap()) =
        run("批量标注中…") {
            val t = tree.value ?: throw IllegalStateException("考点树尚未生成")
            var done = 0
            targets.forEach { r ->
                val stem = stems[r.uid] ?: listOfNotNull(r.given, r.answer).joinToString(" ")
                if (stem.isBlank()) return@forEach
                runCatching {
                    val out = container.annotator.annotate(
                        Annotator.Input(stem, r.given, r.answer, r.src.section.label), t
                    )
                    repo.saveAnnotation(container.annotator.apply(r, out, t))
                    done++
                }
            }
            _message.value = "已标注 $done / ${targets.size} 条"
        }

    fun saveEdits(record: ErrorRecord) = run("保存中…") {
        repo.saveAnnotation(record)
    }

    fun fillAnswer(uid: String, answer: String) = run("保存中…") {
        repo.fillAnswer(uid, answer)
    }

    fun delete(uid: String) = run("删除中…") { repo.delete(uid) }

    fun resolveConflict(uid: String) = run("处理中…") {
        repo.setVerified(uid, Verified.MANUAL)
    }

    // ---------- F8 抽检 ----------

    fun runVerification(batch: String? = null) = run("抽检中…") {
        val t = tree.value ?: throw IllegalStateException("考点树尚未生成")
        val pool = records.value
            .filter { it.status.countsInStats }
            .filter { batch == null || it.src.batch == batch }
        val sample = container.verifier.sample(pool)
        var conflicts = 0
        sample.forEach { r ->
            val stem = listOfNotNull(r.given, r.eye, r.answer).joinToString(" ")
            val result = runCatching { container.verifier.verify(r, stem, t) }.getOrNull() ?: return@forEach
            repo.setVerified(r.uid, result)
            if (result == Verified.CONFLICT) conflicts++
        }
        _message.value = "抽检 ${sample.size} 条，冲突 $conflicts 条"
    }

    // ---------- F3 / F4 报告 ----------

    fun generateMicro(stat: Aggregator.KaodianStat) = run("生成小方向报告…") {
        val n = container.reporter.micro(stat)
        _microNarrative.value = _microNarrative.value + (stat.kaodian to n)
    }

    fun generateMacro() = run("生成大方向报告…") {
        _macroNarrative.value = container.reporter.macro(records.value, tree.value, _macroDepth.value)
    }

    // ---------- F6 维护 ----------

    fun refreshProposals() = run("扫描维护项…") {
        val t = tree.value ?: throw IllegalStateException("考点树尚未生成")
        val rs = records.value.filter { it.status.countsInStats }
        val out = mutableListOf<TreeMaintainer.Proposal>()
        out += container.maintainer.rollUpCandidates(rs, t)
        val pairs = container.maintainer.similarPairs(t)
        if (pairs.isNotEmpty()) out += container.maintainer.proposeMerges(pairs)
        container.maintainer.splitCandidates(rs).forEach { path ->
            container.maintainer.proposeSplit(path, rs)?.let { out += it }
        }
        val unmatched = records.value.filter { it.kaodian.isNullOrBlank() && it.eye != null }
        container.maintainer.proposeAdoption(unmatched, t)?.let { out += it }
        _proposals.value = out
        _message.value = "维护项 ${out.size} 条"
    }

    fun applyProposal(p: TreeMaintainer.Proposal) = run("应用中…") {
        val next = container.treeStore.mutate { nodes ->
            container.maintainer.apply(nodes, p, KaodianTree.bumpVersion(tree.value?.version ?: "v0"))
        }
        val remap = repo.remap(next)
        _proposals.value = _proposals.value - p
        _message.value = "树已升到 ${next.version}；重映射 ${remap.moved} 条，待重标 ${remap.orphaned} 条"
    }

    fun dismissProposal(p: TreeMaintainer.Proposal) {
        _proposals.value = _proposals.value - p
    }

    fun sweepDormancy() = run("休眠扫描…") {
        _message.value = "状态变更 ${repo.sweepDormancy()} 条"
    }

    fun remapStale() = run("重映射中…") {
        val t = tree.value ?: return@run
        val r = repo.remap(t)
        _message.value = "重映射 ${r.moved} 条，待重标 ${r.orphaned} 条"
    }

    // ---------- F5 导出 ----------

    private val _exports = MutableStateFlow<List<File>>(emptyList())
    val exports: StateFlow<List<File>> = _exports.asStateFlow()

    fun refreshExports() { _exports.value = repo.backups() }

    fun exportNow() = run("导出中…") {
        val dir = repo.exportNow()
        refreshExports()
        _message.value = "已导出到 ${dir.absolutePath}"
    }

    // ---------- 派生视图 ----------

    fun stats(depth: Int) = Aggregator.kaodianStats(records.value, tree.value, depth)
    fun maturity() = Aggregator.maturity(records.value)
    fun consistency() = Aggregator.consistency(records.value)
    fun rates() = Aggregator.sectionRates(records.value)
    fun termination() = Aggregator.termination(records.value, tree.value, _microDepth.value)
    fun pending(status: RecordStatus) = records.value.filter { it.status == status }
    fun conflicts() = records.value.filter { it.verified == Verified.CONFLICT }
    fun reverseTable() = Exporter.reverseTable(Exporter.validRecords(records.value))
}
