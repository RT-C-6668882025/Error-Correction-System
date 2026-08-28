package com.ecs.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ecs.core.model.Section
import com.ecs.core.parse.SlotSpec
import com.ecs.core.rules.Validation
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.SectionCard
import com.ecs.ui.nav.Routes

/** 识别结果 + 错题号输入 + 逐条字段确认。 */
@Composable
fun ConfirmScreen(vm: AppViewModel, nav: NavHostController) {
    val scanned by vm.scanned.collectAsState()
    val busy by vm.busy.collectAsState()
    var paper by remember { mutableStateOf("") }
    var spec by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(Section.GF) }
    var autoAnnotate by remember { mutableStateOf(true) }

    val parsed = remember(spec, section) { SlotSpec.parse(spec, section) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        SectionCard("题源") {
            OutlinedTextField(
                value = paper,
                onValueChange = { paper = it },
                label = { Text("卷名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Section.entries.forEach { s ->
                    FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s.label) })
                }
            }
            OutlinedTextField(
                value = spec,
                onValueChange = { spec = it },
                label = { Text("错题号（? 前缀 = 蒙对）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(parsed.preview, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(checked = autoAnnotate, onCheckedChange = { autoAnnotate = it })
                Text("录入后立即标注", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    vm.confirmScan(paper.trim(), section, spec, null, autoAnnotate)
                    nav.navigate(Routes.LIST)
                },
                enabled = paper.isNotBlank() && parsed.entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("入库") }
        }

        SectionCard("识别结果（${scanned.size} 个空）") {
            if (scanned.isEmpty()) {
                Text("没有识别到内容。可以直接用极简录入先把错题号存下来。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            scanned.forEach { q ->
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${q.no}-${q.slot}", style = MaterialTheme.typography.labelLarge)
                        if (q.isChoice) Badge("已剥离选项，按填空录入", MaterialTheme.colorScheme.primary)
                        if (q.confidence < Validation.IDENTIFY_CONF_FLOOR) {
                            Badge("置信度 %.2f".format(q.confidence), MaterialTheme.colorScheme.error)
                        }
                        q.given?.let { Badge(it, MaterialTheme.colorScheme.secondary) }
                    }
                    // 展示的是剥离后的题干，请你确认的就是它
                    Text(q.stem, style = MaterialTheme.typography.bodySmall)
                    q.answer?.let {
                        Text("答案：$it", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    if (q.isChoice) {
                        Text(
                            "选项 ${q.options.joinToString(" / ") { o -> "${o.letter}.${o.content}" }}" +
                                "　仅供你核对，不入库",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
        MessageBar(vm)
    }
}
