package com.ecs.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.ModelDiscovery
import com.ecs.core.model.Protocol
import com.ecs.core.model.activeEndpoints
import com.ecs.core.tree.Skeleton
import com.ecs.core.tree.TopLevel
import com.ecs.data.update.SignatureInfo
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.ApiKeyField
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar
import com.ecs.ui.component.SectionCard
import com.ecs.ui.nav.Routes

@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsState()
    val directions by vm.directions.collectAsState()
    val endpoints by vm.endpoints.collectAsState()
    val textModel by vm.textModel.collectAsState()
    val visionModel by vm.visionModel.collectAsState()
    val textEndpoint by vm.textEndpoint.collectAsState()
    val visionEndpoint by vm.visionEndpoint.collectAsState()
    val update by vm.updateResult.collectAsState()

    var editing by remember { mutableStateOf<ApiEndpoint?>(null) }
    var advanced by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        // 首次使用只需要走完这一张卡：选厂商 → 填 Key → 自动拉模型 → 自动配好两档
        ProviderCard(vm)

        SectionCard("提示词") {
            Text(
                "分析、小方向、大方向三级各一段，判断得准不准一半取决于它们。" +
                    "全部可看、可改、可还原；输出结构那部分锁定，改不坏解析。" +
                    "顶栏那个滑块图标在每一页都能直接进去。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(
                onClick = { nav.navigate(Routes.PROMPTS) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("查看与编辑提示词") }
        }

        SectionCard(if (advanced) "高级" else "高级（手填模型 ID、自定义端点）") {
            Text(
                "上面那张卡覆盖绝大多数情况。中转站、本地 Ollama、厂商没实现 /models 时，" +
                    "在这里手动指定端点与模型 ID——原来的配置方式一个没删。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(
                onClick = { advanced = !advanced },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(if (advanced) "收起" else "展开") }
        }

        if (advanced) {
            SectionCard("视觉模型（识别用）") {
                Text(
                    "拍照识别题号、题干、括号提示词、选项版式走这个模型。Flash 系列免费，" +
                        "录入频率是系统生命线，识别不该按次心疼。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SlotConfig(
                    endpoints = endpoints,
                    selectedEndpoint = visionEndpoint,
                    modelId = visionModel,
                    onEndpoint = vm::setVisionEndpoint,
                    onModel = vm::setVisionModel,
                    ready = vm.endpointReady(visionEndpoint),
                    onTest = { ep -> vm.testEndpoint(ep, visionModel) },
                    endpointOf = vm::endpointOf,
                    suggestions = vm.modelsFor(visionEndpoint).filter { it.vision },
                )
            }

            SectionCard("文本模型（判断用）") {
                Text(
                    "分析、小方向、大方向三级都走这个模型。分析质量直接决定复习页对不对，" +
                        "这一档不建议为省钱降配。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SlotConfig(
                    endpoints = endpoints,
                    selectedEndpoint = textEndpoint,
                    modelId = textModel,
                    onEndpoint = vm::setTextEndpoint,
                    onModel = vm::setTextModel,
                    ready = vm.endpointReady(textEndpoint),
                    onTest = { ep -> vm.testEndpoint(ep, textModel) },
                    endpointOf = vm::endpointOf,
                    suggestions = vm.modelsFor(textEndpoint),
                )
            }

            SectionCard("API 端点") {
                Text(
                    "任何说 OpenAI 兼容或 Anthropic Messages 协议的服务都能接：" +
                        "GLM、Kimi、MiniMax、海内外中转站。地址随便粘，会自动补全成完整请求路径。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                endpoints.forEach { ep ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ep.name, style = MaterialTheme.typography.titleSmall)
                        Badge(ep.protocol.label, MaterialTheme.colorScheme.primary)
                        if (ep.builtIn) Badge("预置", MaterialTheme.colorScheme.secondary)
                        if (!ep.configured) Badge("缺 Key", MaterialTheme.colorScheme.error)
                    }
                    Text(
                        ep.url.ifBlank { "未填地址" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (ep.note.isNotBlank()) {
                        Text(ep.note, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    ApiKeyField(endpoint = ep, onSave = vm::saveEndpoint)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editing = ep }) { Text("改地址 / 协议") }
                        if (!ep.builtIn) {
                            TextButton(onClick = { vm.deleteEndpoint(ep.id) }) { Text("删除") }
                        }
                    }
                }
                Button(
                    onClick = {
                        editing = ApiEndpoint(
                            id = "custom_${System.currentTimeMillis()}",
                            name = "",
                            baseUrl = "",
                            protocol = Protocol.OPENAI,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("新增端点") }
            }
        }

        SectionCard("板块与方向") {
            // 板块是写死的十九支；方向是这些板块下的分析汇总出来的
            val minors = directions.filterNot { it.scope == com.ecs.core.direction.Direction.ALL }
            val major = directions.firstOrNull { it.scope == com.ecs.core.direction.Direction.ALL }
            Text(
                "板块固定 ${Skeleton.BRANCHES.size} 支：词法 ${Skeleton.branchesOf(TopLevel.LEXICAL).size}、" +
                    "句法 ${Skeleton.branchesOf(TopLevel.SYNTAX).size}、" +
                    "语法 ${Skeleton.branchesOf(TopLevel.GRAMMAR).size}。分析时模型只能从这些里选一个。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "已汇总小方向 ${minors.size} 个" + (major?.let { "　大方向读了 ${it.fromCount} 个板块的输出" } ?: "　大方向未生成"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "汇总在复习页做：一个板块一次调用，输入是那一支下每道题的分析。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (major != null) {
                OutlinedButton(
                    onClick = { vm.deleteDirection(com.ecs.core.direction.Direction.ALL) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("删掉大方向重来") }
            }
        }

        SectionCard("导出与备份") {
            Text(
                "导出包是这个库唯一带得走的形态：原始数据、每题的分析、已汇总的方向都在里面。" +
                    "它存在应用私有目录，卸载会一起删掉——换手机或重装前先分享出去。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(
                onClick = { nav.navigate(Routes.EXPORT) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("导出 / 分享备份") }
        }

        SectionCard("检查更新") {
            Text(
                "当前版本 ${vm.installedVersion}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            // 判据是签名指纹本身，不是版本号：v4.0.1 及更早每一版都是构建机现场随机
            // 生成的 debug 密钥，指纹互不相同，Android 拒绝跨签名覆盖安装
            Text(
                "签名指纹 ${SignatureInfo.short(vm.signatureFingerprint)}" +
                    if (vm.signaturePinned) "　与 v4.1.0 起的固定证书一致" else "　不是固定证书",
                style = MaterialTheme.typography.labelSmall,
                color = if (vm.signaturePinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (vm.signaturePinned) {
                Text(
                    "以后每一版都用同一张证书签，直接覆盖安装即可，数据不丢。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Text(
                    "注意：你现在这一版（${vm.installedVersion}）的签名不是固定证书，" +
                        "新包装不上去，必须先卸载再装。卸载会清空本地数据，先到上面导出并分享出去。" +
                        "从 v4.1.0 起签名固定，装上之后就能一直覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(onClick = { vm.checkUpdate() }, modifier = Modifier.padding(top = 8.dp)) {
                Text("检查更新")
            }
            update?.let { result ->
                when {
                    result.error != null -> Text(
                        result.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    result.hasUpdate && result.release != null -> {
                        val release = result.release!!
                        Text(
                            "有新版本 ${release.tagName}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (release.notes.isNotBlank()) {
                            Text(
                                release.notes.lines().take(12).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        val link = release.apkUrl ?: release.pageUrl
                        if (link != null) {
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text(if (release.apkUrl != null) "去下载 APK" else "打开发布页") }
                        }
                    }

                    else -> Text(
                        "已是最新（${result.release?.tagName ?: result.installed}）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        SectionCard("联网范围") {
            Text(
                "识别、分析、汇总小方向与大方向需要联网。原题的浏览与编辑、已汇总的方向、" +
                    "导出与备份全部离线可用。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        MessageBar(vm)
    }

    editing?.let { ep ->
        EndpointDialog(
            endpoint = ep,
            onDismiss = { editing = null },
            onSave = { vm.saveEndpoint(it); editing = null },
        )
    }
}

/**
 * 首次使用的全部：选厂商 → 填一个 Key → 自动拉这个 Key 能用的模型 → 两档各自动挑一个。
 *
 * 原来这一步要用户自己懂「端点」「协议」「模型 ID」，模型清单还写死在代码里，
 * 厂商上新或改名就只能等发版。现在清单来自厂商本身，挑选也有默认答案，
 * 想自己指定的人再去下面的「高级」。
 */
@Composable
private fun ProviderCard(vm: AppViewModel) {
    val endpoints by vm.endpoints.collectAsState()
    val states by vm.models.collectAsState()
    val visionEndpoint by vm.visionEndpoint.collectAsState()
    val textEndpoint by vm.textEndpoint.collectAsState()
    val visionModel by vm.visionModel.collectAsState()
    val textModel by vm.textModel.collectAsState()
    val locked by vm.keyLocked.collectAsState()

    // 默认落在识别那一档的厂商上：录入是第一步，识别先跑起来
    var picked by remember(visionEndpoint) { mutableStateOf(visionEndpoint) }
    val endpoint = endpoints.firstOrNull { it.id == picked }
        ?: endpoints.firstOrNull()
        ?: return
    val state = states[endpoint.id]

    SectionCard("厂商与模型") {
        Text(
            "选一个厂商，填一个 API Key，其余自动完成：会去问厂商这个 Key 能用哪些模型，" +
                "再给识别（视觉）与判断（文本）两档各挑一个，挑完就能开始录入。\n" +
                "两档可以是不同厂商——识别天天跑挑便宜的，判断决定分析质量挑强的。" +
                "存 Key 只会补上还空着的那一档，已经配好的不动。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            endpoints.forEach { ep ->
                FilterChip(
                    selected = ep.id == endpoint.id,
                    onClick = { picked = ep.id },
                    label = { Text(ep.name) },
                )
            }
        }
        if (endpoint.note.isNotBlank()) {
            Text(
                endpoint.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // 存 Key 只填空着的那一档。原来这里是 force=true，于是给第二个厂商填一次 Key，
        // 「识别走智谱、判断走 Anthropic」这种搭配就被悄悄拆成两档同一家了
        ApiKeyField(
            endpoint = endpoint,
            onSave = { saved -> vm.useProvider(saved, force = false) },
            locked = locked,
            onLockChange = vm::setKeyLocked,
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { vm.useProviderFor(endpoint, vision = true) },
                enabled = endpoint.configured,
            ) { Text("只设识别档") }
            OutlinedButton(
                onClick = { vm.useProviderFor(endpoint, vision = false) },
                enabled = endpoint.configured,
            ) { Text("只设判断档") }
            OutlinedButton(
                onClick = { vm.useProvider(endpoint, force = true) },
                enabled = endpoint.configured,
            ) { Text("两档都用它") }
            OutlinedButton(
                onClick = { vm.fetchModels(endpoint.id) },
                enabled = endpoint.configured,
            ) { Text("重拉清单") }
        }

        val hint = when (state) {
            AppViewModel.ModelsState.Loading -> "正在问厂商有哪些模型…"
            is AppViewModel.ModelsState.Loaded ->
                "${state.models.size} 个可用模型" + if (state.cached) "（上次拉到的，联网后可重拉）" else ""
            is AppViewModel.ModelsState.Failed -> state.message
            else -> if (endpoint.configured) "还没拉过清单，点「自动配置」即可" else "先填 Key"
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = if (state is AppViewModel.ModelsState.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (state is AppViewModel.ModelsState.Failed) {
            Text(
                "清单拉不到不影响使用：下面是内置与上次拉到的候选，也可以在「高级」里手填 ID。\n" +
                    "不是每个厂商都开放了 /models 清单接口，报 404 就是这种情况——" +
                    "跟 Key 对不对、模型能不能调没有关系，直接点下面的候选即可。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        val available = vm.modelsFor(endpoint.id)
        ModelPicker(
            title = "识别（视觉）",
            models = available.filter { it.vision },
            selected = if (visionEndpoint == endpoint.id) visionModel else "",
            empty = "这个厂商的候选里没有能看图的模型——换个厂商，" +
                "或者在「高级 → 视觉模型」里直接手填模型 ID（智谱的是 glm-4.6v-flash / glm-4v-flash）",
            onPick = { id ->
                vm.setVisionEndpoint(endpoint.id)
                vm.setVisionModel(id)
            },
        )
        ModelPicker(
            title = "判断（文本）",
            models = available,
            selected = if (textEndpoint == endpoint.id) textModel else "",
            empty = "还没有候选，先拉一次清单",
            onPick = { id ->
                vm.setTextEndpoint(endpoint.id)
                vm.setTextModel(id)
            },
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val nameOf = { id: String -> endpoints.firstOrNull { it.id == id }?.name ?: id }
        Text(
            "当前：识别 $visionModel（${nameOf(visionEndpoint)}）　判断 $textModel（${nameOf(textEndpoint)}）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        val ready = activeEndpoints(endpoints, visionEndpoint, textEndpoint)
        if (ready.isEmpty() || ready.any { !it.configured }) {
            Text(
                "还有档位缺 Key：" + ready.filterNot { it.configured }
                    .joinToString("、") { "${it.endpoint.name}（${it.label}）" }
                    .ifBlank { "两档选中的端点都不在列表里" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 一行可横滑的模型 chips。清单来自厂商，所以这里不做任何写死的过滤。 */
@Composable
private fun ModelPicker(
    title: String,
    models: List<ModelDiscovery.RemoteModel>,
    selected: String,
    empty: String,
    onPick: (String) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (models.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        models.forEach { model ->
            FilterChip(
                selected = model.id == selected,
                onClick = { onPick(model.id) },
                label = { Text(model.label + if (model.note.isNotBlank()) "·${model.note}" else "") },
            )
        }
    }
}

/** 一个档位 = 端点 + 模型 ID。两段都自由，清单只是建议值。 */
@Composable
private fun SlotConfig(
    endpoints: List<ApiEndpoint>,
    selectedEndpoint: String,
    modelId: String,
    onEndpoint: (String) -> Unit,
    onModel: (String) -> Unit,
    ready: Boolean,
    onTest: (ApiEndpoint) -> Unit,
    endpointOf: (String) -> ApiEndpoint?,
    suggestions: List<ModelDiscovery.RemoteModel>,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        endpoints.forEach { ep ->
            FilterChip(
                selected = selectedEndpoint == ep.id,
                onClick = { onEndpoint(ep.id) },
                label = { Text(ep.name) },
            )
        }
    }

    // 候选来自「拉到的清单 → 上次拉到的 → 内置清单」，不再只有写死的那一份
    if (suggestions.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            suggestions.forEach { spec ->
                FilterChip(
                    selected = modelId == spec.id,
                    onClick = { onModel(spec.id) },
                    label = { Text(spec.label + if (spec.note.isNotBlank()) "·${spec.note}" else "") },
                )
            }
        }
    }

    var text by remember(modelId) { mutableStateOf(modelId) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("模型 ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onModel(text.trim()) },
            enabled = text.isNotBlank() && text.trim() != modelId,
        ) { Text("使用这个 ID") }
        endpointOf(selectedEndpoint)?.let { ep ->
            OutlinedButton(onClick = { onTest(ep) }, enabled = ready) { Text("测试连接") }
        }
    }
    if (!ready) {
        Text(
            "该端点还缺地址或 Key",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun EndpointDialog(
    endpoint: ApiEndpoint,
    onDismiss: () -> Unit,
    onSave: (ApiEndpoint) -> Unit,
) {
    var name by remember { mutableStateOf(endpoint.name) }
    var baseUrl by remember { mutableStateOf(endpoint.baseUrl) }
    var apiKey by remember { mutableStateOf(endpoint.apiKey) }
    var protocol by remember { mutableStateOf(endpoint.protocol) }

    val preview = ApiEndpoint(endpoint.id, name, baseUrl, protocol).url

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (endpoint.name.isBlank()) "新增端点" else endpoint.name) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    enabled = !endpoint.builtIn,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "实际请求 ${preview.ifBlank { "—" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Protocol.entries.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = { protocol = p },
                            label = { Text(p.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        endpoint.copy(
                            name = name.trim().ifBlank { "未命名端点" },
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            protocol = protocol,
                        )
                    )
                },
                enabled = baseUrl.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
