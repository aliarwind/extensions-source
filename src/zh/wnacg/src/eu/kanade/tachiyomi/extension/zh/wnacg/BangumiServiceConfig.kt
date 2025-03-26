package com.example.myapplication

import com.google.gson.JsonElement
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okio.IOException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.PrintStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

private const val BASE_URL = "https://api.bgm.tv/"
//private const val ACCESS_TOKEN = "ixTP7V9ecWvcdOs70bqjhpdjtkDXBeLaKsRcudzK"
private const val ACCESS_TOKEN = "rF5mycBLNwLSHRqypY1UsgJ84ulUecjtuLSDkJpQ"

interface BangumiService {
    @POST("v0/search/persons")
    suspend fun searchPersons(
        @Body request: PersonSearchRequest,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): Response<PersonSearchResponse>

    @POST("/v0/search/subjects")
    suspend fun searchSubjects(
        @Body request: SubjectSearchRequest,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): Response<SubjectSearchResponse>

    @GET("v0/subjects")
    suspend fun getSubjects(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("type") type: Int = 1,
        @Query("cat") cat: Int = 1001,
        @Query("sort") sort: String = "date",
    ): Response<SubjectResponse>

    @GET("/v0/subjects/{subject_id}")
    suspend fun getSubject(
        @Path("subject_id") subjectId: Int,
    ): Response<Subject>


    @GET("/v0/subjects/{subject_id}/persons")
    suspend fun getSubjectPersons(
        @Path("subject_id") subjectId: Int,
    ): Response<List<Person>>

    @GET("/v0/persons/{person_id}")
    suspend fun getPerson(
        @Path("person_id") personId: Int,
    ): Response<PersonDetail>
}

@Serializable
data class SubjectResponse(
    val data: List<Subject>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class Subject(
    val id: Int,
    val name: String,
    val name_cn: String,
    @Contextual val tags: JsonElement?,
    @Contextual val infobox: List<InfoBoxItem>,
    val date: String,
    val summary: String?,
    val nsfw: Boolean=false,
    val meta_tags: List<String>
)

@Serializable
data class PersonsResponse(
    val data: List<Person>,
)

@Serializable
data class Person(
    val id: Int,
    val name: String,
    val relation: String,
    @SerialName("career")
    val careers: List<String>? = null
)

@Serializable
data class PersonSearchRequest(
    val keyword: String? = null,
    val filter: Filter? = null
)

@Serializable
data class SubjectSearchRequest(
    val keyword: String? = null,
    val filter: Filter? = null,
    val sort: String?="rank",
    val nsfw: Boolean?=true
)

@Serializable
data class Filter(
    @SerialName("career")
    val careers: List<String>? = null,
    @SerialName("type")
    val type: List<Int>? = null
)

// 响应数据类
@Serializable
data class PersonSearchResponse(
    val data: List<PersonDetail>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class SubjectSearchResponse(
    val data: List<Subject>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class PersonDetail(
    val id: Int,
    val name: String,
    val type: Int,  // 1=现实人物，2=虚构角色
    val career: List<String>?,  // 职业列表
    val infobox: List<InfoBoxItem> = emptyList(),
)

@Serializable
data class InfoBoxItem(
    val key: String,
    @Contextual val value: JsonElement // 可能是String、List或Map
)

data class PersonSaveObj(
    val originalName: String,
    val chineseName: String?,
    val aliases: List<String>,
    val id:Int
)

data class MangaSaveObj(
    val id: Int,
    val name: String,
    val name_cn: String,
    val authorNames: List<String>,
    val authorIds: List<Int>,
    val tags: List<String>,
    val date: String,
//    val summary: String?,
    val nsfw: Boolean=false,
    val aliases: List<String>,
    val meta_tags: List<String>
)

fun createBangumiService():
        BangumiService{
    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // 连接超时
        .readTimeout(30, TimeUnit.SECONDS)    // 读取超时
        .writeTimeout(30, TimeUnit.SECONDS)   // 写入超时
        .callTimeout(60, TimeUnit.SECONDS)    // 整个调用超时（OkHttp 4.0+）
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "amber_dev/my-private-project")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $ACCESS_TOKEN")
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        }
        .retryOnConnectionFailure(true) // 自动重试
        .addInterceptor { chain ->
            var lastException: IOException? = null
            repeat(3) { retryCount -> // 明确重试3次
                try {
                    return@addInterceptor chain.proceed(chain.request())
                } catch (e: SocketTimeoutException) {
                    lastException = e
                    if (retryCount == 2) throw e // 最后一次重试后抛出
                }
            }
            throw lastException ?: IllegalStateException()
        }
        .build()
    return Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .addConverterFactory(
            json.asConverterFactory("application/json".toMediaType())
        )
        .client(okHttpClient)
        .build()
        .create(BangumiService::class.java)
}

suspend fun getManga(){
    val bangumiService = createBangumiService()
    val subject = bangumiService.getSubject(subjectId = 181926);
    val body = subject.body()
    println("${body?.id}  ${body?.name}")
}

suspend fun fetchAllManga(service: BangumiService): List<Subject> {
    val allManga = mutableListOf<Subject>()
    var offset = 0
    val limit = 20  // 每页数量
    var total = service.getSubjects(limit=1).body()?.total ?: 0

    while (offset < total) {
        val response = service.getSubjects(limit = limit, offset = offset)
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch manga at offset $offset")
        }
        val body = response.body()!!
        allManga.addAll(body.data)
        total = body.total  // 更新总数
        offset += limit
        delay(1000)  // 避免触发API限流（每分钟30次请求）
    }
    return allManga
}

fun filterAuthors(persons: List<Person>): List<Person> {
    return persons.filter { person ->
        person.relation == "作者" ||  // 关系是“作者”
                person.careers?.any { it.contains("mangaka") } == true  // 职业包含“漫画”
    }
}

suspend fun fetchAuthorsForManga(
    service: BangumiService,
    mangaList: List<Subject>
): Map<Subject, List<PersonDetail>> {
    val result = mutableMapOf<Subject, List<PersonDetail>>()
    for (manga in mangaList) {
        try {
            // 1. 获取漫画的所有相关人员
            val personsResponse = service.getSubjectPersons(manga.id)
            if (!personsResponse.isSuccessful) continue

            val persons = personsResponse.body()?: emptyList()
            val authors = filterAuthors(persons)

            // 2. 获取作者的详细信息
            val authorDetails = authors.mapNotNull { author ->
                val detailResponse = service.getPerson(author.id)
                if (detailResponse.isSuccessful) detailResponse.body() else null
            }

            result[manga] = authorDetails
            println("Processed manga: ${manga.name} (${authorDetails.size} authors)")
            delay(500)  // 控制请求频率
        } catch (e: Exception) {
            println("Error processing manga ${manga.id}: ${e.message}")
        }
    }
    return result
}

suspend fun main(){
    System.setOut(withContext(Dispatchers.IO) {
        PrintStream(System.out, true, "UTF-8")
    })
    val service = createBangumiService()
    val requeest = SubjectSearchRequest(keyword = "シスターブリーダー", filter = Filter(type = listOf(1)))
    val searchSubjects = service.searchSubjects(requeest)
    val json = Json { prettyPrint = true }
    println(json.encodeToString(searchSubjects.body()))
//    try {
//        val artists = fetchAllMangaWithAuthors()
//        // 保存到本地数据库或文件
//        val json = Gson().toJson(artists)
//        File("manga_artists.json").writeText(json)
//    } catch (e: Exception) {
//        when (e) {
//            is HttpException -> {
//                // 处理HTTP错误
//                if (e.code() == 429) {
//                    println("请求过于频繁，请稍后再试")
//                }
//            }
//            is IOException -> {
//                // 处理网络错误
//            }
//        }
//    }
}