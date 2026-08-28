package com.ecs.ui.screen

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecs.core.agg.Aggregator
import com.ecs.core.report.ReportBuilder
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MarkdownBlock
import com.ecs.ui.component.SectionCard

@Composable
fun MicroScreen(vm: AppViewModel) {
    val records by vm.records.collectAsState()
    val tree by vm.tree.collectAsState()
    val busy by vm.busy.collectAsState()
    val depth by vm.microDepth.collectAsState()
    val narratives by vm.microNarrative.collectAsState()
    val maturity = vm.maturity()
    var selected by remember { mutableStateOf<String?>(null) }

    val stats = Aggregator.kaodianStats(records, tree, depth).filter { it.reportable }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        if (!maturity.microEnabled) {
            SectionCard("报告未开放") {
                Text(maturity.hint, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp))
                Text("不足 50 条时只有倒推表可用：这个量级出的优先级排序基本是噪声。",
                    style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        SectionCard("聚合粒度") {
            Text("depth = $depth", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = depth.toFloat(),
                onValueChange = { vm.setMicroDepth(it.toInt().coerceIn(1, 4)) },
                valueRange = 1f..4f,
                steps = 2,
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            stats.take(30).forEach { s ->
                FilterChip(
                    selected = selected == s.kaodian,
                    onClick = { selected = s.kaodian },
                    label = { Text("${s.kaodian.substringAfterLast('/')} ${s.slots}") },
                )
            }
        }

        val stat = stats.firstOrNull { it.kaodian == selected } ?: stats.firstOrNull()
        if (stat == null) {
            SectionCard("暂无可报告的考点") {
                Text("先给记录标上考点。", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.generateMicro(stat) }) { Text("生成判断部分") }
            }
            MarkdownBlock(
                ReportBuilder.micro(stat, narratives[stat.kaodian] ?: ReportBuilder.MicroNarrative()),
                Modifier.fillMaxWidth(),
            )
        }
        MessageBar(vm)
    }
}
