package com.ecs.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ecs.core.prompt.PromptSlot
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.SectionCard

/**
 * 提示词可视化与编辑。
 *
 * 正文可改可还原，输出契约只读——改语气和判断标准可以，改坏解析不行。
 * 「还原默认」删的是你的覆盖值，不是把提示词清空。
 */
@Composable
fun PromptScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsState()
    val overrides by vm.promptOverrides.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        SectionCard("说明") {
            Text(
                "每段分两半：上半是可改正文，下半是灰色的只读契约（输出结构、文案禁止项、" +
                    "题眼禁用词），保存时自动拼在末尾。正文清空也不会让任务失败，只是少了额外指引。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "已改 ${overrides.count { (slot, body) -> slot.modified(body) }} / ${PromptSlot.entries.size} 段",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(
                onClick = { vm.resetAllPrompts() },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("全部还原默认") }
        }

        PromptSlot.entries.forEach { slot ->
            PromptEditor(
                slot = slot,
                override = overrides[slot],
                onSave = { vm.savePrompt(slot, it) },
                onReset = { vm.resetPrompt(slot) },
            )
        }
        MessageBar(vm)
    }
}

@Composable
private fun PromptEditor(
    slot: PromptSlot,
    override: String?,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    val stored = override ?: slot.body
    var text by remember(stored) { mutableStateOf(stored) }
    val dirty = text.trim() != stored.trim()

    SectionCard(slot.title) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (slot.modified(override)) Badge("已修改", MaterialTheme.colorScheme.primary)
            if (dirty) Badge("未保存", MaterialTheme.colorScheme.error)
        }
        Text(
            slot.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 6.dp),
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("正文（可改）") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "输出契约（只读，始终追加在末尾）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            slot.contract,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSave(text) }, enabled = dirty) { Text("保存") }
            TextButton(
                onClick = { onReset(); text = slot.body },
                enabled = slot.modified(override) || dirty,
            ) { Text("还原默认") }
        }
    }
}
