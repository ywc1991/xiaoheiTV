package com.xiaohei.tv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.Util
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * 包一层播放器的 HTTP 数据源：m3u8 播放列表在交给 ExoPlayer 之前先做广告剔除，
 * 其余请求（视频分片、图片等）原样直通，不做任何缓冲。
 */
class AdFilterDataSourceFactory(
    context: Context,
    private val enabled: () -> Boolean
) : DataSource.Factory {

    // 与 media3 默认行为一致的 UA，避免个别源对 UA 敏感
    private val userAgent: String = Util.getUserAgent(context, "XiaoHeiTV")

    override fun createDataSource(): DataSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            // 库默认连接超时 8 秒，对直播源来说太客气了：死线路要卡满 8 秒才报错。
            // 压到 3 秒，配合「不重试」和线路直通表，死链让位更快。
            .setConnectTimeoutMs(3_000)
            .setReadTimeoutMs(15_000)
            .createDataSource()
        return AdFilterDataSource(http, enabled)
    }
}

private const val TAG = "XiaoHeiTV"

private class AdFilterDataSource(
    private val upstream: DataSource,
    private val enabled: () -> Boolean
) : BaseDataSource(/* isNetwork= */ true) {

    /** 过滤后的播放列表内容；为 null 表示当前请求直通上游 */
    private var filtered: ByteArray? = null
    private var offset = 0
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val length = upstream.open(dataSpec)
        transferStarted(dataSpec)
        started = true

        if (!enabled() || !looksLikePlaylist(dataSpec.uri)) {
            return length
        }

        // 清单读取/过滤跑在播放器的加载线程上：异常必须变成可恢复的 IOException（换线路），
        // 绝不能让 RuntimeException 冒出去把整个 App 崩掉。
        val raw: ByteArray = try {
            readAll()
        } catch (t: Throwable) {
            Log.w(TAG, "清单读取失败，交给播放器换线 ${t.javaClass.simpleName}")
            started = false
            runCatching { transferEnded() }
            throw java.io.IOException("playlist read failed", t)
        }
        val bytes = try {
            val text = String(raw, Charsets.UTF_8)
            // 诊断：清单过滤耗时是切台链路的一部分，只在真的剔掉广告时打，便于后台核对没有拖慢播放
            val t0 = System.nanoTime()
            val out = AdFilter.filter(text, dataSpec.uri.toString()).toByteArray(Charsets.UTF_8)
            val costUs = (System.nanoTime() - t0) / 1000L
            // 直播清单每 10 秒刷新一次，两个播放器同时跑会把日志刷满、把真正的切台记录冲掉。
            // 只有确实剔掉了广告分片才打日志，其余静默。
            if (raw.size != out.size) {
                Log.d(TAG, "已过滤播放列表 ${dataSpec.uri} ${raw.size}B -> ${out.size}B 耗时 ${costUs}us")
            }
            out
        } catch (t: Throwable) {
            // 数据已经读完，没法回退到直通上游；把原文原样交出去最安全
            Log.w(TAG, "清单过滤异常，原样放行 ${t.javaClass.simpleName}")
            raw
        }
        filtered = bytes
        offset = 0
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = filtered ?: return upstream.read(buffer, offset, length)
        if (this.offset >= data.size) return -1 // RESULT_END_OF_INPUT
        val n = minOf(length, data.size - this.offset)
        System.arraycopy(data, this.offset, buffer, offset, n)
        this.offset += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream.responseHeaders ?: emptyMap()

    override fun close() {
        filtered = null
        offset = 0
        if (started) {
            started = false
            transferEnded()
        }
        upstream.close()
    }

    /** 整个播放列表读完（直播列表只有几十 KB；上限 8MB 兜底防止异常大文件占内存） */
    private fun readAll(): ByteArray {
        val out = java.io.ByteArrayOutputStream(16 * 1024)
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = upstream.read(buf, 0, buf.size)
            if (n == -1) break
            out.write(buf, 0, n)
            if (out.size() > 8 * 1024 * 1024) break
        }
        return out.toByteArray()
    }

    private fun looksLikePlaylist(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
        if (path.contains("m3u8") || path.contains("m3u")) return true
        val query = uri.query?.lowercase() ?: return false
        return query.contains("m3u8") || query.contains("m3u")
    }
}
