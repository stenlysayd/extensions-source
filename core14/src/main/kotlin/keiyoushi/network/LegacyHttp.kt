package keiyoushi.network

import eu.kanade.tachiyomi.network.await
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Fetch { DOCUMENT, XHR }
val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build()

suspend fun OkHttpClient.get(url: String, headers: Headers, fetch: Fetch, cacheControl: CacheControl = DEFAULT_CACHE_CONTROL): Response = get(url.toHttpUrl(), headers, fetch, cacheControl)

suspend fun OkHttpClient.get(url: HttpUrl, headers: Headers, fetch: Fetch, cacheControl: CacheControl = DEFAULT_CACHE_CONTROL, ensureSuccess: Boolean = true): Response {
    val request = Request.Builder().url(url).headers(headers).cacheControl(cacheControl)
    if (headers["Accept"] == null) request.header("Accept", if (fetch == Fetch.DOCUMENT) "text/html,*/*" else "application/json,*/*")
    val response = newCall(request.build()).await()
    if (ensureSuccess && !response.isSuccessful) {
        response.close()
        throw IOException("HTTP ${response.code}")
    }
    return response
}
