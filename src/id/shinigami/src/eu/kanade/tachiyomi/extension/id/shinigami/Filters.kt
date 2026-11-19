package eu.kanade.tachiyomi.extension.id.shinigami

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UriFilter {
    fun addToUri(builder: HttpUrl.Builder)
}

open class SelectFilter(
    name: String,
    private val param: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val value = options[state].second
        if (value.isNotEmpty()) builder.setQueryParameter(param, value)
    }
}

class SortFilter :
    Filter.Select<String>(
        "Urutkan",
        arrayOf("Update terbaru", "Seri terbaru", "Paling populer", "Rating tertinggi", "A-Z"),
    ),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val sort = listOf("latest", "new_manga", "popularity", "rating", "title")[state]
        builder.setQueryParameter("sort", sort)
        builder.setQueryParameter("sort_order", if (sort == "title") "asc" else "desc")
    }
}

class StatusFilter :
    SelectFilter(
        "Status",
        "status",
        listOf("Semua" to "", "Ongoing" to "ongoing", "Tamat" to "completed", "Hiatus" to "hiatus"),
    )

class FormatFilter :
    SelectFilter(
        "Format",
        "format",
        listOf("Semua" to "", "Manga" to "manga", "Manhwa" to "manhwa", "Manhua" to "manhua"),
    )

class TypeFilter :
    SelectFilter(
        "Tipe",
        "type",
        listOf("Semua" to "", "Project" to "project", "Mirror" to "mirror"),
    )

class MinimumChaptersFilter :
    SelectFilter(
        "Jumlah chapter minimum",
        "min_chapters",
        listOf("Semua" to "", "10" to "10", "50" to "50", "100" to "100", "200" to "200"),
    )

class ContributorFilter(name: String, private val param: String) :
    Filter.Text(name),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        state.trim().takeIf { it.isNotEmpty() }?.let { builder.setQueryParameter(param, it) }
    }
}

class TaxonomyOption(val slug: String, name: String) : Filter.CheckBox(name)

class TaxonomyFilter(
    name: String,
    private val param: String,
    options: List<TaxonomyOptionDto>,
) : Filter.Group<TaxonomyOption>(name, options.map { TaxonomyOption(it.slug, it.name) }),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val selected = state.filter { it.state }.map { it.slug }
        if (selected.isNotEmpty()) builder.setQueryParameter(param, selected.joinToString(","))
    }
}
