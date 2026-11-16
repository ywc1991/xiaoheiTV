package com.xiaohei.tv.data

import com.xiaohei.tv.model.Channel
import com.xiaohei.tv.model.PodcastShow

/**
 * 内置播客目录 + 远端目录的落点。
 *
 * 只内置「节目单 + RSS 地址」，分集在打开面板时按 RSS 实时拉取并缓存 12 小时——
 * 播客是持续更新的，把分集地址写死在 APK 里过几周就全是旧的。
 *
 * 节目单本身也不再只靠发版维护：GitHub 上有一份 `podcasts.json`，
 * 启动时拉下来合并（见 [PodcastDirStore]）。[builtin] 永远是兜底，
 * 远端文件缺了、坏了、拿不到，列表都退回这 29 档，不会变空。
 *
 * 这 29 个 RSS 已在 2026-11-15 / 11-16 分两批在本机逐个实测过：返回 200、能解析出分集、
 * 首集音频 Range 请求返回 206（mp3 / m4a 直链）。名单里不写需要登录、
 * 或者只有喜马拉雅专辑页（RSS 里没有 enclosure）的源。
 */
object PodcastCatalog {

    val builtin: List<PodcastShow> = listOf(
        PodcastShow(
            "storyfm", "故事FM", "故事 FM",
            "https://feeds.storyfm.cn/storyfm.xml",
            "亲历者口述的真实故事"
        ),
        PodcastShow(
            "huzuohuyou", "忽左忽右", "JustPod",
            "https://feed.xyzfm.space/cv4bkgpuglwp",
            "一档关于历史、社会与思想的对谈节目"
        ),
        PodcastShow(
            "shengdongjixi", "声东击西", "ETW Studio",
            "https://feeds.fireside.fm/shengdongjixi/rss",
            "把世界另一端的声音带到耳边"
        ),
        PodcastShow(
            "daneimitan", "大内密谈", "大内密谈",
            "https://rss.lizhi.fm/rss/14275.xml",
            "生活方式与脑洞漫谈"
        ),
        PodcastShow(
            "wenhuayouxian", "文化有限", "文化有限",
            "https://s1.proxy.wavpub.com/weknownothing.xml",
            "三个爱读书的人聊书"
        ),
        PodcastShow(
            "bowuzhi", "博物志", "小黄鱼播客",
            "https://bowuzhi.typlog.io/feed/audio.xml",
            "博物馆、展览与文物里的细节"
        ),
        PodcastShow(
            "liangyongan", "梁永安的播客", "梁永安",
            "https://feed.xyzfm.space/tbalbybjc3ev",
            "复旦教授聊文学与年轻人的处境"
        ),
        PodcastShow(
            "ritanwuyu", "日谈物语", "日谈公园",
            "https://feed.xyzfm.space/87x9n4x77fpy",
            "日谈公园出品的纪实故事"
        ),
        PodcastShow(
            "dongyaguancha", "东亚观察局", "东亚观察局",
            "https://feed.xyzfm.space/eye-on-east-asia",
            "东亚时事与社会观察"
        ),
        PodcastShow(
            "mianji", "面基", "面基",
            "https://feed.xyzfm.space/6hpdgggtxpxb",
            "和各行各业的人聊聊他们怎么想"
        ),
        PodcastShow(
            "rizhilu", "日知录", "日谈公园",
            "https://feed.xyzfm.space/6fqtp9h4u8vm",
            "日常里那些值得记下来的知识"
        ),
        PodcastShow(
            "kernelpanic", "内核恐慌", "吴涛、Rio",
            "https://pan.icu/feed",
            "号称硬核却没什么干货的信息技术闲聊"
        ),
        PodcastShow(
            "pythonhunter", "捕蛇者说", "pythonhunter",
            "https://pythonhunter.org/feed",
            "几个写 Python 的人聊技术与职业"
        ),
        PodcastShow(
            "anywayfm", "Anyway.FM", "JJ Ying & Leon Gao",
            "https://anyway.fm/rss.xml",
            "两位设计师聊视觉设计"
        ),
        PodcastShow(
            "buheshiyi", "不合时宜", "不合时宜",
            "https://feed.xyzfm.space/ww7cqnybekty",
            "青年视角看世界，时事与文化对谈"
        ),
        PodcastShow(
            "guigu101", "硅谷101", "硅谷101",
            "https://feeds.fireside.fm/sv101/rss",
            "硅谷科技、创投与华人故事"
        ),
        PodcastShow(
            "zhangxiaojun", "张小珺商业访谈录", "张小珺",
            "https://feed.xyzfm.space/dk4yh3pkpjp3",
            "和商业关键人物深度对谈"
        ),
        PodcastShow(
            "wandian", "晚点聊", "晚点LatePost",
            "https://feeds.fireside.fm/latetalk/rss",
            "晚点团队对话创业者与投资人"
        ),
        PodcastShow(
            "zhangjing42", "42章经", "42章经",
            "https://feed.xyzfm.space/evgg6xle9rdc",
            "创业、投资与商业观察"
        ),
        PodcastShow(
            "kejizaozhidao", "科技早知道", "声动",
            "https://feeds.fireside.fm/guiguzaozhidao/rss",
            "全球科技资讯与趋势解读"
        ),
        PodcastShow(
            "kejiluandun", "科技乱炖", "科技乱炖",
            "https://feeds.daopub.com/ld.xml",
            "科技热点与业界八卦漫谈"
        ),
        PodcastShow(
            "luanfanshu", "乱翻书", "潘乱",
            "https://feed.xyzfm.space/yxuruh3f9mc4",
            "互联网与商业深度观察"
        ),
        PodcastShow(
            "zhixing", "知行小酒馆", "有知有行",
            "https://feed.xyzfm.space/j8yp8gxkmgqr",
            "投资、理财与生活思考"
        ),
        PodcastShow(
            "bannatie", "半拿铁", "半拿铁",
            "https://proxy.wavpub.com/caffebreve.xml",
            "商业沉浮与品牌故事"
        ),
        PodcastShow(
            "wuliaozhai", "无聊斋", "无聊斋",
            "https://feed.xyzfm.space/njwyhpcjqn9t",
            "喜剧演员的轻松闲聊"
        ),
        PodcastShow(
            "xxlth", "谐星聊天会", "谐星聊天会",
            "https://feeds.danlirencomedy.com/xxlth.xml",
            "单口喜剧演员群聊现场"
        ),
        PodcastShow(
            "naofangdianbo", "脑放电波", "脑放电波",
            "https://feed.xyzfm.space/wupdmt9er7nb",
            "科学、技术与现代社会话题"
        ),
        PodcastShow(
            "kanlixiang", "看理想圆桌", "看理想",
            "https://api.vistopia.com.cn/rss/program/13.xml",
            "人文知识分子的圆桌对谈"
        ),
        PodcastShow(
            "yanzhonghuashu", "岩中花述", "岩中花述",
            "https://feed.xyzfm.space/hwen8wf69c6g",
            "女性人物深度访谈"
        )
    )

    /**
     * 当前生效的节目单。
     *
     * 会被远端目录整体替换，而读它的地方在主线程（建列表）和后台线程（拉分集）都有，
     * 所以选择整体换引用而不是原地改：`@Volatile` + 不可变 List，读到的一定是一份完整快照。
     */
    @Volatile
    private var active: List<PodcastShow> = builtin

    val shows: List<PodcastShow> get() = active

    /**
     * 应用一份远端目录。[result] 为 null 表示没有可用目录，退回内置清单。
     * 返回 true 表示节目单**确实变了**（调用方需要重排列表）。
     */
    fun apply(result: PodcastDir.Result?): Boolean {
        val next = if (result == null) builtin
        else PodcastDir.merge(builtin, result.shows, result.removed)
        if (next == active) return false
        active = next
        return true
    }

    /** 单测用：把目录恢复成内置状态 */
    fun reset() {
        active = builtin
    }

    /**
     * 播客在左右两栏里就是「一档节目」这一行：
     * 没有线路表（音频地址要等打开面板才知道），所以 urls 为空，
     * 靠 isPodcast 让按键逻辑走「打开分集面板」而不是「直接起播」。
     */
    fun asChannel(show: PodcastShow): Channel = Channel(
        name = show.name,
        group = ChannelGroups.PODCAST,
        logo = "",
        urls = emptyList(),
        isAudio = true,
        isPodcast = true,
        subtitle = show.author
    )

    fun byName(name: String): PodcastShow? = active.firstOrNull { it.name == name }
}
