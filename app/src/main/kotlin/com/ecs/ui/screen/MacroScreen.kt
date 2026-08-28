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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecs.core.report.ReportBuilder
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MarkdownBlock
import com.ecs.ui.component.SectionCard

@Composable
fun MacroScreen(vm: AppViewModel) {
    val records by vm.records.collectAsState()
    val tree by vm.tree.collectAsState()
    val busy by vm.busy.collectAsState()
    val depth by vm.macroDepth.collectAsState()
    val narrative by vm.macroNarrative.collectAsState()
    val maturity = vm.maturity()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        SectionCard("数据成熟度") {
            Text("${maturity.level.label} · ${maturity.countedSlots} 条 · ${maturity.hint}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }

        if (!maturity.macroEnabled) {
            SectionCard("大方向未开放") {
                Text("50 条以下的全局结论会带偏方向，入口置灰。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            return@Column
        }

        SectionCard("聚合粒度") {
            Text("depth = $depth", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = depth.toFloat(),
                onValueChange = { vm.setMacroDepth(it.toInt().coerceIn(1, 4)) },
                valueRange = 1f..4f,
                steps = 2,
            )
        }

        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.generateMacro() }) { Text("生成判断部分") }
        }

        MarkdownBlock(
            ReportBuilder.macro(records, tree, depth, narrative),
            Modifier.fillMaxWidth(),
        )
        MessageBar(vm)
    }
}
