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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.SectionCard

/** F6：全部改为「Agent 提议 + 人一键确认」。 */
@Composable
fun MaintenanceScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsState()
    val proposals by vm.proposals.collectAsState()
    val tree by vm.tree.collectAsState()
    val records by vm.records.collectAsState()

    val stale = records.count { it.treeVersion != null && it.treeVersion != tree?.version }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)

        SectionCard("考点树") {
            Text("版本 ${tree?.version ?: "未生成"} · 末端节点 ${tree?.liveNodes?.size ?: 0}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            if (stale > 0) {
                Text("$stale 条记录停留在旧版本，改一次树就产生一批孤儿记录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refreshProposals() }) { Text("扫描维护项") }
                if (stale > 0) OutlinedButton(onClick = { vm.remapStale() }) { Text("批量重映射") }
            }
        }

        SectionCard("提议队列（${proposals.size}）") {
            if (proposals.isEmpty()) {
                Text("暂无提议。命名归一每 100 条扫一次；末端 <3 条且总量 >100 触发向上合并；" +
                    "末端占比 >15% 触发拆分；待归位攒够 10 条触发归位。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            proposals.forEach { p ->
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(p.summary, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.applyProposal(p) }) { Text("确认") }
                        OutlinedButton(onClick = { vm.dismissProposal(p) }) { Text("忽略") }
                    }
                }
            }
        }

        SectionCard("其他维护") {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.runVerification() }) { Text("抽检 5%") }
                OutlinedButton(onClick = { vm.sweepDormancy() }) { Text("休眠扫描") }
            }
        }
        MessageBar(vm)
    }
}
