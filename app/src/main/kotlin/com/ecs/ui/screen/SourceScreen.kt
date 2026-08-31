package com.ecs.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecs.core.dup.Duplicates
import com.ecs.core.model.ErrorRecord
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar

/**
 * 原题：一切数据的来源。
 *
 * 题干和答案都能改——识别错了要能改回来，改完可以重跑分析。
 * 复习页看到的每一条形态，最终都追溯到这里的某一道题。
 *
 * 这一页同时是原题的管理页：长按进多选，选中的可以一起分析、也可以一起删。
 * 分析由选择决定跑哪些，不再是「全部待分析」一把梭——重录一份卷子之后
 * 只想跑新的那几条，是常态。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceScreen(vm: AppViewModel, onEditPrompt: () -> Unit = {}) {
    val records by vm.records.collectAsState()
    val busy by vm.busy.collectAsState()
    val selected by vm.selected.collectAsState()
    var onlyPending by remember { mutableStateOf(false) }
    var onlyUnclassified by remember { mutableStateOf(false) }
    var onlyDuplicates by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ErrorRecord?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val pending = vm.pending()
    val unclassified = vm.unclassified()

    // 判重是纯计算，但没必要每次重组都算一遍——记录没变就不重算
    val dupGroups = remember(records) { Duplicates.groups(records) }
    val dupKeep = remember(dupGroups) { dupGroups.map { it.keep.uid }.toSet() }
    val dupDrop = remember(dupGroups) { dupGroups.flatMap { g -> g.drop.map { it.uid } }.toSet() }

    val filtered = records.filter { r ->
        (!onlyPending || r.formShape.isNullOrBlank()) &&
            (!onlyUnclassified || (!r.formShape.isNullOrBlank() && !r.analyzed())) &&
            (!onlyDuplicates || r.uid in dupKeep || r.uid in dupDrop) &&
            (query.isBlank() ||
                r.stem.orEmpty().contains(query, ignoreCase = true) ||
                r.answer.orEmpty().contains(query, ignoreCase = true) ||
                r.branch.orEmpty().contains(query))
    }
    val picking = selected.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        BusyBar(busy)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("题干 / 答案 / 板块") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        // 三个数分开摆：待分析是「还没跑」，未归类是「跑了但没落进板块」，
        // 重复是「同一道题录了两遍」——混成一个数字就查不出来了
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${filtered.size} 道", style = MaterialTheme.typography.labelMedium)
            FilterChip(
                selected = onlyPending,
                onClick = {
                    onlyPending = !onlyPending
                    if (onlyPending) { onlyUnclassified = false; onlyDuplicates = false }
                },
                label = { Text("待分析 $pending") },
            )
            if (unclassified > 0) {
                FilterChip(
                    selected = onlyUnclassified,
                    onClick = {
                        onlyUnclassified = !onlyUnclassified
                        if (onlyUnclassified) { onlyPending = false; onlyDuplicates = false }
                    },
                    label = { Text("未归类 $unclassified") },
                )
            }
            if (dupDrop.isNotEmpty()) {
                FilterChip(
                    selected = onlyDuplicates,
                    onClick = {
                        onlyDuplicates = !onlyDuplicates
                        if (onlyDuplicates) { onlyPending = false; onlyUnclassified = false }
                    },
                    label = { Text("重复 ${dupDrop.size}") },
                )
            }
        }

        if (picking) {
            // 选中之后所有动作都在这一行里：分析、删除、全选、取消
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选中 ${selected.size}", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { vm.analyzeSelected() }) { Text("分析") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("删除") }
                TextButton(onClick = { vm.selectAll(filtered.map { it.uid }) }) { Text("全选") }
                TextButton(onClick = { vm.clearSelection() }) { Text("取消") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "长按一条进入多选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (pending + unclassified > 0) {
                    // 没归进板块就进不了复习页：那一支是空的，小方向也就无从汇总
                    TextButton(onClick = {
                        vm.selectAll(
                            records.filter { !it.analyzed() && !it.stem.isNullOrBlank() }.map { it.uid }
                        )
                    }) { Text("选中待分析 ${pending + unclassified}") }
                }
                if (dupDrop.isNotEmpty()) {
                    TextButton(onClick = { vm.selectDuplicates() }) { Text("选中重复 ${dupDrop.size}") }
                }
                TextButton(onClick = onEditPrompt) { Text("改分析提示词") }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.uid }) { r ->
                val checked = r.uid in selected
                Card(
                    colors = if (checked) {
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else CardDefaults.cardColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .combinedClickable(
                            // 多选态里点一下就是选，不再打开编辑框——
                            // 勾了三十条之后误触打开一个对话框会很烦
                            onClick = { if (picking) vm.toggleSelect(r.uid) else editing = r },
                            onLongClick = { vm.toggleSelect(r.uid) },
                        ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (picking) {
                                Checkbox(checked = checked, onCheckedChange = { vm.toggleSelect(r.uid) })
                            }
                            Text(r.src.paper, style = MaterialTheme.typography.labelSmall)
                            Badge("第${r.src.no}题", MaterialTheme.colorScheme.secondary)
                            when {
                                r.formShape.isNullOrBlank() ->
                                    Badge("待分析", MaterialTheme.colorScheme.error)
                                // 跑过了但 branch 落在十九支之外：复习页看不到它
                                !r.analyzed() ->
                                    Badge("未归类", MaterialTheme.colorScheme.error)
                            }
                            when (r.uid) {
                                // 留哪条、删哪条要在卡片上看得见，否则「选中重复」等于闭眼删
                                in dupKeep -> Badge("重复·留这条", MaterialTheme.colorScheme.primary)
                                in dupDrop -> Badge("重复", MaterialTheme.colorScheme.error)
                            }
                        }
                        Text(
                            r.stem?.takeIf { it.isNotBlank() } ?: "（没有题干，点开补）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        r.answer?.let {
                            Text("答案　$it", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        r.formShape?.let {
                            Text("答案形式　$it", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        r.branch?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        MessageBar(vm)
    }

    if (confirmDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 $count 条原题？") },
            text = {
                Text(
                    "原题是一切的来源，删掉之后它的分析、以及它在小方向里贡献的那一份都不再有依据。\n\n" +
                        "删之前会自动存一份备份（设置 → 导出与备份 里能找到并导回来），但这一步本身不可撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteSelected(); confirmDelete = false }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    editing?.let { record ->
        SourceDialog(
            record = record,
            onDismiss = { editing = null },
            onSave = { vm.saveRecord(it); editing = null },
            onAnalyze = { vm.analyze(it); editing = null },
            onDelete = { vm.delete(record.uid); editing = null },
        )
    }
}

@Composable
private fun SourceDialog(
    record: ErrorRecord,
    onDismiss: () -> Unit,
    onSave: (ErrorRecord) -> Unit,
    onAnalyze: (ErrorRecord) -> Unit,
    onDelete: () -> Unit,
) {
    var stem by remember { mutableStateOf(record.stem.orEmpty()) }
    var answer by remember { mutableStateOf(record.answer.orEmpty()) }
    var basis by remember { mutableStateOf(record.basis.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun edited() = record.copy(
        stem = stem.ifBlank { null },
        answer = answer.ifBlank { null },
        basis = basis.ifBlank { null },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${record.src.paper} 第${record.src.no}题") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = stem,
                    onValueChange = { stem = it },
                    label = { Text("题干") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("答案") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = basis,
                    onValueChange = { basis = it },
                    label = { Text("判断依据（看到什么就知道填什么）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "板块　${record.branch ?: "未归类"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "答案形式　${record.formShape ?: "—"}（由分析推出，重跑分析会覆盖）",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { onAnalyze(edited()) },
                    enabled = stem.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (record.branch == null) "分析" else "重新分析") }
                if (confirmDelete) {
                    Text(
                        "再点一次「删除」就删掉这一条，不可撤销。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edited()) }) { Text("保存") } },
        dismissButton = {
            Row {
                // 删除要点两次：这个按钮紧挨着「关闭」，误触的代价是一条原题
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "确认删除" else "删除")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
