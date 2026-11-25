package com.xiaohei.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.CrashGuard
import com.xiaohei.tv.DeviceProfile
import com.xiaohei.tv.R
import com.xiaohei.tv.UiMode
import com.xiaohei.tv.update.UpdateChecker

/**
 * 设置页。只留三项——检查更新、需求和反馈、关于。
 *
 * 之前这里堆了播放源切换、自定义源地址、在线更新、广告过滤开关、线路避坑记录……
 * 结果是把「看电视」这件事的开关混进了配置面板：用户想找的永远是被埋在最后一行。
 * 现在只保留「要不要更新」「扫码提需求」「这是什么应用」，其余按默认行为走，不再暴露。
 */
class SettingsActivity : Activity() {

    /** 与 MainActivity 一致：钳制系统超大字体，避免设置页文字撑破卡片布局 */
    override fun attachBaseContext(newBase: Context) {
        val fixed = com.xiaohei.tv.DisplayCompat.correctedConfig(newBase.resources.configuration)
        super.attachBaseContext(if (fixed != null) newBase.createConfigurationContext(fixed) else newBase)
    }

    private data class Row(val title: String, val desc: String, val action: () -> Unit)

    private var adapter: SettingsAdapter? = null

    /** 检测到的、比当前安装版本更新的版本；null 表示没有。 */
    private var latest: UpdateChecker.Release? = null
    private var checking = false
    private var lastError: String? = null

    companion object {
        private const val FEEDBACK_URL = "https://docs.qq.com/sheet/DWmhEd21wU2hpWm5s"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // 手机显示触摸提示，电视保留遥控器提示
        findViewById<TextView>(R.id.settingsHint)?.text =
            if (UiMode.isPhone(this)) "点按设置项　·　返回键退出"
            else "↑↓ 选择　OK 确认　← 返回"

        val recycler = findViewById<RecyclerView>(R.id.settingsList)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SettingsAdapter(buildRows())
        recycler.adapter = adapter
        // 进来先静默查一次，有新版本直接体现在「检查更新」那一行上，不打断用户
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) doCheck(silent = true)
        }, 400L)
    }

    private fun buildRows(): List<Row> = listOf(
        // 检查更新：检测到新版本后，这一行本身就是「下载并安装」的入口
        Row(updateTitle(), updateDesc()) { onCheckUpdateClicked() },
        Row(
            "需求和反馈",
            if (UiMode.isPhone(this)) "点击直接打开填写页面" else "用手机微信扫码填写你的需求和建议"
        ) { openFeedback() },
        Row("关于", "小黑TV ${UpdateChecker.currentVersion(this)} · 版本信息与说明") { showAbout() }
    )

    /* ---------------- 版本检测 ---------------- */

    private fun updateTitle(): String = when {
        checking -> "正在检查更新…"
        latest != null -> "发现新版本 v${latest!!.version}，点此下载安装"
        else -> "检查更新 · 当前 v${UpdateChecker.currentVersion(this)}"
    }

    private fun updateDesc(): String {
        val cur = UpdateChecker.currentVersion(this)
        val r = latest
        if (r != null) {
            val size = UpdateChecker.sizeText(r.apkSize)
            val brief = UpdateChecker.cleanNotes(r.notes)
                .lineSequence()
                .map { it.trim().trimStart('*', '-', ' ').trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.take(48)
                ?: "修复问题、优化播放体验"
            return buildString {
                append("当前 v").append(cur)
                if (size.isNotEmpty()) append(" · 新包 ").append(size)
                append(" · ").append(brief)
            }
        }
        lastError?.let { return "上次检查失败：$it · 点此重试" }
        return "当前 v$cur · 从 GitHub 检查最新版本，发现新版本可直接下载并安装"
    }

    private fun onCheckUpdateClicked() {
        latest?.let {
            showUpdateDialog(it)
            return
        }
        if (checking) return
        doCheck(silent = false)
    }

    private fun doCheck(silent: Boolean) {
        if (checking) return
        checking = true
        refreshRows()
        UpdateChecker.check(this) { result ->
            if (isFinishing) return@check
            checking = false
            val cur = UpdateChecker.currentVersion(this)
            val r = result.release
            if (r != null && UpdateChecker.isNewer(r.version, cur)) {
                latest = r
                lastError = null
                refreshRows()
                if (silent) toast("发现新版本 v${r.version}，可在设置里更新") else showUpdateDialog(r)
            } else {
                latest = null
                lastError = result.error
                refreshRows()
                if (!silent) {
                    toast(result.error?.let { "检查更新失败：$it" } ?: "已是最新版本 v$cur")
                }
            }
        }
    }

    /**
     * 应用内更新弹窗。
     *
     * 2.20.1 的问题：用 AlertDialog.setMessage 塞 600 字更新说明，整块对话框被撑得比屏幕还高，
     * 底部「下载并安装 / 以后再说」被顶出画面 —— 电视上能看到版本号，却找不到下载按钮。
     * 标准 AlertDialog 的高度不被屏幕约束，这是它在大字号电视上的固有毛病。
     *
     * 现在改为自绘：说明区放在 ScrollView 里，按屏幕高度算上限（超出才滚动），
     * 按钮单独一行固定在底部，任何分辨率下都在可视区域内。
     */
    private fun showUpdateDialog(r: UpdateChecker.Release) {
        val cur = UpdateChecker.currentVersion(this)
        val size = UpdateChecker.sizeText(r.apkSize)
        val notes = UpdateChecker.cleanNotes(r.notes)

        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.updateTitle).text = "发现新版本 v${r.version}"
        view.findViewById<TextView>(R.id.updateSub).text = buildString {
            append("当前 v").append(cur).append("  →  新版本 v").append(r.version)
            if (size.isNotEmpty()) append("（").append(size).append("）")
        }
        view.findViewById<TextView>(R.id.updateNotes).text =
            if (notes.length > 700) notes.take(700) + "…" else notes

        val btnInstall = view.findViewById<TextView>(R.id.btnInstall)
        val btnLater = view.findViewById<TextView>(R.id.btnLater)
        val dlg = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.setContentView(view)
        dlg.setCanceledOnTouchOutside(true)
        btnInstall.setOnClickListener {
            dlg.dismiss()
            UpdateChecker.downloadAndInstall(this, r) {
                UpdateChecker.showPermissionDialog(this)
            }
        }
        btnLater.setOnClickListener { dlg.dismiss() }

        val dm = resources.displayMetrics
        val maxW = (dm.widthPixels * 0.62f).toInt()
        val maxH = (dm.heightPixels * 0.84f).toInt()

        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.72f)
        }

        // 定制 TV 主题上对话框可能抛异常，不能让「检查更新」整个入口失效
        if (runCatching { dlg.show() }.isFailure) {
            toast("发现新版本 v${r.version}，可到官网手动下载")
            return
        }
        val win = dlg.window
        win?.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT)

        // 说明再长也不许把按钮顶出屏幕：超出上限就把 ScrollView 收窄，剩余部分滚动查看
        val scroll = view.findViewById<ScrollView>(R.id.updateScroll)
        view.post {
            if (view.height > maxH) {
                val overflow = view.height - maxH
                (scroll.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
                    lp.height = (scroll.height - overflow).coerceAtLeast((56 * dm.density).toInt())
                    scroll.layoutParams = lp
                    view.requestLayout()
                    win?.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
            btnInstall.requestFocus()
        }
    }

    private fun openFeedback() {
        // 手机上直接拉起浏览器/微信打开链接，省掉「扫码」这一步
        if (UiMode.isPhone(this)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 不预先 resolveActivity：包可见性过滤下它可能返回 null，但 startActivity 仍能成功
            if (runCatching { startActivity(intent) }.isFailure) {
                toast("无法打开浏览器，请用微信访问：docs.qq.com/sheet/DWmhEd21wU2hpWm5s")
            }
            return
        }
        showFeedbackQr()
    }

    private fun showFeedbackQr() {
        val view = layoutInflater.inflate(R.layout.dialog_feedback_qr, null)
        val dlg = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("知道了", null)
        // 定制 TV 主题上对话框可能抛异常，用 toast 兜底，不让入口失效
        if (runCatching { dlg.show() }.isFailure) {
            toast("请用手机微信扫码访问：docs.qq.com/sheet/DWmhEd21wU2hpWm5s")
        }
    }

    private fun showAbout() {
        val v = UpdateChecker.currentVersion(this)
        val lv = CrashGuard.level(this)
        val slots = CrashGuard.slotCount(this)
        val conservative = DeviceProfile.isConservative(this@SettingsActivity)
        val stability = buildString {
            append("\n\n运行状态：")
            append(if (lv == 0 && !conservative) "标准（预播 $slots 路）"
            else "兼容模式（预播 $slots 路，减少解码器占用）")
            append("\n设备：").append(CrashGuard.deviceInfo(this@SettingsActivity))
            val last = CrashGuard.recentSummary(this@SettingsActivity)
            if (last.isNotEmpty()) append("\n最近一次异常：").append(last)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("关于小黑TV")
            .setMessage(
                "版本 v$v\n\n" +
                    "一个只用遥控器就能看完的电视直播应用：左栏分组、右栏频道，" +
                    "全屏上下键直接切台，返回键只做界面回退、不会退出。\n\n" +
                    "内置央视 / 卫视 / 港澳台频道，同一频道带多条备用线路；" +
                    "播放清单里插播的广告分片会被自动剔除。\n\n" +
                    "所有频道均为公开免费直播源，内容版权归各电视台所有，本应用不存储、不分发任何视频内容。" +
                    stability
            )
            .setPositiveButton("知道了", null)
        if (lv > 0 || CrashGuard.manual(this) >= 0 || conservative) {
            dlg.setNeutralButton("恢复标准模式") { _, _ ->
                if (lv > 0) CrashGuard.reset(this)
                // 保守档是设备画像判出来的，reset() 清不掉；显式锁到标准模式
                CrashGuard.setManual(this, 0)
                toast("已恢复，重启小黑TV后生效")
                refreshRows()
            }
        }
        runCatching { dlg.show() }
    }

    private fun refreshRows() {
        val a = adapter ?: return
        a.rows = buildRows()
        // 只有两项，整表刷新即可，不必算索引
        a.notifyDataSetChanged()
    }

    private fun toast(msg: String) = runCatching {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private class SettingsAdapter(var rows: List<Row>) :
        RecyclerView.Adapter<SettingsAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.settingTitle)
            val desc: TextView = view.findViewById(R.id.settingDesc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.desc.text = row.desc
            holder.itemView.setOnClickListener { row.action() }
        }

        override fun getItemCount(): Int = rows.size
    }
}
