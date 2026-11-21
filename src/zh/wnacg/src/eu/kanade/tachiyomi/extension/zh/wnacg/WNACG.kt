package eu.kanade.tachiyomi.extension.zh.wnacg

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.source.MirrorPreferences
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toMarkdown
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

@Source
abstract class WNACG :
    KeiSource(),
    ConfigurableSource {
    private val preferences = getPreferences { preferenceMigration() }

    // The site's release page leaves out some mirrors
    override suspend fun fetchBaseUrls(client: OkHttpClient): List<String> = client.get(RELEASE_PAGE_URL, Fetch.DOCUMENT).asJsoup()
        .selectFirst("script:containsData(window.__INITIAL_CONFIG__=)")!!.data()
        .substringAfter("__INITIAL_CONFIG__=").substringBeforeLast(";")
        .parseAs<ReleasePageConfig>().sites.map { it.url } + UNLISTED_MIRRORS

    // Hosts without "www.", which links can leave out
    private val mirrorHosts: List<String>
        get() = (fetchableBaseUrl as MirrorPreferences).mirrors.map { it.second.toHttpUrl().host.removePrefix("www.") }

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(::loginHintInterceptor)
        // Cloudflare answers 429 past 60 page requests a minute
        rateLimit(50, 1.minutes) { it.host.removePrefix("www.") in mirrorHosts }
        addCookie { preferences.loginCookies }
    }

    // Mobile user agents get a mobile layout
    override fun Headers.Builder.configureHeaders(): Headers.Builder {
        val chromeVersion = chromeVersionRegex.find(get("User-Agent").orEmpty())?.groupValues?.get(1) ?: "156"
        return set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion.0.0.0 Safari/537.36")
    }

    // Guests get 404 on some galleries and on login-only categories
    private fun loginHintInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val path = request.url.encodedPath
        if (response.code == 404 && (path.startsWith("/photos-") || unlistedCategoryId(path) != null)) {
            response.close()
            throw IOException(LOGIN_HINT)
        }
        return response
    }

    private val popularPagingState = FilterPagingState()

    override suspend fun getPopularManga(page: Int): MangasPage {
        val blacklist = preferences.titleBlacklist
        if (blacklist.isEmpty()) {
            popularPagingState.reset()
            return mangaListParse(client.get(popularMangaUrl(page), Fetch.DOCUMENT)).filterBlockedTitles()
        }
        val maxScanPages = preferences.blacklistMaxScanPages

        return fetchFilteredMangaPage(
            appPage = page,
            key = "$maxScanPages|${blacklist.joinToString("\u0000")}",
            state = popularPagingState,
            urlForPage = ::popularMangaUrl,
            blacklist = blacklist,
            maxScanPages = maxScanPages,
        )
    }

    private fun popularMangaUrl(page: Int, period: String = "week") = "$baseUrl/albums-favorite_ranking-page-$page-type-$period.html"

    private val latestPagingState = FilterPagingState()

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val blacklist = preferences.titleBlacklist
        if (blacklist.isEmpty()) {
            latestPagingState.reset()
            return mangaListParse(client.get(latestUpdatesUrl(page), Fetch.DOCUMENT)).filterBlockedTitles()
        }
        val maxScanPages = preferences.blacklistMaxScanPages

        return fetchFilteredMangaPage(
            appPage = page,
            key = "$maxScanPages|${blacklist.joinToString("\u0000")}",
            state = latestPagingState,
            urlForPage = ::latestUpdatesUrl,
            blacklist = blacklist,
            maxScanPages = maxScanPages,
        )
    }

    private fun latestUpdatesUrl(page: Int) = "$baseUrl/albums-index-page-$page.html"

    private val searchPagingState = FilterPagingState()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val blacklist = preferences.titleBlacklist
        if (!preferences.filterSearchResults || blacklist.isEmpty()) {
            searchPagingState.reset()
            return mangaListParse(client.get(searchMangaUrl(page, query, filters), Fetch.DOCUMENT))
        }

        val urlForPage = { sourcePage: Int -> searchMangaUrl(sourcePage, query, filters) }
        val maxScanPages = preferences.blacklistMaxScanPages
        return fetchFilteredMangaPage(
            appPage = page,
            key = urlForPage(1) + "|$maxScanPages|" + blacklist.joinToString("\u0000"),
            state = searchPagingState,
            urlForPage = urlForPage,
            blacklist = blacklist,
            maxScanPages = maxScanPages,
        )
    }

    private fun searchMangaUrl(page: Int, query: String, filters: FilterList): String {
        if (query.isBlank()) {
            val tagFilter = filters.firstInstanceOrNull<TagFilter>()
            if (tagFilter != null && tagFilter.state.isNotEmpty()) {
                return baseUrl.toHttpUrl().newBuilder()
                    .addPathSegment("albums-index-page-$page-tag-${tagFilter.state}.html")
                    .build()
                    .toString()
            }
            val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
            if (categoryFilter != null && categoryFilter.toUriPart().isNotEmpty()) {
                return "$baseUrl/${categoryFilter.toUriPart().format(page)}"
            }
            return popularMangaUrl(page, filters.firstInstanceOrNull<RankingFilter>()?.toUriPart() ?: "week")
        }
        return "$baseUrl/search/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("s", filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "create_time_DESC")
            .addQueryParameter("q", query)
            .addQueryParameter("p", page.toString())
            .build()
            .toString()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        // Links can come from any of the site's mirrors
        if (url.host.removePrefix("www.") !in mirrorHosts) return null
        val (aid, sid) = mangaUrlRegex.matchEntire(url.encodedPath)?.destructured ?: return null
        val path = "/photos-index-aid-${sid.ifEmpty { aid }}.html"

        return mangaDetailsParse(client.get(baseUrl + path, Fetch.DOCUMENT).asJsoup()).apply {
            this.url = path
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(
            mangaDetailsParse(document).apply { url = manga.url },
            if (fetchChapters) chaptersParse(document, manga) else chapters,
        )
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val title = document.selectFirst("h2")?.text() ?: throw IOException(LOGIN_HINT)
        this.title = title
        val tags = document.select("div.addtags a.tagshow").eachText()
        val (authorName, artistName) = extractNames(tags, title)
        author = authorName
        artist = artistName
        val category = document.selectFirst("div.uwconn label:contains(分類：)")?.text()
            ?.removePrefix("分類：")?.split('／').orEmpty()
        genre = (
            category + tags.filter { tag ->
                val isMatch = TAGS_FILTER_REGEX_LIST.any { regex -> tag.matches(regex) }
                !isMatch && !TAGS_EXCLUDE_LIST.contains(tag)
            }
            ).distinct().joinToString().ifEmpty { null }
        thumbnail_url = document.selectFirst("div.uwthumb img")?.attrOrNull("src")?.toImageUrl()
        description = document.select("div.asTBcell p")
            .firstOrNull { it.text().startsWith("簡介") || it.text().startsWith("简介") }
            ?.toMarkdown()
            ?.removePrefix("簡介：")
            ?.removePrefix("簡介:")
            ?.removePrefix("简介：")
            ?.removePrefix("简介:")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Collections (合集) carry a chapter count; single galleries don't change
        if (document.selectFirst("div.uwconn label:contains(章節：)") == null) {
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        } else {
            val statusText = document.selectFirst("div.uwconn label:contains(狀態)")?.text().orEmpty()
            status = if ("連載中" in statusText) SManga.ONGOING else SManga.COMPLETED
        }
    }

    /**
     * The page has no author field, so the names have to come from the title. Two things make that
     * reliable: the site tags the circle and the person (genres follow, and both names appear as tags),
     * and the title convention is `[circle (person)]` for doujin, `[person]` otherwise.
     *
     * A parenthesis outside a bracket is the parody or the original work ("(オリジナル)", "(Fate)"), not a
     * person, so only bracket groups are candidates. When no bracket carries a name the prefix of
     * "person - title" is used; the rest stays "某绅士" and is left to the manual edit / LLM suggestion.
     */
    private fun extractNames(tags: List<String>, title: String): Pair<String, String> {
        val candidates =
            BRACKET_REGEX.findAll(title).mapIndexedNotNull { index, match ->
                val raw = match.groupValues[1].trim()
                val (circle, person) = splitCircleAndPerson(raw)
                val name =
                    person.takeIf { !isNoiseName(it) }
                        ?: raw.takeIf { !isNoiseName(it) }
                        ?: return@mapIndexedNotNull null

                val tagScore =
                    when {
                        tags.any { it.equals(name, ignoreCase = true) } -> 6
                        tags.any { it.length > 1 && name.contains(it, ignoreCase = true) } -> 3
                        else -> 0
                    }
                val score =
                    tagScore +
                        (if (circle != null && !isNoiseName(circle)) 2 else 0) +
                        (if (CJK_REGEX.containsMatchIn(name)) 1 else 0) +
                        (2 - index).coerceAtLeast(0)

                NameCandidate(name, score, index)
            }.sortedWith(compareByDescending<NameCandidate> { it.score }.thenBy { it.index })

        // The site does not distinguish author and artist: the person is both
        val name =
            candidates.firstOrNull()?.name
                ?: prefixName(title)
                ?: leadingParenName(title)
                ?: FALLBACK_AUTHOR
        return name to name
    }

    /** `[circle (person)]` -> circle to person; a bare `[name]` has no circle. */
    private fun splitCircleAndPerson(raw: String): Pair<String?, String> {
        val match = CIRCLE_AND_PERSON_REGEX.matchEntire(raw) ?: return null to raw
        val circle = match.groupValues[1].trim()
        return circle.ifEmpty { null } to match.groupValues[2].trim()
    }

    private fun isNoiseName(raw: String): Boolean {
        val name = raw.trim().trim('-', '_', ' ', '　')
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return true
        if (!LETTER_REGEX.containsMatchIn(name)) return true
        if (COUNT_REGEX.matches(name)) return true
        if (EVENT_REGEX.containsMatchIn(name)) return true
        if (PLATFORM_REGEX.matches(name)) return true
        if (AI_REGEX.matches(name)) return true
        if (METADATA_KEYWORDS.any { name.contains(it, ignoreCase = true) }) return true

        return preferences.authorExcludeList.any { name.contains(it, ignoreCase = true) }
    }

    // "Hana Bunny - Title", common for Patreon/Fanbox artists, when the title has no usable bracket
    private fun prefixName(title: String): String? = TITLE_PREFIX_REGEX.find(title)?.groupValues?.get(1)?.trim()
        ?.takeIf { !isNoiseName(it) }

    // "（person）title"
    private fun leadingParenName(title: String): String? = LEADING_PAREN_REGEX.find(title)?.groupValues?.get(1)?.trim()
        ?.takeIf { !isNoiseName(it) }

    private class NameCandidate(
        val name: String,
        val score: Int,
        val index: Int,
    )

    private suspend fun chaptersParse(document: Document, manga: SManga): List<SChapter> {
        val lastPage = document.select("div.f_left.paginator a[href]")
            .mapNotNull { chapterListPageRegex.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
            ?: 1
        val aid = mangaAidRegex.find(manga.url)!!.groupValues[1]
        val otherPages = coroutineScope {
            (2..lastPage).map { page ->
                async { client.get("$baseUrl/photos-index-aid-$aid-page-$page.html", Fetch.DOCUMENT).asJsoup().select(CHAPTER_SELECTOR) }
            }.awaitAll()
        }
        val chapterElements = (document.select(CHAPTER_SELECTOR) + otherPages.flatten())
            .distinctBy { it.attr("data-chid") }

        if (chapterElements.isEmpty()) {
            return listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Ch. 1"
                    date_upload = chapterDateFormat.tryParseDate(
                        document.selectFirst("li.gallary_item .info_col")?.text()?.let { chapterDateRegex.find(it)?.value },
                        chapterZone,
                    )
                },
            )
        }
        return chapterElements.map { element ->
            SChapter.create().apply {
                url = "/photos-index-aid-${element.attr("data-chid")}.html"
                name = element.text()
                date_upload = chapterDateFormat.tryParseDate(
                    chapterDateRegex.find(element.attr("title"))?.value,
                    chapterZone,
                )
            }
        }.reversed()
    }

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        .select("a.ar_item")
        .mapNotNull { element ->
            SManga.create().apply {
                url = element.attrOrNull("href") ?: return@mapNotNull null
                title = element.attrOrNull("title") ?: return@mapNotNull null
                thumbnail_url = element.selectFirst("img")?.attrOrNull("src")?.toImageUrl()
            }
        }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val html = client.get(getChapterUrl(chapter).replace("-index-", "-gallery-"), Fetch.XHR).body.string()
        return pageImageRegex.findAll(html).mapIndexedTo(ArrayList()) { index, match ->
            Page(index, imageUrl = match.value.toImageUrl())
        }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("注意：排序仅用于关键词搜索"),
        SortFilter(),
        Filter.Separator(),
        Filter.Header("注意：分类和标签均不支持搜索"),
        Filter.Header("注意：在 WebView 中登录后刷新列表并重置，可显示韩漫等分类"),
        CategoryFilter(preferences.extraCategories),
        Filter.Header("注意：排行仅在未选分类和标签时生效"),
        RankingFilter(),
        Filter.Separator(),
        Filter.Header("注意：仅支持 1 个标签，不支持分类"),
        TagFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        getPreferencesInternal(screen.context, preferences)
            .forEach(screen::addPreference)
    }

    private fun mangaListParse(response: Response): MangasPage {
        val document = response.asJsoup()
        saveExtraCategories(document)
        val mangas = document.select(".gallary_item").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst("span.thispage + a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // The site's menu lists more categories once logged in
    private fun saveExtraCategories(document: Document) {
        val categories = document.select("#album_tabs > li").flatMap { item ->
            val parent = item.selectFirst("> a") ?: return@flatMap emptyList()
            listOf(parent.text() to parent) + item.select(".drop_dwon a").map { "${parent.text()}-${it.text()}" to it }
        }.mapNotNull { (name, link) ->
            unlistedCategoryId(link.attr("href"))?.let { name to it }
        }
        preferences.extraCategories = categories
    }

    private fun MangasPage.filterBlockedTitles(
        blacklist: List<String> = preferences.titleBlacklist,
    ): MangasPage {
        if (blacklist.isEmpty()) return this

        val filteredMangas = mangas.filterNot { manga ->
            blacklist.any { keyword -> manga.title.contains(keyword, ignoreCase = true) }
        }
        return MangasPage(filteredMangas, hasNextPage)
    }

    private suspend fun fetchFilteredMangaPage(
        appPage: Int,
        key: String,
        state: FilterPagingState,
        urlForPage: (Int) -> String,
        blacklist: List<String>,
        maxScanPages: Int,
    ): MangasPage {
        val snapshot = state.snapshot(appPage, key)
        var sourcePage = appPage + snapshot.offset
        repeat(maxScanPages) {
            val page = mangaListParse(client.get(urlForPage(sourcePage), Fetch.DOCUMENT)).filterBlockedTitles(blacklist)
            when {
                page.mangas.isNotEmpty() -> {
                    state.updateOffset(key, snapshot.generation, sourcePage - appPage)
                    return page
                }
                !page.hasNextPage -> return MangasPage(emptyList(), false)
            }
            sourcePage++
        }
        throw Exception("连续 $maxScanPages 页均无可显示结果，请调整黑名单或扫描页数后刷新。")
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst(".title > a")!!
        url = link.attr("href")
        title = link.text()
        thumbnail_url = element.selectFirst("img")?.attrOrNull("src")?.toImageUrl()
    }

    // Protocol-relative image urls, some with extra leading slashes
    private fun String.toImageUrl() = "https://" + trimStart('/')

    companion object {
        private const val LOGIN_HINT = "内容不存在或需要登录，请在 WebView 中登录后重试"
        private const val RELEASE_PAGE_URL = "https://wnacglink.com/"
        private val UNLISTED_MIRRORS = listOf("https://www.wn01.cfd", "https://www.wnacg.com")
        private const val CHAPTER_SELECTOR = "div.sr_compact a.tagshow[data-chid]"
        private val pageImageRegex = Regex(
            """//[^\s"'\\]+\.(?:jpeg|jpg|png|webp|gif)(?:\?[^\s"'\\]*)?""",
            RegexOption.IGNORE_CASE,
        )

        // Gallery page, reader and chapter links; a chapter's sid is its collection
        private val mangaUrlRegex = Regex("""/photos-(?:index|slide|list|gallery)(?:-page-\d+)?-aid-(\d+)(?:-sid-(\d+))?(?:-page-\d+)?\.html""")
        private val chromeVersionRegex = Regex("""Chrome/(\d+)""")
        private val mangaAidRegex = Regex("""photos-index-aid-(\d+)""")
        private val chapterListPageRegex = Regex("""photos-index-aid-\d+-page-(\d+)\.html""")
        private val chapterDateRegex = Regex("""\d{4}-\d{2}-\d{2}""")
        private val chapterDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val chapterZone = ZoneId.of("Asia/Taipei")

        // Names: only [] / 【】 groups carry them, a parenthesised part inside one is the person and the
        // part before it the circle ("[サークル (作者)]")
        private val BRACKET_REGEX = Regex("""[\[【]([^\]】]{1,80})[\]】]""")
        private val CIRCLE_AND_PERSON_REGEX = Regex("""^(.*?)[（(]([^（）()]{1,40})[)）]$""")
        private val TITLE_PREFIX_REGEX = Regex("""^([^\-–—:：\[\]【】()（）]{2,30}?)\s+[-–—]\s+\S""")
        private val LEADING_PAREN_REGEX = Regex("""^[（(]([^（）()]{2,40})[)）]""")
        private const val MAX_NAME_LENGTH = 40
        private const val FALLBACK_AUTHOR = "某绅士"
        private val LETTER_REGEX = Regex("""\p{L}""")
        private val CJK_REGEX = Regex("""[\u3040-\u30ff\u4e00-\u9fff]""")

        // Release metadata, scanner groups and platforms leak into the brackets; none of them is a person
        private val METADATA_KEYWORDS = listOf(
            "汉化", "漢化", "翻訳", "翻译", "翻譯", "机翻", "機翻", "重嵌", "嵌字", "修图", "修圖", "自翻",
            "上色", "全彩", "カラー", "中文", "中譯", "中译", "掃圖", "扫图", "魔改", "無修正", "无修正",
            "DL版", "電子版", "电子版", "成年", "同人誌", "サークル", "AI生成", "AI Generated", "Patreon",
            "Fanbox", "Fantia", "BOOTH", "DLsite", "Pixiv", "Twitter", "R-18", "雜誌", "杂志", "短編集",
            "総集編", "合集", "日語", "日语",
        )
        private val COUNT_REGEX = Regex("""^\d+\s*[pP页頁]?$""")
        private val EVENT_REGEX = Regex("""^(?:c\d{2,3}|comic1|comiket|例大祭|コミティア|mjk-|fancy|melon)""", RegexOption.IGNORE_CASE)
        private val PLATFORM_REGEX = Regex("""^(?:patreon|fanbox|fantia|booth|dlsite|pixiv|twitter|ci-en|cien)$""", RegexOption.IGNORE_CASE)
        private val AI_REGEX = Regex("""^(?:ai|ai生成|ai generated|ai翻译|ai彩色|ai上色)$""", RegexOption.IGNORE_CASE)

        private val ONLY_ALPHA_REGEX = Regex("^[a-zA-Z]$")
        private val ONLY_NUMBER_REGEX = Regex("""^\d+$""")
        private val TAGS_FILTER_REGEX_LIST = listOf(ONLY_NUMBER_REGEX, ONLY_ALPHA_REGEX)
        private val TAGS_EXCLUDE_LIST = listOf("yyy", "xxx")
    }
}

private class PagingSnapshot(
    val generation: Int,
    val offset: Int,
)

private class FilterPagingState {
    private var key: String? = null
    private var generation = 0
    private var offset = 0

    @Synchronized
    fun snapshot(appPage: Int, key: String): PagingSnapshot {
        if (appPage == 1 || this.key != key) {
            this.key = key
            generation++
            offset = 0
        }
        return PagingSnapshot(generation, offset)
    }

    @Synchronized
    fun reset() {
        key = null
        generation++
        offset = 0
    }

    @Synchronized
    fun updateOffset(key: String, generation: Int, offset: Int) {
        if (this.key != key || this.generation != generation) return
        this.offset = offset
    }
}
