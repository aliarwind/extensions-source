package com.example.myapplication

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.IOException
import retrofit2.HttpException
import java.io.File
import java.io.PrintStream

private const val AUTHOR_FILE = "manga_artists.json"
private const val AUTHOR_CACHE_FILE = "manga_artists_cache.json"
private const val BATCH_SIZE = 30
const val MANGA_FILE = "manga_info.json"
private const val MANGA_CACHE_FILE = "manga_info_cache.json"
private const val OFFSET = 44430

// 缓存类，用于管理已处理的作者信息
object AuthorCache {
    private val cachedAuthors = mutableSetOf<Int>() // 缓存作者ID
    private val fileLock = Any()

    // 从文件加载缓存
    fun loadFromFile(): Set<Int> {
        return synchronized(fileLock) {
            val file = File(AUTHOR_CACHE_FILE)
            if (file.exists()) {
                try {
                    val json = file.readText()
                    Gson().fromJson(json, Array<Int>::class.java).toSet()
                } catch (e: Exception) {
                    emptySet()
                }
            } else {
                emptySet()
            }
        }.also { cachedAuthors.addAll(it) }
    }

    // 添加作者到缓存并保存到文件
    fun addAndSave(authorId: Int) {
        synchronized(fileLock) {
            if (cachedAuthors.add(authorId)) {
                saveToFile()
            }
        }
    }

    // 批量添加作者到缓存并保存到文件
    fun addAllAndSave(authorIds: Collection<Int>) {
        synchronized(fileLock) {
            if (cachedAuthors.addAll(authorIds)) {
                saveToFile()
            }
        }
    }

    // 检查作者是否已存在
    fun contains(authorId: Int): Boolean = cachedAuthors.contains(authorId)

    // 保存缓存到文件
    private fun saveToFile() {
        synchronized(fileLock) {
            try {
                val json = Gson().toJson(cachedAuthors.toTypedArray())
                File(AUTHOR_CACHE_FILE).writeText(json)
            } catch (e: Exception) {
                println("Failed to save cache: ${e.message}")
            }
        }
    }
}

// 缓存类，用于管理已处理的漫畫信息
object SubjectCache {
    private val cachedSubjects = mutableSetOf<Int>() // 缓存漫畫ID
    private val fileLock = Any()

    // 从文件加载缓存
    fun loadFromFile(): Set<Int> {
        return synchronized(fileLock) {
            val file = File(MANGA_CACHE_FILE)
            if (file.exists()) {
                try {
                    val json = file.readText()
                    Gson().fromJson(json, Array<Int>::class.java).toSet()
                } catch (e: Exception) {
                    emptySet()
                }
            } else {
                emptySet()
            }
        }.also { cachedSubjects.addAll(it) }
    }

    fun addAndSave(subjectId: Int) {
        synchronized(fileLock) {
            if (cachedSubjects.add(subjectId)) {
                saveToFile()
            }
        }
    }

    fun addAllAndSave(subjectIds: Collection<Int>) {
        synchronized(fileLock) {
            if (cachedSubjects.addAll(subjectIds)) {
                saveToFile()
            }
        }
    }

    fun contains(authorId: Int): Boolean = cachedSubjects.contains(authorId)

    // 保存缓存到文件
    private fun saveToFile() {
        synchronized(fileLock) {
            try {
                val json = Gson().toJson(cachedSubjects.toTypedArray())
                File(MANGA_CACHE_FILE).writeText(json)
            } catch (e: Exception) {
                println("Failed to save cache: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

public fun extractPersonDetails(person: PersonDetail): PersonSaveObj {
    val chineseName = person.infobox
        .firstOrNull { it.key == "简体中文名" }
        ?.value?.let { value ->
            when {
                value is JsonPrimitive -> value.asString
                else -> null
            }
        }
    // 提取所有别名
    val aliases = person.infobox
        .filter { it.key == "别名" }
        .flatMap { item ->
            when (val value = item.value) {
                is JsonArray -> {
                    // 处理JsonArray类型的别名
                    value.map { ele -> ele.asJsonObject.get("v").asString }
                }
                else -> emptyList()
            }
        }
        .filter { it.isNotBlank() }
    return PersonSaveObj(
        originalName = person.name,
        chineseName = chineseName,
        aliases = aliases,
        id = person.id
    )
}

public fun extractMangaDetails(manga: Subject, authors: List<Person>): MangaSaveObj {
    val chineseName = manga.infobox
        .firstOrNull { it.key == "简体中文名" }
        ?.value?.let { value ->
            when {
                value is JsonPrimitive -> value.asString
                else -> null
            }
        }
    // 提取所有别名
    val aliases = manga.infobox
        .filter { it.key == "别名" }
        .flatMap { item ->
            when (val value = item.value) {
                is JsonArray -> {
                    // 处理JsonArray类型的别名
                    value.map { ele -> ele.asJsonObject.get("v").asString }
                }
                else -> emptyList()
            }
        }
        .filter { it.isNotBlank() }
    return MangaSaveObj(
        id = manga.id,
        name = manga.name,
        name_cn = manga.name_cn,
        tags = manga.tags?.asJsonArray?.mapNotNull { obj -> obj.asJsonObject.get("name").asString }
            ?: emptyList(),
        date = manga.date,
//        summary = manga.summary,
        nsfw = manga.nsfw,
        aliases = aliases,
        authorNames = authors.map { author->author.name },
        authorIds = authors.map { author->author.id },
        meta_tags = manga.meta_tags
    )
}

// 其他接口和数据类保持不变...

suspend fun fetchAllMangaWithAuthorsIncremental(): List<PersonSaveObj> {
    // 加载已有缓存
    AuthorCache.loadFromFile()
    SubjectCache.loadFromFile()
    val service = createBangumiService()
    val resultList = mutableListOf<PersonSaveObj>()
    val outputFile = File(AUTHOR_FILE)
    val outputFileManga = File(MANGA_FILE)

    // 如果输出文件已存在，加载已有数据
    if (outputFile.exists()) {
        try {
            val existingData = Gson().fromJson(outputFile.readText(), Array<PersonSaveObj>::class.java)
            println("existing author size: ${existingData.size}")
            resultList.addAll(existingData)
        } catch (e: Exception) {
            println("Failed to load existing data: ${e.message}")
            e.printStackTrace()
        }
    }

    val allMangaList = mutableListOf<MangaSaveObj>()
    // 如果输出文件已存在，加载已有数据
    if (outputFileManga.exists()) {
        try {
            val existingData = Gson().fromJson(outputFileManga.readText(), Array<MangaSaveObj>::class.java)
            println("existing manga size: ${existingData.size}")
            allMangaList.addAll(existingData)
        } catch (e: Exception) {
            println("Failed to load existing data: ${e.message}")
            e.printStackTrace()
        }
    }

    try {
        // 获取漫画总数
        val totalResponse = service.getSubjects(limit = 1)
        val total = totalResponse.body()?.total ?: 0
        var offset = OFFSET

        while (offset < total) {
            // 获取一批漫画
            val mangaResponse = service.getSubjects(limit = BATCH_SIZE, offset = offset)
            println("offset ${offset}")
            if (!mangaResponse.isSuccessful) {
                println("Failed to fetch manga at offset $offset")
                offset += BATCH_SIZE
                continue
            }

            val mangaList = mangaResponse.body()?.data ?: emptyList()

            // 处理每部漫画的作者
            for (manga in mangaList) {
                try {
                    println("mangaId ${manga.id} mangaName ${manga.name} ${manga.name_cn}")
                    // 检查是否已处理过
                    if (SubjectCache.contains(manga.id)) {
                        println("該漫畫已處理過 跳過")
                        continue
                    }
                    if (!manga.nsfw){
                        println("not nsfw pass")
                        continue
                    }
                    // 获取漫画的所有相关人员
                    val personsResponse = service.getSubjectPersons(manga.id)
                    if (!personsResponse.isSuccessful){
                        println("Failed to fetch subjectPersons at manga:${manga.name} ")
                        continue
                    }

                    val persons = personsResponse.body()?: emptyList()
                    println(persons.joinToString(", ") { it.name })
                    if (persons.isEmpty()){
                        continue
                    }
                    val authors = filterAuthors(persons)
                    // 处理每个作者
                    for (author in authors) {
                        // 检查是否已处理过
                        if (AuthorCache.contains(author.id)) continue

                        // 获取作者详细信息
                        val detailResponse = service.getPerson(author.id)
                        if (!detailResponse.isSuccessful) {
                            println("Failed to fetch personDetail at author:${author.name} ")
                            continue
                        }

                        val detail = detailResponse.body() ?: continue
                        val personSaveObj = extractPersonDetails(detail)
                        println("success get author ${personSaveObj.originalName} ${personSaveObj.aliases.joinToString(",")}")
                        // 添加到结果列表
                        resultList.add(personSaveObj)
                        AuthorCache.addAndSave(author.id)

                        // 增量保存到文件
                        saveIncremental(outputFile, resultList)
                    }
                    val mangaSaveObj = extractMangaDetails(manga, authors)
                    allMangaList.add(mangaSaveObj)
                    saveIncrementaMangal(outputFileManga, allMangaList)
                    SubjectCache.addAndSave(manga.id)
                } catch (e: Exception) {
                    println("Error processing manga ${manga.id}: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
                delay(500) // 控制请求频率
            }

            offset += BATCH_SIZE
            delay(1000) // 控制批量请求频率
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return resultList
}

private fun saveIncremental(file: File, data: List<PersonSaveObj>) {
    try {
        val json = Gson().toJson(data.toTypedArray())
        file.writeText(json)
    } catch (e: Exception) {
        println("Failed to save incremental data: ${e.message}")
        e.printStackTrace()
    }
}

private fun saveIncrementaMangal(file: File, data: List<MangaSaveObj>) {
    try {
        val json = Gson().toJson(data.toTypedArray())
        file.writeText(json)
    } catch (e: Exception) {
        println("Failed to save incremental data: ${e.message}")
        e.printStackTrace()
    }
}

suspend fun main() {
    System.setOut(withContext(Dispatchers.IO) {
        PrintStream(System.out, true, "UTF-8")
    })

    try {
        // 先加载已有缓存
        val artists = fetchAllMangaWithAuthorsIncremental()
        println("Total artists processed: ${artists.size}")
    } catch (e: Exception) {
        when (e) {
            is HttpException -> {
                if (e.code() == 429) {
                    println("请求过于频繁，请稍后再试")
                }
            }
            is IOException -> {
                println("网络错误: ${e.message}")
            }
            else -> {
                println("发生错误: ${e.message}")
            }
        }
    }
}