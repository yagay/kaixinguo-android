package com.kadaliao.kaixinguo.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppPassportProtocolTest {
    private class TestSigner : AppPassportSigner {
        override val deviceId = "111"
        override val installId = "222"
        override fun sign(endpoint: String, method: String, parameters: Map<String, String>,
                          body: ByteArray?): AppPassportRequest =
            AppPassportRequest(endpoint, method, mapOf(
                "x-gorgon" to "test", "x-khronos" to "test",
                "x-helios" to "test", "x-medusa" to "test",
            ), body)
    }

    @Test fun rejectsUnsignedAuthenticationBeforeNetworkCall() {
        var calls = 0
        val signer = object : AppPassportSigner {
            override val deviceId = "111"
            override val installId = "222"
            override fun sign(endpoint: String, method: String, parameters: Map<String, String>,
                              body: ByteArray?) = AppPassportRequest(endpoint, method, emptyMap(), body)
        }
        val client = AppSmsLogin(signer, AppPassportTransport {
            calls++
            AppPassportResponse(200, "")
        })
        assertTrue(runCatching { client.send("13800138000") }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun platformChallengeRequiresHumanVerification() {
        val client = AppSmsLogin(TestSigner(), AppPassportTransport {
            AppPassportResponse(200,
                """{"message":"error","data":{"error_code":1105,"verify_center_decision_conf":{}}}""")
        })
        val error = runCatching { client.send("13800138000") }.exceptionOrNull()
        assertTrue(error is AppSmsLogin.Rejection)
        assertTrue((error as AppSmsLogin.Rejection).requiresChallenge)
    }

    @Test fun completedTransportNeverTrustsEmptyReply() {
        val client = AppSmsLogin(TestSigner(), AppPassportTransport {
            AppPassportResponse(200, """{"message":"success","data":{}}""")
        })
        client.send("13800138000")
        assertTrue(runCatching { client.verify("1234") }.exceptionOrNull() is IOException)
    }
}
