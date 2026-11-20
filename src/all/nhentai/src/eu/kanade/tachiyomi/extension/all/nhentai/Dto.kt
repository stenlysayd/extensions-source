package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class CdnConfig(
    @SerialName("image_servers") val imageServers: List<String>,
    @SerialName("thumb_servers") val thumbServers: List<String>,
) {
    fun toServers(origin: String): CdnServers? {
        val images = imageServers.cdnOrigins(origin)
        val thumbnails = thumbServers.cdnOrigins(origin)
        if (images.isEmpty() || thumbnails.isEmpty()) return null
        return CdnServers(origin, images, thumbnails)
    }
}

@Serializable
internal class GalleryPage(
    val result: List<GalleryItem>,
    @SerialName("num_pages") val numPages: Int,
)

@Serializable
internal class GalleryItem(
    private val id: Int,
    @SerialName("english_title") private val englishTitle: String?,
    @SerialName("japanese_title") private val japaneseTitle: String?,
    private val thumbnail: String,
) {
    fun toManga(servers: CdnServers, shortTitles: Boolean): SManga = galleryManga(
        id,
        displayTitle(id, shortTitles, englishTitle, japaneseTitle),
        servers.thumbnailUrl(thumbnail, id),
    )
}

@Serializable
internal class Gallery(
    private val id: Int,
    private val title: GalleryTitle,
    private val cover: GalleryCover,
    private val tags: List<GalleryTag>,
    private val scanlator: String = "",
    @SerialName("upload_date") private val uploadDate: Long,
    @SerialName("num_pages") private val numPages: Int,
    @SerialName("num_favorites") private val numFavorites: Int,
    val pages: List<GalleryImage> = emptyList(),
) {
    fun toManga(servers: CdnServers, shortTitles: Boolean): SManga = galleryManga(
        id,
        displayTitle(id, shortTitles, title.english, title.japanese, title.pretty),
        servers.thumbnailUrl(cover.path, id),
    ).apply {
        val artists = tags.filter { it.type == "artist" }.joinToString { it.name }
        val groups = tags.filter { it.type == "group" }.joinToString { it.name }
        artist = artists
        author = groups.ifBlank { artists }
        genre = tags.filter { it.type == "tag" || it.type == "category" || it.type == "language" }
            .joinToString { it.name }
        description = buildString {
            this@Gallery.title.japanese?.takeIf { it.isNotBlank() }?.let { appendLine(it).appendLine() }
            appendLine("Pages: $numPages")
            appendLine("Favorites: $numFavorites")
            val parodies = tags.filter { it.type == "parody" }.joinToString { it.name }
            if (parodies.isNotEmpty()) appendLine("Parodies: $parodies")
            val characters = tags.filter { it.type == "character" }.joinToString { it.name }
            if (characters.isNotEmpty()) append("Characters: $characters")
        }.trimEnd()
    }

    fun toChapter(): SChapter = SChapter.create().apply {
        url = "/g/$id/"
        name = "Chapter"
        chapter_number = 1F
        date_upload = uploadDate * 1000L
        scanlator = this@Gallery.scanlator.ifBlank { tags.filter { it.type == "group" }.joinToString { it.name } }
    }
}

@Serializable
internal class GalleryTitle(val english: String?, val japanese: String?, val pretty: String?)

@Serializable
internal class GalleryCover(val path: String)

@Serializable
internal class GalleryImage(val number: Int, val path: String)

@Serializable
internal class GalleryTag(val name: String, val type: String)

private fun galleryManga(id: Int, galleryTitle: String, image: String?): SManga = SManga.create().apply {
    url = "/g/$id/"
    title = galleryTitle
    thumbnail_url = image
    status = SManga.COMPLETED
    update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
}

@Serializable
internal class RandomGallery(val id: Int)

private val titleAffixes = Regex("""\[[^]]*]|\([^)]*\)|\{[^}]*}""")

private fun displayTitle(
    id: Int,
    shorten: Boolean,
    english: String?,
    japanese: String?,
    pretty: String? = null,
): String {
    val full = sequenceOf(english, japanese, pretty).firstOrNull { !it.isNullOrBlank() } ?: "#$id"
    if (!shorten) return full
    return pretty?.takeIf { it.isNotBlank() } ?: full.replace(titleAffixes, "").trim().ifEmpty { full }
}
