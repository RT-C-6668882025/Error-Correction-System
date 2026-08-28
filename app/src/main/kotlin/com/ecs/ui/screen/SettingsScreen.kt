package com.ecs.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ecs.core.model.ModelCatalog
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.SectionCard

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsState()
    val tree by vm.tree.collectAsState()
    val storedAnthropic by vm.anthropicKey.collectAsState()
    val storedZhipu by vm.zhipuKey.collectAsState()
    val textModel by vm.textModel.collectAsState()
    val visionModel by vm.visionModel.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        SectionCard("视觉模型（识别用）") {
            Text(
                "拍照识别题号、题干、括号提示词、选项版式走这个模型。Flash 系列免费，" +
                    "录入频率是系统生命线，识别不该按次心疼。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            ModelPicker(
                models = ModelCatalog.VISION_MODELS,
                selected = visionModel,
                onSelect = vm::setVisionModel,
            )
            CustomModelField(
                current = visionModel,
                known = ModelCatalog.VISION_MODELS.map { it.id },
                label = "自定义视觉模型 ID",
                onSet = vm::setVisionModel,
            )
            if (vm.missingKeyFor(visionModel, vision = true)) {
                Text(
                    "缺 ${ModelCatalog.resolve(visionModel, vision = true).provider.label} 的 Key",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SectionCard("文本模型（判断用）") {
            Text(
                "考点树生成、标注、抽检、报告叙述走这个模型。标注质量直接决定聚合是否可信，" +
                    "这一档不建议为省钱降配。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            ModelPicker(
                models = ModelCatalog.TEXT_MODELS,
                selected = textModel,
                onSelect = vm::setTextModel,
            )
            CustomModelField(
                current = textModel,
                known = ModelCatalog.TEXT_MODELS.map { it.id },
                label = "自定义文本模型 ID",
                onSet = vm::setTextModel,
            )
            if (vm.missingKeyFor(textModel, vision = false)) {
                Text(
                    "缺 ${ModelCatalog.resolve(textModel).provider.label} 的 Key",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        ApiKeyCard(
            title = "Anthropic API Key",
            hint = ModelCatalog.Provider.ANTHROPIC.keyHint,
            stored = storedAnthropic,
            onSave = vm::setAnthropicKey,
        )

        ApiKeyCard(
            title = "智谱 GLM API Key",
            hint = ModelCatalog.Provider.ZHIPU.keyHint,
            stored = storedZhipu,
            onSave = vm::setZhipuKey,
        )

        SectionCard("考点树") {
            Text(
                tree?.let { "${it.version} · ${it.liveNodes.size} 个末端节点" } ?: "尚未生成",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "生成 120-180 个末端节点，每个带规则形态与向量。没有树就没有 form_rule，标注无从谈起。",
                style = MaterialTheme.typography.labelSmall,
            )
            Button(onClick = { vm.generateTree() }, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (tree == null) "生成考点树" else "重新生成（会换版本）")
            }
        }

        SectionCard("联网范围") {
            Text(
                "识别、标注、报告生成、树生成与维护需要联网。录入、列表、倒推表、聚合数字、" +
                    "导出与备份全部离线可用。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        MessageBar(vm)
    }
}

@Composable
private fun ModelPicker(
    models: List<ModelCatalog.ModelSpec>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        models.forEach { spec ->
            FilterChip(
                selected = selected == spec.id,
                onClick = { onSelect(spec.id) },
                label = { Text(spec.label) },
            )
        }
    }
    val spec = ModelCatalog.byId(selected)
    if (spec != null) {
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(spec.provider.label, MaterialTheme.colorScheme.primary)
            if (spec.note.isNotBlank()) Badge(spec.note, MaterialTheme.colorScheme.secondary)
        }
    }
}

/** 模型 ID 会随厂商更新漂移，留一个出口，改 ID 不用等发版。 */
@Composable
private fun CustomModelField(
    current: String,
    known: List<String>,
    label: String,
    onSet: (String) -> Unit,
) {
    var text by remember(current) { mutableStateOf(if (current in known) "" else current) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedButton(
        onClick = { onSet(text.trim()) },
        enabled = text.isNotBlank() && text.trim() != current,
        modifier = Modifier.padding(top = 4.dp),
    ) { Text("使用这个 ID") }
}

@Composable
private fun ApiKeyCard(title: String, hint: String, stored: String, onSave: (String) -> Unit) {
    var key by remember(stored) { mutableStateOf(stored) }
    SectionCard(title) {
        Text(hint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = { onSave(key.trim()) },
            enabled = key.trim() != stored,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("保存") }
    }
}
