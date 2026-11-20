package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

@Source
abstract class Nhentai : KeiSource() {

    private val apiUrl get() = "$baseUrl/api/v2"
    private val apiHost by lazy { baseUrl.toHttpUrl().host }
    private val cdnLock = Mutex()
    private var cdn: CdnConfig? = null

    private val languageQuery
        get() = when (lang) {
            "en" -> "language:english"
            "ja" -> "language:japanese"
            "zh" -> "language:chinese"
            else -> ""
        }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("User-Agent", "Mihon-NHentai/1.6.64 (https://github.com/stenlysayd/extensions-source)")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(10, 1.minutes) { it.host == apiHost && it.encodedPath == "/api/v2/search" }
        .rateLimit(15, 1.minutes) {
            it.host == apiHost && it.encodedPath == "/api/v2/galleries"
        }
        .rateLimit(20, 1.minutes) {
            it.host == apiHost && it.encodedPath.startsWith("/api/v2/galleries/")
        }

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, languageQuery, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = search(page, languageQuery, "date")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val keyword = query.trim()
        val lookup = keyword.removePrefix("id:").trim().toIntOrNull()?.takeIf { it > 0 }
        if (lookup != null) {
            return MangasPage(
                if (page == 1) listOfNotNull(lookupGallery(lookup)) else emptyList(),
                hasNextPage = false,
            )
        }
        val terms = buildList {
            if (keyword.isNotEmpty()) add(keyword)
            if (languageQuery.isNotEmpty()) add(languageQuery)
            filters.filterIsInstance<TagFilter>().forEach { addAll(it.terms()) }
            filters.filterIsInstance<ComparisonFilter>().forEach { filter ->
                val value = filter.state.trim()
                if (value.isNotEmpty()) add("${filter.field}:$value")
            }
        }
        val sort = filters.firstInstanceOrNull<SortFilter>()?.sort ?: "date"
        return search(page, terms.joinToString(" "), sort)
    }

    private suspend fun search(page: Int, query: String, sort: String): MangasPage {
        val latest = query.isEmpty() && sort == "date"
        val url = (if (latest) "$apiUrl/galleries" else "$apiUrl/search")
            .toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                if (!latest) {
                    // The search endpoint requires a query even for an unfiltered popularity sort.
                    addQueryParameter("query", query.ifEmpty { "\"\"" })
                    addQueryParameter("sort", sort)
                }
            }
            .build()
        val data = client.get(url, Fetch.XHR).parseAs<GalleryPage>()
        if (data.result.isEmpty()) return MangasPage(emptyList(), false)
        val thumbServer = cdnConfig().thumbServer
        return MangasPage(data.result.map { it.toManga(thumbServer) }, page < data.numPages)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        Filter.Header("Separate values with commas. Prefix a value with - to exclude it."),
        TagFilter("Tags", "tag"),
        TagFilter("Artists", "artist"),
        TagFilter("Groups", "group"),
        TagFilter("Parodies", "parody"),
        TagFilter("Characters", "character"),
        TagFilter("Categories", "category"),
        TagFilter("Languages", "language"),
        ComparisonFilter("Pages (e.g. >10)", "pages"),
        ComparisonFilter("Favorites (e.g. >=100)", "favorites"),
        ComparisonFilter("Uploaded (e.g. <7d)", "uploaded"),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != apiHost) return null
        val galleryId = galleryPath.matchEntire(url.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return lookupGallery(galleryId)
    }

    private suspend fun lookupGallery(id: Int): SManga? {
        val response = client.get("$apiUrl/galleries/$id", Fetch.XHR, ensureSuccess = false)
        val gallery = response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw IOException("HTTP error ${it.code}")
            it.parseAs<Gallery>()
        }
        return gallery.toManga(cdnConfig().thumbServer).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val gallery = gallery(manga.url)
        return SMangaUpdate(gallery.toManga(cdnConfig().thumbServer), listOf(gallery.toChapter()))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = gallery(chapter.url).pages
        if (pages.isEmpty()) throw IOException("NHentai returned no pages for this gallery.")
        val imageServer = cdnConfig().imageServer
        return pages.sortedBy { it.number }.mapIndexed { index, page ->
            Page(index, imageUrl = mediaUrl(imageServer, page.path))
        }
    }

    private suspend fun gallery(path: String): Gallery {
        val id = galleryPath.matchEntire(path)?.groupValues?.get(1) ?: throw IOException("Invalid NHentai gallery URL.")
        return client.get("$apiUrl/galleries/$id", Fetch.XHR).parseAs()
    }

    private suspend fun cdnConfig(): CdnConfig = cdnLock.withLock {
        cdn ?: client.get("$apiUrl/cdn", Fetch.XHR).parseAs<CdnConfig>().also {
            if (it.imageServers.isEmpty() || it.thumbServers.isEmpty()) {
                throw IOException("NHentai returned an empty CDN configuration.")
            }
            cdn = it
        }
    }

    private class TagFilter(name: String, private val field: String) : Filter.Text(name) {
        fun terms(): List<String> = state.split(',').mapNotNull {
            val value = it.trim()
            val name = value.removePrefix("-").trim().replace("\"", "")
            if (name.isEmpty()) return@mapNotNull null
            val prefix = if (value.startsWith('-')) "-" else ""
            "$prefix$field:\"$name\""
        }
    }

    private class ComparisonFilter(name: String, val field: String) : Filter.Text(name)

    private class SortFilter : Filter.Select<String>("Sort by", sortOptions.map { it.first }.toTypedArray()) {
        val sort get() = sortOptions[state].second
    }

    companion object {
        private val galleryPath = Regex("""/g/(\d+)/?""")
        private val sortOptions = listOf(
            "Newest" to "date",
            "Popular (all time)" to "popular",
            "Popular today" to "popular-today",
            "Popular this week" to "popular-week",
            "Popular this month" to "popular-month",
        )
    }
}
