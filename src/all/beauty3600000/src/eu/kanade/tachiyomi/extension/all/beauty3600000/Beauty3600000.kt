package eu.kanade.tachiyomi.extension.all.beauty3600000

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
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter

@Source
abstract class Beauty3600000 : KeiSource() {

    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val category = filters.firstInstanceOrNull<Category>()?.let { CATEGORIES[it.state].second }.orEmpty()
        val tag = filters.firstInstanceOrNull<Tag>()?.state?.trim().orEmpty()
        val keyword = query.trim()
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            when {
                keyword.isEmpty() && tag.isEmpty() && category.isNotEmpty() ->
                    addPathSegments("category/$category/")

                keyword.isEmpty() && category.isEmpty() && tag.isNotEmpty() ->
                    addPathSegment("tag").addPathSegment(tag).addPathSegment("")

                else -> {
                    if (keyword.isNotEmpty()) addQueryParameter("s", keyword)
                    if (category.isNotEmpty()) addQueryParameter("category_name", category)
                    if (tag.isNotEmpty()) addQueryParameter("tag", tag)
                }
            }
            if (page > 1) addPathSegments("page/$page/")
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val entries = document.select(POST_SELECTOR).mapNotNull { post ->
            val link = post.selectFirst(".entry-title a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                title = link.text()
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = post.selectFirst(".blog-entry-content img")?.imageUrl()
            }
        }
        return MangasPage(entries.distinctBy { it.url }, document.selectFirst("#main a.next.page-numbers") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return document.selectFirst(DETAIL_SELECTOR)?.toManga(document.location())?.apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val post = document.selectFirst(DETAIL_SELECTOR) ?: throw Exception("Gallery not found")
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Gallery"
            chapter_number = 1F
            date_upload = DateTimeFormatter.ISO_OFFSET_DATE_TIME.tryParseZonedDateTime(
                post.selectFirst("time.published")?.attr("datetime"),
            )
        }
        return SMangaUpdate(post.toManga(document.location()), listOf(chapter))
    }

    private fun Element.toManga(permalink: String): SManga = SManga.create().apply {
        setUrlWithoutDomain(permalink)
        title = selectFirst("h1.entry-title")?.text() ?: throw Exception("Gallery title not found")
        genre = select("a[rel~=category], .entry-footer a[rel=tag]")
            .map { it.text() }
            .distinct()
            .joinToString()
        description = selectFirst(".entry-content")?.text()?.takeIf { it.isNotBlank() }
        thumbnail_url = select(".entry-content img").firstNotNullOfOrNull { it.imageUrl() }
        status = SManga.COMPLETED
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        val images = document.select("$DETAIL_SELECTOR .entry-content img")
            .mapNotNull { it.imageUrl() }
            .distinct()
        return images.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    private fun Element.imageUrl(): String? {
        // Images Lazyload and Slideshow stores the real URL in file.
        val url = absUrl("file").ifEmpty { absUrl("src") }.toHttpUrlOrNull() ?: return null
        return url.toString().takeUnless { url.encodedPath.substringAfterLast('/').startsWith("blank_") }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(Category(), Tag())

    private class Category : Filter.Select<String>("Category", CATEGORIES.map { it.first }.toTypedArray())
    private class Tag : Filter.Text("Tag slug (from /tag/…)")

    companion object {
        private const val POST_SELECTOR = "#main article.post"
        private const val DETAIL_SELECTOR = "body.single-post $POST_SELECTOR"
        private val CATEGORIES = listOf(
            "All" to "",
            "Aidol" to "aidol",
            "China" to "china",
            "Chinese" to "chinese",
            "Cosplay" to "cosplay",
            "Gravure" to "gravure",
            "Japan" to "japan",
            "Korea" to "korea",
            "Thailand" to "thailand",
            "Uncategorized" to "uncategorized",
            "Magazine" to "magazine",
            "Photobook" to "photobook",
            "Western" to "western",
        )
    }
}
