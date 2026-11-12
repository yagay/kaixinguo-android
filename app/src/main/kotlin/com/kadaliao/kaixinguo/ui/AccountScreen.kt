package com.kadaliao.kaixinguo.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadaliao.kaixinguo.KaixinguoApplication
import com.kadaliao.kaixinguo.account.AccountPhase
import com.kadaliao.kaixinguo.account.PassportPhase
import com.kadaliao.kaixinguo.account.OfficialSignInActivity

@Composable
fun AccountScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as KaixinguoApplication
    val account = app.account
    val passport = app.passport
    val follows = app.follows
    val state by account.state.collectAsState()
    val passportState by passport.state.collectAsState()
    val websiteHistory by account.websiteHistory.collectAsState()
    val recommendation by account.recommendations.collectAsState()
    val router = LocalRouter.current
    var loginMode by remember { mutableIntStateOf(0) }
    var phone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(passportState.qrExpiresAt, passportState.smsRetryAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    var askSignOut by remember { mutableStateOf(false) }

    val startOfficialLogin = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookie = result.data?.getStringExtra(OfficialSignInActivity.EXTRA_WEBSITE_SESSION)
            if (!cookie.isNullOrBlank()) {
                passport.cancel()
                account.signInWithOfficialWebsite(cookie)
            }
        }
    }
    if (askSignOut) {
        AlertDialog(
            onDismissRequest = { askSignOut = false },
            title = { Text("退出红果官网账号？") },
            text = { Text("只会删除本机加密保存的登录态，原有本机收藏和观看历史保持不变。") },
            confirmButton = {
                TextButton(onClick = { askSignOut = false; passport.cancel(); account.signOut() }) {
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
                            if (state.websiteSignedIn) {
                                Text("注意：网站账号认证不等于红果 App 的云端账号已连接。")
                                if (state.verifiedAtEpochMs > 0) {
                                    val checkedAt = remember(state.verifiedAtEpochMs) {
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                            .withZone(ZoneId.systemDefault())
                                            .format(Instant.ofEpochMilli(state.verifiedAtEpochMs))
                                    }
                                    Text("官网核验时间：" + checkedAt)
                                }
                            }
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
                            passport.cancel()
                            startOfficialLogin.launch(
                                Intent(context, OfficialSignInActivity::class.java),
                            )
                        },
                    ) { Text(if (state.websiteSignedIn) "重新登录官网" else "打开红果官网登录") }


                    Spacer(Modifier.height(12.dp))
                    Text("其他登录方式", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = loginMode == 0,
                            onClick = { if (loginMode != 0) { passport.cancel(); loginMode = 0 } },
                            label = { Text("扫码登录") },
                        )
                        FilterChip(
                            selected = loginMode == 1,
                            onClick = { if (loginMode != 1) { passport.cancel(); loginMode = 1 } },
                            label = { Text("短信验证码") },
                        )
                    }
                    if (loginMode == 0) {
                        val qr = passportState.qrImage
                        val bitmap = remember(qr) {
                            qr?.let { image ->
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                                if (bounds.outWidth in 100..1024 && bounds.outHeight in 100..1024) {
                                    BitmapFactory.decodeByteArray(image, 0, image.size)
                                } else null
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "红果账号登录二维码",
                                modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally),
                            )
                            if (passportState.qrExpiresAt > 0) {
                                val seconds = ((passportState.qrExpiresAt - now + 999) / 1000).coerceAtLeast(0)
                                Text("二维码有效期剩余 " + seconds + " 秒")
                            }
                        }
                        OutlinedButton(
                            onClick = passport::qrLogin,
                            enabled = !passportState.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (qr != null) "重新获取二维码" else "获取扫码二维码")
                        }
                    } else {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.take(32) },
                            label = { Text("手机号（大陆11位，或 +国家代码 空格 号码）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val waitSeconds = ((passportState.smsRetryAt - now + 999) / 1000).coerceAtLeast(0)
                        OutlinedButton(
                            onClick = { passport.sendSms(phone) },
                            enabled = !passportState.busy && waitSeconds == 0L,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (waitSeconds > 0) "请等待 " + waitSeconds + " 秒" else "发送登录验证码")
                        }
                        if (passportState.phase == PassportPhase.SMS_SENT ||
                            passportState.phase == PassportPhase.SMS_VERIFYING) {
                            Text("验证码已发送至 " + passportState.maskedPhone)
                            OutlinedTextField(
                                value = smsCode,
                                onValueChange = { smsCode = it.filter(Char::isDigit).take(8) },
                                label = { Text("短信验证码") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { val submitted = smsCode; smsCode = ""; passport.verifySms(submitted) },
                                enabled = smsCode.length in 4..8 && !passportState.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("验证并登录") }
                        }
                    }
                    if (passportState.message.isNotBlank()) {
                        Text(
                            passportState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (passportState.phase in listOf(
                        PassportPhase.QR_LOADING, PassportPhase.SMS_SENDING,
                        PassportPhase.SMS_VERIFYING,
                    )) {
                        CircularProgressIndicator()
                    }
                    if (passportState.phase != PassportPhase.IDLE) {
                        TextButton(onClick = { passport.cancel(); smsCode = "" }) {
                            Text("取消当前登录")
                        }
                    }

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
                    headlineContent = { Text("本机数据备份与恢复") },
                    supportingContent = {
                        Text("导出或手动合并本机收藏、观看历史和追更记录；不会包含账号登录态。")
                    },
                    trailingContent = {
                        TextButton(onClick = { router.open(Route.AccountBackup) }) {
                            Text("管理")
                        }
                    },
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
                    headlineContent = { Text("本机追更与更新检查") },
                    supportingContent = {
                        Text(
                            "已追更 " + (follows?.entries?.size ?: 0) + " 部 · 待查看更新 " +
                                (follows?.unreadCount ?: 0) + " 部；使用匿名剧集数据，不会上传账号。",
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { router.open(Route.LocalFollows) }) { Text("查看") }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("红果账号个性化推荐") },
                    supportingContent = {
                        Text(
                            when {
                                !state.websiteSignedIn -> "当前未登录官网，刷剧使用普通推荐"
                                recommendation.ready -> "已获取网关验证的账号推荐。刷剧的「推荐」自动使用独立账号列表。"
                                recommendation.message.isNotBlank() ->
                                    recommendation.message + "；仍显示普通推荐，不冒充账号结果。"
                                else -> "官网登录不能代替 App 业务身份。进入刷剧后将只读验证账号推荐。"
                            },
                        )
                    },
                    trailingContent = {
                        TextButton(
                            enabled = state.websiteSignedIn,
                            onClick = {
                                account.retryAccountRecommendations()
                                router.select(Tab.REELS)
                            },
                        ) { Text("验证") }
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

            item { HorizontalDivider() }
            item {
                ListItem(
                    headlineContent = { Text("观看历史与差异对照") },
                    supportingContent = {
                        Text("分别查看本机历史与官网历史（只读实验性接口），对照缺少的作品。" +
                            "不会自动覆盖或上传观看记录。")
                    },
                    trailingContent = {
                        TextButton(onClick = { router.open(Route.AccountHistory) }) {
                            Text("查看")
                        }
                    },
                )
            }
            item {
                Text(
                    "支持网页内人工登录、扫码和官网验证码登录；密码与验证码不会保存到应用账号存储。" +
                        "若平台要求滑块等验证，请直接在官方网页内完成。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
