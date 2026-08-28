package com.ecs.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ecs.core.model.ApiEndpoint

/**
 * Key 输入框。设置页与录入页共用，都写回同一个 saveEndpoint，
 * 所以两处显示的永远是同一份数据。
 *
 * 缺 Key 时展开并标红——那是必须马上填的东西；填好后缩成一行，点开仍可改。
 * 锁定开关防误触：已经能用的配置不该因为手滑就被改坏。
 */
@Composable
fun ApiKeyField(
    endpoint: ApiEndpoint,
    onSave: (ApiEndpoint) -> Unit,
    locked: Boolean = false,
    onLockChange: ((Boolean) -> Unit)? = null,
) {
    var expanded by remember(endpoint.id, endpoint.configured) {
        mutableStateOf(endpoint.apiKey.isBlank())
    }
    var key by remember(endpoint.id, endpoint.apiKey) { mutableStateOf(endpoint.apiKey) }

    // 锁上就只剩一行状态，连输入框都不给，手滑改不到
    val editable = !locked || endpoint.apiKey.isBlank()

    if (!expanded || !editable) {
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (endpoint.apiKey.isBlank()) "还没填 Key" else "Key 已配置",
                style = MaterialTheme.typography.labelSmall,
                color = if (endpoint.apiKey.isBlank()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            if (editable) {
                TextButton(onClick = { expanded = true }) { Text("修改") }
            }
            LockToggle(locked, onLockChange)
        }
        return
    }

    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("API Key") },
        singleLine = true,
        isError = endpoint.apiKey.isBlank() && key.isBlank(),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = {
                onSave(endpoint.copy(apiKey = key.trim()))
                expanded = false
            },
            enabled = key.trim() != endpoint.apiKey,
        ) { Text("保存") }
        if (endpoint.apiKey.isNotBlank()) {
            TextButton(onClick = { key = endpoint.apiKey; expanded = false }) { Text("取消") }
        }
        LockToggle(locked, onLockChange)
    }
}

@Composable
private fun LockToggle(locked: Boolean, onLockChange: ((Boolean) -> Unit)?) {
    if (onLockChange == null) return
    IconButton(onClick = { onLockChange(!locked) }) {
        Icon(
            imageVector = if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            contentDescription = if (locked) "已锁定，点一下解锁" else "未锁定，点一下锁上",
            tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
    }
}
