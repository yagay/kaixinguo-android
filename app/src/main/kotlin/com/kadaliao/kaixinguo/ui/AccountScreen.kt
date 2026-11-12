package com.kadaliao.kaixinguo.ui

import android.app.Activity
import android.content.Intent
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadaliao.kaixinguo.KaixinguoApplication
import com.kadaliao.kaixinguo.account.AccountPhase
import com.kadaliao.kaixinguo.account.OfficialSignInActivity

@Composable
fun AccountScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as KaixinguoApplication
    val account = app.account
    val state by account.state.collectAsState()
    var askSignOut by remember { mutableStateOf(false) }

    val startOfficialLogin = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookie = result.data?.getStringExtra(OfficialSignInActivity.EXTRA_WEBSITE_SESSION)
            if (!cookie.isNullOrBlank()) account.signInWithOfficialWebsite(cookie)
        }
    }
    if (askSignOut) {
        AlertDialog(
            onDismissRequest = { askSignOut = false },
            title = { Text("退出红果官网账号？") },
            text = { Text("只会删除本机加密保存的登录态，原有本机收藏和观看历史保持不变。") },
            confirmButton = {
                TextButton(onClick = { askSignOut = false; account.signOut() }) {
                    Text("退出登录")
                }
            },
            dismissButton = {
                TextButton(onClick = { askSignOut = false }) { Text("取消") }
            },
        )
    }

    Scaffold(topBar = { BackBar("账号与同步") }, contentWindowInsets = WindowInsets(0)) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            when (state.phase) {
                                AccountPhase.WEBSITE_SIGNED_IN -> state.profile?.nickname ?: "官网用户"
                                AccountPhase.VERIFYING -> "正在验证官网账号"
                                AccountPhase.RESTORING -> "正在恢复登录态"
                                AccountPhase.EXPIRED -> "官网登录状态已失效"
                                AccountPhase.UNAVAILABLE -> "账号暂不可用"
                                AccountPhase.SIGNED_OUT -> "尚未登录"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (state.message.isNotBlank()) Text(state.message)
                            if (state.websiteSignedIn) Text(
                                "注意：网站账号认证不等于红果 App 的云端账号已连接。",
                            )
                        }
                    },
                    trailingContent = {
                        if (state.phase in setOf(AccountPhase.VERIFYING, AccountPhase.RESTORING)) {
                            CircularProgressIndicator()
                        }
                    },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.phase != AccountPhase.VERIFYING &&
                            state.phase != AccountPhase.RESTORING,
                        onClick = {
                            startOfficialLogin.launch(
                                Intent(context, OfficialSignInActivity::class.java),
                            )
                        },
                    ) { Text(if (state.websiteSignedIn) "重新登录官网" else "打开红果官网登录") }

                    if (state.hasSavedSession) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = account::recheck,
                            enabled = state.phase != AccountPhase.RESTORING &&
                                state.phase != AccountPhase.VERIFYING,
                        ) { Text("重新验证保存的登录态") }
                    }
                    if (state.hasSavedSession || state.websiteSignedIn) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { askSignOut = true },
                        ) { Text("退出账号并清除登录态") }
                    }
                }
            }
            item { HorizontalDivider() }
            item {
                Text(
                    "账号功能",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("官网账号资料") },
                    supportingContent = {
                        Text(
                            if (state.websiteSignedIn) "已验证网站登录，会话已加密保存在本机"
                            else "使用红果官网完成登录后可查看登录状态",
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("云端收藏、历史、点赞和播放进度") },
                    supportingContent = {
                        Text(
                            "尚未连接：当前 openhongguo SDK 只支持匿名内容，" +
                                "需要另行验证红果 App 业务身份和云端请求签名。" +
                                "本机收藏及观看历史继续正常使用，不会自动上传。",
                        )
                    },
                )
            }
            item {
                Text(
                    "此页面只通过红果官网进行人工验证；不会索取或保存账号密码、短信验证码。" +
                        "若平台要求滑块等验证，请直接在官网页面完成。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
