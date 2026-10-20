package com.soakula.wacr.ui.library

import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class LibraryRoute(
    val contact: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val query: String = ""
) {
    init {
        require(contact == null || contact.isNotBlank()) { "Invalid library contact" }
        require((from == null) == (to == null)) { "Incomplete library date range" }
        require(from == null || (from.year in 1..9999 && checkNotNull(to).year in 1..9999 && from <= to)) {
            "Invalid library date range"
        }
    }

    fun encode(): String = Uri.Builder().path("library").apply {
        contact?.let { appendQueryParameter("contact", it) }
        from?.let { appendQueryParameter("from", it.toString()) }
        to?.let { appendQueryParameter("to", it.toString()) }
        if (query.isNotEmpty()) appendQueryParameter("query", query)
    }.build().toString()

    companion object {
        fun parse(value: String): LibraryRoute {
            val uri = Uri.parse(value)
            require(uri.scheme == null && uri.authority == null && uri.path == "library" && uri.fragment == null) {
                "Invalid library route"
            }
            require(uri.queryParameterNames.all { it in setOf("contact", "from", "to", "query") }) {
                "Unsupported library argument"
            }
            fun argument(key: String): String? {
                val values = uri.getQueryParameters(key)
                require(values.size <= 1) { "Duplicate library argument" }
                return values.singleOrNull()
            }
            fun date(key: String): LocalDate? = argument(key)?.let {
                try { LocalDate.parse(it) }
                catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid library date") }
            }
            return LibraryRoute(argument("contact"), date("from"), date("to"), argument("query").orEmpty()).also {
                require(it.encode() == value) { "Noncanonical library route" }
            }
        }
    }
}
