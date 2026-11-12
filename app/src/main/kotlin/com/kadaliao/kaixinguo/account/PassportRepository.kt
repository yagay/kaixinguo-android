package com.kadaliao.kaixinguo.account

import android.content.Context
import com.hongguo.account.protocol.AccountCredentials
import com.hongguo.account.protocol.AccountProtocolException
import com.hongguo.account.protocol.AppPhoneLogin
import com.hongguo.account.protocol.PhoneLogin
import okhttp3.OkHttpClient
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PassportPhase {
    IDLE, QR_LOADING, QR_WAITING, QR_SCANNED, QR_EXPIRED, SMS_SENDING, SMS_SENT,
    SMS_VERIFYING, COMPLETED, FAILED,
}

data class PassportUiState(
    val phase: PassportPhase = PassportPhase.IDLE,
    val qrImage: ByteArray? = null,
    val qrExpiresAt: Long = 0,
    val smsRetryAt: Long = 0,
    val maskedPhone: String = "",
    val message: String = "",
) {
    val busy: Boolean get() = phase in setOf(
        PassportPhase.QR_LOADING, PassportPhase.SMS_SENDING, PassportPhase.SMS_VERIFYING,
    )
}

/**
 * Ephemeral login controller. Passwords and SMS codes are never persisted;
 * only verified cookies are handed to AccountRepository for encrypted storage.
 */
class PassportRepository(
    context: Context,
    private val onVerifiedCookie: (String) -> Unit,
    private val onVerifiedAppCredentials: (AccountCredentials) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("kxg_passport_throttle", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(PassportUiState())
    val state: StateFlow<PassportUiState> = mutable.asStateFlow()
    private var generation = 0L
    private var client: WebPassportClient? = null
    private var appClient: AppPhoneLogin? = null

    private fun supersede(): Pair<Long, WebPassportClient> {
        generation++
        client?.cancel()
        appClient?.cancel()
        appClient = null
        return generation to WebPassportClient().also { client = it }
    }

    private fun supersedeSms(): Pair<Long, AppPhoneLogin> {
        generation++
        client?.cancel()
        client = null
        appClient?.cancel()
        return generation to AppPhoneLogin(OkHttpClient()).also { appClient = it }
    }

    fun qrLogin() {
        val (token, auth) = supersede()
        mutable.value = PassportUiState(phase = PassportPhase.QR_LOADING, message = "正在生成登录二维码…")
        Diagnostics.record("passport", "qr_started")
        scope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) { auth.startQr() }
                if (generation != token) return@launch
                mutable.value = PassportUiState(
                    phase = PassportPhase.QR_WAITING,
                    qrImage = challenge.image,
                    qrExpiresAt = challenge.expiresAt,
                    message = "请使用红果 App 扫码并确认",
                )
                while (isActive && generation == token && System.currentTimeMillis() < challenge.expiresAt) {
                    delay(1800)
                    if (generation != token) return@launch
                    val status = withContext(Dispatchers.IO) { auth.pollQr() }
                    if (generation != token) return@launch
                    when (status) {
                        WebPassportClient.QrStatus.SCANNED -> {
                            mutable.value = mutable.value.copy(
                                phase = PassportPhase.QR_SCANNED,
                                message = "二维码已扫描，请在手机上确认",
                            )
                        }
                        WebPassportClient.QrStatus.CONFIRMED -> {
                            val cookie = withContext(Dispatchers.IO) { auth.sessionCookie() }
                            if (generation != token) return@launch
                            mutable.value = PassportUiState(
                                phase = PassportPhase.COMPLETED,
                                message = "扫码已确认，正在核验网站账号",
                            )
                            Diagnostics.record("passport", "qr_confirmed")
                            onVerifiedCookie(cookie)
                            auth.cancel()
                            client = null
                            return@launch
                        }
                        WebPassportClient.QrStatus.EXPIRED -> break
                        WebPassportClient.QrStatus.PENDING -> Unit
                    }
                }
                if (generation == token) {
                    mutable.value = PassportUiState(
                        phase = PassportPhase.QR_EXPIRED,
                        message = "二维码已过期，请手动刷新",
                    )
                    Diagnostics.record("passport", "qr_expired")
                }
            } catch (failure: Exception) {
                if (generation == token) failureState("qr_failed", failure)
            }
        }
    }

    fun sendSms(number: String) {
        val cooldown = prefs.getLong("retry_at", 0)
        if (System.currentTimeMillis() < cooldown) {
            mutable.value = PassportUiState(
                phase = PassportPhase.FAILED,
                smsRetryAt = cooldown,
                message = "短信发送太频繁，请按倒计时稍后重试",
            )
            return
        }
        try { PhoneLogin.parse(number) } catch (e: IllegalArgumentException) {
            mutable.value = PassportUiState(
                phase = PassportPhase.FAILED,
                message = e.message.orEmpty().take(120),
            )
            return
        }
        val (token, auth) = supersedeSms()
        // Persist before the first network packet to prevent duplicate sends
        // after a timeout, process death or repeated taps.
        val throttle = System.currentTimeMillis() + 60000L
        prefs.edit().putLong("retry_at", throttle).commit()
        mutable.value = PassportUiState(
            phase = PassportPhase.SMS_SENDING,
            smsRetryAt = throttle,
            message = "正在通过红果 App 认证接口申请验证码…",
        )
        Diagnostics.record("passport", "sms_app_requested")
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) { auth.send(number) }
                if (generation != token) return@launch
                val retryAt = System.currentTimeMillis() +
                    receipt.retrySeconds.coerceIn(30, 3600) * 1000L
                prefs.edit().putLong("retry_at", retryAt).apply()
                mutable.value = PassportUiState(
                    phase = PassportPhase.SMS_SENT,
                    smsRetryAt = retryAt,
                    maskedPhone = receipt.maskedMobile,
                    message = "验证码已申请，请按短信输入；不会自动重新发送",
                )
                Diagnostics.record("passport", "sms_app_sent")
            } catch (e: Exception) {
                if (generation == token) failureState("sms_failed", e)
            }
        }
    }

    fun verifySms(code: String) {
        val auth = appClient ?: return
        if (mutable.value.phase != PassportPhase.SMS_SENT) return
        val token = generation
        val before = mutable.value
        mutable.value = before.copy(
            phase = PassportPhase.SMS_VERIFYING,
            message = "正在向红果 App 认证接口核验验证码…",
        )
        scope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) { auth.verify(code) }
                if (generation != token) return@launch
                mutable.value = PassportUiState(
                    phase = PassportPhase.COMPLETED,
                    message = "App 验证码已确认，正在验证账号业务身份",
                )
                Diagnostics.record("passport", "sms_app_confirmed")
                onVerifiedAppCredentials(credentials)
                auth.cancel()
                appClient = null
            } catch (e: PhoneLogin.Failure) {
                if (generation != token) return@launch
                mutable.value = before.copy(
                    phase = if (e.canRetryCode) PassportPhase.SMS_SENT else PassportPhase.FAILED,
                    message = safeMessage(e),
                )
                Diagnostics.record("passport", if (e.needsWebsite)
                    "sms_app_challenge_required" else "sms_app_code_rejected")
            } catch (e: Exception) {
                if (generation == token) failureState("sms_verify_failed", e)
            }
        }
    }

    fun cancel() {
        generation++
        client?.cancel()
        client = null
        appClient?.cancel()
        appClient = null
        mutable.value = PassportUiState()
        Diagnostics.record("passport", "cancelled")
    }

    private fun failureState(event: String, error: Exception) {
        mutable.value = mutable.value.copy(
            phase = PassportPhase.FAILED,
            qrImage = null,
            message = safeMessage(error),
        )
        Diagnostics.record("passport", event)
        Diagnostics.record("passport", when (error) {
            is PhoneLogin.Failure -> if (error.needsWebsite)
                "sms_app_challenge_required" else "sms_app_rejected"
            is AccountProtocolException -> "sms_app_transport_rejected"
            else -> passportErrorClass(error)
        })
    }

    private fun safeMessage(error: Exception): String = when (error) {
        is PhoneLogin.Failure -> error.message.orEmpty().take(200)
        is AccountProtocolException -> error.message.orEmpty().take(200)
        is PassportException -> error.message.orEmpty()
        is PassportHttpException -> when (error.status) {
            401, 403 -> "官网拒绝本次短信登录请求（HTTP ${error.status}）。可通过页面上方的「打开红果官网登录」完成平台验证"
            429 -> "短信登录请求频繁（HTTP 429），请等待倒计时后再试"
            in 500..599 -> "官网短信服务暂不可用（HTTP ${error.status}），请稍后再试"
            else -> "官网短信接口返回 HTTP ${error.status}，可尝试网页内登录"
        }
        is IllegalArgumentException -> error.message.orEmpty().take(120)
        else -> "红果 App 短信请求未完成，请检查网络；如需滑块验证可使用上方的官网登录"
    }
}
