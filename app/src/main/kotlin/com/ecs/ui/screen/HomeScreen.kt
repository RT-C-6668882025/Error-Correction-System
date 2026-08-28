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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ecs.core.agg.Aggregator
import com.ecs.core.model.RecordStatus
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.SectionCard
import com.ecs.ui.component.StatRow
import com.ecs.ui.nav.Routes
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: AppViewModel, nav: NavHostController) {
    val records by vm.records.collectAsState()
    val tree by vm.tree.collectAsState()
    val busy by vm.busy.collectAsState()

    val maturity = vm.maturity()
    val consistency = vm.consistency()
    val rates = vm.rates()
    val term = vm.termination()
    val activeKaodian = Aggregator.kaodianStats(records, tree, vm.microDepth.collectAsState().value)
        .count { it.reportable }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        if (tree == null) {
            SectionCard("考点树尚未生成") {
                Text(
                    "没有树就没有 form_rule，标注无从谈起。先在设置里填 API Key，再生成树。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.generateTree() }) { Text("生成考点树") }
                    OutlinedButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("设置") }
                }
            }
        }

        SectionCard("总览") {
            StatRow(
                "总空数" to maturity.countedSlots.toString(),
                "活跃考点" to activeKaodian.toString(),
                "标注一致率" to consistency.display,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge("成熟度 ${maturity.level.label}", MaterialTheme.colorScheme.primary)
                if (consistency.alarm) Badge("一致率告警", MaterialTheme.colorScheme.error)
                if (tree != null) Badge("考点树 ${tree!!.version}", MaterialTheme.colorScheme.secondary)
            }
            Text(maturity.hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            if (consistency.alarm) {
                Text(
                    "一致率低于 85%：标注流程有系统性问题，停止攒数据，先修提示词或考点树。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SectionCard("分题型错误率") {
            rates.forEach { r ->
                Text(
                    buildString {
                        append("${r.section.label}　")
                        append(r.rate?.let { "${(it * 100).roundToInt()}%（${fmt(r.weightedWrong)} / ${r.totalSlots}）" }
                            ?: "分母缺失，补 total_in_section 后可算")
                        r.weightedLoss?.let { append("　加权失分 ${fmt(it)}/${r.section.fullScore}") }
                        if (r.papersSkipped > 0) append("　[${r.papersSkipped} 卷未计入]")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        SectionCard("终止条件达成度") {
            Text("活跃考点 $activeKaodian（<5 进入保温）", style = MaterialTheme.typography.bodySmall)
            Text(
                "轮次相似度 " + (term.roundSimilarity?.let { "%.2f".format(it) } ?: "—") +
                    if (term.sourceExhausted) "　题源已榨干，换题源" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("考点树覆盖率 ${(term.coverage * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            if (term.warmMode) Text("已可切保温模式，仅定期抽检", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("待办") {
            val pendingAnswer = vm.pending(RecordStatus.PENDING_ANSWER).size
            val incomplete = vm.pending(RecordStatus.INCOMPLETE).size
            val conflicts = vm.conflicts().size
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.navigate(Routes.LIST) }, modifier = Modifier.weight(1f)) {
                    Text("待补答案 $pendingAnswer")
                }
                OutlinedButton(onClick = { nav.navigate(Routes.LIST) }, modifier = Modifier.weight(1f)) {
                    Text("待完善 $incomplete")
                }
                OutlinedButton(onClick = { nav.navigate(Routes.LIST) }, modifier = Modifier.weight(1f)) {
                    Text("冲突 $conflicts")
                }
            }
        }

        SectionCard("快捷入口") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate(Routes.ENTRY) }, modifier = Modifier.weight(1f)) { Text("录入") }
                OutlinedButton(onClick = { nav.navigate(Routes.MICRO) }, modifier = Modifier.weight(1f)) { Text("小方向") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.runVerification() }, modifier = Modifier.weight(1f)) { Text("跑一次抽检") }
                OutlinedButton(onClick = { nav.navigate(Routes.MAINTENANCE) }, modifier = Modifier.weight(1f)) { Text("维护") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.navigate(Routes.EXPORT) }, modifier = Modifier.weight(1f)) { Text("导出") }
                OutlinedButton(onClick = { nav.navigate(Routes.SETTINGS) }, modifier = Modifier.weight(1f)) { Text("设置") }
            }
        }
        MessageBar(vm)
    }
}

internal fun fmt(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)

@Composable
internal fun MessageBar(vm: AppViewModel) {
    val message by vm.message.collectAsState()
    if (message != null) {
        SectionCard("提示") {
            Text(message!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(onClick = { vm.dismissMessage() }, modifier = Modifier.padding(top = 8.dp)) {
                Text("知道了")
            }
        }
    }
}
