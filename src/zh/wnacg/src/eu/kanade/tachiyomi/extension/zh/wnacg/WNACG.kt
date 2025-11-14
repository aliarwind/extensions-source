package eu.kanade.tachiyomi.extension.zh.wnacg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneId
import java.util.regex.Pattern

class WNACG :
    HttpSource(),
    ConfigurableSource {

    override val name = "紳士漫畫"
    override val lang = "zh"
    override val supportsLatest = true

    private val preferences = getPreferences { preferenceMigration() }

    override val baseUrl = when (System.getenv("CI")) {
        "true" -> getCiBaseUrl()
        else -> preferences.baseUrl
    }

    private val updateUrlInterceptor = UpdateUrlInterceptor(preferences)

    fun OkHttpClient.addInterceptor(interceptor: Interceptor): OkHttpClient {
        return this.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    // 使用示例
    val originalClient: OkHttpClient = network.cloudflareClient
//    override val client =  originalClient

    override val client =
        originalClient.newBuilder()
            .addInterceptor(updateUrlInterceptor)
            .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0")
        .set("Referer", baseUrl)
        .set("Sec-Fetch-Mode", "no-cors")
        .set("Sec-Fetch-Site", "cross-site")

    // Popular

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/albums-favorite_ranking-page-$page-type-week.html", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".gallary_item").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst("span.thispage + a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/albums-index-page-$page.html", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".gallary_item").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst("span.thispage + a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            val tagFilter = filters.firstInstanceOrNull<TagFilter>()
            if (tagFilter != null && tagFilter.state.isNotEmpty()) {
                return GET("$baseUrl/albums-index-page-$page-tag-${tagFilter.state}.html", headers)
            }
            val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
            if (categoryFilter != null && categoryFilter.toUriPart().isNotEmpty()) {
                return GET("$baseUrl/" + categoryFilter.toUriPart().format(page), headers)
            }
            return popularMangaRequest(page)
        }
        val url = "$baseUrl/search/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("s", "create_time_DESC")
            .addQueryParameter("q", query)
            .addQueryParameter("p", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Manga details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            val tags = document.select("a.tagshow")
                .takeIf { it.isNotEmpty() }
                ?.map { it.text().trim() }
            val filterTags = tags?.filter { tag ->
                val isMatch = TAGS_FILTER_REGEX_LIST.any { regex -> tag.matches(regex) }
                // 排除符合正則表達式的 tag 和預設列表中的 tag
                !isMatch && !TAGS_EXCLUDE_LIST.contains(tag)
            }
            title = document.selectFirst("h2")!!.text()
            artist = extractAuthor(tags, title)
            author = artist
            genre = filterTags?.joinToString(", ") { it.trim() }
            thumbnail_url = "http:" + document.selectFirst("div.uwthumb img")!!.attr("src")
            description = document.selectFirst("div.asTBcell p")?.html()?.replace("<br>", "\n")
            status = SManga.COMPLETED

        }
    }

    fun extractAuthor(tags: List<String>?, title: String): String {
        val possibleAuthors = mutableListOf<String>()
        // 按优先级依次提取
        for (regex in PRIORITY_REGEXES) {
            regex.findAll(title).forEach { match ->
                val author = match.groupValues[1].trim()
                if (author.isNotEmpty()
                    && !author.contains("汉化")
                    && !author.contains("漢化")
                    && !author.contains("机翻")
                    && !author.contains("機翻")
                    && !author.contains("无修正")
                    && !author.contains("無修正")
                    && !author.contains("中译")
                    && !author.contains("中譯")
                    && !author.contains("中文")
                    && !author.contains("翻译")
                    && !author.contains("翻譯")
                    && !author.contains("自翻")
                    && !author.contains("日語")
                    && !author.contains("日语")
                    && !author.contains("上色")
                    && !author.contains("全彩")
                    && !author.contains("風的工房")
                    && !author.contains("風之工房")
                    && !author.contains("风的工房")
                    && !author.contains("风之工房")
                    && !author.contains("掃圖")
                    && !author.contains("扫图")
                ) {
                    possibleAuthors.add(author)
                }
            }
        }
//        // 1. 按优先级尝试不同正则匹配
//        val possibleAuthors = TITLE_AUTHOR_REGEX0.findAll(manga.title)
//            .flatMap { matchResult ->
//                // 获取所有非空的捕获组（1-3组分别对应三种括号）
//                matchResult.groupValues.drop(1).filter { it.isNotEmpty() }
//            }
//            .toList()

        // 2. 优先从 tags 匹配作者
        val artist = possibleAuthors.firstOrNull { author ->
            tags?.any { tag -> author.contains(tag, ignoreCase = true) } == true
        }
            // 3. 次优选择：直接取第一个匹配到的作者
            ?: possibleAuthors.firstOrNull()
                // 4. 默认值
                ?: "某绅士"

        return artist
    }

    // Chapter list

//    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
//        val chapter = SChapter.create().apply {
//            url = manga.url
//            name = "Ch. 1"
//        }
//        return Observable.just(listOf(chapter))
//    }

    private val datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})")

    @RequiresApi(Build.VERSION_CODES.O)
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val requestUrl = response.request.url.toString()
        val chapterUrl = requestUrl.removePrefix(baseUrl)
        val infoColText = document.selectFirst("div.gallary_wrap.tb")
            ?.selectFirst("li.li.tb.gallary_item")
            ?.selectFirst("div.info_col")
            ?.text()?.trim()
        var _date_upload = 0L
        if (infoColText != null) {
            val matcher = datePattern.matcher(infoColText)
            if (matcher.find()) {
                val extractedDate = matcher.group(1)
                val date = LocalDate.parse(extractedDate)
                // 2. 转换为当天的起始时间（00:00:00），并转为时间戳（毫秒）
                _date_upload = date.atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        }

        val singleChapter = SChapter.create().apply {
            name = "Ch. 1"
            url = chapterUrl
            date_upload = _date_upload
        }
        return listOf(singleChapter)
    }


    // Pages

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url.replace("-index-", "-gallery-"), headers)

    override fun pageListParse(response: Response): List<Page> = pageImageRegex.findAll(response.body.string()).mapIndexedTo(ArrayList()) { index, match ->
        Page(index, imageUrl = "http:" + match.value)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList() = FilterList(
        Filter.Header("注意：分类和标签均不支持搜索"),
        CategoryFilter(),
        Filter.Separator(),
        Filter.Header("注意：仅支持 1 个标签，不支持分类"),
        TagFilter(),
    )

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        getPreferencesInternal(screen.context, preferences, updateUrlInterceptor.isUpdated)
            .forEach(screen::addPreference)
    }

    // Helpers

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst(".title > a")!!
        url = link.attr("href")
        title = link.text()
        thumbnail_url = element.selectFirst("img")!!.absUrl("src").replaceBefore(':', "http")
    }

    companion object {
        private val pageImageRegex = Regex("""//\S*(jpeg|jpg|png|webp|gif)""")
        val TITLE_AUTHOR_REGEX0: Regex = Regex("""\[(.*?)]|\((.*?)\)|（(.*?)）""")

        val PRIORITY_REGEXES = listOf(
            Regex("""\[(.*?)]"""),           // 1. 先匹配 [内容]
//            Regex("""\((.*?)\)"""),       // 2. 再匹配 (内容)  —— 避免嵌套问题
//            Regex("""（(.*?)）""")      // 3. 最后匹配 （内容）
        )
        val ONLY_ALPHA_REGEX: Regex = Regex("^[a-zA-Z]$")
        val ONLY_NUMBER_REGEX: Regex = Regex("^\\d+$")
        val TAGS_FILTER_REGEX_LIST = listOf(ONLY_NUMBER_REGEX, ONLY_ALPHA_REGEX)
        val TAGS_EXCLUDE_LIST = listOf("yyy", "xxx")
    }
}
