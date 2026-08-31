package com.ecs.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecs.Container
import com.ecs.EcsApp
import com.ecs.agent.Analyzer
import com.ecs.agent.Batch
import com.ecs.agent.PaperScanner
import com.ecs.core.agg.Aggregator
import com.ecs.core.direction.Direction
import com.ecs.core.dup.Duplicates
import com.ecs.core.export.CsvImporter
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.Confidence
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.ModelCatalog
import com.ecs.core.model.ModelDiscovery
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

    /** 每个端点的模型清单：没拉过、拉取中、拉到了、拉失败（附退回的静态清单）。 */
    sealed interface ModelsState {
        data object Idle : ModelsState
        data object Loading : ModelsState
        data class Loaded(val models: List<ModelDiscovery.RemoteModel>, val cached: Boolean) : ModelsState
        data class Failed(val message: String, val fallback: List<ModelDiscovery.RemoteModel>) : ModelsState
    }

    private val _models = MutableStateFlow<Map<String, ModelsState>>(emptyMap())
    val models: StateFlow<Map<String, ModelsState>> = _models.asStateFlow()

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
        primeModelCache()
    }

    /** 上次拉到的清单先摆出来，离线打开设置页也有候选可选；联网重拉会覆盖它。 */
    private suspend fun primeModelCache() {
        val cache = runCatching { container.settings.modelCache.first() }.getOrDefault(emptyMap())
        val primed = cache.filterValues { it.isNotEmpty() }.mapValues { (id, ids) ->
            ModelsState.Loaded(offlineModelsOf(id, ids), cached = true)
        }
        // 本次会话已经真的拉过的不覆盖
        _models.value = primed + _models.value
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

    /**
     * 选一个厂商、填一个 Key，剩下的自己配好。
     *
     * 以前要用户先懂「端点」「协议」「模型 ID」三件事才能开始用；现在填完 Key
     * 就去问厂商有哪些模型，视觉档与文本档各自动挑一个，识别立刻能跑。
     * 拉不到清单也不清掉任何已有配置——照旧能用手填的那套。
     */
    fun useProvider(endpoint: ApiEndpoint, force: Boolean = false) = run("配置 ${endpoint.name}…") {
        container.settings.upsertEndpoint(endpoint)
        refreshSettings()

        val saved = endpointOf(endpoint.id) ?: endpoint
        val list = loadModels(saved)
        if (list.isEmpty()) {
            _message.value = "已保存 ${saved.name} 的 Key，但没拉到模型清单，" +
                "可以在设置页「高级」里手填模型 ID"
            _messageBad.value = true
            return@run
        }

        val vision = ModelDiscovery.pick(list, vision = true)
        val text = ModelDiscovery.pick(list, vision = false)

        if (vision != null && adopts(_visionEndpoint.value, saved.id, force)) {
            setVisionEndpoint(saved.id)
            setVisionModel(vision.id)
        }
        if (text != null && adopts(_textEndpoint.value, saved.id, force)) {
            setTextEndpoint(saved.id)
            setTextModel(text.id)
        }
        _message.value = buildString {
            append("${saved.name} 已就绪：")
            append("识别用 ${_visionModel.value}　判断用 ${_textModel.value}")
            // 纯文本厂商（DeepSeek、MiniMax）没有视觉模型，识别档只能留在别处
            if (vision == null) append("　（这个厂商没有视觉模型，识别没换）")
            if (!force) append("　已经配好的那一档没动，想改用下面的「只设识别档 / 只设判断档」")
        }
        _messageBad.value = false
    }

    /**
     * 只把某一档指到这个厂商，另一档一个字都不动。
     *
     * 「视觉走智谱、判断走 Anthropic」是完全正当的搭配——识别天天跑要便宜，
     * 判断决定分析质量要强，本来就很难是同一家。所以两档必须能分开配。
     */
    fun useProviderFor(endpoint: ApiEndpoint, vision: Boolean) = run("配置 ${endpoint.name}…") {
        container.settings.upsertEndpoint(endpoint)
        refreshSettings()

        val saved = endpointOf(endpoint.id) ?: endpoint
        val list = loadModels(saved)
        val slot = if (vision) "识别" else "判断"
        val picked = ModelDiscovery.pick(list, vision = vision)
        if (picked == null) {
            _message.value = if (vision) {
                "${saved.name} 这边没找到能看图的模型，识别档没动。" +
                    "可以在「高级 → 视觉模型」里直接手填模型 ID"
            } else {
                "${saved.name} 没拉到可用模型，判断档没动"
            }
            _messageBad.value = true
            return@run
        }
        if (vision) {
            setVisionEndpoint(saved.id)
            setVisionModel(picked.id)
        } else {
            setTextEndpoint(saved.id)
            setTextModel(picked.id)
        }
        _message.value = "$slot 档已改为 ${saved.name}　${picked.id}（另一档没动）"
        _messageBad.value = false
    }

    /**
     * 要不要把这一档改指到刚配好的厂商。
     *
     * 空着或还没填 Key 的档位当然要接管——那正是「填一个 Key 就能用」。但已经配好
     * 并且在用另一个厂商的档位不能动：有人就是视觉走智谱、判断走 Anthropic，
     * 在录入页补填一个 Key 不该把这种搭配悄悄拆掉。设置页里点「自动配置」是明确
     * 要求，那时 force。
     */
    private fun adopts(currentId: String, newId: String, force: Boolean): Boolean =
        force || currentId == newId || endpointOf(currentId)?.configured != true

    /** 手动重拉某个端点的清单。厂商上新之后不用等发版，也不用重装。 */
    fun fetchModels(endpointId: String) = run("拉取模型清单…") {
        val ep = endpointOf(endpointId) ?: return@run
        val list = loadModels(ep)
        _message.value =
            if (list.isEmpty()) "${ep.name} 没拉到清单，先看下地址和 Key"
            else "${ep.name} 有 ${list.size} 个可用模型"
        _messageBad.value = list.isEmpty()
    }

    /**
     * 拉清单并落到状态里。失败不抛：这一步失败只意味着少了个便利，
     * 静态清单和手填 ID 都还在，不该把上层的动作整个中断掉。
     */
    private suspend fun loadModels(endpoint: ApiEndpoint): List<ModelDiscovery.RemoteModel> {
        _models.value += endpoint.id to ModelsState.Loading
        return runCatching { container.client.listModels(endpoint) }
            .onSuccess { fetched ->
                // 缓存只记厂商真回过的，内置候选每次现补
                container.settings.saveModelCache(endpoint.id, fetched.map { it.id })
            }
            .map { fetched ->
                // 厂商清单不一定是全集（智谱的 /models 就不回 glm-4v 系列），
                // 拉到了也要把内置候选补上，否则拉一次清单反而把视觉档整个抹掉
                val merged = ModelDiscovery.merge(fetched, builtInModels(endpoint.id))
                _models.value += endpoint.id to ModelsState.Loaded(merged, cached = false)
                merged
            }
            .onFailure { e ->
                _models.value += endpoint.id to ModelsState.Failed(
                    message = e.message ?: "拉取失败",
                    fallback = offlineModels(endpoint.id),
                )
            }
            .getOrDefault(emptyList())
    }

    /** 拉不到时的候选：上次拉到的（存在本地）优先，没有就用内置清单。 */
    private suspend fun offlineModels(endpointId: String): List<ModelDiscovery.RemoteModel> {
        val cached = runCatching { container.settings.modelCache.first()[endpointId] }.getOrNull()
        if (cached.isNullOrEmpty()) return builtInModels(endpointId)
        return ModelDiscovery.merge(offlineModelsOf(endpointId, cached), builtInModels(endpointId))
    }

    private fun offlineModelsOf(endpointId: String, ids: List<String>): List<ModelDiscovery.RemoteModel> =
        ids.map { id ->
            val known = ModelCatalog.byId(id)
            ModelDiscovery.RemoteModel(
                id = id,
                label = known?.label ?: id,
                vision = ModelDiscovery.isVision(id),
                note = known?.note.orEmpty(),
            )
        }.ifEmpty { builtInModels(endpointId) }

    private fun builtInModels(endpointId: String): List<ModelDiscovery.RemoteModel> =
        ModelCatalog.MODELS.filter { it.endpointId == endpointId }.map {
            ModelDiscovery.RemoteModel(it.id, it.label, it.vision, it.note)
        }

    /** 端点当前可选的模型：拉到的、缓存的、内置的，按这个优先级。 */
    fun modelsFor(endpointId: String): List<ModelDiscovery.RemoteModel> =
        when (val state = _models.value[endpointId]) {
            is ModelsState.Loaded -> state.models
            is ModelsState.Failed -> state.fallback
            else -> builtInModels(endpointId)
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
            val r = analyzeEach(
                targets = result.records,
                onProgress = { i, n -> _busy.value = "分析中　$i/$n" },
                optionsOf = { optionsByKey[it.src.no to it.src.slot].orEmpty() },
            )
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

    /**
     * 每道题一次调用，彼此不共用上下文，所以可以同时发出去。
     * 串行跑十道题就是十次网络等待相加——这是「分析很慢」的全部来源。
     */
    private suspend fun analyzeEach(
        targets: List<ErrorRecord>,
        onProgress: ((Int, Int) -> Unit)? = null,
        optionsOf: (ErrorRecord) -> List<String> = { emptyList() },
    ): BatchResult {
        val runnable = targets.filter { !it.stem.isNullOrBlank() }
        val results = Batch.map(
            items = runnable,
            concurrency = Analyzer.CONCURRENCY,
            onProgress = { finished, total -> onProgress?.invoke(finished, total) },
        ) { r ->
            val out = container.analyzer.analyze(
                Analyzer.Input(r.stem.orEmpty(), r.given, r.answer, optionsOf(r))
            )
            repo.saveAnalysis(container.analyzer.apply(r, out))
            out
        }

        var done = 0
        var unmatched = 0
        var failed = 0
        var firstError: String? = null
        results.forEach { result ->
            result.fold(
                onSuccess = { if (it.unmatched) unmatched++ else done++ },
                // 静默吞掉失败会让人以为「没分析」是设计如此，而不是 Key 没填
                onFailure = {
                    failed++
                    if (firstError == null) firstError = it.message ?: it.toString()
                },
            )
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

    /**
     * 分析选中的那些。
     *
     * 原来只有一个「分析全部待分析」，什么都不能挑：想只跑刚录的那几条、
     * 或者只重跑归错板块的那几条，都只能一条条点开。现在由选择决定跑哪些，
     * 一条也是它，三十条也是它——批量与单条走同一条路径。
     */
    fun analyzeSelected() = run("分析中…") {
        val targets = selectedRecords()
        if (targets.isEmpty()) {
            _message.value = "先选中要分析的题（长按一条进入多选）"
            _messageBad.value = true
            return@run
        }
        val runnable = targets.filter { !it.stem.isNullOrBlank() }
        if (runnable.isEmpty()) {
            _message.value = "选中的都没有题干，分析不了——先点开补题干"
            _messageBad.value = true
            return@run
        }
        val r = analyzeEach(runnable, onProgress = { i, n -> _busy.value = "分析中　$i/$n" })
        _selected.value = emptySet()
        _message.value = buildString {
            append("分析 ${r.done} 条")
            if (targets.size > runnable.size) append("；跳过没题干的 ${targets.size - runnable.size} 条")
            if (r.unmatched > 0) append("；$unmatchedHint${r.unmatched} 条")
            if (r.failed > 0) append("；失败 ${r.failed} 条：${r.firstError}")
        }
        // 一条都没归进板块，等于复习页还是空的——这是坏消息
        _messageBad.value = r.failed > 0 || r.done == 0
    }

    // ---------- 原题管理：选中、判重、删除 ----------

    private val _selected = MutableStateFlow<Set<String>>(emptySet())

    /** 选中的 uid。空集合 = 不在多选态，页面照常显示。 */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun toggleSelect(uid: String) {
        _selected.value = if (uid in _selected.value) _selected.value - uid else _selected.value + uid
    }

    /** 全选当前筛出来的这些——选择永远只作用在看得见的那批上。 */
    fun selectAll(uids: List<String>) { _selected.value = _selected.value + uids }

    fun clearSelection() { _selected.value = emptySet() }

    private fun selectedRecords(): List<ErrorRecord> =
        records.value.filter { it.uid in _selected.value }

    /**
     * 选中重复题里可以删掉的那些，每一簇留一条。
     *
     * 只选中、不直接删：删哪些得让人自己看过再点，这也是为什么留下的那条
     * 会在页面上标出来。
     */
    fun selectDuplicates() {
        val groups = Duplicates.groups(records.value)
        if (groups.isEmpty()) {
            _message.value = "没有找到重复的题（同题干、同空序、同答案才算）"
            _messageBad.value = false
            return
        }
        val drop = groups.flatMap { g -> g.drop.map { it.uid } }
        _selected.value = drop.toSet()
        _message.value = "${groups.size} 组重复，选中了可以删的 ${drop.size} 条" +
            "；每组留下的那条是已分析优先、其次有答案的、再其次录得早的"
        _messageBad.value = false
    }

    /** 重复题里多出来的条数，给页面上的入口显示用。 */
    fun duplicateCount(): Int = Duplicates.redundant(records.value).size

    /** 删掉选中的。不可撤销，所以调用方必须先确认过。 */
    fun deleteSelected() = run("删除中…") {
        val uids = _selected.value.toList()
        if (uids.isEmpty()) {
            _message.value = "没有选中任何题"
            _messageBad.value = true
            return@run
        }
        val n = repo.deleteAll(uids)
        _selected.value = emptySet()
        _message.value = "已删除 $n 条（删之前自动存了一份备份，在 设置 → 导出与备份 里）"
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
