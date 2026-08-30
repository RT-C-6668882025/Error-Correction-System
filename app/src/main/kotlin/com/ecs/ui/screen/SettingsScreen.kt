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
import com.ecs.core.model.ModelCatalog
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        // 填 Key 是首次使用的第一步，放在最上面
        val active = activeEndpoints(endpoints, visionEndpoint, textEndpoint)
        SectionCard("API Key") {
            Text(
                "识别、分析、汇总方向都要联网。下面是当前两档在用的端点，填完就能用。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (active.isEmpty()) {
                Text(
                    "两档选中的端点都不在列表里，先到下方「API 端点」里选一个。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            active.forEach { slot ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(slot.endpoint.name, style = MaterialTheme.typography.titleSmall)
                    Badge(slot.label, MaterialTheme.colorScheme.primary)
                    if (!slot.configured) Badge("待填", MaterialTheme.colorScheme.error)
                }
                if (slot.endpoint.note.isNotBlank()) {
                    Text(
                        slot.endpoint.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                ApiKeyField(endpoint = slot.endpoint, onSave = vm::saveEndpoint)
            }
            if (active.isNotEmpty() && active.all { it.configured }) {
                Text(
                    "两档均已配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

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
                vision = true,
                onEndpoint = vm::setVisionEndpoint,
                onModel = vm::setVisionModel,
                ready = vm.endpointReady(visionEndpoint),
                onTest = { ep -> vm.testEndpoint(ep, visionModel) },
                endpointOf = vm::endpointOf,
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
                vision = false,
                onEndpoint = vm::setTextEndpoint,
                onModel = vm::setTextModel,
                ready = vm.endpointReady(textEndpoint),
                onTest = { ep -> vm.testEndpoint(ep, textModel) },
                endpointOf = vm::endpointOf,
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

/** 一个档位 = 端点 + 模型 ID。两段都自由，清单只是建议值。 */
@Composable
private fun SlotConfig(
    endpoints: List<ApiEndpoint>,
    selectedEndpoint: String,
    modelId: String,
    vision: Boolean,
    onEndpoint: (String) -> Unit,
    onModel: (String) -> Unit,
    ready: Boolean,
    onTest: (ApiEndpoint) -> Unit,
    endpointOf: (String) -> ApiEndpoint?,
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

    val suggestions = ModelCatalog.suggestionsFor(selectedEndpoint, vision)
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
