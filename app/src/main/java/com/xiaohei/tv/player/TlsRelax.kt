package com.xiaohei.tv.player

import android.util.Log
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 放宽 TLS 校验（全进程生效，幂等）。
 *
 * 免费直播源里大量 HTTPS 线路用的是自签证书、过期证书，或者证书链在电视的旧 CA 库里不全
 * （实测 CCTV-5 的 6 条线路里有 2 条就死在 `SSLHandshakeException: Chain validation failed`）。
 * 这些线路内容本身是公开的直播流，不做任何账号、支付等敏感传输，跳过证书校验是合理取舍。
 *
 * 抽成单例是因为预热线程也要用同一条规则——否则「播放器能连、预热连不上」，
 * 缓存永远是空的。
 */
object TlsRelax {

    @Volatile
    private var applied = false

    fun apply() {
        if (applied) return
        applied = true
        try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAll, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.w("XiaoHeiTV", "relaxTls failed: ${e.javaClass.simpleName}")
        }
    }
}
