package com.ecs.ui.screen

import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ecs.core.model.activeEndpoints
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.ApiKeyField
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar
import com.ecs.ui.component.SectionCard
import com.ecs.ui.nav.Routes

@Composable
fun EntryScreen(vm: AppViewModel, nav: NavHostController) {
    var tab by remember { mutableIntStateOf(0) }
    val busy by vm.busy.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)
        KeyBar(vm)
        TabRow(selectedTabIndex = tab) {
            listOf("拍照", "导入").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        if (tab == 0) ScanEntry(vm, nav) else ImportEntry(vm)
        MessageBar(vm)
    }
}

/**
 * 录入页顶部的 Key 区。缺 Key 时展开标红，当场能填；填好缩成一行。
 * 一次性配置不该长期霸占你天天要用的页面，所以配好之后它只剩一行。
 */
@Composable
private fun KeyBar(vm: AppViewModel) {
    val endpoints by vm.endpoints.collectAsState()
    val visionEndpoint by vm.visionEndpoint.collectAsState()
    val textEndpoint by vm.textEndpoint.collectAsState()
    val locked by vm.keyLocked.collectAsState()

    val active = activeEndpoints(endpoints, visionEndpoint, textEndpoint)
    val missing = active.filterNot { it.configured }
    val allReady = active.isNotEmpty() && missing.isEmpty()

    SectionCard(if (allReady) "API" else "还不能识别：先填 API Key") {
        if (!allReady) {
            Text(
                "拍照识别要联网。下面是当前在用的端点，填完就能拍。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // 已经配好的只显示一行摘要，没配好的展开让你当场填
        (if (allReady) active else missing).forEach { slot ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(slot.endpoint.name, style = MaterialTheme.typography.titleSmall)
                Badge(slot.label, MaterialTheme.colorScheme.primary)
                if (!slot.configured) Badge("待填", MaterialTheme.colorScheme.error)
            }
            if (slot.endpoint.note.isNotBlank() && !slot.configured) {
                Text(
                    slot.endpoint.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            ApiKeyField(
                endpoint = slot.endpoint,
                onSave = vm::saveEndpoint,
                locked = locked,
                onLockChange = vm::setKeyLocked,
            )
        }
    }
}

@Composable
private fun ScanEntry(vm: AppViewModel, nav: NavHostController) {
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris -> picked = uris }

    SectionCard("拍照录入") {
        Text(
            "判据只有一条：一个句子、句中至少一个空。不分题型。\n" +
                "只识别印刷体——手写作答不识别，识别错会连带污染后续全部分析。\n" +
                "识别完在确认页逐条标错 / 蒙对，标了的才入库。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("选照片（${picked.size}）") }
            Button(
                enabled = picked.isNotEmpty(),
                onClick = {
                    // 压缩与编码交给 ViewModel 在 IO 线程做，这里只递 Uri
                    vm.scan(picked)
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
            "与导出包 data.json / data.csv 同结构，含题干。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { jsonPicker.launch("*/*") }) { Text("导入 JSON") }
            OutlinedButton(onClick = { csvPicker.launch("*/*") }) { Text("导入 CSV") }
        }
    }
}
