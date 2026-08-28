package com.ecs.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecs.Container
import com.ecs.EcsApp
import com.ecs.agent.Annotator
import com.ecs.agent.PaperScanner
import com.ecs.core.agg.Aggregator
import com.ecs.core.export.CsvImporter
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.ModelCatalog
import com.ecs.core.model.Section
import com.ecs.core.prompt.PromptSlot
import com.ecs.core.report.ReportBuilder
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.Skeleton
import com.ecs.data.repo.RecordRepository
import com.ecs.data.update.UpdateChecker
import com.ecs.ui.util.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container: Container = (app as EcsApp).container
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val repo: RecordRepository = container.repository

    /** 原题：一切数据的来源。 */
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

    private val _keyLocked = MutableStateFlow(false)
    val keyLocked: StateFlow<Boolean> = _keyLocked.asStateFlow()

    /** 复习层级：末端 → 大类，自下而上。 */
    private val _level = MutableStateFlow(Aggregator.Level.DEFAULT)
    val level: StateFlow<Aggregator.Level> = _level.asStateFlow()

    private val _scanned = MutableStateFlow<List<PaperScanner.Question>>(emptyList())
    val scanned: StateFlow<List<PaperScanner.Question>> = _scanned.asStateFlow()

    private val _narratives = MutableStateFlow<Map<String, ReportBuilder.Narrative>>(emptyMap())
    val narratives: StateFlow<Map<String, ReportBuilder.Narrative>> = _narratives.asStateFlow()

    private val _promptOverrides = MutableStateFlow<Map<PromptSlot, String>>(emptyMap())
    val promptOverrides: StateFlow<Map<PromptSlot, String>> = _promptOverrides.asStateFlow()

    private val _updateResult = MutableStateFlow<UpdateChecker.Result?>(null)
    val updateResult: StateFlow<UpdateChecker.Result?> = _updateResult.asStateFlow()

    private val _exports = MutableStateFlow<List<File>>(emptyList())
    val exports: StateFlow<List<File>> = _exports.asStateFlow()

    val installedVersion: String get() = container.updateChecker.installedVersion()

    init {
        viewModelScope.launch {
            container.treeStore.load()
            refreshSettings()
            _promptOverrides.value = runCatching { container.promptStore.overrides() }.getOrDefault(emptyMap())
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
        _keyLocked.value = runCatching { s.keyLocked.first() }.getOrDefault(false)
        _level.value = Aggregator.Level.ofDepth(runCatching { s.level.first() }.getOrDefault(4))
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

    fun setKeyLocked(locked: Boolean) {
        _keyLocked.value = locked
        viewModelScope.launch { container.settings.setKeyLocked(locked) }
    }

    fun setLevel(level: Aggregator.Level) {
        _level.value = level
        viewModelScope.launch { container.settings.setLevel(level.depth) }
    }

    fun endpointOf(id: String): ApiEndpoint? = _endpoints.value.firstOrNull { it.id == id }

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

    // ---------- 考点树 ----------

    fun generateTree() = run("正在生成考点树…") { buildTree() }

    /**
     * 需要树而没有树时当场生成，而不是跳过标注。
     *
     * 树是标注的前置条件，而标注是原题变成复习内容的唯一途径——
     * 这一环缺了，整条链路会安静地什么都不产出：原题全是待标注、复习页空的、
     * 文本模型一次都没被调用过。以前它只藏在设置页最底下等你自己想起来。
     */
    private suspend fun ensureTree(): KaodianTree =
        container.treeStore.tree.value ?: container.treeStore.load() ?: buildTree()

    private suspend fun buildTree(): KaodianTree {
        val version = container.treeStore.tree.value?.version?.let { KaodianTree.bumpVersion(it) } ?: "v1"
        val result = container.treeGenerator.generate(version = version) { done, total, label ->
            _busy.value = "正在生成考点树　$label　$done/$total"
        }
        container.treeStore.save(result.tree)
        _message.value = buildString {
            append("考点树已生成：${result.tree.liveNodes.size} 个末端节点（${result.tree.version}）")
            if (result.failed.isNotEmpty()) {
                // 部分成功照样能用，但要说清楚哪几支缺了，可以到设置页只重试这几支
                append("；${result.failed.size} 支没出来：")
                append(result.failed.joinToString("、") { it.branch.path })
                append("　原因：${result.failed.first().reason}")
            }
        }
        return result.tree
    }

    /** 只重跑指定的分支，其余节点原样保留。用于补齐上次失败的那几支。 */
    fun regenerateBranches(branches: List<Skeleton.Branch>) = run("正在补生成…") {
        if (branches.isEmpty()) return@run
        val current = ensureTree()
        val result = container.treeGenerator.generate(
            version = current.version,
            only = branches,
        ) { done, total, label -> _busy.value = "正在补生成　$label　$done/$total" }

        val replaced = branches.map { it.path }.toSet()
        val kept = current.nodes.filterNot { node ->
            node.path.split("/").take(2).joinToString("/") in replaced
        }
        val merged = KaodianTree(current.version, kept + result.tree.nodes)
        container.treeStore.save(merged)
        _message.value = "已补上 ${result.tree.nodes.size} 个末端节点，现共 ${merged.liveNodes.size} 个" +
            if (result.failed.isEmpty()) "" else "；仍有 ${result.failed.size} 支失败"
    }

    // ---------- 录入 ----------

    /**
     * 收的是 Uri 不是编好的 base64：压缩与编码都在 IO 线程上做。
     * 原来在 onClick 里直接 readBytes + encode，大图会先卡住主线程，
     * 编出来的几十兆正文再把上传拖到超时。
     */
    fun scan(uris: List<Uri>, section: Section) = run("识别中…") {
        val images = withContext(Dispatchers.IO) {
            ImageLoader.loadAll(getApplication<Application>(), uris)
        }
        if (images.isEmpty()) {
            _message.value = "这些图片读不出来，换几张再试"
            return@run
        }
        _scanned.value = container.scanner.scan(images, section)
        _message.value = "识别到 ${_scanned.value.size} 个空"
    }

    fun clearScan() { _scanned.value = emptyList() }

    /**
     * 确认录入。题干在这一步落库——原题是一切数据的来源，
     * 之后换模型重跑标注也还有原料。
     */
    fun confirmScan(
        paper: String,
        section: Section,
        spec: String,
        autoAnnotate: Boolean,
    ) = run("录入中…") {
        val questions = _scanned.value
        val details = questions.associate { q ->
            (q.no to q.slot) to RecordRepository.Detail(q.stem, q.given, q.answer)
        }
        val result = repo.quickAdd(paper, section, spec, details = details)
        val optionsByKey = questions.associate { q -> (q.no to q.slot) to q.options.map { it.content } }
        val stripped = questions.count { it.isChoice }

        if (autoAnnotate) {
            // 没有树就当场生成——跳过标注等于这批题白录
            val t = ensureTree()
            _busy.value = "标注中…"
            var done = 0
            var refused = 0
            var failed = 0
            var firstError: String? = null
            result.records.forEach { r ->
                val key = r.src.no to r.src.slot
                val stem = r.stem.orEmpty()
                if (stem.isBlank()) return@forEach
                runCatching {
                    val out = container.annotator.annotate(
                        Annotator.Input(stem, r.given, r.answer, section.label, optionsByKey[key].orEmpty()),
                        t,
                    )
                    repo.saveAnnotation(container.annotator.apply(r, out, t))
                    if (out.notFormType) refused++ else done++
                }.onFailure {
                    // 静默吞掉失败会让人以为「没标注」是设计如此，而不是 Key 没填
                    failed++
                    if (firstError == null) firstError = it.message ?: it.toString()
                }
            }
            _message.value = buildString {
                append("已录入 ${result.inserted} 条，标注 $done 条")
                if (stripped > 0) append("；剥离选择题 $stripped 道")
                if (refused > 0) append("；$refused 道推不出形态，留在原题页等你手动处理")
                if (failed > 0) append("；失败 $failed 条：$firstError")
            }
        } else {
            _message.value = "已录入 ${result.inserted} 条" +
                if (stripped > 0) "；剥离选择题 $stripped 道" else ""
        }
        _scanned.value = emptyList()
    }

    fun importJson(text: String) = run("导入中…") {
        _message.value = "已导入 ${repo.importAll(json.decodeFromString<List<ErrorRecord>>(text))} 条"
    }

    fun importCsv(text: String) = run("导入中…") {
        _message.value = "已导入 ${repo.importAll(CsvImporter.parse(text))} 条"
    }

    // ---------- 原题 ----------

    fun saveRecord(record: ErrorRecord) = run("保存中…") {
        repo.saveAnnotation(record)
    }

    fun delete(uid: String) = run("删除中…") { repo.delete(uid) }

    /** 手动重跑标注：题干改对了之后用。 */
    fun annotate(record: ErrorRecord) = run("标注中…") {
        val t = ensureTree()
        val stem = record.stem?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("这条没有题干，先补上再标注")
        val out = container.annotator.annotate(
            Annotator.Input(stem, record.given, record.answer, record.src.section.label), t,
        )
        val saved = repo.saveAnnotation(container.annotator.apply(record, out, t))
        _message.value = when {
            out.notFormType -> "剥离后推不出形态，这题不属于填空类考点"
            out.unmatched -> "五个候选都不匹配"
            else -> "已标注：${saved.kaodian}"
        }
    }

    /**
     * 批量补标注。之前只能一条条点开对话框重标，
     * 已经录进去的那批（比如树还没生成时录的）没有出路。
     */
    fun annotateAll() = run("标注中…") {
        val pending = records.value.filter {
            it.kaodian.isNullOrBlank() && !it.stem.isNullOrBlank()
        }
        if (pending.isEmpty()) {
            _message.value = "没有待标注的题（没有题干的标不了，先补题干）"
            return@run
        }
        val t = ensureTree()
        var done = 0
        var refused = 0
        var failed = 0
        var firstError: String? = null
        pending.forEachIndexed { i, r ->
            _busy.value = "标注中　${i + 1}/${pending.size}"
            runCatching {
                val out = container.annotator.annotate(
                    Annotator.Input(r.stem.orEmpty(), r.given, r.answer, r.src.section.label), t,
                )
                repo.saveAnnotation(container.annotator.apply(r, out, t))
                if (out.notFormType) refused++ else done++
            }.onFailure {
                failed++
                if (firstError == null) firstError = it.message ?: it.toString()
            }
        }
        _message.value = buildString {
            append("标注 $done 条")
            if (refused > 0) append("；$refused 道推不出形态")
            if (failed > 0) append("；失败 $failed 条：$firstError")
        }
    }

    // ---------- 复习 ----------

    fun groups(): List<Aggregator.Group> =
        Aggregator.groups(records.value, tree.value, _level.value)

    fun narrate(group: Aggregator.Group) = run("生成判断…") {
        _narratives.value = _narratives.value + (group.kaodian to container.reporter.narrate(group))
    }

    // ---------- 导出 ----------

    fun refreshExports() { _exports.value = repo.backups() }

    fun exportNow() = run("导出中…") {
        val dir = repo.exportNow()
        refreshExports()
        _message.value = "已导出到 ${dir.absolutePath}"
    }

    /** 还没标注的条数，原题页顶部用它提示还有多少要处理。 */
    fun unannotated(): Int = records.value.count { it.kaodian.isNullOrBlank() }
}
