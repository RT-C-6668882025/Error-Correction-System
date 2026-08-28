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
import com.ecs.core.model.Confidence
import com.ecs.core.model.Difficulty
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Section
import com.ecs.core.model.Verified
import com.ecs.core.rules.Validation
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar

@Composable
fun ListScreen(vm: AppViewModel) {
    val records by vm.records.collectAsState()
    val tree by vm.tree.collectAsState()
    val busy by vm.busy.collectAsState()

    var status by remember { mutableStateOf<RecordStatus?>(null) }
    var section by remember { mutableStateOf<Section?>(null) }
    var confidence by remember { mutableStateOf<Confidence?>(null) }
    var difficulty by remember { mutableStateOf<Difficulty?>(null) }
    var onlyConflicts by remember { mutableStateOf(false) }
    var kaodianPrefix by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ErrorRecord?>(null) }

    val filtered = records.filter { r ->
        (status == null || r.status == status) &&
            (section == null || r.src.section == section) &&
            (confidence == null || r.confidence == confidence) &&
            (difficulty == null || r.difficulty == difficulty) &&
            (!onlyConflicts || r.verified == Verified.CONFLICT) &&
            (kaodianPrefix.isBlank() || r.kaodian.orEmpty().startsWith(kaodianPrefix)) &&
            (paper.isBlank() || r.src.paper.contains(paper))
    }

    Column(Modifier.fillMaxSize()) {
        BusyBar(busy)

        // 三个待办入口
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TodoChip("待补答案 ${records.count { it.status == RecordStatus.PENDING_ANSWER }}",
                status == RecordStatus.PENDING_ANSWER) {
                status = if (status == RecordStatus.PENDING_ANSWER) null else RecordStatus.PENDING_ANSWER
                onlyConflicts = false
            }
            TodoChip("待完善 ${records.count { it.status == RecordStatus.INCOMPLETE }}",
                status == RecordStatus.INCOMPLETE) {
                status = if (status == RecordStatus.INCOMPLETE) null else RecordStatus.INCOMPLETE
                onlyConflicts = false
            }
            TodoChip("标注冲突 ${records.count { it.verified == Verified.CONFLICT }}", onlyConflicts) {
                onlyConflicts = !onlyConflicts
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Section.entries.forEach { s ->
                FilterChip(selected = section == s, onClick = { section = if (section == s) null else s },
                    label = { Text(s.label) })
            }
            Confidence.entries.forEach { c ->
                FilterChip(selected = confidence == c, onClick = { confidence = if (confidence == c) null else c },
                    label = { Text(c.label) })
            }
            Difficulty.entries.forEach { d ->
                FilterChip(selected = difficulty == d, onClick = { difficulty = if (difficulty == d) null else d },
                    label = { Text(d.label) })
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = kaodianPrefix, onValueChange = { kaodianPrefix = it },
                label = { Text("考点前缀") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = paper, onValueChange = { paper = it },
                label = { Text("卷名") }, singleLine = true, modifier = Modifier.weight(1f),
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${filtered.size} 条", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterVertically))
            if (status == RecordStatus.INCOMPLETE && filtered.isNotEmpty()) {
                OutlinedButton(onClick = { vm.annotateBatch(filtered.take(20)) }) { Text("批量标注前 20 条") }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.uid }) { r -> RecordRow(r) { editing = r } }
        }
    }

    editing?.let { record ->
        DetailDialog(
            record = record,
            treeVersion = tree?.version,
            onDismiss = { editing = null },
            onSave = { vm.saveEdits(it); editing = null },
            onDelete = { vm.delete(record.uid); editing = null },
            onAnnotate = { stem -> vm.annotate(record, stem); editing = null },
            onResolve = { vm.resolveConflict(record.uid); editing = null },
        )
    }
}

@Composable
private fun TodoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun RecordRow(r: ErrorRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.id, style = MaterialTheme.typography.labelLarge)
                Badge(r.status.label, statusColor(r.status))
                if (r.confidence == Confidence.LUCKY) Badge("蒙对", MaterialTheme.colorScheme.secondary)
                if (r.verified == Verified.CONFLICT) Badge("冲突", MaterialTheme.colorScheme.error)
            }
            Text(r.src.paper, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary)
            r.kaodian?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            r.eye?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.formContext?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
                r.answer?.let { Text("→ $it", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun statusColor(status: RecordStatus) = when (status) {
    RecordStatus.ACTIVE -> MaterialTheme.colorScheme.primary
    RecordStatus.DORMANT -> MaterialTheme.colorScheme.secondary
    RecordStatus.ARCHIVED -> MaterialTheme.colorScheme.secondary
    RecordStatus.PENDING_ANSWER -> MaterialTheme.colorScheme.error
    RecordStatus.INCOMPLETE -> MaterialTheme.colorScheme.error
}

/** 详情抽屉：派生字段可编辑，原子字段与 form_rule 锁定。 */
@Composable
private fun DetailDialog(
    record: ErrorRecord,
    treeVersion: String?,
    onDismiss: () -> Unit,
    onSave: (ErrorRecord) -> Unit,
    onDelete: () -> Unit,
    onAnnotate: (String) -> Unit,
    onResolve: () -> Unit,
) {
    var eye by remember { mutableStateOf(record.eye.orEmpty()) }
    var formContext by remember { mutableStateOf(record.formContext.orEmpty()) }
    var answer by remember { mutableStateOf(record.answer.orEmpty()) }
    var note by remember { mutableStateOf(record.note.orEmpty()) }
    var difficulty by remember { mutableStateOf(record.difficulty) }
    var stem by remember { mutableStateOf(record.given.orEmpty()) }

    val eyeIssues = Validation.checkEye(eye.ifBlank { null })
    val ctxIssues = Validation.checkFormContext(formContext.ifBlank { null })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.id) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "${record.src.paper} · ${record.src.section.label} · 第${record.src.no}题第${record.src.slot}空 · " +
                        "${record.src.batch} · ${record.confidence.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                record.kaodian?.let {
                    Text("考点　$it", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Text("规则形态　${record.formRule ?: "—"}（由考点树带出，只读）",
                    style = MaterialTheme.typography.bodySmall)
                Text("树版本　${record.treeVersion ?: "—"}" +
                    if (treeVersion != null && record.treeVersion != treeVersion) "（当前 $treeVersion，待重映射）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)

                OutlinedTextField(
                    value = eye, onValueChange = { eye = it },
                    label = { Text("题眼（10-25 字，客观特征）") },
                    isError = eye.isNotBlank() && eyeIssues.isNotEmpty(),
                    supportingText = { Text(eyeIssues.firstOrNull()?.message ?: "${eye.length} 字") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = formContext, onValueChange = { formContext = it },
                    label = { Text("语境形态（≤20 字，可空）") },
                    isError = ctxIssues.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = answer, onValueChange = { answer = it },
                    label = { Text("答案") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Difficulty.entries.forEach { d ->
                        FilterChip(selected = difficulty == d, onClick = { difficulty = d },
                            label = { Text(d.label) })
                    }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("备注（不参与统计）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = stem, onValueChange = { stem = it },
                    label = { Text("题干 / 提示词（供 Agent 标注用）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAnnotate(stem) }, enabled = stem.isNotBlank()) {
                        Text("Agent 标注")
                    }
                    if (record.verified == Verified.CONFLICT) {
                        OutlinedButton(onClick = onResolve) { Text("人工确认") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    record.copy(
                        eye = eye.ifBlank { null },
                        formContext = formContext.ifBlank { null },
                        answer = answer.ifBlank { null },
                        note = note.ifBlank { null },
                        given = stem.ifBlank { record.given },
                        difficulty = difficulty,
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
