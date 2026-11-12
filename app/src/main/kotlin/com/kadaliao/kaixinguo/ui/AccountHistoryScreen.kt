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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadaliao.kaixinguo.KaixinguoApplication
import com.kadaliao.kaixinguo.account.compareHistory

@Composable
fun AccountHistoryScreen() {
    val app = LocalContext.current.applicationContext as KaixinguoApplication
    val model = LocalModel.current
    val router = LocalRouter.current
    val account = app.account
    val state by account.state.collectAsState()
    val website by account.websiteHistory.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    val local = model.history.list
    val remote = website.records
    val comparison = remember(local, remote) {
        compareHistory(local.map { it.seriesID }, remote.map { it.series.seriesId })
    }

    Scaffold(topBar = { BackBar("观看历史") }, contentWindowInsets = WindowInsets(0)) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item {
                Text(
                    "本机历史与官网历史完全独立。官网历史使用未完成真机验收的只读接口，" +
                        "不等于红果 App 的完整云端历史；比较仅按剧集 ID，不会推断进度的新旧。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = tab == 0, onClick = { tab = 0 },
                        label = { Text("本机 " + local.size) },
                    )
                    FilterChip(
                        selected = tab == 1, onClick = { tab = 1 },
                        label = { Text("官网 " + if (website.loaded) remote.size.toString() else "—") },
                    )
                    FilterChip(
                        selected = tab == 2, onClick = { tab = 2 },
                        label = { Text("差异") },
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it.take(100) },
                    label = { Text("筛选剧名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (tab == 0) {
                val visible = local.filter { it.title.contains(filter, ignoreCase = true) }
                if (visible.isEmpty()) item { Text("本机没有符合条件的观看记录", Modifier.padding(16.dp)) }
                items(visible, key = { "local:" + it.seriesID }) { watch ->
                    ListItem(
                        headlineContent = { Text(watch.title.ifBlank { "短剧" }) },
                        supportingContent = {
                            Text("第 " + watch.episode + " 集 · 本机记录")
                        },
                        trailingContent = {
                            TextButton(onClick = { model.playSeason(watch.seriesID) }) {
                                Text("继续")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (website.loaded) "官网读取到 " + remote.size + " 部"
                            else "尚未读取官网观看历史",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(8.dp),
                        )
                        Button(
                            onClick = account::loadWebsiteHistory,
                            enabled = state.websiteSignedIn && !website.loading,
                        ) { Text(if (website.loading) "读取中…" else "读取官网历史") }
                    }
                }
                if (website.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                if (website.error.isNotBlank()) {
                    item {
                        Text(
                            website.error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                if (tab == 1) {
                    val visible = remote.filter {
                        it.series.title.contains(filter, ignoreCase = true)
                    }
                    if (website.loaded && visible.isEmpty()) {
                        item { Text("官网返回的结果中没有符合条件的作品", Modifier.padding(16.dp)) }
                    }
                    items(visible, key = { "website:" + it.series.seriesId }) { watch ->
                        ListItem(
                            headlineContent = { Text(watch.series.title) },
                            supportingContent = {
                                Text("第 " + watch.episode + " 集 · 官网读取（实验性）")
                            },
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        router.open(Route.Series(watch.series.seriesId))
                                    },
                                ) { Text("详情") }
                            },
                        )
                        HorizontalDivider()
                    }
                } else {
                    if (!website.loaded) {
                        item {
                            Text(
                                "请先登录并成功读取官网历史，才能比较差异。",
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        item {
                            Text(
                                "两边都有 " + comparison.shared.size + " 部 · 仅本机 " +
                                    comparison.localOnly.size + " 部 · 仅官网 " +
                                    comparison.websiteOnly.size + " 部",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                        val missingLocal = remote.filter {
                            it.series.seriesId in comparison.websiteOnly &&
                                it.series.title.contains(filter, ignoreCase = true)
                        }
                        val missingWebsite = local.filter {
                            it.seriesID in comparison.localOnly &&
                                it.title.contains(filter, ignoreCase = true)
                        }
                        if (missingLocal.isNotEmpty()) {
                            item { Text("仅官网有记录", modifier = Modifier.padding(8.dp)) }
                            items(missingLocal, key = { "compare-web:" + it.series.seriesId }) { item ->
                                ListItem(
                                    headlineContent = { Text(item.series.title) },
                                    supportingContent = { Text("第 " + item.episode + " 集") },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            router.open(Route.Series(item.series.seriesId))
                                        }) { Text("详情") }
                                    },
                                )
                            }
                        }
                        if (missingWebsite.isNotEmpty()) {
                            item { Text("仅本机有记录", modifier = Modifier.padding(8.dp)) }
                            items(missingWebsite, key = { "compare-local:" + it.seriesID }) { item ->
                                ListItem(
                                    headlineContent = { Text(item.title) },
                                    supportingContent = { Text("第 " + item.episode + " 集") },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            router.open(Route.Series(item.seriesID))
                                        }) { Text("详情") }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
