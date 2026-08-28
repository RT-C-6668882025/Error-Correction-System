package com.ecs.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.ecs.core.agg.Aggregator
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.BusyBar

/**
 * 最常用页面。练到看见任一题眼都条件反射想到同一形态，该考点即掌握。
 * 「遮住答案」是自测开关。
 */
@Composable
fun ReverseTableScreen(vm: AppViewModel) {
    val records by vm.records.collectAsState()
    val tree by vm.tree.collectAsState()
    val busy by vm.busy.collectAsState()
    var masked by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val depth by vm.microDepth.collectAsState()

    val groups = remember(records, tree, depth) {
        Aggregator.kaodianStats(records, tree, depth)
    }.filter { query.isBlank() || it.kaodian.contains(query) || it.formRule.contains(query) }

    Column(Modifier.fillMaxSize()) {
        BusyBar(busy)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("考点 / 形态") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            FilterChip(selected = masked, onClick = { masked = !masked }, label = { Text("遮住答案") })
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(groups, key = { it.kaodian }) { stat ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stat.kaodian, style = MaterialTheme.typography.titleSmall)
                        Text(stat.formRule, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        stat.rows.forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(row.eye, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                                Text(row.formContext ?: "—", Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary)
                                Text(
                                    if (masked) "＿＿" else (row.answer ?: "—"),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
