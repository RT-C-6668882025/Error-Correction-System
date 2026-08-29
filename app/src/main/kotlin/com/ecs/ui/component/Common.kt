package com.ecs.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun StatRow(vararg pairs: Pair<String, String>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        pairs.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 报告与倒推表都是 Markdown 文本，等宽显示比半吊子渲染更可读。 */
@Composable
fun MarkdownBlock(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(12.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

/**
 * 提示条。
 *
 * 注意摆放位置：它必须排在一个**没有吃满高度**的兄弟组件旁边。
 * 之前原题页和复习页是 `Column { …; LazyColumn(fillMaxSize()); MessageBar() }`，
 * LazyColumn 把剩余高度全占了，这条提示的高度是 0——所有的成功与失败都说给了空气。
 * 现在那两处的 LazyColumn 用 `weight(1f)`。
 */
@Composable
fun MessageBar(vm: com.ecs.ui.AppViewModel) {
    val message by vm.message.collectAsState()
    val bad by vm.messageBad.collectAsState()
    val text = message ?: return

    SectionCard(if (bad) "出问题了" else "提示") {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        OutlinedButton(onClick = { vm.dismissMessage() }, modifier = Modifier.padding(top = 8.dp)) {
            Text("知道了")
        }
    }
}

@Composable
fun BusyBar(label: String?) {
    if (label != null) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
