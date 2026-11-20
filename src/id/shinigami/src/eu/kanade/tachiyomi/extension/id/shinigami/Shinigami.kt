package eu.kanade.tachiyomi.extension.id.shinigami

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Instant

@Source
abstract class Shinigami : KeiSource() {
    private val apiUrl = "https://api.shinigami.io/v1"

    private val apiHeaders: Headers
        get() = headersBuilder()
            .set("Accept", "application/json")
            .add("DNT", "1")
            .add("Sec-GPC", "1")
            .build()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    // ====================== Popular ======================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchMangaList(
        mangaListUrl(page, limit = 24).apply {
            addQueryParameter("sort", "popularity")
            addQueryParameter("sort_order", "desc")
        },
    )

    // ====================== Latest ======================

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchMangaList(
        mangaListUrl(page, limit = 20).apply {
            addQueryParameter("is_update", "true")
            addQueryParameter("sort", "latest")
            addQueryParameter("sort_order", "desc")
        },
    )

    // ====================== Search ======================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = mangaListUrl(page, limit = 24)
            .addQueryParameter("sort", "latest")
            .addQueryParameter("sort_order", "desc")
        query.trim().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("q", it) }

        filters.filterIsInstance<UriFilter>().forEach {
            it.addToUri(url)
        }

        return fetchMangaList(url)
    }

    private fun mangaListUrl(page: Int, limit: Int): HttpUrl.Builder = "$apiUrl/manga/list".toHttpUrl().newBuilder()
        .addQueryParameter("offset", ((page - 1L) * limit).toString())
        .addQueryParameter("limit", limit.toString())

    private suspend fun fetchMangaList(url: HttpUrl.Builder): MangasPage = parseMangaList(client.get(url.build(), apiHeaders, Fetch.XHR))

    private fun parseMangaList(response: Response): MangasPage {
        val rootObject = response.parseAs<ShinigamiBrowseDto>()
        val projectList = rootObject.data.map(::popularMangaFromObject)
        return MangasPage(projectList, rootObject.meta.hasNext)
    }

    private fun popularMangaFromObject(obj: ShinigamiBrowseDataDto): SManga = SManga.create().apply {
        title = obj.title
        thumbnail_url = obj.thumbnail
        url = obj.mangaId
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val genres = async { fetchTaxonomy("genre") }
        val themes = async { fetchTaxonomy("theme") }
        val subformats = async { fetchTaxonomy("subformat") }
        FilterDataDto(genres.await(), themes.await(), subformats.await()).toJsonElement()
    }

    private suspend fun fetchTaxonomy(type: String): List<TaxonomyOptionDto> = buildList {
        do {
            val url = "$apiUrl/taxonomy/list".toHttpUrl().newBuilder()
                .addQueryParameter("type", type)
                .addQueryParameter("offset", size.toString())
                .addQueryParameter("limit", "100")
                .build()
            val response = client.get(url, apiHeaders, Fetch.XHR).parseAs<TaxonomyListDto>()
            addAll(response.data)
        } while (response.meta.hasNext && response.data.isNotEmpty())
    }.distinctBy { it.slug }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        buildList {
            add(SortFilter())
            add(StatusFilter())
            add(FormatFilter())
            add(TypeFilter())
            add(MinimumChaptersFilter())
            add(ContributorFilter("Penulis (slug)", "author"))
            add(ContributorFilter("Ilustrator (slug)", "artist"))
            data?.parseAs<FilterDataDto>()?.let {
                add(TaxonomyFilter("Genre", "genre", it.genres))
                add(TaxonomyFilter("Tema", "theme", it.themes))
                add(TaxonomyFilter("Subformat", "subformat", it.subformats))
            }
        },
    )

    // ====================== Manga Details & Chapters ======================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url.removePrefix("/series/")}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url.removePrefix("/chapter/")}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val host = url.host
        val isShinigamiHost = host == baseUrl.toHttpUrl().host ||
            host == "shinigami.mx" || host.endsWith(".shinigami.mx") ||
            host == "shinigami.asia" || host.endsWith(".shinigami.asia")
        if (!isShinigamiHost) return null
        val firstSegment = url.pathSegments.firstOrNull()
        if (firstSegment != "series") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { UUID_PATTERN.matches(it) } ?: return null

        val manga = SManga.create().apply {
            this.url = id
        }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) {
            async { fetchMangaDetails(manga) }
        } else {
            null
        }

        val chaptersDeferred = if (fetchChapters) {
            async { fetchChapterList(manga) }
        } else {
            null
        }

        val updatedManga = detailsDeferred?.await() ?: manga
        val updatedChapters = chaptersDeferred?.await() ?: chapters

        SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchMangaDetails(manga: SManga): SManga {
        val id = storedId(manga.url, "/series/")
        val response = client.get("$apiUrl/manga/detail/$id", apiHeaders, Fetch.XHR)
        val mangaDetailsResponse = response.parseAs<ShinigamiMangaDetailDto>()
        val mangaDetails = mangaDetailsResponse.data

        return manga.apply {
            mangaDetails.title?.takeIf { it.isNotBlank() }?.let { title = it }
            mangaDetails.thumbnail?.let { thumbnail_url = it }
            author = mangaDetails.taxonomy["Author"]?.joinToString { it.name }.orEmpty()
            artist = mangaDetails.taxonomy["Artist"]?.joinToString { it.name }.orEmpty()
            status = mangaDetails.status.toStatus()
            description = buildString {
                append(mangaDetails.description)
                mangaDetails.altTitle?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Judul alternatif: ").append(it.split('|').joinToString { title -> title.trim() })
                }
            }

            genre = listOf("Genre", "Theme", "Subformat", "Format")
                .flatMap { mangaDetails.taxonomy[it].orEmpty() }
                .map { it.name }
                .distinct()
                .joinToString()
        }
    }

    private fun Int.toStatus(): Int = when (this) {
        1 -> SManga.ONGOING
        2 -> SManga.COMPLETED
        3 -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> = buildList {
        val id = storedId(manga.url, "/series/")
        val listUrl = "$apiUrl/chapter/$id/list".toHttpUrl().newBuilder()
            .addQueryParameter("sort_by", "chapter_number")
            .addQueryParameter("sort_order", "desc")
            .addQueryParameter("limit", "100")
            .build()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val url = cursor?.let { listUrl.newBuilder().addQueryParameter("cursor", it).build() } ?: listUrl
            val result = client.get(url, apiHeaders, Fetch.XHR).parseAs<ShinigamiChapterListDto>()
            result.chapterList.mapTo(this, ::chapterFromObject)
            cursor = if (result.meta.hasNext) {
                result.meta.nextCursor?.takeIf { it.isNotBlank() && seenCursors.add(it) }
                    ?: throw Exception("Gagal memuat seluruh daftar chapter")
            } else {
                null
            }
        } while (cursor != null)
    }

    private fun chapterFromObject(obj: ShinigamiChapterListDataDto): SChapter = SChapter.create().apply {
        date_upload = Instant.tryParse(obj.date.orEmpty())
        name = "Chapter ${obj.number.toString().removeSuffix(".0")} ${obj.title.orEmpty()}".trim()
        chapter_number = obj.number.toFloat()
        url = obj.chapterId
    }

    // ====================== Page List ======================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = storedId(chapter.url, "/chapter/")
        val response = client.get("$apiUrl/chapter/detail/$id", apiHeaders, Fetch.XHR)
        val result = response.parseAs<ShinigamiPageListDto>()

        return result.pageList.chapterPage.pages.mapIndexed { index, imageName ->
            Page(index, imageUrl = "${result.pageList.baseUrl}${result.pageList.chapterPage.path}$imageName")
        }
    }

    override fun getImageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .add("DNT", "1")
            .add("Sec-GPC", "1")
            .build()

        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(imageHeaders)
            .get()
            .build()
    }

    private fun storedId(url: String, prefix: String): String = url.removePrefix(prefix).takeIf { UUID_PATTERN.matches(it) }
        ?: throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    }
}
