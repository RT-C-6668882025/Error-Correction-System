package com.ecs.ui.screen

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
import com.ecs.core.model.ErrorRecord
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar
import com.ecs.ui.component.SectionCard

/**
 * 原题：一切数据的来源。
 *
 * 题干和答案都能改——识别错了要能改回来，改完可以重跑标注。
 * 复习页看到的每一条形态，最终都追溯到这里的某一道题。
 */
@Composable
fun SourceScreen(vm: AppViewModel) {
    val records by vm.records.collectAsState()
    val busy by vm.busy.collectAsState()
    var onlyUnannotated by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ErrorRecord?>(null) }

    val unannotated = vm.unannotated()
    val filtered = records.filter { r ->
        (!onlyUnannotated || r.kaodian.isNullOrBlank()) &&
            (query.isBlank() ||
                r.stem.orEmpty().contains(query, ignoreCase = true) ||
                r.answer.orEmpty().contains(query, ignoreCase = true) ||
                r.kaodian.orEmpty().contains(query))
    }

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
                label = { Text("题干 / 答案 / 考点") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = onlyUnannotated,
                onClick = { onlyUnannotated = !onlyUnannotated },
                label = { Text("待标注 $unannotated") },
            )
        }
        Text(
            "${filtered.size} 道",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.uid }) { r ->
                Card(
                    onClick = { editing = r },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.src.paper, style = MaterialTheme.typography.labelSmall)
                            Badge("第${r.src.no}题", MaterialTheme.colorScheme.secondary)
                            if (r.kaodian.isNullOrBlank()) {
                                Badge("待标注", MaterialTheme.colorScheme.error)
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
                        r.formRule?.let {
                            Text("答案形式　$it", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        r.kaodian?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        MessageBar(vm)
    }

    editing?.let { record ->
        SourceDialog(
            record = record,
            onDismiss = { editing = null },
            onSave = { vm.saveRecord(it); editing = null },
            onAnnotate = { vm.annotate(it); editing = null },
            onDelete = { vm.delete(record.uid); editing = null },
        )
    }
}

@Composable
private fun SourceDialog(
    record: ErrorRecord,
    onDismiss: () -> Unit,
    onSave: (ErrorRecord) -> Unit,
    onAnnotate: (ErrorRecord) -> Unit,
    onDelete: () -> Unit,
) {
    var stem by remember { mutableStateOf(record.stem.orEmpty()) }
    var answer by remember { mutableStateOf(record.answer.orEmpty()) }
    var eye by remember { mutableStateOf(record.eye.orEmpty()) }

    fun edited() = record.copy(
        stem = stem.ifBlank { null },
        answer = answer.ifBlank { null },
        eye = eye.ifBlank { null },
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
                    value = eye,
                    onValueChange = { eye = it },
                    label = { Text("题眼（看到什么就知道填什么）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "考点　${record.kaodian ?: "未标注"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "答案形式　${record.formRule ?: "—"}（由考点树带出，只读）",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { onAnnotate(edited()) },
                    enabled = stem.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (record.kaodian == null) "标注" else "重新标注") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edited()) }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
