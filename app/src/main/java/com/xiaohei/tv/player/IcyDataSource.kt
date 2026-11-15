package com.xiaohei.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.xiaohei.tv.data.IcyStripper

/**
 * 电台专用的 ICY 读取层。
 *
 * 夹在播放器与已有数据源之间，**只在两个条件同时成立时才介入**：
 * - 当前是电台态（[enabled]）；
 * - 服务器响应头里真的带了 `icy-metaint`。
 *
 * 视频台、HLS 切片、去广告那条链路全部原样直通，一次分支判断之外没有多余开销。
 * 想清空「正在播放」也走这里：拿不到元数据就不回调，界面自然显示「直播中」。
 */
class IcyDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val enabled: () -> Boolean,
    private val onTitle: (String) -> Unit
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        IcyDataSource(delegate.createDataSource(), enabled, onTitle)
}

private class IcyDataSource(
    private val upstream: DataSource,
    private val enabled: () -> Boolean,
    private val onTitle: (String) -> Unit
) : BaseDataSource(/* isNetwork= */ true) {

    // 8 字节对 16 字节音频块，最坏情况也能整块装下（音频量不会超过输入量）
    private val inBuf = ByteArray(16 * 1024)
    private val outBuf = ByteArray(32 * 1024)
    private var outPos = 0
    private var outLen = 0
    private var stripper: IcyStripper? = null
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val length = upstream.open(dataSpec)
        transferStarted(dataSpec)
        started = true
        outPos = 0
        outLen = 0
        val metaint = if (enabled()) {
            upstream.responseHeaders?.get("icy-metaint")
                ?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        } else {
            0
        }
        stripper = if (metaint > 0) IcyStripper(metaint) else null
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val st = stripper ?: return upstream.read(buffer, offset, length)
        while (outPos >= outLen) {
            val n = upstream.read(inBuf, 0, inBuf.size)
            if (n == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            outLen = st.push(inBuf, n, outBuf)
            outPos = 0
            st.takeTitle()?.let { title ->
                // 这里跑在播放器的加载线程上：回调抛异常会变成播放错误、直接把电台打断，
                // 所以一律吞掉——拿不到标题是可以接受的，播不了不行。
                runCatching { onTitle(title) }
            }
        }
        val n = minOf(length, outLen - outPos)
        System.arraycopy(outBuf, outPos, buffer, offset, n)
        outPos += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream.responseHeaders ?: emptyMap()

    override fun close() {
        stripper = null
        outPos = 0
        outLen = 0
        if (started) {
            started = false
            runCatching { transferEnded() }
        }
        upstream.close()
    }
}
