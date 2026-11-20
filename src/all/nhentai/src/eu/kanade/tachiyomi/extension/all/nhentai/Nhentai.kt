package eu.kanade.tachiyomi.extension.all.nhentai

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.DEFAULT_CACHE_CONTROL
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

@Source
abstract class Nhentai :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()
    private val apiKey get() = preferences.getString(API_KEY, "").orEmpty().trim()
    private val shortTitles get() = preferences.getString(TITLE_PREF, "full") == "short"
    private val defaultSort get() = preferences.getString(SORT_PREF, "date") ?: "date"
    private val apiUrl get() = "$baseUrl/api/v2"
    private val apiHost by lazy { baseUrl.toHttpUrl().host }
    private val apiClient by lazy {
        // Requests carrying account credentials must stay on the API origin.
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
    private val cdnLock = Mutex()

    @Volatile
    private var cdn: CdnServers? = null

    private val languageQuery
        get() = when (lang) {
            "en" -> "language:english"
            "ja" -> "language:japanese"
            "zh" -> "language:chinese"
            else -> ""
        }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("User-Agent", "Mihon-NHentai/1.6.67 (https://github.com/stenlysayd/extensions-source)")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(10, 1.minutes) { it.host == apiHost && it.encodedPath == "/api/v2/search" }
        .rateLimit(15, 1.minutes) {
            it.host == apiHost && it.encodedPath in setOf("/api/v2/galleries", "/api/v2/favorites")
        }
        .rateLimit(20, 1.minutes) {
            it.host == apiHost && it.encodedPath.startsWith("/api/v2/galleries/")
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val alternate = cdn?.alternateFor(request.url) ?: return@addInterceptor chain.proceed(request)
            if (request.method != "GET" || request.header("Authorization") != null) {
                return@addInterceptor chain.proceed(request)
            }
            try {
                val response = chain.proceed(request)
                if (response.code !in 500..599 && response.code != 404 && response.code != 408) {
                    return@addInterceptor response
                }
                response.close()
            } catch (error: IOException) {
                if (chain.call().isCanceled()) throw error
            }
            chain.proceed(request.newBuilder().url(alternate).build())
        }

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, languageQuery, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = search(page, languageQuery, "date")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val keyword = query.trim()
        val lookup = keyword.removePrefix("id:").trim().toIntOrNull()?.takeIf { it > 0 }
        if (lookup != null) {
            return if (page == 1) singleGallery(lookup) else MangasPage(emptyList(), false)
        }
        if (filters.firstInstanceOrNull<RandomFilter>()?.state == true) {
            if (page != 1) return MangasPage(emptyList(), false)
            val random = apiGet("$apiUrl/galleries/random".toHttpUrl(), fresh = true).parseAs<RandomGallery>()
            return singleGallery(random.id)
        }

        val terms = buildList {
            if (keyword.isNotEmpty()) add(keyword)
            filters.filterIsInstance<TagFilter>().forEach { addAll(it.terms()) }
            filters.filterIsInstance<ComparisonFilter>().forEach { filter ->
                val value = filter.state.trim()
                if (value.isNotEmpty()) add("${filter.field}:$value")
            }
        }
        val offset = filters.firstInstanceOrNull<PageOffset>()?.state?.trim()?.takeUnless { it.isEmpty() }?.let {
            it.toIntOrNull()?.takeIf { number -> number in 0..(Int.MAX_VALUE - page) }
                ?: throw IOException("Enter a valid non-negative page offset.")
        } ?: 0
        val resultPage = page + offset
        if (filters.firstInstanceOrNull<FavoritesFilter>()?.state == true) {
            val key = apiKey.ifEmpty { throw IOException("Add an NHentai API key in the extension settings to view favorites.") }
            val url = "$apiUrl/favorites".toHttpUrl().newBuilder()
                .addQueryParameter("page", resultPage.toString())
                .apply { if (terms.isNotEmpty()) addQueryParameter("q", terms.joinToString(" ")) }
                .build()
            return listGalleries(url, resultPage, key)
        }
        val sort = filters.firstInstanceOrNull<SortFilter>()?.sort ?: defaultSort
        val scopedQuery = (terms + languageQuery).filter(String::isNotEmpty).joinToString(" ")
        return search(resultPage, scopedQuery, sort)
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
        return listGalleries(url, page)
    }

    private suspend fun listGalleries(url: HttpUrl, page: Int, key: String = apiKey): MangasPage {
        val data = apiGet(url, key).parseAs<GalleryPage>()
        if (data.result.isEmpty()) return MangasPage(emptyList(), false)
        val servers = cdnServers()
        val shorten = shortTitles
        return MangasPage(data.result.map { it.toManga(servers, shorten) }, page < data.numPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != apiHost && url.host != "www.$apiHost") return null
        return galleryId(url.encodedPath)?.let { lookupGallery(it) }
    }

    private suspend fun singleGallery(id: Int) = MangasPage(listOfNotNull(lookupGallery(id)), false)

    private suspend fun lookupGallery(id: Int): SManga? {
        val response = apiGet("$apiUrl/galleries/$id".toHttpUrl(), allowNotFound = true)
        val gallery = response.use {
            if (it.code == 404) return null
            it.parseAs<Gallery>()
        }
        return gallery.toManga(cdnServers(), shortTitles).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val gallery = gallery(manga.url)
        return SMangaUpdate(gallery.toManga(cdnServers(), shortTitles), listOf(gallery.toChapter()))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = gallery(chapter.url).pages
        if (pages.isEmpty()) return emptyList()
        val servers = cdnServers()
        return pages.sortedBy { it.number }.mapIndexed { index, page ->
            val imageUrl = servers.imageUrl(page.path, page.number)
                ?: throw IOException("NHentai returned an invalid image URL for page ${page.number}.")
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(defaultSort),
        Filter.Header("Separate values with commas. Prefix a value with - to exclude it."),
        TagFilter("Tags", "tag"),
        TagFilter("Artists", "artist"),
        TagFilter("Groups", "group"),
        TagFilter("Parodies", "parody"),
        TagFilter("Characters", "character"),
        TagFilter("Categories", "category"),
        TagFilter("Languages", "language"),
        ComparisonFilter("Pages (e.g. >10)", "pages"),
        ComparisonFilter("Favorite count (e.g. >=100)", "favorites"),
        ComparisonFilter("Uploaded (e.g. <7d)", "uploaded"),
        PageOffset(),
        Filter.Separator(),
        FavoritesFilter(),
        RandomFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = "Display manga title as"
            entries = arrayOf("Full title", "Short title")
            entryValues = arrayOf("full", "short")
            setDefaultValue("full")
            summary = "%s"
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = SORT_PREF
            title = "Default search sort"
            entries = sortOptions.map { it.first }.toTypedArray()
            entryValues = sortOptions.map { it.second }.toTypedArray()
            setDefaultValue("date")
            summary = "%s"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = API_KEY
            title = "NHentai API key"
            summary = "Optional for browsing. Required for account favorites. Get a key in NHentai account settings."
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        }.also(screen::addPreference)
    }

    private suspend fun gallery(path: String): Gallery {
        val id = galleryId(path) ?: throw IOException("Invalid NHentai gallery URL.")
        return apiGet("$apiUrl/galleries/$id".toHttpUrl()).parseAs()
    }

    private fun galleryId(path: String): Int? = galleryPath.matchEntire(path)?.groupValues?.get(1)
        ?.toIntOrNull()?.takeIf { it > 0 }

    private suspend fun cdnServers(): CdnServers = cdn ?: cdnLock.withLock {
        cdn ?: (
            readCdn("/cdn") ?: readCdn("/config")
                ?: throw IOException("NHentai did not return usable HTTPS CDN servers.")
            ).also { cdn = it }
    }

    private suspend fun readCdn(path: String): CdnServers? = apiGet("$apiUrl$path".toHttpUrl(), key = "", allowNotFound = true).use {
        if (it.code == 404) null else it.parseAs<CdnConfig>().toServers(apiHost)
    }

    private suspend fun apiGet(
        url: HttpUrl,
        key: String = apiKey,
        fresh: Boolean = false,
        allowNotFound: Boolean = false,
    ): Response {
        require(url.isHttps && url.host == apiHost && url.port == 443 && url.encodedPath.startsWith("/api/v2/"))
        if (key.any { it !in '!'..'~' }) throw IOException("The NHentai API key contains invalid characters.")
        val apiHeaders = headers.newBuilder()
            .apply { if (key.isNotEmpty()) set("Authorization", "Key $key") }
            .build()
        val cache = when {
            key.isNotEmpty() -> privateApiCache
            fresh -> CacheControl.FORCE_NETWORK
            else -> DEFAULT_CACHE_CONTROL
        }
        val response = apiClient.get(url, apiHeaders, Fetch.XHR, cache, ensureSuccess = false)
        if (response.isSuccessful || (allowNotFound && response.code == 404)) return response
        response.close()
        throw IOException(
            when (response.code) {
                401, 403 -> if (key.isNotEmpty()) {
                    "NHentai rejected the API key or denied access. Check the key in extension settings."
                } else {
                    "NHentai denied access (HTTP ${response.code})."
                }
                429 -> "NHentai rate limit reached. Wait a minute and try again."
                else -> "NHentai API returned HTTP ${response.code}."
            },
        )
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

    private class PageOffset : Filter.Text("Offset results by pages")

    private class FavoritesFilter : Filter.CheckBox("Show account favorites only", false)

    private class RandomFilter : Filter.CheckBox("Open a random gallery (any language)", false)

    private class SortFilter(default: String) :
        Filter.Select<String>(
            "Sort by",
            sortOptions.map { it.first }.toTypedArray(),
            sortOptions.indexOfFirst { it.second == default }.coerceAtLeast(0),
        ) {
        val sort get() = sortOptions[state].second
    }

    companion object {
        private const val API_KEY = "api_key"
        private const val TITLE_PREF = "Display manga title as:"
        private const val SORT_PREF = "Default sort preference when searching"
        private val galleryPath = Regex("""/g/(\d+)(?:/\d+)?/?""")
        private val privateApiCache = CacheControl.Builder().noCache().noStore().build()
        private val sortOptions = listOf(
            "Newest" to "date",
            "Popular (all time)" to "popular",
            "Popular today" to "popular-today",
            "Popular this week" to "popular-week",
            "Popular this month" to "popular-month",
        )
    }
}
