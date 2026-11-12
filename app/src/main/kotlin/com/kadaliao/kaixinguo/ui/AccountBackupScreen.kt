package com.kadaliao.kaixinguo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadaliao.kaixinguo.KaixinguoApplication
import com.kadaliao.kaixinguo.account.LocalDataBackup
import com.kadaliao.kaixinguo.account.LocalDataBackupCodec
import com.kadaliao.kaixinguo.account.previewLocalImport
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Explicit user-owned local data export/import. A website account session
 * never enters either file. Nothing is sent to Hongguo.
 */
@Composable
fun AccountBackupScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as KaixinguoApplication
    val model = LocalModel.current
    val follows = app.follows
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<LocalDataBackup?>(null) }
    var preferNewer by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null && !working) {
            val snapshot = LocalDataBackup(
                history = model.history.list.toList(),
                favorites = model.favorites.list.toList(),
                follows = follows?.entries?.toList().orEmpty(),
                exportedAt = Instant.now(),
            )
            working = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val content = LocalDataBackupCodec.encode(snapshot)
                        context.contentResolver.openOutputStream(uri, "wt")
                            ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                            ?: throw IllegalStateException("Cannot write selected document")
                    }
                }
                working = false
                if (result.isSuccess) Diagnostics.record("account", "local_backup_exported")
                snackbar.showSnackbar(
                    if (result.isSuccess) "本机数据备份已导出" else "导出失败，请检查保存位置或数据大小",
                )
            }
        }
    }
    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !working) {
            working = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ByteArrayOutputStream()
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Cannot read selected document")
                        input.use { stream ->
                            val chunk = ByteArray(8192)
                            while (true) {
                                val n = stream.read(chunk)
                                if (n < 0) break
                                if (bytes.size() + n > LocalDataBackupCodec.MAX_FILE_BYTES) {
                                    throw IllegalArgumentException("Backup file too large")
                                }
                                bytes.write(chunk, 0, n)
                            }
                        }
                        LocalDataBackupCodec.decode(bytes.toString(Charsets.UTF_8.name()))
                    }
                }
                working = false
                result.fold(
                    onSuccess = {
                        pending = it
                        preferNewer = false
                        Diagnostics.record("account", "local_backup_previewed")
                    },
                    onFailure = {
                        Diagnostics.record("account", "local_backup_rejected")
                        snackbar.showSnackbar("备份无法读取、版本不支持或包含无效记录")
                    },
                )
            }
        }
    }

    val preview = pending?.let { backup ->
        previewLocalImport(
            backup,
            model.history.list.map { it.seriesID }.toSet(),
            model.favorites.list.map { it.seriesID }.toSet(),
            follows?.entries?.map { it.id }?.toSet().orEmpty(),
        )
    }
    if (pending != null && preview != null) {
        val data = pending!!
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("确认导入本机数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("历史：新增 " + preview.historyNew + "，已存在 " + preview.historyExisting)
                    Text("收藏：新增 " + preview.favoritesNew + "，已存在 " + preview.favoritesExisting)
                    Text("追更：新增 " + preview.followsNew + "，已存在 " + preview.followsExisting)
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("更新较旧的本机记录") },
                        supportingContent = {
                            Text("默认关闭。开启后，仅允许备份更新时间严格晚于本机记录的项目覆盖对应条目。")
                        },
                        trailingContent = {
                            Switch(checked = preferNewer, onCheckedChange = { preferNewer = it })
                        },
                    )
                    Text(
                        "不会删除本机其他记录，也不会导入登录信息或向红果服务器写入任何数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val history = model.history.importEntries(data.history, preferNewer)
                    val favorites = model.favorites.importEntries(data.favorites, preferNewer)
                    val followsResult = follows?.importEntries(data.follows, preferNewer) ?: (0 to 0)
                    pending = null
                    Diagnostics.record("account", "local_backup_applied")
                    scope.launch {
                        snackbar.showSnackbar(
                            "已添加 " + (history.added + favorites.added + followsResult.first) +
                                " 项，更新 " +
                                (history.updated + favorites.updated + followsResult.second) + " 项",
                        )
                    }
                }) { Text("确认导入") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = { BackBar("本机数据备份与恢复") },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "可备份本机收藏、观看进度和追更列表，用于换机或修复前保存数据。" +
                        "不会导出官网账号登录态，也不会同步到红果云端。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("本机观看历史") },
                    supportingContent = { Text(model.history.list.size.toString() + " 部") },
                )
                ListItem(
                    headlineContent = { Text("本机收藏") },
                    supportingContent = { Text(model.favorites.list.size.toString() + " 部") },
                )
                ListItem(
                    headlineContent = { Text("本机追更") },
                    supportingContent = { Text((follows?.entries?.size ?: 0).toString() + " 部") },
                )
            }
            item {
                Button(
                    onClick = {
                        val date = LocalDateTime.now().format(
                            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"),
                        )
                        save.launch("Kaixinguo-local-backup-" + date + ".json")
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导出本机数据") }
            }
            item {
                OutlinedButton(
                    onClick = { open.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("选择备份文件并预览") }
            }
            item { HorizontalDivider() }
            item {
                Text(
                    "隐私提醒：导出文件包含剧集名称、观看时间和进度等个人观看记录，" +
                        "请自行妥善保管。支持的 JSON 备份最大 2 MB；导入前会验证格式和数量，" +
                        "并需要再次手动确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
