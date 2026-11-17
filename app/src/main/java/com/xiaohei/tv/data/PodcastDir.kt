package com.xiaohei.tv.data

import com.xiaohei.tv.model.PodcastShow

/**
 * 远端播客目录（`podcasts.json`）的解析、校验与合并。
 *
 * 目录放在 GitHub 上，随时可以改、不用发版——好处是改完电视上重启就生效，
 * 代价是它是**不可信输入**：
 *  - `id` 会被当作缓存文件名（`files/podfeed/<id>.tsv`）和进度键，
 *    必须挡住 `../`、`/`、超长、以及任何非 ASCII，否则一份恶意 JSON 就能写到应用私有目录之外；
 *  - `feed` 只会被拿去发心跳请求，但也要挡 `file://`、`javascript:` 这类协议；
 *  - 条数、字段长度、标题长度都要有上限，老电视的列表经不起几百档。
 *
 * 校验失败的**单条记录**直接丢掉，不影响其他记录；整份解析失败才退回内置清单。
 */
object PodcastDir {

    /** 一档播客一行；40 档已经是遥控器能翻的极限 */
    const val MAX_SHOWS = 40

    private const val MAX_NAME = 24
    private const val MAX_AUTHOR = 24
    private const val MAX_DESC = 60
    private const val MAX_FEED = 400
    private const val MAX_COVER = 400
    private const val MAX_CATEGORY = 8

    /** id 只允许小写字母/数字/下划线/短横，且必须以字母数字开头：同时满足文件名安全 */
    private val idRegex = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

    /**
     * 解析结果。
     * [shows] 是新目录里的节目（按文件里的顺序），
     * [removed] 是显式 `"enabled": false` 的 id（用来下线内置节目）。
     */
    data class Result(
        val shows: List<PodcastShow>,
        val removed: Set<String> = emptySet(),
        val version: Int = 0
    )

    /** 整份文件不可用时返回 null（调用方退回内置清单 / 旧缓存） */
    fun parse(text: String): Result? {
        if (text.isBlank()) return null
        val root = try {
            MiniJson.parse(text)
        } catch (e: Exception) {
            return null
        } as? Map<*, *> ?: return null

        val rawShows = root["shows"] as? List<*> ?: return null
        val version = (root["version"] as? Double)?.toInt() ?: 0

        val shows = ArrayList<PodcastShow>(rawShows.size)
        val removed = HashSet<String>()
        val seen = HashSet<String>()
        for (item in rawShows) {
            if (shows.size >= MAX_SHOWS) break
            val o = item as? Map<*, *> ?: continue
            val id = (o["id"] as? String)?.trim()?.lowercase().orEmpty()
            if (!idRegex.matches(id) || !seen.add(id)) continue

            // enabled 缺省为 true；单独写成 false 的条目只需要 id，用来下线内置节目
            val enabled = o["enabled"] as? Boolean ?: true
            if (!enabled) {
                removed.add(id)
                continue
            }

            val name = (o["name"] as? String)?.trim().orEmpty().take(MAX_NAME)
            val feed = (o["feed"] as? String)?.trim().orEmpty()
            if (name.isEmpty() || !isHttpUrl(feed) || feed.length > MAX_FEED) continue
            val author = (o["author"] as? String)?.trim().orEmpty().take(MAX_AUTHOR)
            val desc = (o["desc"] as? String)?.trim().orEmpty().take(MAX_DESC)
            // 封面上书架用：只认 http(s)，其它一律当没写（书架会退回首字兑底）
            val cover = (o["cover"] as? String)?.trim().orEmpty()
            // 分类决定它落在书架的哪一节；写了个不认识的分类也不会丢，会落到「其他」
            val category = (o["category"] as? String)?.trim().orEmpty().take(MAX_CATEGORY)

            shows.add(
                PodcastShow(
                    id = id, name = name, author = author, feed = feed, desc = desc,
                    cover = if (isHttpUrl(cover) && cover.length <= MAX_COVER) cover else "",
                    category = category
                )
            )
        }
        // 全是废条目等同于「这份文件没用」，别拿它把列表清空
        if (shows.isEmpty() && removed.isEmpty()) return null
        return Result(shows = shows, removed = removed, version = version)
    }

    /**
     * 合并顺序：远端在前（顺序完全由文件控制），
     * 内置里**没有被文件提到**的排在后面兜底——这样万一远端文件漏了一档、
     * 或者哪天仓库挂了，内置的那些仍然在，不至于整个播客分组消失。
     * 想真正下线一档内置节目，就在文件里写 `"enabled": false`。
     */
    fun merge(
        builtin: List<PodcastShow>,
        remote: List<PodcastShow>,
        removed: Set<String>
    ): List<PodcastShow> {
        // 远端文件只负责「有哪些节目、什么顺序」，封面和分类通常是它不知道的部分
        // （手写 JSON 里几乎不会写这两栏）。所以同 id / 同 feed 的条目要拿本地的补上：
        // 不补的话，远端清单一覆盖，书架就会变成一墙没有封面的灰格子，
        // 分类也全空、全挤进「其他」——这正是线上踩到的样子。
        val localById = builtin.associateBy { it.id }
        val localByFeed = builtin.associateBy { it.feed }

        val out = ArrayList<PodcastShow>(builtin.size + remote.size)
        val ids = HashSet<String>()
        for (s in remote) {
            if (s.id in removed || !ids.add(s.id)) continue
            val local = localById[s.id] ?: localByFeed[s.feed]
            // 认出来的本地条目要算「已经被远端认领」，否则下面兜底那轮又会按老 id 加一遍，
            // 同一档播客在书架上出现两次
            if (local != null) ids.add(local.id)
            out.add(
                if (local == null) s else s.copy(
                    author = s.author.ifEmpty { local.author },
                    desc = s.desc.ifEmpty { local.desc },
                    cover = s.cover.ifEmpty { local.cover },
                    category = s.category.ifEmpty { local.category }
                )
            )
        }
        for (s in builtin) {
            if (s.id in removed || s.id in ids) continue
            out.add(s)
        }
        return if (out.size > MAX_SHOWS) out.subList(0, MAX_SHOWS).toList() else out
    }

    private fun isHttpUrl(u: String): Boolean =
        (u.startsWith("http://") || u.startsWith("https://")) && u.length > 10 &&
            !u.contains(' ') && !u.startsWith("https:///")
}
