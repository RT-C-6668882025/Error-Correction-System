package com.ecs.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecs.data.backup.BackupManager
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar
import com.ecs.ui.component.SectionCard

@Composable
fun ExportScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsState()
    val exports by vm.exports.collectAsState()

    LaunchedEffect(Unit) { vm.refreshExports() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)
        SectionCard("导出包") {
            Text(
                "README.md（≤120 行）+ data.json + data.csv + directions.json。" +
                    "交给一个全新模型会话，不加任何提示词，它应当能读懂三级结构并接着往下汇总。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(onClick = { vm.exportNow() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("立即导出")
            }
        }
        SectionCard("自动备份") {
            Text(
                "每新增 ${BackupManager.EVERY} 条自动导出一次，保留最近 ${BackupManager.KEEP} 份。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            exports.forEach {
                Text(it.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Text(it.absolutePath, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            if (exports.isEmpty()) {
                Text("还没有备份。", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        MessageBar(vm)
    }
}
