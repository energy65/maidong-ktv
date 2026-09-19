package com.local.ktv

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * B站卡拉OK视频搜索（移植自 ktv-song-web 后端 utils.ts）。
 *
 * 技术点：
 * 1. 检索策略：1 路纯关键词直搜 + 4 路「关键词+卡拉OK标签」并发搜索，按 bvid 合并去重；
 *    直搜结果信任 B站自身排序，绕过本地相关性过滤（兼容罗马音↔假名跨文字查询）。
 * 2. 接口兜底：先走 legacy 搜索接口，失败后走 WBI 签名接口
 *    （nav 取 imgKey/subKey → MIXIN_KEY 置换表取 32 位 mixinKey → 参数+wts 排序后 MD5 出 w_rid）。
 * 3. 相关性评分：标题规范化后完整匹配+200、子串+120、位置加分、token 命中加分，宁缺勿滥。
 * 4. 排序：有卡拉OK标签优先 → 相关分 → 点击热度（本地持久化）→ 标签优先级 → 标题。
 * 5. 播放：结果映射为 Song(category="B站", remote=true)，videoUrl 为
 *    bilibili://video/BVxxx 深链，唤起 B站 App；无 B站 App 时回退浏览器网页版。
 */
object BilibiliSearchClient {
    private const val TAG = "BiliSearch"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.bilibili.com/"
    private const val SHORT_HOST = "b23.tv"
    private const val FALLBACK_COOKIE = "buvid3=codex-search"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    /** 用于构造 B站搜索查询的标签后缀（改动会增加接口调用量）。 */
    private val SEARCH_TAGS = arrayOf("カラオケ", "ニコカラ", "纯k自用", "卡拉OK")

    /** 在标题中检测的卡拉OK标签（仅检测标题，查询标签本身不可靠）。 */
    private val DETECT_TAGS = arrayOf("ニコカラ", "カラオケ", "纯k自用", "纯k投屏", "卡拉OK")
    private val TAG_PRIORITY = mapOf(
        "ニコカラ" to 0, "カラオケ" to 1, "纯k自用" to 2, "纯k投屏" to 2, "卡拉OK" to 3,
    )
    private val TAG_MATCHERS = mapOf(
        "ニコカラ" to Regex("(ニコカラ|nicokara)", RegexOption.IGNORE_CASE),
        "カラオケ" to Regex("(カラオケ|karaoke)", RegexOption.IGNORE_CASE),
        "纯k自用" to Regex("(纯k自用)", RegexOption.IGNORE_CASE),
        "纯k投屏" to Regex("(纯k投屏|投屏自用)", RegexOption.IGNORE_CASE),
        "卡拉OK" to Regex("(卡拉OK|ktv字幕)", RegexOption.IGNORE_CASE),
    )

    /** WBI 签名的密钥混淆置换表。 */
    private val WBI_MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32,
        15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19,
        29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63,
        57, 62, 11, 36, 20, 34, 44, 52,
    )

    private val INSTRUMENTAL_PATTERNS = listOf(
        Regex("\\boff[\\s_-]*vocal\\b", RegexOption.IGNORE_CASE),
        Regex("\\boffvocal\\b", RegexOption.IGNORE_CASE),
        Regex("\\bno[\\s_-]*vocal\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwithout[\\s_-]*vocal\\b", RegexOption.IGNORE_CASE),
        Regex("\\binstrumental\\b", RegexOption.IGNORE_CASE),
        Regex("\\binst(?:\\.|rumental)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bkaraoke(?:\\s+ver(?:sion)?)?\\b", RegexOption.IGNORE_CASE),
        Regex("オフボーカル"),
        Regex("伴奏"),
    )
    private val VOCAL_PATTERNS = listOf(
        Regex("\\bon[\\s_-]*vocal\\b", RegexOption.IGNORE_CASE),
        Regex("\\bonvocal\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwith[\\s_-]*vocal\\b", RegexOption.IGNORE_CASE),
        Regex("人声"),
        Regex("原唱"),
    )

    /** 关键词结果缓存：LRU + 24h 过期（单机等价于源项目的 Redis 缓存）。 */
    private const val CACHE_MAX_SIZE = 50
    private const val CACHE_TTL_MS = 24 * 3600_000L
    private val searchCache = object : LinkedHashMap<String, Pair<Long, List<Song>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Song>>>): Boolean =
            size > CACHE_MAX_SIZE
    }

    private var clickCounts: MutableMap<String, JSONObject>? = null

    /** 检索结果内部承载结构，最终映射为 [Song]。 */
    private class BiliVideo {
        var bvid = ""
        var title = ""
        var author = ""
        val tags = LinkedHashSet<String>()
        val rawTags = LinkedHashSet<String>()
    }

    /** 搜索 B站卡拉OK视频，返回按相关性+热度排序的 Song 列表（须在后台线程调用）。 */
    @JvmStatic
    fun search(keyword: String): List<Song> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val cacheKey = trimmed.lowercase()
        synchronized(searchCache) {
            searchCache[cacheKey]?.let { (ts, songs) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return songs
            }
        }
        val counts = loadClickCounts()
        val result = searchInternal(trimmed, counts)
        synchronized(searchCache) { searchCache[cacheKey] = System.currentTimeMillis() to result }
        return result
    }

    /** 记录点击热度（点击结果后调用，持久化到 AppPaths.biliClicksFile）。 */
    @JvmStatic
    fun recordClick(bvid: String) {
        if (bvid.isEmpty()) return
        synchronized(this) {
            val counts = loadClickCounts()
            val entry = counts[bvid] ?: JSONObject()
            entry.put("count", entry.optInt("count", 0) + 1)
            entry.put("lastClickMs", System.currentTimeMillis())
            counts[bvid] = entry
            saveClickCounts(counts)
        }
        synchronized(searchCache) { searchCache.clear() }
    }

    /** 标题疑似伴奏版（off vocal / instrumental / 伴奏 / オフボーカル），且未标注人声。 */
    @JvmStatic
    fun isInstrumental(text: String): Boolean {
        val value = stripHtml(text)
        if (value.isEmpty()) return false
        if (VOCAL_PATTERNS.any { it.containsMatchIn(value) }) return false
        return INSTRUMENTAL_PATTERNS.any { it.containsMatchIn(value) }
    }

    /** 网页版回退地址（page 为 0 基，网页端 p 参数为 1 基）。 */
    @JvmStatic
    fun browserUrl(bvid: String, page: Int): String =
        "https://www.bilibili.com/video/$bvid?p=${page.coerceAtLeast(0) + 1}"

    /**
     * 解析 b23.tv 短链 / bilibili.com 网页地址 / 纯 BV 号为
     * bilibili://video/BVxxx?page=N（page 为 0 基）深链；无法识别返回 null。
     */
    @JvmStatic
    fun resolveLink(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 已是内部协议：规范化并保留 page。
        if (trimmed.startsWith("bilibili://")) {
            val fake = "https://" + trimmed.removePrefix("bilibili://")
            extractBvid(fake)?.let { bv ->
                val page = Regex("[?&]page=(\\d+)").find(fake)?.groupValues?.get(1)?.toIntOrNull()
                return if (page != null) "bilibili://video/$bv?page=${page.coerceAtLeast(0)}" else "bilibili://video/$bv"
            }
            return null
        }

        // 纯 BV 号。
        if (Regex("^BV[a-zA-Z0-9]{10}$").matches(trimmed)) return "bilibili://video/$trimmed"

        val normalized = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        val url = runCatching { URL(normalized) }.getOrNull() ?: return null
        val host = url.host.lowercase()
        val isBiliHost = host == SHORT_HOST || host == "bilibili.com" || host.endsWith(".bilibili.com")
        if (!isBiliHost) return null

        var target = normalized
        if (host == SHORT_HOST) {
            // 短链：禁止自动重定向，读 Location 头拿到真实地址。
            val connection = URL(normalized).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.responseCode
                connection.getHeaderField("location")?.let {
                    target = if (it.startsWith("http")) it else "https://b23.tv$it"
                }
            } catch (e: Exception) {
                Log.w(TAG, "b23.tv 短链解析失败", e)
            } finally {
                connection.disconnect()
            }
        }

        val bvid = extractBvid(target) ?: return null
        val page = Regex("[?&]p=(\\d+)").find(target)?.groupValues?.get(1)?.toIntOrNull()
        // 网页端 p 参数为 1 基，App 深链 page 为 0 基。
        return if (page != null && page > 0) "bilibili://video/$bvid?page=${page - 1}" else "bilibili://video/$bvid"
    }

    // ---------------------------------------------------------------- 内部实现

    private fun searchInternal(keyword: String, clickCounts: Map<String, JSONObject>): List<Song> {
        val videos = LinkedHashMap<String, BiliVideo>()
        val directBvids = HashSet<String>()

        val cookie = getBilibiliCookie()
        val keys = runCatching { getBilibiliWbiKeys(cookie) }.getOrNull()

        fun searchByKeyword(kw: String): List<JSONObject> =
            searchBilibiliVideosByKeyword(kw, cookie, keys)

        fun addToMap(item: JSONObject, tag: String?) {
            val bvid = item.optString("bvid").trim()
            if (bvid.isEmpty()) return
            val current = videos[bvid]
            if (current != null) {
                if (tag != null) {
                    val title = stripHtml(item.optString("title", current.title))
                    if (TAG_MATCHERS[tag]?.containsMatchIn(title) == true) current.tags.add(tag)
                }
                return
            }
            val video = BiliVideo()
            video.bvid = bvid
            video.title = stripHtml(item.optString("title", bvid))
            video.author = stripHtml(item.optString("author"))
            parseRawTags(item.optString("tag")).forEach(video.rawTags::add)
            DETECT_TAGS.filter { TAG_MATCHERS[it]?.containsMatchIn(video.title) == true }.forEach(video.tags::add)
            videos[bvid] = video
        }

        // 1 路直搜 + N 路带标签后缀并发检索。
        val tasks = mutableListOf<() -> Unit>()
        tasks.add {
            runCatching {
                searchByKeyword(keyword).forEachIndexed { index, item ->
                    val bvid = item.optString("bvid").trim()
                    if (bvid.isNotEmpty() && index < 10) directBvids.add(bvid)
                    addToMap(item, null)
                }
            }.onFailure { Log.w(TAG, "B站直搜失败: $keyword", it) }
        }
        SEARCH_TAGS.forEach { tag ->
            tasks.add {
                val kw = "$keyword $tag"
                runCatching {
                    searchByKeyword(kw).take(20).forEach { addToMap(it, tag) }
                }.onFailure { Log.w(TAG, "B站标签搜索失败: $kw", it) }
            }
        }
        runInParallel(tasks)

        val items = videos.values.toList()
        // 相关性过滤：直搜结果信任 B站排序直接放行，其余按评分宁缺勿滥。
        val relevant = items.filter { it.bvid in directBvids || scoreBiliVideo(it, keyword) > 0 }
        val sorted = sortBiliVideos(relevant, keyword, clickCounts)
        return sorted.take(80).map { it.toSong(clickCounts[it.bvid]?.optInt("count", 0) ?: 0) }
    }

    /** legacy 接口优先，失败回退 WBI 签名接口。 */
    private fun searchBilibiliVideosByKeyword(
        keyword: String,
        cookie: String,
        wbiKeys: Pair<String, String>?,
    ): List<JSONObject> {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER, "Cookie" to cookie)
        val legacy = runCatching {
            val params = mapOf("search_type" to "video", "keyword" to keyword, "page" to "1")
            val body = httpGet("https://api.bilibili.com/x/web-interface/search/type?" + buildQuery(params), headers)
            val response = JSONObject(body)
            if (response.optInt("code") == 0) response.optJSONObject("data")?.optJSONArray("result") else null
        }.getOrNull()
        if (legacy != null) return legacy.toList()

        val (imgKey, subKey) = wbiKeys ?: throw IllegalStateException("B站 WBI 密钥获取失败")
        val query = encWbi(mapOf("search_type" to "video", "keyword" to keyword, "page" to "1"), imgKey, subKey)
        val body = httpGet("https://api.bilibili.com/x/web-interface/wbi/search/type?$query", headers)
        val response = JSONObject(body)
        check(response.optInt("code") == 0) { "B站搜索失败: ${response.optString("message", response.optInt("code").toString())}" }
        return response.optJSONObject("data")?.optJSONArray("result")?.toList() ?: emptyList()
    }

    /** 访问 B站首页取 set-cookie（补 buvid3），未取到用占位值。 */
    private fun getBilibiliCookie(): String = runCatching {
        val connection = URL("https://www.bilibili.com/").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", REFERER)
            connection.responseCode
            val cookies = connection.headerFields
                ?.filterKeys { it.equals("set-cookie", ignoreCase = true) }
                ?.values
                ?.flatten()
                ?.map { it.substringBefore(';').trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val result = cookies.toMutableList()
            if (result.none { it.startsWith("buvid3=") }) result.add(FALLBACK_COOKIE)
            result.joinToString("; ")
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(FALLBACK_COOKIE)

    /** 从 nav 接口取 WBI imgKey/subKey。 */
    private fun getBilibiliWbiKeys(cookie: String): Pair<String, String> {
        val body = httpGet(
            "https://api.bilibili.com/x/web-interface/nav",
            mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER, "Cookie" to cookie),
        )
        val wbiImg = JSONObject(body).optJSONObject("data")?.optJSONObject("wbi_img")
        val imgKey = wbiImg?.optString("img_url")?.substringAfterLast('/')?.substringBefore('.')
        val subKey = wbiImg?.optString("sub_url")?.substringAfterLast('/')?.substringBefore('.')
        check(!imgKey.isNullOrEmpty() && !subKey.isNullOrEmpty()) { "B站 WBI 密钥获取失败" }
        return imgKey to subKey
    }

    /** WBI 签名：参数+wts 按 key 排序拼接后 MD5（query+mixinKey）= w_rid。 */
    private fun encWbi(params: Map<String, String>, imgKey: String, subKey: String): String {
        val mixinKey = getMixinKey(imgKey + subKey)
        val sanitized = LinkedHashMap<String, String>().apply {
            putAll(params.mapValues { it.value.replace(Regex("[!'()*]"), "") })
            put("wts", (System.currentTimeMillis() / 1000).toString())
        }
        val query = buildQuery(sanitized) // TreeMap 按 key 升序
        val wRid = md5(query + mixinKey)
        return "$query&w_rid=$wRid"
    }

    private fun getMixinKey(origin: String): String {
        val mixed = StringBuilder()
        for (index in WBI_MIXIN_KEY_ENC_TAB) {
            if (index < origin.length) mixed.append(origin[index])
        }
        return mixed.substring(0, minOf(32, mixed.length))
    }

    private fun extractBvid(target: String): String? =
        Regex("/BV[a-zA-Z0-9]{10}", RegexOption.IGNORE_CASE).find(target)?.value?.removePrefix("/")

    private fun parseRawTags(tagText: String): List<String> =
        if (tagText.isEmpty()) emptyList()
        else tagText.split(',', '，').map { stripHtml(it).trim() }.filter { it.isNotEmpty() }

    private fun stripHtml(input: String): String = input
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toInt().toChar().toString()
        }
        .trim()

    /** 规范化文本：去大小写与全半角分隔符（B站标题常用 ／ ～ 等分隔，会阻断子串匹配）。 */
    private fun normalizeSearchText(input: String): String = input.lowercase().replace(
        Regex("[\\s\\-_.|/\\\\()\\[\\]{}【】「」『』（）'\"`~!@#$%^&*+=,，。！？：:；;／～·　・×♪♫★☆]"),
        "",
    )

    private fun scoreBiliVideo(item: BiliVideo, keyword: String): Int {
        val normalizedKeyword = normalizeSearchText(keyword)
        if (normalizedKeyword.isEmpty()) return 0

        val titleText = normalizeSearchText(item.title)
        val tagsText = normalizeSearchText(item.tags.joinToString(" "))
        val haystack = "$titleText $tagsText"
        if (titleText.isEmpty()) return 0

        val stopwords = setOf(
            "the", "and", "for", "with", "from", "this", "that", "you", "your", "are", "was", "were", "to", "of", "in", "on", "at",
            "feat", "ft",
        )
        val tokens = keyword.lowercase()
            .split(Regex("[\\s\\-_.|/\\\\()\\[\\]{}【】「」『』（）'\"`~!@#$%^&*+=,，。！？：:；;]+"))
            .map(::normalizeSearchText)
            .filter { it.length >= 3 && it !in stopwords }
        val tokenHits = tokens.count { titleText.contains(it) }

        // 宁缺勿滥：完整匹配放行；否则要求多 token 证据。
        val fullMatch = titleText.contains(normalizedKeyword)
        if (!fullMatch) {
            if (tokens.isEmpty()) return 0
            if (tokens.size == 1) {
                if (!titleText.contains(tokens[0]) || tokens[0].length < 5) return 0
            } else if (tokens.size == 2) {
                if (tokenHits < 2) return 0
            } else {
                val longHit = tokens.any { it.length >= 5 && titleText.contains(it) }
                if (tokenHits < 2 || !longHit) return 0
            }
        }

        var score = 10
        if (titleText == normalizedKeyword) score += 200
        if (titleText.contains(normalizedKeyword)) score += 120
        if (tagsText.contains(normalizedKeyword)) score += 10
        val firstIndex = haystack.indexOf(normalizedKeyword)
        if (firstIndex >= 0) score += maxOf(0, 40 - firstIndex)
        score += maxOf(0, 20 - maxOf(0, item.title.length - keyword.length))
        if (tokens.isNotEmpty()) score += tokenHits * 35
        return score
    }

    private fun sortBiliVideos(
        items: List<BiliVideo>,
        keyword: String,
        clickCounts: Map<String, JSONObject>,
    ): List<BiliVideo> {
        val normalizedKeyword = normalizeSearchText(keyword)
        val scoreOf: (BiliVideo) -> Int = { if (normalizedKeyword.isEmpty()) 0 else scoreBiliVideo(it, keyword) }
        return items.sortedWith(
            compareByDescending<BiliVideo> { video -> video.tags.any { (TAG_PRIORITY[it] ?: 99) <= 3 } }
                .thenByDescending(scoreOf)
                .thenByDescending { clickCounts[it.bvid]?.optInt("count", 0) ?: 0 }
                .thenBy { video -> video.tags.map { TAG_PRIORITY[it] ?: 99 }.minOrNull() ?: 99 }
                .thenByDescending { it.tags.size }
                .thenBy { it.title },
        )
    }

    private fun BiliVideo.toSong(clickCount: Int): Song = Song().apply {
        id = bvid
        title = this@toSong.title
        singer = author.ifEmpty { "B站UP主" }
        category = "B站"
        language = inferLanguage(this@toSong.title)
        pinyin = ""
        album = ""
        path = ""
        downloadUrl = ""
        videoUrl = "bilibili://video/$bvid"
        originalUrl = ""
        accompanyUrl = ""
        lyricUrl = ""
        remote = true
        playCount = clickCount
    }

    private fun inferLanguage(title: String): String {
        val text = title.lowercase()
        return when {
            containsAny(text, "粤语", "广东", "香港", "cantonese") -> "粤语"
            containsAny(text, "闽南", "台语", "hokkien") -> "闽南语"
            containsAny(text, "英语", "英文", "english") -> "英语"
            Regex("[ぁ-んァ-ヶ]").containsMatchIn(title) || containsAny(text, "日语", "日本", "japanese") -> "日语"
            containsAny(text, "韩语", "韩国", "korean") -> "韩语"
            else -> "国语"
        }
    }

    private fun containsAny(text: String, vararg keys: String): Boolean = keys.any { text.contains(it) }

    /** 点击热度：懒加载 AppPaths.biliClicksFile，365 天过期清理。 */
    private fun loadClickCounts(): MutableMap<String, JSONObject> {
        synchronized(this) {
            clickCounts?.let { return it }
            val map = HashMap<String, JSONObject>()
            runCatching {
                val file = AppPaths.biliClicksFile
                if (file.exists()) {
                    val root = JSONObject(file.readText())
                    val now = System.currentTimeMillis()
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val bvid = keys.next()
                        val entry = root.optJSONObject(bvid) ?: continue
                        if (now - entry.optLong("lastClickMs", 0L) > 365L * 24 * 3600_000L) continue
                        map[bvid] = entry
                    }
                }
            }.onFailure { Log.w(TAG, "读取点击热度失败", it) }
            clickCounts = map
            return map
        }
    }

    private fun saveClickCounts(counts: Map<String, JSONObject>) {
        runCatching {
            val root = JSONObject()
            counts.forEach { (bvid, entry) -> root.put(bvid, entry) }
            val file = AppPaths.biliClicksFile
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "保存点击热度失败", it) }
    }

    // ------------------------------------------------------------ 网络与工具

    private fun runInParallel(tasks: List<() -> Unit>) {
        val threads = tasks.map { Thread(it) }
        threads.forEach { it.isDaemon = true; it.start() }
        threads.forEach { it.join(30_000) }
    }

    /** key 升序的 x-www-form-urlencoded 查询串（与 URLSearchParams 行为一致）。 */
    private fun buildQuery(params: Map<String, String>): String =
        params.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun org.json.JSONArray.toList(): List<JSONObject> {
        val result = ArrayList<JSONObject>(length())
        for (i in 0 until length()) {
            optJSONObject(i)?.let(result::add)
        }
        return result
    }
}
