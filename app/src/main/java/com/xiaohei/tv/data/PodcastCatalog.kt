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
 * 远端文件缺了、坏了、拿不到，列表都退回这 40 档，不会变空。
 *
 * 这批 RSS 已在 2026-11-15/11-16 及 11-23 分多批在本机逐个实测过：返回 200、能解析出分集、
 * 首集音频 Range 请求返回 206（mp3 / m4a 直链）。名单里不写需要登录、
 * 或者只有喜马拉雅专辑页（RSS 里没有 enclosure）的源。
 * 注意 feed.xyzfm.space 域名在 11-23 复测时返回 403，故新增档一律选其它可访问的托管源
 * （荔枝 / 看理想 / typlog / wavpub / 独立站）。
 */
object PodcastCatalog {

    /** 分类的固定展示顺序：书架按这个顺序分节，节内保持 [builtin] 里手写的位置 */
    val CATEGORIES = listOf("故事纪实", "人文对谈", "科技数码", "商业观察", "轻松闲聊")

    val builtin: List<PodcastShow> = listOf(
        PodcastShow(
            "storyfm", "故事FM", "故事 FM",
            "https://feeds.storyfm.cn/storyfm.xml",
            "亲历者口述的真实故事",
            cover = "https://media-one.wavpub.com/covers/1ebcbf5d-9dd2-4b69-b681-b54c22541cf2/977035cf6ab63b6e.png",
            category = "故事纪实"
        ),
        PodcastShow(
            "huzuohuyou", "忽左忽右", "JustPod",
            "https://feed.xyzfm.space/cv4bkgpuglwp",
            "一档关于历史、社会与思想的对谈节目",
            cover = "https://image.xyzcdn.net/FrYMqb37Wg_LA8aenDJJ8SFGsjea",
            category = "人文对谈"
        ),
        PodcastShow(
            "shengdongjixi", "声东击西", "ETW Studio",
            "https://feeds.fireside.fm/shengdongjixi/rss",
            "把世界另一端的声音带到耳边",
            cover = "https://cdn.fireside.fm/file/fireside-images-2024/podcasts/images/8/8dd8a56f-9636-415a-8c00-f9ca6778e511/cover.jpg?v=4",
            category = "人文对谈"
        ),
        PodcastShow(
            "daneimitan", "大内密谈", "大内密谈",
            "https://rss.lizhi.fm/rss/14275.xml",
            "生活方式与脑洞漫谈",
            cover = "https://cdn.lizhi.fm/podcast_cover/2018/12/10/2708425912842789447_600x600.jpg",
            category = "轻松闲聊"
        ),
        PodcastShow(
            "wenhuayouxian", "文化有限", "文化有限",
            "https://s1.proxy.wavpub.com/weknownothing.xml",
            "三个爱读书的人聊书",
            cover = "https://fdfs.xmcdn.com/group86/M09/93/15/wKg5Jl7oVC7jwzOyAAKPBjf4fAA078.jpg",
            category = "人文对谈"
        ),
        PodcastShow(
            "bowuzhi", "博物志", "小黄鱼播客",
            "https://bowuzhi.typlog.io/feed/audio.xml",
            "博物馆、展览与文物里的细节",
            cover = "https://i.typlog.com/bowuzhi/8265386531_995896.png?x-oss-process=style/sl",
            category = "人文对谈"
        ),
        PodcastShow(
            "liangyongan", "梁永安的播客", "梁永安",
            "https://feed.xyzfm.space/tbalbybjc3ev",
            "复旦教授聊文学与年轻人的处境",
            cover = "https://image.xyzcdn.net/FvIoHGddh56FQPpqqZbG5QrDapdy.jpg",
            category = "人文对谈"
        ),
        PodcastShow(
            "ritanwuyu", "日谈物语", "日谈公园",
            "https://feed.xyzfm.space/87x9n4x77fpy",
            "日谈公园出品的纪实故事",
            cover = "https://image.xyzcdn.net/FgKrO6z3werDYCWBPUaTKfuLcQIy.jpg",
            category = "故事纪实"
        ),
        PodcastShow(
            "dongyaguancha", "东亚观察局", "东亚观察局",
            "https://feed.xyzfm.space/eye-on-east-asia",
            "东亚时事与社会观察",
            cover = "https://image.xyzcdn.net/FsPm9Kh9XglTbg7Fl9vCW0mRg-Py.jpg",
            category = "人文对谈"
        ),
        PodcastShow(
            "mianji", "面基", "面基",
            "https://feed.xyzfm.space/6hpdgggtxpxb",
            "和各行各业的人聊聊他们怎么想",
            cover = "https://image.xyzcdn.net/Ftol1VYmqtTUXqIxdMjkt0BcBuFX.png",
            category = "人文对谈"
        ),
        PodcastShow(
            "rizhilu", "日知录", "日谈公园",
            "https://feed.xyzfm.space/6fqtp9h4u8vm",
            "日常里那些值得记下来的知识",
            cover = "https://image.xyzcdn.net/FrVOB3d78sC3V8__zqDOyDDogh2P",
            category = "人文对谈"
        ),
        PodcastShow(
            "kernelpanic", "内核恐慌", "吴涛、Rio",
            "https://pan.icu/feed",
            "号称硬核却没什么干货的信息技术闲聊",
            cover = "https://is1-ssl.mzstatic.com/image/thumb/Podcasts115/v4/42/29/85/422985a5-7649-67e9-bb86-16e69db62528/mza_17711615031769451067.png/1000x1000bb.jpg",
            category = "科技数码"
        ),
        PodcastShow(
            "pythonhunter", "捕蛇者说", "pythonhunter",
            "https://pythonhunter.org/feed",
            "几个写 Python 的人聊技术与职业",
            cover = "https://i.typlog.com/pythonhunter/8444779951_186779.png",
            category = "科技数码"
        ),
        PodcastShow(
            "anywayfm", "Anyway.FM", "JJ Ying & Leon Gao",
            "https://anyway.fm/rss.xml",
            "两位设计师聊视觉设计",
            cover = "https://s.anyway.red/images/cover-new.png",
            category = "科技数码"
        ),
        PodcastShow(
            "buheshiyi", "不合时宜", "不合时宜",
            "https://feed.xyzfm.space/ww7cqnybekty",
            "青年视角看世界，时事与文化对谈",
            cover = "https://image.xyzcdn.net/Fq-ccfNzhgTqQNzlG-1qA_jOow7l.png",
            category = "人文对谈"
        ),
        PodcastShow(
            "guigu101", "硅谷101", "硅谷101",
            "https://feeds.fireside.fm/sv101/rss",
            "硅谷科技、创投与华人故事",
            cover = "https://cdn.fireside.fm/file/fireside-images-2024/podcasts/images/f/f0f20376-8faf-4940-b920-84af6c734e2d/cover.jpg?v=6",
            category = "商业观察"
        ),
        PodcastShow(
            "zhangxiaojun", "张小珺商业访谈录", "张小珺",
            "https://feed.xyzfm.space/dk4yh3pkpjp3",
            "和商业关键人物深度对谈",
            cover = "https://image.xyzcdn.net/Fnuyt9PQDQx0I0wlErG3ZHcW9Gpu.png",
            category = "商业观察"
        ),
        PodcastShow(
            "wandian", "晚点聊", "晚点LatePost",
            "https://feeds.fireside.fm/latetalk/rss",
            "晚点团队对话创业者与投资人",
            cover = "https://cdn.fireside.fm/file/fireside-images-2024/podcasts/images/1/19a49f52-28ee-4e25-b8e9-4f0ee70917e0/cover.jpg?v=7",
            category = "商业观察"
        ),
        PodcastShow(
            "zhangjing42", "42章经", "42章经",
            "https://feed.xyzfm.space/evgg6xle9rdc",
            "创业、投资与商业观察",
            cover = "https://image.xyzcdn.net/FiZZKf2-OlC5ZFzM8VLLmtz4P0ie.png",
            category = "商业观察"
        ),
        PodcastShow(
            "kejizaozhidao", "科技早知道", "声动",
            "https://feeds.fireside.fm/guiguzaozhidao/rss",
            "全球科技资讯与趋势解读",
            cover = "https://cdn.fireside.fm/file/fireside-images-2024/podcasts/images/4/4931937e-0184-4c61-a658-6b03c254754d/cover.jpg?v=10",
            category = "科技数码"
        ),
        PodcastShow(
            "kejiluandun", "科技乱炖", "科技乱炖",
            "https://feeds.daopub.com/ld.xml",
            "科技热点与业界八卦漫谈",
            cover = "https://media-one.wavpub.com/covers/7008bf9f-6510-4c43-b035-078e1bfabfff/fa390d6c7c1ac5c9.png",
            category = "科技数码"
        ),
        PodcastShow(
            "luanfanshu", "乱翻书", "潘乱",
            "https://feed.xyzfm.space/yxuruh3f9mc4",
            "互联网与商业深度观察",
            cover = "https://image.xyzcdn.net/FqqQkG2Api63iYHz38DWlnFdR-1y.jpg",
            category = "商业观察"
        ),
        PodcastShow(
            "zhixing", "知行小酒馆", "有知有行",
            "https://feed.xyzfm.space/j8yp8gxkmgqr",
            "投资、理财与生活思考",
            cover = "https://image.xyzcdn.net/Fso6ZPHSi62eZJOLhorcqpx8TEwv.jpg",
            category = "商业观察"
        ),
        PodcastShow(
            "bannatie", "半拿铁", "半拿铁",
            "https://proxy.wavpub.com/caffebreve.xml",
            "商业沉浮与品牌故事",
            cover = "https://fdfs.xmcdn.com/storages/ac27-audiofreehighqps/71/C8/GMCoOSIHcO5qAAE-OQHg-X-n.jpeg",
            category = "商业观察"
        ),
        PodcastShow(
            "wuliaozhai", "无聊斋", "无聊斋",
            "https://feed.xyzfm.space/njwyhpcjqn9t",
            "喜剧演员的轻松闲聊",
            cover = "https://image.xyzcdn.net/FtKC0bGtqnMRdHf-1OK5hmMs4c-q",
            category = "轻松闲聊"
        ),
        PodcastShow(
            "xxlth", "谐星聊天会", "谐星聊天会",
            "https://feeds.danlirencomedy.com/xxlth.xml",
            "单口喜剧演员群聊现场",
            cover = "https://media-one.wavpub.com/covers/be42b6bc-6b73-4e8f-b3b7-e1bacf3c4f3f/a6047b5e4b7a1357.jpg",
            category = "轻松闲聊"
        ),
        PodcastShow(
            "naofangdianbo", "脑放电波", "脑放电波",
            "https://feed.xyzfm.space/wupdmt9er7nb",
            "科学、技术与现代社会话题",
            cover = "https://image.xyzcdn.net/FvE6LbjcXht5T7prhU59SQNqnTdB.png",
            category = "轻松闲聊"
        ),
        PodcastShow(
            "kanlixiang", "看理想圆桌", "看理想",
            "https://api.vistopia.com.cn/rss/program/13.xml",
            "人文知识分子的圆桌对谈",
            cover = "https://cdn.vistopia.com.cn/1679020549051.jpg",
            category = "人文对谈"
        ),
        PodcastShow(
            "yanzhonghuashu", "岩中花述", "岩中花述",
            "https://feed.xyzfm.space/hwen8wf69c6g",
            "女性人物深度访谈",
            cover = "https://image.xyzcdn.net/Fhcbb6JgoBoehDn8iex2HR3cbx9g",
            category = "故事纪实"
        ),

        // ---- 2026-11-23 新增 11 档：全部本机实测 RSS 200 / 有分集 / 音频直链 206 ----
        // 人文对谈
        PodcastShow(
            "liangwendao", "梁文道·八分", "看理想",
            "https://api.vistopia.com.cn/rss/program/11.xml",
            "梁文道讲文化、历史与当下",
            cover = "https://cdn.vistopia.com.cn/img/podcast-bafen.jpg",
            category = "人文对谈"
        ),
        PodcastShow(
            "zhenbianfeng", "枕边风", "枕边风",
            "https://rss.lizhi.fm/rss/490028.xml",
            "两个人聊书、电影与生活的私房话",
            cover = "https://cdn.lizhi.fm/podcast_cover/2026/03/20/3201408457022232135.jpg",
            category = "人文对谈"
        ),
        PodcastShow(
            "xinrui", "信睿播客", "信睿",
            "https://rss.lizhi.fm/rss/195684579.xml",
            "思想、文化与社会议题对谈",
            cover = "https://cdn.lizhi.fm/podcast_cover/2021/12/29/2915253896971030087.jpg",
            category = "人文对谈"
        ),
        PodcastShow(
            "kandilishi", "看理想时刻", "看理想",
            "https://api.vistopia.com.cn/rss/program/333.xml",
            "看理想讲者的短篇声音专栏",
            cover = "https://cdn5.vistopia.com.cn/3a257b79-db75-439e-a767-cfa23e293b0c.jpg",
            category = "人文对谈"
        ),
        // 科技数码
        PodcastShow(
            "sspai", "少数派播客", "少数派",
            "https://sspai.typlog.io/feed/audio.xml",
            "高效工作、数字生活与科技应用",
            cover = "https://i.typlog.com/sspai/8339963934_163514.png?x-oss-process=style/sl",
            category = "科技数码"
        ),
        // 商业观察
        PodcastShow(
            "fengtouquan", "疯投圈", "疯投圈",
            "https://crazy.capital/feed",
            "消费、投资与商业趋势的深度对谈",
            cover = "https://crazy.capital/assets/icon-square-normal-dark-cn@4x.png",
            category = "商业观察"
        ),
        PodcastShow(
            "meilixiang", "没理想编辑部", "看理想",
            "https://api.vistopia.com.cn/rss/program/116.xml",
            "编辑部聊文化热点与日常",
            cover = "https://cdn.vistopia.com.cn/img/podcast-mlxfm-v1.jpg",
            category = "商业观察"
        ),
        // 故事纪实
        PodcastShow(
            "chuizhiruxi", "垂直入戏", "故事FM",
            "https://feeds.storyfm.cn/dive-in.xml",
            "故事FM 出品的沉浸纪实故事",
            cover = "https://media-one.wavpub.com/covers/1a747605-5636-4bf4-8853-164b97e5d2b0/5318d3d97a719f67.jpg",
            category = "故事纪实"
        ),
        PodcastShow(
            "xianzhe", "贤者时间", "贤者时间",
            "https://rss.lizhi.fm/rss/137289307.xml",
            "两个女性的生活观察与闲谈",
            cover = "https://cdn.lizhi.fm/podcast_cover/2019/08/31/2757429913142982215.jpg",
            category = "故事纪实"
        ),
        // 轻松闲聊
        PodcastShow(
            "yile", "怡楽播客", "怡楽",
            "https://s2.proxy.wavpub.com/yeelokradio.xml",
            "轻松闲聊生活与文化",
            cover = "https://cdn.lizhi.fm/podcast_cover/2024/02/19/3060348109531479623.jpg",
            category = "轻松闲聊"
        ),
        PodcastShow(
            "xiaofangjian", "小房间", "小房间",
            "https://proxy.wavpub.com/xiaofangjian.xml",
            "粤语闲聊，聊天南地北",
            cover = "https://image.xyzcdn.net/FuD-sHlw52xuosffd_RgulC5JUsX.jpg",
            category = "轻松闲聊"
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
     * 书架展示顺序：按分类的固定顺序分节，节内保持节目单里的手写顺序。
     *
     * 和有声书一样不交给排序规则：中文按 Unicode 排出来是乱的，
     * 而「哪档挨着哪档」本来就是编节目单时想好的。
     * 远端目录里没写分类（或写了个没见过的）的，统一落到最后一节「未分类」，一档都不丢。
     */
    val shelf: List<PodcastShow>
        get() = CATEGORIES.flatMap { c -> active.filter { it.category == c } } +
            active.filter { it.category !in CATEGORIES }

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
     * logo 用专辑封面——和书架上看到的是同一张图，两处对得上才认得出是同一档。
     */
    fun asChannel(show: PodcastShow): Channel = Channel(
        name = show.name,
        group = ChannelGroups.PODCAST,
        logo = show.cover,
        urls = emptyList(),
        isAudio = true,
        isPodcast = true,
        // 第二行显示「分类 · 作者」：右栏列表里也能一眼看出这档属于哪一类
        subtitle = listOf(show.category, show.author).filter { it.isNotEmpty() }.joinToString(" · ")
    )

    fun byName(name: String): PodcastShow? =
        active.firstOrNull { it.name == name } ?: AudiobookCatalog.byName(name)
}
