package eu.kanade.tachiyomi.extension.all.nhentai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class CdnServers(
    private val origin: String,
    private val images: List<HttpUrl>,
    private val thumbnails: List<HttpUrl>,
) {
    fun imageUrl(path: String, number: Int): String? = resolve(images, path, number)

    fun thumbnailUrl(path: String, id: Int): String? = resolve(thumbnails, path, id)

    private fun resolve(servers: List<HttpUrl>, path: String, index: Int): String? {
        if (path.isBlank()) return null
        return servers[Math.floorMod(index, servers.size)].resolve(path)
            ?.takeIf { it.isOfficialMedia(origin) }
            ?.toString()
    }

    fun alternateFor(url: HttpUrl): HttpUrl? {
        if (!url.isOfficialMedia(origin)) return null
        val servers = if (images.any { it.host == url.host }) images else thumbnails
        val current = servers.indexOfFirst { it.host == url.host }
        if (current == -1 || servers.size < 2) return null
        val alternative = servers[(current + 1) % servers.size]
        return url.newBuilder().host(alternative.host).build()
    }
}

internal fun List<String>.cdnOrigins(origin: String): List<HttpUrl> = mapNotNull { value ->
    value.toHttpUrlOrNull()?.takeIf {
        it.isOfficialMedia(origin) && it.encodedPath == "/" && it.query == null && it.fragment == null
    }
}.distinctBy { it.host }

private fun HttpUrl.isOfficialMedia(origin: String): Boolean = isHttps && port == 443 && host.endsWith(".$origin") && username.isEmpty() && password.isEmpty()
