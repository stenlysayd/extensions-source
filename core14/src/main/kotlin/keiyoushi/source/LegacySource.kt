package keiyoushi.source

import android.util.Log
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.util.concurrent.atomic.AtomicBoolean

class MangaUpdate(val manga: SManga, val chapters: List<SChapter>)

/** Bridges the Rx API used by legacy readers to the source's suspend operations. */
abstract class KeiSource : HttpSource() {
    final override val supportsLatest get() = true
    final override val client by lazy { network.cloudflareClient.newBuilder().configureClient().build() }
    protected open fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this
    protected open fun Headers.Builder.configureHeaders(): Headers.Builder = this
    final override fun headersBuilder(): Headers.Builder = super.headersBuilder().set("Referer", "$baseUrl/").configureHeaders()

    abstract suspend fun getPopularManga(page: Int): MangasPage
    abstract suspend fun getLatestUpdates(page: Int): MangasPage
    abstract suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage
    protected abstract suspend fun getMangaByUrl(url: HttpUrl): SManga?
    protected abstract suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): MangaUpdate
    abstract suspend fun getPageList(chapter: SChapter): List<Page>
    override suspend fun getMangaDetails(manga: SManga) = fetchMangaUpdate(manga, emptyList(), true, false).manga.apply { initialized = true }
    override suspend fun getChapterList(manga: SManga) = fetchMangaUpdate(manga, emptyList(), false, true).chapters
    private fun <T> observable(block: suspend () -> T): Observable<T> = Observable.fromCallable { runBlocking { block() } }
    override fun fetchPopularManga(page: Int) = observable { getPopularManga(page) }
    override fun fetchLatestUpdates(page: Int) = observable { getLatestUpdates(page) }
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) = observable {
        val url = query.toHttpUrlOrNull()
        if (url == null) getSearchMangaList(page, query, filters) else MangasPage(listOfNotNull(getMangaByUrl(url)), false)
    }
    override fun fetchMangaDetails(manga: SManga) = observable { getMangaDetails(manga) }
    override fun fetchChapterList(manga: SManga) = observable { getChapterList(manga) }
    override fun fetchPageList(chapter: SChapter) = observable { getPageList(chapter) }
    protected open fun getImageRequest(page: Page): Request = super.imageRequest(page)
    override fun imageRequest(page: Page) = getImageRequest(page)
    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url
    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    protected open val supportsFilterFetching get() = false
    protected open val filterFetchHint get() = ""
    protected open suspend fun fetchFilterData(): JsonElement = error("No remote filters")
    protected abstract fun getFilterList(data: JsonElement?): FilterList

    @Volatile private var filterData: JsonElement? = null
    private val loading = AtomicBoolean()
    override fun getFilterList(): FilterList {
        val data = filterData
        val filters = getFilterList(data)
        if (!supportsFilterFetching || data != null) return filters
        if (loading.compareAndSet(false, true)) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    filterData = fetchFilterData()
                } catch (e: Exception) {
                    Log.w(name, "Could not load filters", e)
                } finally {
                    loading.set(false)
                }
            }
        }
        return FilterList(filters + Filter.Header(filterFetchHint))
    }

    // The 1.4 HttpSource declares these hooks abstract even when fetch methods are overridden.
    override fun popularMangaRequest(page: Int): Request = error("Use fetchPopularManga")
    override fun popularMangaParse(response: Response): MangasPage = error("Use fetchPopularManga")
    override fun latestUpdatesRequest(page: Int): Request = error("Use fetchLatestUpdates")
    override fun latestUpdatesParse(response: Response): MangasPage = error("Use fetchLatestUpdates")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = error("Use fetchSearchManga")
    override fun searchMangaParse(response: Response): MangasPage = error("Use fetchSearchManga")
    override fun mangaDetailsParse(response: Response): SManga = error("Use fetchMangaDetails")
    override fun chapterListParse(response: Response): List<SChapter> = error("Use fetchChapterList")
    override fun pageListParse(response: Response): List<Page> = error("Use fetchPageList")
    override fun imageUrlParse(response: Response): String = error("Source provides direct image URLs")
}
