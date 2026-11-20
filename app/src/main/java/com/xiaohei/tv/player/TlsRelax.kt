package com.xiaohei.tv.player

import android.util.Log
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
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
 *
 * Android 4.4 还要多一层：系统默认只开 TLSv1，而现在的源站基本只接受 TLSv1.2，
 * 握手会直接失败。这里把连接支持的协议全打开（4.4 的 OpenSSL 本来支持 1.2，
 * 只是被默认关掉），否则 4.4 机器就是「能装、装上去全是黑屏」。
 */
object TlsRelax {

    @Volatile
    private var applied = false

    /** 按优先级排：能用新的就不用旧的 */
    private val WANTED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")

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
            HttpsURLConnection.setDefaultSSLSocketFactory(ProtocolUpgradingFactory(sc.socketFactory))
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.w("XiaoHeiTV", "relaxTls failed: ${e.javaClass.simpleName}")
        }
    }

    /** 把每个新连接上「支持的协议」尽量都打开：5.0+ 等价于系统默认，4.4 上是补丁。 */
    private class ProtocolUpgradingFactory(private val inner: SSLSocketFactory) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = inner.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = inner.supportedCipherSuites

        private fun upgrade(s: Socket): Socket {
            if (s is SSLSocket) {
                val supported = s.supportedProtocols.toHashSet()
                val wanted = WANTED_PROTOCOLS.filter { it in supported }
                if (wanted.isNotEmpty()) {
                    try {
                        s.enabledProtocols = wanted.toTypedArray()
                    } catch (e: Exception) {
                        Log.w("XiaoHeiTV", "enableTls failed: ${e.javaClass.simpleName}")
                    }
                }
            }
            return s
        }

        override fun createSocket(s: Socket?, host: String, port: Int, autoClose: Boolean): Socket =
            upgrade(inner.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            upgrade(inner.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            upgrade(inner.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            upgrade(inner.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            upgrade(inner.createSocket(address, port, localAddress, localPort))
    }
}
