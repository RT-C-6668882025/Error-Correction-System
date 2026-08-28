package com.ecs.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecs.core.agg.Aggregator
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar
import com.ecs.ui.component.SectionCard

/**
 * 复习：自下而上的递归。
 *
 * 末端层一个考点一张卡，交出这一类空的答案形式；往上一层，下层的形态成为上层的输入。
 * 页面上只有形态与题眼，没有错误率、次数、难度分布——那些答的是「错得怎么样」，
 * 不是「该填成什么形态」。
 */
@Composable
fun ReviewScreen(vm: AppViewModel) {
    val records by vm.records.collectAsState()
    val tree by vm.tree.collectAsState()
    val busy by vm.busy.collectAsState()
    val level by vm.level.collectAsState()
    val narratives by vm.narratives.collectAsState()
    var masked by remember { mutableStateOf(false) }

    val groups = remember(records, tree, level) { Aggregator.groups(records, tree, level) }

    Column(Modifier.fillMaxSize()) {
        BusyBar(busy)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Aggregator.Level.entries.forEach { l ->
                FilterChip(
                    selected = level == l,
                    onClick = { vm.setLevel(l) },
                    label = { Text(l.label) },
                )
            }
            FilterChip(
                selected = masked,
                onClick = { masked = !masked },
                label = { Text("遮住答案") },
            )
        }
        Text(
            "${level.hint}　${groups.size} 组",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        if (groups.isEmpty()) {
            SectionCard("还没有可复习的") {
                Text(
                    "先到录入页拍一张卷子，标注完成后这里就会按考点层级汇总出答案形式。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(groups, key = { it.kaodian }) { group ->
                GroupCard(
                    group = group,
                    masked = masked,
                    narrative = narratives[group.kaodian],
                    onNarrate = { vm.narrate(group) },
                )
            }
        }
        MessageBar(vm)
    }
}

@Composable
private fun GroupCard(
    group: Aggregator.Group,
    masked: Boolean,
    narrative: com.ecs.core.report.ReportBuilder.Narrative?,
    onNarrate: () -> Unit,
) {
    var expanded by remember(group.kaodian) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.leafName, style = MaterialTheme.typography.titleSmall)
                Badge(group.root, MaterialTheme.colorScheme.secondary)
            }
            Text(
                group.kaodian,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            // 这一组的输出：往上一层时，它就是上层的输入
            group.formRules.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
                Text(if (expanded) "收起 ${group.size} 道" else "展开 ${group.size} 道")
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(bottom = 6.dp))
                group.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(row.eye, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            row.formContext ?: "—",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            if (masked) "＿＿" else (row.answer ?: "—"),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (group.children.size > 1) {
                    Text(
                        "由这些考点汇总：${group.children.joinToString("、") { it.substringAfterLast('/') }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                narrative?.let {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Labeled("决定性判断点", it.decisivePoint)
                    Labeled("最易混淆", it.confusable)
                    Labeled("怎么练", it.fixPath)
                }
                OutlinedButton(onClick = onNarrate, modifier = Modifier.padding(top = 6.dp)) {
                    Text(if (narrative == null) "让模型补判断" else "重新生成")
                }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    if (value.isBlank()) return
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
}
