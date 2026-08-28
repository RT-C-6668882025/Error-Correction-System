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
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.ModelCatalog
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Section
import com.ecs.core.model.Verified
import com.ecs.core.prompt.PromptSlot
import com.ecs.core.report.ReportBuilder
import com.ecs.core.tree.KaodianTree
import com.ecs.data.repo.RecordRepository
import com.ecs.data.update.UpdateChecker
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

    private val _endpoints = MutableStateFlow(BuiltInEndpoints.ALL)
    val endpoints: StateFlow<List<ApiEndpoint>> = _endpoints.asStateFlow()

    private val _textModel = MutableStateFlow(ModelCatalog.DEFAULT_TEXT)
    val textModel: StateFlow<String> = _textModel.asStateFlow()

    private val _visionModel = MutableStateFlow(ModelCatalog.DEFAULT_VISION)
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()

    private val _textEndpoint = MutableStateFlow(ModelCatalog.DEFAULT_TEXT_ENDPOINT)
    val textEndpoint: StateFlow<String> = _textEndpoint.asStateFlow()

    private val _visionEndpoint = MutableStateFlow(ModelCatalog.DEFAULT_VISION_ENDPOINT)
    val visionEndpoint: StateFlow<String> = _visionEndpoint.asStateFlow()

    private val _microDepth = MutableStateFlow(3)
    val microDepth: StateFlow<Int> = _microDepth.asStateFlow()

    private val _macroDepth = MutableStateFlow(2)
    val macroDepth: StateFlow<Int> = _macroDepth.asStateFlow()

    private val _promptOverrides = MutableStateFlow<Map<PromptSlot, String>>(emptyMap())
    val promptOverrides: StateFlow<Map<PromptSlot, String>> = _promptOverrides.asStateFlow()

    private val _updateResult = MutableStateFlow<UpdateChecker.Result?>(null)
    val updateResult: StateFlow<UpdateChecker.Result?> = _updateResult.asStateFlow()

    val installedVersion: String get() = container.updateChecker.installedVersion()

    init {
        viewModelScope.launch {
            container.treeStore.load()
            refreshSettings()
            _promptOverrides.value = runCatching { container.promptStore.overrides() }.getOrDefault(emptyMap())
            repo.sweepDormancy()
        }
    }

    private suspend fun refreshSettings() {
        val s = container.settings
        _endpoints.value = runCatching { s.endpoints.first() }.getOrDefault(BuiltInEndpoints.ALL)
        _textModel.value = runCatching { s.textModel.first() }.getOrDefault(ModelCatalog.DEFAULT_TEXT)
        _visionModel.value = runCatching { s.visionModel.first() }.getOrDefault(ModelCatalog.DEFAULT_VISION)
        _textEndpoint.value =
            runCatching { s.textEndpoint.first() }.getOrDefault(ModelCatalog.DEFAULT_TEXT_ENDPOINT)
        _visionEndpoint.value =
            runCatching { s.visionEndpoint.first() }.getOrDefault(ModelCatalog.DEFAULT_VISION_ENDPOINT)
        _microDepth.value = runCatching { s.microDepth.first() }.getOrDefault(3)
        _macroDepth.value = runCatching { s.macroDepth.first() }.getOrDefault(2)
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

    // ---------- 端点与模型 ----------

    fun saveEndpoint(endpoint: ApiEndpoint) = run("保存端点…") {
        container.settings.upsertEndpoint(endpoint)
        refreshSettings()
        _message.value = "已保存 ${endpoint.name}　请求地址 ${endpoint.url}"
    }

    fun deleteEndpoint(id: String) = run("删除端点…") {
        container.settings.deleteEndpoint(id)
        refreshSettings()
    }

    /** 中转站地址、协议、Key 三者错任一个，报错都长得一样，所以给一个能自查的按钮。 */
    fun testEndpoint(endpoint: ApiEndpoint, modelId: String) = run("测试连接…") {
        _message.value = container.client.ping(endpoint, modelId)
    }

    fun setTextModel(v: String) {
        _textModel.value = v
        viewModelScope.launch { container.settings.setTextModel(v) }
    }

    fun setVisionModel(v: String) {
        _visionModel.value = v
        viewModelScope.launch { container.settings.setVisionModel(v) }
    }

    fun setTextEndpoint(v: String) {
        _textEndpoint.value = v
        viewModelScope.launch { container.settings.setTextEndpoint(v) }
    }

    fun setVisionEndpoint(v: String) {
        _visionEndpoint.value = v
        viewModelScope.launch { container.settings.setVisionEndpoint(v) }
    }

    fun setMicroDepth(v: Int) {
        _microDepth.value = v
        viewModelScope.launch { container.settings.setMicroDepth(v) }
    }

    fun setMacroDepth(v: Int) {
        _macroDepth.value = v
        viewModelScope.launch { container.settings.setMacroDepth(v) }
    }

    fun endpointOf(id: String): ApiEndpoint? = _endpoints.value.firstOrNull { it.id == id }

    /** 选中的端点缺 Key 或缺地址——设置页据此给出准确提示。 */
    fun endpointReady(id: String): Boolean = endpointOf(id)?.configured == true

    // ---------- 提示词 ----------

    fun savePrompt(slot: PromptSlot, body: String) = run("保存提示词…") {
        container.promptStore.save(slot, body)
        _promptOverrides.value = container.promptStore.overrides()
    }

    fun resetPrompt(slot: PromptSlot) = run("还原默认…") {
        container.promptStore.reset(slot)
        _promptOverrides.value = container.promptStore.overrides()
    }

    fun resetAllPrompts() = run("全部还原…") {
        container.promptStore.resetAll()
        _promptOverrides.value = container.promptStore.overrides()
        _message.value = "所有提示词已还原为默认"
    }

    // ---------- 检查更新 ----------

    fun checkUpdate() = run("检查更新…") {
        _updateResult.value = container.updateChecker.check()
    }

    // ---------- F7.1 树生成 ----------

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
        val optionsByKey = questions.associate { q ->
            (q.no to q.slot) to q.options.map { it.content }
        }
        val lowConfidence = questions.filter { it.confidence < 0.8 }.size
        val stripped = questions.count { it.isChoice }

        if (autoAnnotate) {
            val t = tree.value
            if (t == null) {
                _message.value = "已录入 ${result.inserted} 条；考点树未生成，标注跳过"
            } else {
                var done = 0
                var refused = 0
                result.records.forEach { r ->
                    val key = r.src.no to r.src.slot
                    val stem = stems[key].orEmpty()
                    if (stem.isBlank()) return@forEach
                    runCatching {
                        val out = container.annotator.annotate(
                            Annotator.Input(
                                stem, r.given, r.answer, section.label, optionsByKey[key].orEmpty()
                            ),
                            t,
                        )
                        repo.saveAnnotation(container.annotator.apply(r, out, t))
                        if (out.notFormType) refused++ else done++
                    }
                }
                _message.value = buildString {
                    append("已录入 ${result.inserted} 条，标注 $done 条")
                    if (stripped > 0) append("；剥离选择题 $stripped 道")
                    if (refused > 0) append("；$refused 道推不出形态，已挡在人工队列")
                    if (lowConfidence > 0) append("；$lowConfidence 个空识别置信度偏低，留在待完善")
                }
            }
        } else {
            _message.value = "已录入 ${result.inserted} 条" +
                if (stripped > 0) "；剥离选择题 $stripped 道" else ""
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

    fun annotate(record: ErrorRecord, stem: String, options: List<String> = emptyList()) =
        run("标注中…") {
            val t = tree.value ?: throw IllegalStateException("考点树尚未生成")
            val out = container.annotator.annotate(
                Annotator.Input(stem, record.given, record.answer, record.src.section.label, options), t
            )
            val saved = repo.saveAnnotation(container.annotator.apply(record, out, t))
            _message.value = when {
                out.notFormType -> "剥离后推不出形态，这题不属于填空类考点，已记为不完整进人工队列"
                out.unmatched -> "五个候选都不匹配，已记为待归位"
                else -> "已标注：${saved.kaodian}（${saved.status.label}）"
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

    fun maturity() = Aggregator.maturity(records.value)
    fun consistency() = Aggregator.consistency(records.value)
    fun rates() = Aggregator.sectionRates(records.value)
    fun termination() = Aggregator.termination(records.value, tree.value, _microDepth.value)
    fun pending(status: RecordStatus) = records.value.filter { it.status == status }
    fun conflicts() = records.value.filter { it.verified == Verified.CONFLICT }
    fun reverseTable() = Exporter.reverseTable(Exporter.validRecords(records.value))
}
