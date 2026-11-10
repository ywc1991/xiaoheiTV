package com.xiaohei.tv.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检测 + 应用内下载安装。
 *
 * 版本信息来源是 GitHub Releases 的 latest 接口（仓库 ywc1991/xiaoheiTV），
 * 里面 tag_name 当版本号、body 当更新说明、apk 资产的 browser_download_url 当下载地址。
 * 下载走本进程直连，存到外部私有目录，再用 FileProvider 交给系统安装器，
 * 这样不需要读写公共存储权限，也绕开了 targetSdk 33 的 file:// 限制。
 */
object UpdateChecker {

    private const val OWNER_REPO = "ywc1991/xiaoheiTV"
    private const val API_URL = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    private const val UA = "xiaoheiTV-updater"
    private const val TAG = "XiaoHeiTV-update"

    /** GitHub 上一个可安装的版本。 */
    data class Release(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        /** 资源接口地址（api.github.com/.../releases/assets/:id），国内最可靠的下载入口 */
        val apkApiUrl: String = ""
    )

    /** 结果：release 与 error 互斥，两个都为 null 则表示「已是最新」。 */
    class CheckResult(val release: Release?, val error: String?)

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** 后台请求 GitHub，结果回主线程。 */
    fun check(ctx: Context, callback: (CheckResult) -> Unit) {
        Thread {
            var release: Release? = null
            var err: String? = null
            var conn: HttpURLConnection? = null
            try {
                conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connect()
                val code = conn.responseCode
                if (code == 403) {
                    err = "请求太频繁，请稍后再试"
                } else if (code != 200) {
                    err = "服务器返回 $code"
                } else {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val o = JSONObject(text)
                    val tag = o.optString("tag_name", "")
                    val assets = o.optJSONArray("assets")
                    var url = ""
                    var apiUrl = ""
                    var size = 0L
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            val name = a.optString("name", "")
                            if (name.endsWith(".apk", true) && !name.contains("source", true)) {
                                url = a.optString("browser_download_url", "")
                                apiUrl = a.optString("url", "")
                                size = a.optLong("size", 0L)
                                break
                            }
                        }
                    }
                    if (tag.isEmpty() || url.isEmpty()) {
                        err = "未找到可下载的安装包"
                    } else {
                        release = Release(
                            tag = tag,
                            version = normalize(tag),
                            notes = o.optString("body", "").trim(),
                            apkUrl = url,
                            apkSize = size,
                            apkApiUrl = apiUrl
                        )
                    }
                }
            } catch (e: Exception) {
                err = e.message ?: "网络不可用"
            } finally {
                conn?.disconnect()
            }
            val act = ctx as? Activity
            if (act != null) {
                if (act.isFinishing) return@Thread
                act.runOnUiThread { callback(CheckResult(release, err)) }
            } else {
                callback(CheckResult(release, err))
            }
        }.start()
    }

    /** 去掉 v 前缀，只留数字点分。 */
    fun normalize(v: String): String =
        v.trim().removePrefix("v").removePrefix("V").trim()

    /** 按点分段比较数字，段数不足视为 0；忽略 -beta 之类后缀。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(v: String): List<Int> =
        normalize(v).split(".", "-", "_", "+")
            .map { part -> part.takeWhile { it.isDigit() } }
            .mapNotNull { it.toIntOrNull() }

    fun sizeText(bytes: Long): String =
        if (bytes <= 0) "" else String.format("%.1f MB", bytes / 1024.0 / 1024.0)

    /** GitHub 的更新说明是 markdown，电视上只显示纯文本，去掉标题井号和代码围栏 */
    fun cleanNotes(raw: String): String = raw.replace("\r", "")
        .lines()
        .joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.startsWith("#") -> t.trimStart('#').trim()
                t.startsWith("```") -> ""
                else -> line.trimEnd()
            }
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** 更新包目录（外部私有目录优先，无外部存储时退回内部）。 */
    fun updateDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, "update").apply { mkdirs() }
    }

    /** 已经下载好、且大小与远端一致的本地安装包；对不上就丢掉，避免拿残留的旧文件去装 */
    fun pendingApk(ctx: Context, version: String, expectedSize: Long): File? {
        val f = File(updateDir(ctx), fileName(version))
        if (!f.exists()) return null
        val len = f.length()
        if (len <= 256 * 1024) {
            f.delete()
            return null
        }
        if (expectedSize > 0 && len != expectedSize) {
            f.delete()
            return null
        }
        return f
    }

    private fun fileName(version: String) = "xiaoheiTV-$version.apk"

    /**
     * 下载并安装。
     * @param onNeedPermission Android 8+ 未授予「安装未知应用」时回调，一般用于提示用户去开启。
     */
    fun downloadAndInstall(activity: Activity, release: Release, onNeedPermission: () -> Unit = {}) {
        // 先确认能装，再决定是直装本地包还是重新下载
        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        android.util.Log.i(TAG, "downloadAndInstall v${release.version} canInstall=$canInstall")
        if (!canInstall) {
            onNeedPermission()
            return
        }
        // 已经下过同一版本，直接装，省一次流量
        pendingApk(activity, release.version, release.apkSize)?.let {
            android.util.Log.i(TAG, "reuse pending apk ${it.absolutePath} ${it.length()}")
            install(activity, it)
            return
        }
        startDownload(activity, release, retry = 1)
    }

    /** 弹「去设置里开启安装未知应用」。 */
    fun showPermissionDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("需要「安装未知应用」权限")
            .setMessage("系统要求先允许小黑TV安装应用。开启后返回本页，再点一次「下载并安装」即可。")
            .setPositiveButton("去开启") { _, _ ->
                val ok = try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.packageName)
                        )
                    )
                    true
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    try {
                        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    } catch (_: Exception) {
                        toast(activity, "请到系统设置里允许安装未知来源应用")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 下载候选地址。
     *
     * 顺序很重要：国内直连 GitHub 的 release 资产（github.com/…/releases/download/…）
     * 基本是握手超时，但 `api.github.com` 通常是通的——检查更新能返回结果就是证据。
     * 所以第一顺位走资源接口，把 Accept 设成 application/octet-stream 直接拿二进制。
     */
    private data class Source(val url: String, val accept: String, val label: String)

    private fun sources(release: Release): List<Source> {
        val raw = release.apkUrl
        val list = ArrayList<Source>(4)
        if (release.apkApiUrl.isNotEmpty()) {
            list.add(Source(release.apkApiUrl, "application/octet-stream", "GitHub 接口"))
        }
        if (raw.isNotEmpty()) list.add(Source(raw, "", "GitHub 直连"))
        if (raw.isNotEmpty()) {
            list.add(Source("https://ghproxy.net/$raw", "", "镜像 ghproxy"))
            list.add(Source("https://gh-proxy.com/$raw", "", "镜像 gh-proxy"))
            list.add(Source("https://ghfast.top/$raw", "", "镜像 ghfast"))
        }
        return list
    }

    /**
     * 手动跟随重定向：资源接口会 302 到 objects.githubusercontent.com，
     * 交给 HttpURLConnection 自动跟会把 Accept 头丢掉，拿回来的就是一段 JSON 说明而不是 APK。
     * （现象正是「大小不对」或下到一个几十 KB 的错误页。）
     */
    private fun openFollowing(url: String, accept: String): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            if (accept.isNotEmpty()) c.setRequestProperty("Accept", accept)
            c.connect()
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrEmpty() || hops >= 5) throw java.io.IOException("下载跳转失败（HTTP $code）")
                current = if (loc.startsWith("http")) loc
                else URL(URL(current), loc).toString()
                hops++
                continue
            }
            return c
        }
    }

    private fun startDownload(activity: Activity, release: Release, retry: Int) {
        val dir = updateDir(activity)
        android.util.Log.i(TAG, "startDownload ${release.apkUrl} api=${release.apkApiUrl} -> ${dir.absolutePath}")
        dir.listFiles()?.forEach { if (it.name.endsWith(".apk") || it.name.endsWith(".part")) it.delete() }
        val target = File(dir, fileName(release.version))
        val tmp = File(dir, target.name + ".part")
        val urls = sources(release)

        val dialog = ProgressDialog(activity).apply {
            setTitle("正在下载 v${release.version}")
            setMessage(if (release.apkSize > 0) "0% · 共 ${sizeText(release.apkSize)}" else "0%")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(true)
            setButton(AlertDialog.BUTTON_NEGATIVE, "取消") { _, _ -> cancel() }
        }
        dialog.setOnCancelListener { tmp.delete() }
        dialog.show()

        Thread {
            var ok = false
            var err = ""
            var cancelled = false
            var round = 0
            // 每个地址一轮；全部失败后再整体重试 retry 次
            while (round <= retry && !ok && !cancelled) {
                round++
                var src = 0
                while (src < urls.size && !ok && !cancelled) {
                    val cand = urls[src]
                    src++
                    val label = cand.label
                    if (src > 1) {
                        activity.runOnUiThread {
                            if (dialog.isShowing) dialog.setMessage("上一路不通，改走$label…")
                        }
                    }
                    var conn: HttpURLConnection? = null
                    try {
                        conn = openFollowing(cand.url, cand.accept)
                        val code = conn.responseCode
                        android.util.Log.i(TAG, "download $label http=$code len=${conn.contentLength}")
                        if (code != 200) {
                            err = "下载失败（HTTP $code）"
                            continue
                        }
                        val total = if (conn.contentLength > 0) conn.contentLength.toLong() else release.apkSize
                        conn.inputStream.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var lastPct = -1
                                while (true) {
                                    if (dialog.isShowing.not()) {
                                        cancelled = true
                                        break
                                    }
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        activity.runOnUiThread {
                                            if (dialog.isShowing) {
                                                dialog.progress = pct
                                                dialog.setMessage(
                                                    if (release.apkSize > 0)
                                                        "$pct% · ${sizeText(done)} / ${sizeText(release.apkSize)}"
                                                    else "$pct%"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (cancelled) {
                            tmp.delete()
                        } else {
                            val got = tmp.length()
                            // 大小必须和远端一致：既挡半截文件，也挡镜像返回的错误网页
                            val sizeOk = if (release.apkSize > 0) got == release.apkSize else got > 256 * 1024
                            if (sizeOk) {
                                if (target.exists()) target.delete()
                                ok = tmp.renameTo(target)
                                if (!ok) err = "写入安装包失败"
                            } else {
                                android.util.Log.w(TAG, "size mismatch $got != ${release.apkSize} via $label")
                                tmp.delete()
                                err = "安装包大小不对（$got 字节）"
                            }
                        }
                    } catch (e: Exception) {
                        err = e.message ?: "下载中断"
                        tmp.delete()
                    } finally {
                        conn?.disconnect()
                    }
                }
                if (cancelled) break
            }

            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                try { dialog.dismiss() } catch (_: Exception) {}
                when {
                    ok -> install(activity, target)
                    cancelled -> toast(activity, "已取消下载")
                    else -> {
                        AlertDialog.Builder(activity)
                            .setTitle("下载失败")
                            .setMessage(err.ifEmpty { "网络异常" } + "\n\n可以稍后重试，或到官网手动下载安装包。")
                            .setPositiveButton("重试") { _, _ -> startDownload(activity, release, 0) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun install(activity: Activity, apk: File) {
        android.util.Log.i(TAG, "install ${apk.absolutePath} len=${apk.length()}")
        verify(activity, apk)?.let {
            android.util.Log.w(TAG, "verify failed: $it")
            toast(activity, it)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "startActivity install failed", e)
            toast(activity, "无法打开安装器：" + (e.message ?: "未知错误"))
        }
    }

    /**
     * 安装前核对包名与签名，避免把下载到的东西（被劫持/下错文件）交给安装器。
     * 返回 null 表示通过，否则返回错误提示。
     */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            null
        } ?: return "安装包已损坏，请重新下载"
        if (info.packageName != ctx.packageName) {
            return "下载到的不是小黑TV（包名 ${info.packageName}），已阻止安装"
        }
        val self = try {
            pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            null
        } ?: return "无法读取本机签名"
        val a = info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val b = self.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        if (a.isEmpty() || b.isEmpty() || a.none { b.contains(it) }) {
            return "安装包签名与当前版本不一致，已阻止安装"
        }
        return null
    }

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
