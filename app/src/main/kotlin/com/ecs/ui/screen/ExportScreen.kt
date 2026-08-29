package com.ecs.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ecs.data.backup.BackupManager
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar
import com.ecs.ui.component.SectionCard
import java.io.File

@Composable
fun ExportScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsState()
    val exports by vm.exports.collectAsState()

    LaunchedEffect(Unit) { vm.refreshExports() }

    fun share(zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享导出包"))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BusyBar(busy)
        SectionCard("导出包") {
            Text(
                "README.md（≤120 行）+ data.json + data.csv + directions.json。" +
                    "交给一个全新模型会话，不加任何提示词，它应当能读懂三级结构并接着往下汇总。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            // 这句是实话，不是免责声明：不说清楚，下次换手机数据就没了
            Text(
                "导出包存在应用私有目录，卸载会一起删掉。换手机或重装前，先「分享」送出去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = { vm.exportNow() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("立即导出")
            }
        }
        SectionCard("已有的备份") {
            Text(
                "每新增 ${BackupManager.EVERY} 条自动导出一次，保留最近 ${BackupManager.KEEP} 份。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (exports.isEmpty()) {
                Text(
                    "还没有备份。点上面的「立即导出」。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            exports.forEach { dir ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(dir.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${dir.listFiles()?.size ?: 0} 个文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    OutlinedButton(onClick = { vm.shareExport(dir, ::share) }) { Text("分享") }
                }
            }
        }
        SectionCard("怎么导回来") {
            Text(
                "分享出去的是一个 zip，解开里面的 data.json（或 data.csv），" +
                    "在录入页「导入」里选它就能回到库里。原始数据与分析结果都在，" +
                    "方向要重新汇总一次。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        MessageBar(vm)
    }
}
