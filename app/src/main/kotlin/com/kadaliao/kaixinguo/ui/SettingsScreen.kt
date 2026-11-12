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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadaliao.kaixinguo.KaixinguoApplication
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Settings stay under Mine. There is no extra bottom navigation item. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as KaixinguoApplication
    val settings = app.settings
    val router = LocalRouter.current
    val account = app.account
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val createReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            val snapshot = Diagnostics.snapshot(context, app)
            val includeLogcat = settings.includeProcessLogcat
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        Diagnostics.writeReport(context, uri, snapshot, includeLogcat)
                    }
                }
                snackbar.showSnackbar(
                    if (result.isSuccess) "诊断日志已导出" else "导出失败，请检查文件保存位置",
                )
                settings.setIncludeProcessLogcat(false)
            }
        }
    }

    Scaffold(
        topBar = { BackBar("设置") },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text("账号与同步") },
                    supportingContent = {
                        Text(
                            if (account.state.value.websiteSignedIn) "官网已登录 · 云端数据待接入"
                            else "官网登录、登录态管理与云端功能状态",
                        )
                    },
                    leadingContent = {
                        androidx.compose.material3.Icon(
                            Icons.Outlined.ManageAccounts, contentDescription = null,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        TextButton(onClick = { router.open(Route.Account) }) { Text("进入") }
                    },
                )
            }
            item { HorizontalDivider() }
            item { SettingsHeading("应用设置") }
            item {
                ListItem(
                    headlineContent = { Text("启动时恢复播放页面") },
                    supportingContent = { Text("重新打开应用时，恢复上次正在观看的剧集") },
                    trailingContent = {
                        Switch(
                            checked = settings.resumeOnLaunch,
                            onCheckedChange = settings::setResumeOnLaunch,
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("自动画中画") },
                    supportingContent = { Text("切换应用时按系统能力进入画中画；关闭后按现有规则暂停") },
                    trailingContent = {
                        Switch(
                            checked = settings.automaticPictureInPicture,
                            onCheckedChange = settings::setAutomaticPictureInPicture,
                        )
                    },
                )
            }
            item { HorizontalDivider() }
            item { SettingsHeading("诊断与日志") }
            item {
                ListItem(
                    headlineContent = { Text("导出诊断日志") },
                    supportingContent = {
                        Text("包含应用状态、刷剧与播放器事件、账号状态及错误阶段；不收集账号凭据")
                    },
                    leadingContent = {
                        androidx.compose.material3.Icon(
                            Icons.Outlined.BugReport, contentDescription = null,
                        )
                    },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                val stamp = LocalDateTime.now().format(
                                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"),
                                )
                                createReport.launch("Kaixinguo-diagnostic-" + stamp + ".txt")
                            },
                        ) { Text("导出") }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("附加当前应用进程 Logcat") },
                    supportingContent = {
                        Text(
                            "可帮助排查闪退和 SDK 问题，但系统日志可能含敏感信息。" +
                                "仅本次导出有效，分享前请检查。",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.includeProcessLogcat,
                            onCheckedChange = settings::setIncludeProcessLogcat,
                        )
                    },
                )
            }
            item {
                TextButton(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onClick = {
                        Diagnostics.clear()
                        scope.launch { snackbar.showSnackbar("应用诊断事件已清空") }
                    },
                ) { Text("清空应用诊断事件") }
            }
            item { HorizontalDivider() }
            item { SettingsHeading("关于") }
            item {
                ListItem(
                    headlineContent = { Text("Kaixinguo") },
                    supportingContent = { Text("账号模块与匿名播放相互独立；仅应用内完成的功能会显示为可用") },
                )
            }
        }
    }
}

@Composable
private fun SettingsHeading(value: String) {
    Text(
        value,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
