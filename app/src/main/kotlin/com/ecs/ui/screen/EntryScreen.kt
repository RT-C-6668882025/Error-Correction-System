package com.ecs.ui.screen

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ecs.agent.AgentClient
import com.ecs.core.model.Section
import com.ecs.core.parse.SlotSpec
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.SectionCard
import com.ecs.ui.nav.Routes

/**
 * F1.2 极简录入是默认入口，完整录入是次级选项。
 * 备考最忙的时候恰恰是最不想多操作的时候。
 */
@Composable
fun EntryScreen(vm: AppViewModel, nav: NavHostController) {
    var tab by remember { mutableIntStateOf(0) }
    val busy by vm.busy.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)
        TabRow(selectedTabIndex = tab) {
            listOf("极简", "拍照识别", "导入").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> QuickEntry(vm)
            1 -> ScanEntry(vm, nav)
            else -> ImportEntry(vm)
        }
        MessageBar(vm)
    }
}

@Composable
private fun QuickEntry(vm: AppViewModel) {
    var paper by remember { mutableStateOf("") }
    var spec by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(Section.GF) }

    val parsed = remember(spec, section) { SlotSpec.parse(spec, section) }

    SectionCard("三秒录入") {
        OutlinedTextField(
            value = paper,
            onValueChange = { paper = it },
            label = { Text("卷名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Section.entries.forEach { s ->
                FilterChip(
                    selected = section == s,
                    onClick = { section = s },
                    label = { Text(s.label) },
                )
            }
        }
        OutlinedTextField(
            value = spec,
            onValueChange = { spec = it },
            label = { Text(if (section == Section.GF) "错题号　如 3, ?5, 7" else "错题号　如 12-1, ?15-2") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            parsed.preview,
            style = MaterialTheme.typography.labelMedium,
            color = if (parsed.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "? 前缀表示蒙对，按 0.5 权重计入",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        OutlinedTextField(
            value = total,
            onValueChange = { total = it.filter { c -> c.isDigit() } },
            label = { Text("该卷该题型总空数（可后补，缺则不算错误率）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = {
                vm.quickAdd(paper.trim(), section, spec, total.toIntOrNull())
                spec = ""
            },
            enabled = paper.isNotBlank() && parsed.entries.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("提交") }
        Text(
            "其余字段留空，状态记为「不完整」，空闲时在列表页的待完善入口批量补标注。",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ScanEntry(vm: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    var section by remember { mutableStateOf(Section.GF) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris -> picked = uris }

    SectionCard("识别题号 / 题干 / 括号提示词 / 空位") {
        Text(
            "只识别印刷体。手写作答不识别——识别错会连带污染题眼和后续全部分析，" +
                "作答由你直接输入错题号。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Section.entries.forEach { s ->
                FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s.label) })
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("选照片（${picked.size}）") }
            Button(
                enabled = picked.isNotEmpty(),
                onClick = {
                    val images = picked.mapNotNull { uri ->
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                AgentClient.Image(
                                    mediaType = context.contentResolver.getType(uri) ?: "image/jpeg",
                                    base64 = Base64.encodeToString(input.readBytes(), Base64.NO_WRAP),
                                )
                            }
                        }.getOrNull()
                    }
                    vm.scan(images, section)
                    nav.navigate(Routes.CONFIRM)
                },
            ) { Text("识别") }
        }
    }
}

@Composable
private fun ImportEntry(vm: AppViewModel) {
    val context = LocalContext.current

    fun read(uri: Uri?): String = uri?.let {
        runCatching {
            context.contentResolver.openInputStream(it)?.use { s -> s.readBytes().decodeToString() }
        }.getOrNull()
    }.orEmpty()

    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        read(it).takeIf { t -> t.isNotBlank() }?.let(vm::importJson)
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        read(it).takeIf { t -> t.isNotBlank() }?.let(vm::importCsv)
    }

    SectionCard("批量导入") {
        Text(
            "与导出包 data.json / data.csv 同结构。校验走录入同一条路径：缺字段照样收，只是状态不同。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { jsonPicker.launch("*/*") }) { Text("导入 JSON") }
            OutlinedButton(onClick = { csvPicker.launch("*/*") }) { Text("导入 CSV") }
        }
    }
}
