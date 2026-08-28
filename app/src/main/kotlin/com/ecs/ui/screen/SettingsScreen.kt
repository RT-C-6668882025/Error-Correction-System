package com.ecs.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.SectionCard

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsState()
    val storedKey by vm.apiKey.collectAsState()
    val tree by vm.tree.collectAsState()
    var key by remember(storedKey) { mutableStateOf(storedKey) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)
        SectionCard("模型接入") {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(onClick = { vm.setApiKey(key.trim()) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("保存")
            }
            Text(
                "识别、标注、报告生成需要联网，其余功能全部离线可用。",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        SectionCard("考点树") {
            Text(
                tree?.let { "${it.version} · ${it.liveNodes.size} 个末端节点" } ?: "尚未生成",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "生成 120-180 个末端节点，每个带规则形态与向量。没有树就没有 form_rule，标注无从谈起。",
                style = MaterialTheme.typography.labelSmall,
            )
            Button(onClick = { vm.generateTree() }, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (tree == null) "生成考点树" else "重新生成（会换版本）")
            }
        }
        MessageBar(vm)
    }
}
