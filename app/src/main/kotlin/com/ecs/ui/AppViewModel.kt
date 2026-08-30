package com.ecs.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecs.Container
import com.ecs.EcsApp
import com.ecs.agent.Analyzer
import com.ecs.agent.PaperScanner
import com.ecs.core.agg.Aggregator
import com.ecs.core.direction.Direction
import com.ecs.core.export.CsvImporter
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.Confidence
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.ModelCatalog
import com.ecs.core.parse.SlotSpec
import com.ecs.core.prompt.PromptSlot
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

    /** 原始数据：一切的来源。 */
    val records: StateFlow<List<ErrorRecord>> =
        repo.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 两级汇总的产物。 */
    val directions: StateFlow<List<Direction>> = container.directionStore.directions

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 这条消息是不是坏消息，决定它标不标红。
     *
     * 「已录入 12 条，分析 12 条」和「已录入 12 条，分析 0 条；失败 12 条：未配置 API Key」
     * 现在长得一模一样，后者会被当成前者一眼扫过去。
     *
     * 每次动作开始时清掉，只有失败路径把它置上——所以不会有残留的红。
     */
    private val _messageBad = MutableStateFlow(false)
    val messageBad: StateFlow<Boolean> = _messageBad.asStateFlow()

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

    private val _scanned = MutableStateFlow<List<PaperScanner.Question>>(emptyList())
    val scanned: StateFlow<List<PaperScanner.Question>> = _scanned.asStateFlow()

    /** 确认页上的逐题标记：(题号, 空序) → 错 / 蒙对。不在表里的就是没标。 */
    private val _marks = MutableStateFlow<Map<Pair<Int, Int>, Confidence>>(emptyMap())
    val marks: StateFlow<Map<Pair<Int, Int>, Confidence>> = _marks.asStateFlow()

    private val _promptOverrides = MutableStateFlow<Map<PromptSlot, String>>(emptyMap())
    val promptOverrides: StateFlow<Map<PromptSlot, String>> = _promptOverrides.asStateFlow()

    private val _updateResult = MutableStateFlow<UpdateChecker.Result?>(null)
    val updateResult: StateFlow<UpdateChecker.Result?> = _updateResult.asStateFlow()

    private val _exports = MutableStateFlow<List<File>>(emptyList())
    val exports: StateFlow<List<File>> = _exports.asStateFlow()

    val installedVersion: String get() = container.updateChecker.installedVersion()

    /** 已装包的签名指纹，和发版说明里那一行对得上就能直接覆盖安装。 */
    val signatureFingerprint: String? by lazy { container.signature.fingerprint() }

    val signaturePinned: Boolean by lazy { container.signature.matchesPinned() }

    init {
        viewModelScope.launch {
            container.directionStore.load()
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
    }

    fun dismissMessage() {
        _message.value = null
        _messageBad.value = false
    }

    private fun run(label: String, block: suspend () -> Unit) {
        // 一次只跑一件事。但「点了没反应」也是一种坏体验，所以要说一声
        _busy.value?.let {
            _message.value = "正在$it　等它结束再点"
            return
        }
        viewModelScope.launch {
            _busy.value = label
            _messageBad.value = false
            try {
                block()
            } catch (e: Exception) {
                _message.value = e.message ?: e.toString()
                _messageBad.value = true
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

    // ---------- 录入 ----------

    /**
     * 收的是 Uri 不是编好的 base64：压缩与编码都在 IO 线程上做。
     * 直接 readBytes + encode 会卡住主线程，编出来的几十兆正文再把上传拖到超时。
     */
    fun scan(uris: List<Uri>) = run("识别中…") {
        val images = withContext(Dispatchers.IO) {
            ImageLoader.loadAll(getApplication<Application>(), uris)
        }
        if (images.isEmpty()) {
            _message.value = "这些图片读不出来，换几张再试"
            return@run
        }
        _scanned.value = container.scanner.scan(images)
        _marks.value = emptyMap()
        _message.value = "识别到 ${_scanned.value.size} 个空，逐条标上错 / 蒙对再提交"
    }

    fun clearScan() {
        _scanned.value = emptyList()
        _marks.value = emptyMap()
    }

    /** 三态循环：没标 → 错 → 蒙对 → 没标。 */
    fun cycleMark(no: Int, slot: Int) {
        val key = no to slot
        val next = when (_marks.value[key]) {
            null -> Confidence.WRONG
            Confidence.WRONG -> Confidence.LUCKY
            Confidence.LUCKY -> null
        }
        _marks.value = if (next == null) _marks.value - key else _marks.value + (key to next)
    }

    fun markAllWrong() {
        _marks.value = _scanned.value.associate { (it.no to it.slot) to Confidence.WRONG }
    }

    fun clearMarks() { _marks.value = emptyMap() }

    /**
     * 提交。只有标过的入库——不标的既不入库也不分析。
     * 题干在这一步落库，之后换模型重跑分析还有原料。
     */
    fun submit(paper: String, autoAnalyze: Boolean) = run("录入中…") {
        val questions = _scanned.value
        val marked = _marks.value
        val marks = questions.filter { (it.no to it.slot) in marked }
            .map { SlotSpec.Mark(it.no, it.slot, marked.getValue(it.no to it.slot)) }
        if (marks.isEmpty()) {
            _message.value = "一条都没标，没有东西可以提交"
            return@run
        }

        val details = questions.associate { q ->
            (q.no to q.slot) to RecordRepository.Detail(q.stem, q.given, q.answer)
        }
        val result = repo.add(paper, marks, details = details)
        val optionsByKey = questions.associate { q -> (q.no to q.slot) to q.options.map { it.content } }
        val stripped = questions.count { it.isChoice && (it.no to it.slot) in marked }

        if (autoAnalyze) {
            _busy.value = "分析中…"
            val r = analyzeEach(result.records) { optionsByKey[it.src.no to it.src.slot].orEmpty() }
            _message.value = buildString {
                append("已录入 ${result.inserted} 条，分析 ${r.done} 条")
                if (stripped > 0) append("；剥离选择题 $stripped 道")
                if (r.unmatched > 0) append("；$unmatchedHint${r.unmatched} 条")
                if (r.failed > 0) append("；失败 ${r.failed} 条：${r.firstError}")
            }
            _messageBad.value = r.failed > 0 || r.done == 0
        } else {
            _message.value = "已录入 ${result.inserted} 条" +
                if (stripped > 0) "；剥离选择题 $stripped 道" else ""
        }
        clearScan()
    }

    fun importJson(text: String) = run("导入中…") {
        _message.value = "已导入 ${repo.importAll(json.decodeFromString<List<ErrorRecord>>(text))} 条"
    }

    fun importCsv(text: String) = run("导入中…") {
        _message.value = "已导入 ${repo.importAll(CsvImporter.parse(text))} 条"
    }

    // ---------- 功能一：分析 ----------

    private data class BatchResult(
        val done: Int = 0,
        val unmatched: Int = 0,
        val failed: Int = 0,
        val firstError: String? = null,
    )

    private val unmatchedHint = "归不进板块 "

    private suspend fun analyzeEach(
        targets: List<ErrorRecord>,
        onProgress: ((Int, Int) -> Unit)? = null,
        optionsOf: (ErrorRecord) -> List<String> = { emptyList() },
    ): BatchResult {
        var done = 0
        var unmatched = 0
        var failed = 0
        var firstError: String? = null
        targets.forEachIndexed { i, r ->
            onProgress?.invoke(i + 1, targets.size)
            val stem = r.stem.orEmpty()
            if (stem.isBlank()) return@forEachIndexed
            runCatching {
                val out = container.analyzer.analyze(
                    Analyzer.Input(stem, r.given, r.answer, optionsOf(r))
                )
                repo.saveAnalysis(container.analyzer.apply(r, out))
                if (out.unmatched) unmatched++ else done++
            }.onFailure {
                // 静默吞掉失败会让人以为「没分析」是设计如此，而不是 Key 没填
                failed++
                if (firstError == null) firstError = it.message ?: it.toString()
            }
        }
        return BatchResult(done, unmatched, failed, firstError)
    }

    /** 单条重跑：题干改对了之后用。 */
    fun analyze(record: ErrorRecord) = run("分析中…") {
        val stem = record.stem?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("这条没有题干，先补上再分析")
        val out = container.analyzer.analyze(Analyzer.Input(stem, record.given, record.answer))
        val saved = repo.saveAnalysis(container.analyzer.apply(record, out))
        _message.value =
            if (out.unmatched) "归不进十九个板块，留在原题页等你处理：${out.formShape}"
            else "已分析：${saved.branch}　${saved.formShape}"
    }

    /** 批量补分析。 */
    fun analyzeAll() = run("分析中…") {
        val pending = records.value.filter { !it.analyzed() && !it.stem.isNullOrBlank() }
        if (pending.isEmpty()) {
            _message.value = "没有待分析的题（没有题干的分析不了，先补题干）"
            return@run
        }
        val r = analyzeEach(pending, onProgress = { i, n -> _busy.value = "分析中　$i/$n" })
        _message.value = buildString {
            append("分析 ${r.done} 条")
            if (r.unmatched > 0) append("；$unmatchedHint${r.unmatched} 条，去掉「只看待分析」能筛出来重跑")
            if (r.failed > 0) append("；失败 ${r.failed} 条：${r.firstError}")
        }
        // 一条都没归进板块，等于复习页还是空的——这是坏消息
        _messageBad.value = r.failed > 0 || r.done == 0
    }

    // ---------- 功能二：递归式复习 ----------

    fun blocks(): List<Aggregator.Block> = Aggregator.blocks(records.value, includeEmpty = true)

    fun directionOf(scope: String): Direction? = container.directionStore.of(scope)

    /** 小方向：输入是这一支下每道题的分析。 */
    fun buildMinor(branch: Skeleton.Branch) = run("汇总 ${branch.path}…") {
        val block = Aggregator.block(records.value, branch)
        val direction = container.directionBuilder.minor(block)
        container.directionStore.save(direction)
        _message.value =
            if (direction.empty) "${branch.path} 没汇总出东西，看看提示词或换个模型"
            else "${branch.path} 汇总出 ${direction.size()} 个节点，由 ${direction.fromCount} 条分析而来"
    }

    /** 把所有有题的板块都汇总一遍。 */
    fun buildAllMinors() = run("汇总中…") {
        val blocks = Aggregator.blocks(records.value)
        if (blocks.isEmpty()) {
            _message.value = "还没有分析好的题，先去原题页分析"
            return@run
        }
        var done = 0
        var failed = 0
        var firstError: String? = null
        blocks.forEachIndexed { i, block ->
            _busy.value = "汇总　${block.path}　${i + 1}/${blocks.size}"
            runCatching {
                container.directionStore.save(container.directionBuilder.minor(block))
                done++
            }.onFailure {
                failed++
                if (firstError == null) firstError = it.message ?: it.toString()
            }
        }
        _message.value = "汇总 $done 个板块" + if (failed > 0) "；失败 $failed 个：$firstError" else ""
        _messageBad.value = failed > 0 || done == 0
    }

    /**
     * 大方向：输入只有各小方向的输出。
     * 这里刻意不传 records——一旦回头读原题，两级就成了各算各的。
     */
    fun buildMajor() = run("生成大方向…") {
        val minors = container.directionStore.all().filter { it.scope != Direction.ALL }
        if (minors.isEmpty()) {
            _message.value = "还没有任何小方向。先在复习页汇总几个板块"
            return@run
        }
        val major = container.directionBuilder.major(minors)
        container.directionStore.save(major)
        _message.value = "大方向已生成：${major.size()} 个节点，读的是 ${major.fromCount} 个板块的输出"
    }

    fun deleteDirection(scope: String) = run("删除…") {
        container.directionStore.delete(scope)
    }

    // ---------- 原题 ----------

    fun saveRecord(record: ErrorRecord) = run("保存中…") { repo.saveAnalysis(record) }

    fun delete(uid: String) = run("删除中…") { repo.delete(uid) }

    /**
     * 原题页顶部的三个数。
     *
     * 「还没跑过分析」和「跑过了但没归进板块」必须分开：混成一个数字，
     * 人会以为分析没跑，而真相是跑了、答案形式也有，只是 branch 落在十九支之外，
     * 于是复习页一片空白而没人知道为什么。
     */
    fun pending(): Int = Aggregator.notAnalyzed(records.value).size

    fun unclassified(): Int = Aggregator.unclassified(records.value).size

    fun analyzedCount(): Int = Aggregator.analyzed(records.value).size

    // ---------- 导出 ----------

    fun refreshExports() { _exports.value = repo.backups() }

    fun exportNow() = run("导出中…") {
        val dir = repo.exportNow()
        refreshExports()
        _message.value = "已导出 ${dir.name}。它在应用私有目录里，卸载会一起删掉——点「分享」送出去才带得走。"
    }

    /**
     * 打包一份导出，把 zip 交给调用方去唤起系统分享。
     *
     * 这一步存在的理由：导出包写在 getExternalFilesDir 下，卸载即删，
     * Android 11 之后文件管理器也不好碰。没有分享出口，等于没有备份。
     */
    fun shareExport(dir: File, onReady: (File) -> Unit) = run("打包中…") {
        val zip = withContext(Dispatchers.IO) { repo.zipFor(dir) }
        onReady(zip)
    }
}
