package keiyoushi.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Response
import org.jsoup.Jsoup
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val legacyJson = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
    coerceInputValues = true
}
inline fun <reified T> Response.parseAs(): T = use { legacyJson.decodeFromString(requireNotNull(it.body).string()) }
inline fun <reified T> JsonElement.parseAs(): T = legacyJson.decodeFromJsonElement(this)
inline fun <reified T> T.toJsonElement(): JsonElement = legacyJson.encodeToJsonElement(this)
fun Response.asJsoup() = use { Jsoup.parse(requireNotNull(it.body).byteStream(), null, it.request.url.toString()) }
fun DateTimeFormatter.tryParseZonedDateTime(value: String?): Long = runCatching { ZonedDateTime.parse(value, this).toInstant().toEpochMilli() }.getOrDefault(0L)
