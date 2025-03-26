package eu.kanade.tachiyomi.extension.zh.wnacg

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import eu.kanade.tachiyomi.extension.R

data class Author(
    val name: String,
    val aliases: List<String>,
)

class AuthorRepository(private val context: Context) {
    private var authors: List<Author> = emptyList()
    private val nameToAuthorMap: Map<String, Author> by lazy {
        buildSearchIndex()
    }

    // 初始化加载数据
    fun loadAuthors() {
        val inputStream = context.resources.openRawResource(R.raw.authors)
        val json = inputStream.bufferedReader().use { it.readText() }
        val type = object : TypeToken<List<Author>>() {}.type
        authors = Gson().fromJson(json, type)
    }

    // 构建搜索索引
    private fun buildSearchIndex(): Map<String, Author> {
        val map = mutableMapOf<String, Author>()
        authors.forEach { author ->
            // 添加正式名称映射
            map[author.name.lowercase()] = author
            // 添加所有别名映射
            author.aliases.forEach { alias ->
                map[alias.lowercase()] = author
            }
        }
        return map
    }

    // 通过任意名称查找作者
    fun findAuthorByName(name: String): Author? {
        return nameToAuthorMap[name.lowercase()]
    }

    // 获取所有作者
    fun getAllAuthors(): List<Author> = authors
}
