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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ecs.core.model.Confidence
import com.ecs.core.rules.Validation
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar
import com.ecs.ui.component.SectionCard
import com.ecs.ui.nav.Routes

/**
 * 识别结果确认。
 *
 * 识别不区分题型，交出来的就是「一个句子 + 句中的空」。哪些是错的、哪些是蒙对的，
 * 只有你知道——所以逐条标，标了的才入库。没标的连分析都不用跑。
 */
@Composable
fun ConfirmScreen(vm: AppViewModel, nav: NavHostController) {
    val scanned by vm.scanned.collectAsState()
    val marks by vm.marks.collectAsState()
    val busy by vm.busy.collectAsState()
    var paper by remember { mutableStateOf("") }
    var autoAnalyze by remember { mutableStateOf(true) }

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
            Text(
                "识别到 ${scanned.size} 个空，已标 ${marks.size} 个。只有标过的入库。",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.markAllWrong() }) { Text("全标错") }
                OutlinedButton(onClick = { vm.clearMarks() }) { Text("清空标记") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(checked = autoAnalyze, onCheckedChange = { autoAnalyze = it })
                Text("录入后立即分析", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    vm.submit(paper.trim(), autoAnalyze)
                    nav.navigate(Routes.SOURCE)
                },
                enabled = paper.isNotBlank() && marks.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("提交 ${marks.size} 条") }
        }

        SectionCard("识别结果") {
            if (scanned.isEmpty()) {
                Text(
                    "没有识别到内容。回上一页重拍，或者先提交再到原题页手动补题干。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            scanned.forEach { q ->
                val mark = marks[q.no to q.slot]
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${q.no}-${q.slot}", style = MaterialTheme.typography.labelLarge)
                        // 一个 chip 循环三态：没标 → 错 → 蒙对 → 没标
                        FilterChip(
                            selected = mark != null,
                            onClick = { vm.cycleMark(q.no, q.slot) },
                            label = { Text(mark?.label ?: "不标") },
                        )
                        if (q.isChoice) Badge("已剥离选项", MaterialTheme.colorScheme.primary)
                        if (q.confidence < Validation.IDENTIFY_CONF_FLOOR) {
                            Badge("识别 %.2f".format(q.confidence), MaterialTheme.colorScheme.error)
                        }
                        q.given?.let { Badge(it, MaterialTheme.colorScheme.secondary) }
                    }
                    // 展示的是剥离后的题干，请你确认的就是它
                    Text(
                        q.stem,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mark == null) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    q.answer?.let {
                        Text(
                            "答案：$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (q.isChoice) {
                        Text(
                            "选项 ${q.options.joinToString(" / ") { o -> "${o.letter}.${o.content}" }}" +
                                "　仅供你核对，不入库",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (mark == Confidence.LUCKY) {
                        Text(
                            "蒙对：答案对了但不是推出来的，照样要分析",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
        MessageBar(vm)
    }
}
