package eu.kanade.tachiyomi.extension.zh.wnacg

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.SwitchPreferenceCompat
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString

fun getPreferencesInternal(
    context: Context,
    preferences: SharedPreferences,
) = arrayOf(
    EditTextPreference(context).apply {
        key = AUTHOR_EXCLUDE_PREF
        title = "作者匹配排除字符"
        summary = "用逗号分隔，匹配到的作者名若包含任一关键字则排除\n例如: LMK,ABC 工作室"
        dialogTitle = "作者匹配排除字符（逗号分隔）"
        setOnPreferenceChangeListener { _, newValue ->
            preferences.edit().putString(key, newValue as String).commit()
        }
    },

    EditTextPreference(context).apply {
        key = TITLE_BLACKLIST_PREF
        title = "标题屏蔽关键词"
        summary = titleBlacklistSummary(preferences.getString(TITLE_BLACKLIST_PREF, ""))
        setOnPreferenceChangeListener { _, newValue ->
            summary = titleBlacklistSummary(newValue as String)
            true
        }
        dialogTitle = "标题屏蔽关键词"
        setDefaultValue("")
    },

    EditTextPreference(context).apply {
        key = BLACKLIST_MAX_SCAN_PAGES_PREF
        title = "黑名单过滤最大扫描页数"
        summary = maxScanPagesSummary(preferences.blacklistMaxScanPages)
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        setOnPreferenceChangeListener { _, newValue ->
            val value = (newValue as String).toIntOrNull()
            if (value == null || value < 1) {
                Toast.makeText(context, "请输入不小于 1 的正整数。", Toast.LENGTH_SHORT).show()
                false
            } else {
                summary = maxScanPagesSummary(value)
                true
            }
        }
        dialogTitle = "黑名单过滤最大扫描页数"
        setDefaultValue(DEFAULT_MAX_SCAN_PAGES.toString())
    },

    SwitchPreferenceCompat(context).apply {
        key = FILTER_SEARCH_RESULTS_PREF
        title = "在搜索结果中使用标题屏蔽"
        summary = "开启后，关键词、分类和标签搜索结果也会应用标题屏蔽关键词。"
        setDefaultValue(true)
    },

    EditTextPreference(context).apply {
        key = LOGIN_COOKIE_PREF
        title = "登录 Cookie"
        summary = loginCookieSummary(preferences.getString(LOGIN_COOKIE_PREF, ""))
        setOnPreferenceChangeListener { _, newValue ->
            summary = loginCookieSummary(newValue as String)
            true
        }
        dialogTitle = "登录 Cookie"
        setDefaultValue("")
    },
)

var SharedPreferences.extraCategories: List<Pair<String, String>>
    get() = getString(EXTRA_CATEGORIES_PREF, null)?.parseAs<List<Pair<String, String>>>().orEmpty()
    set(value) = edit().putString(EXTRA_CATEGORIES_PREF, value.toJsonString()).apply()

val SharedPreferences.authorExcludeList: List<String>
    get() = getString(AUTHOR_EXCLUDE_PREF, "")!!
        .split(",", "，")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val SharedPreferences.titleBlacklist: List<String>
    get() = getString(TITLE_BLACKLIST_PREF, "")
        .orEmpty()
        .split(',', '\n', '\r')
        .map(String::trim)
        .filter(String::isNotEmpty)

val SharedPreferences.filterSearchResults: Boolean
    get() = getBoolean(FILTER_SEARCH_RESULTS_PREF, true)

val SharedPreferences.blacklistMaxScanPages: Int
    get() = getString(BLACKLIST_MAX_SCAN_PAGES_PREF, DEFAULT_MAX_SCAN_PAGES.toString())
        ?.toIntOrNull()
        ?.takeIf { it >= 1 }
        ?: DEFAULT_MAX_SCAN_PAGES

val SharedPreferences.loginCookies: List<Pair<String, String>>
    get() = getString(LOGIN_COOKIE_PREF, "").orEmpty().parseCookiePairs()

private fun loginCookieSummary(value: String?) = if (value.orEmpty().parseCookiePairs().isEmpty()) {
    "未设置。在浏览器登录 wnacg 后，从开发者工具网络面板复制任意请求的 Cookie 请求头" +
        "（形如 名称=值; 名称=值）粘贴到这里。请确保插件当前网址与登录域名一致。"
} else {
    "已设置 Cookie，登录态会注入到请求中。"
}

private fun String.parseCookiePairs(): List<Pair<String, String>> = split(';').mapNotNull { part ->
    val separator = part.indexOf('=')
    if (separator <= 0) return@mapNotNull null
    val name = part.substring(0, separator).trim()
    val value = part.substring(separator + 1).trim()
    if (name.isEmpty() || value.isEmpty()) null else name to value
}

private fun titleBlacklistSummary(value: String?) = value?.takeIf(String::isNotBlank)?.let { "$it\n$TITLE_BLACKLIST_SUMMARY" }
    ?: TITLE_BLACKLIST_SUMMARY

private fun maxScanPagesSummary(value: Int) = "当前值：$value\n$BLACKLIST_MAX_SCAN_PAGES_SUMMARY"

fun SharedPreferences.preferenceMigration() {
    if (contains(URL_LIST_PREF)) {
        edit()
            .remove(DEFAULT_LIST_PREF)
            .remove(URL_LIST_PREF)
            .remove(URL_INDEX_PREF)
            .apply()
    }
}

private const val DEFAULT_LIST_PREF = "defaultBaseUrl"
private const val URL_LIST_PREF = "baseUrlList"
private const val URL_INDEX_PREF = "baseUrlIndex"
private const val EXTRA_CATEGORIES_PREF = "extraCategories"
private const val AUTHOR_EXCLUDE_PREF = "authorExcludeList"
private const val TITLE_BLACKLIST_PREF = "titleBlacklist"
private const val BLACKLIST_MAX_SCAN_PAGES_PREF = "blacklistMaxScanPages"
private const val FILTER_SEARCH_RESULTS_PREF = "filterSearchResults"
private const val LOGIN_COOKIE_PREF = "loginCookie"
private const val DEFAULT_MAX_SCAN_PAGES = 5
private const val TITLE_BLACKLIST_SUMMARY = "使用英文逗号或换行分隔多个关键词；忽略大小写，始终作用于热门和最新列表。"
private const val BLACKLIST_MAX_SCAN_PAGES_SUMMARY =
    "每次加载最多检查多少个网站页面，包含当前页。找到未被屏蔽的内容后立即返回；只在启用黑名单过滤时生效。"
