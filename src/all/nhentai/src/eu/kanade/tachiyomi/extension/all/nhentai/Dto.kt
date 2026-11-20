package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
internal class CdnConfig(
    @SerialName("image_servers") val imageServers: List<String>,
    @SerialName("thumb_servers") val thumbServers: List<String>,
) {
    val imageServer get() = imageServers.first()
    val thumbServer get() = thumbServers.first()
}

@Serializable
internal class GalleryPage(
    val result: List<GalleryItem>,
    @SerialName("num_pages") val numPages: Int,
)

@Serializable
internal class GalleryItem(
    private val id: Int,
    @SerialName("english_title") private val englishTitle: String,
    @SerialName("japanese_title") private val japaneseTitle: String? = null,
    private val thumbnail: String,
) {
    fun toManga(thumbServer: String): SManga = galleryManga(
        id,
        englishTitle.ifBlank { japaneseTitle.orEmpty().ifBlank { "#$id" } },
        mediaUrl(thumbServer, thumbnail),
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
    fun toManga(thumbServer: String): SManga = galleryManga(
        id,
        title.english.ifBlank { title.pretty.ifBlank { title.japanese.orEmpty().ifBlank { "#$id" } } },
        mediaUrl(thumbServer, cover.path),
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
internal class GalleryTitle(val english: String, val japanese: String? = null, val pretty: String)

@Serializable
internal class GalleryCover(val path: String)

@Serializable
internal class GalleryImage(val number: Int, val path: String)

@Serializable
internal class GalleryTag(val name: String, val type: String)

private fun galleryManga(id: Int, galleryTitle: String, image: String): SManga = SManga.create().apply {
    url = "/g/$id/"
    title = galleryTitle
    thumbnail_url = image
    status = SManga.COMPLETED
    update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
}

internal fun mediaUrl(server: String, path: String): String = path.toHttpUrlOrNull()?.toString()
    ?: "${server.trimEnd('/')}/${path.trimStart('/')}"
