package com.kadaliao.kaixinguo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadaliao.kaixinguo.KaixinguoApplication

/** Functional local follow list; not a cloud account subscription. */
@Composable
fun LocalFollowsScreen() {
    val app = LocalContext.current.applicationContext as KaixinguoApplication
    val follows = app.follows ?: return
    val router = LocalRouter.current
    val rows = follows.entries

    Scaffold(topBar = { BackBar("本机追更") }, contentWindowInsets = WindowInsets(0)) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "仅保存在本机，不是红果账号云端追更。点击「检查更新」后读取已关注作品的最新集数；" +
                        "不会自动后台轮询或发送系统通知。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "已追更 " + rows.size + " 部 · " + follows.unreadCount + " 部有更新",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(8.dp),
                    )
                    Button(
                        enabled = !follows.checking && rows.isNotEmpty(),
                        onClick = follows::checkUpdates,
                    ) {
                        Text(if (follows.checking) "正在检查…" else "检查更新")
                    }
                }
            }
            if (follows.failedCount > 0 && !follows.checking) {
                item {
                    Text(
                        "成功检查 " + follows.checkedCount + " 部，失败 " + follows.failedCount +
                            " 部。失败项目保留旧状态，可再次检查。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        "还没有追更的短剧。打开任意剧集详情，点击「加入本机追更」。",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(rows, key = { it.id }) { entry ->
                Column {
                    ListItem(
                        headlineContent = { Text(entry.title, maxLines = 2) },
                        supportingContent = {
                            Text(
                                if (entry.hasNewEpisodes)
                                    "更新至第 " + entry.latestEpisodes + " 集（之前 " +
                                        entry.seenEpisodes + " 集）"
                                else if (entry.latestEpisodes > 0)
                                    "目前共 " + entry.latestEpisodes + " 集 · 无未读更新"
                                else "集数未知，等待首次检查",
                            )
                        },
                        trailingContent = {
                            Column {
                                TextButton(onClick = {
                                    follows.markRead(entry.id)
                                    router.open(Route.Series(entry.id))
                                }) { Text("详情") }
                                TextButton(onClick = { follows.remove(entry.id) }) { Text("取消追更") }
                            }
                        },
                    )
                    if (entry.hasNewEpisodes) {
                        TextButton(
                            modifier = Modifier.padding(start = 12.dp),
                            onClick = { follows.markRead(entry.id) },
                        ) { Text("标记为已读") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
