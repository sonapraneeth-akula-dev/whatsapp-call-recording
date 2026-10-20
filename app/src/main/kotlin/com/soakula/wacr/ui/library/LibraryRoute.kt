package com.soakula.wacr.ui.library

import android.net.Uri

data class LibraryRoute(val contact: String? = null) {
    init { require(contact == null || contact.isNotBlank()) { "Invalid library contact" } }

    fun encode(): String = Uri.Builder().path("library").apply {
        contact?.let { appendQueryParameter("contact", it) }
    }.build().toString()

    companion object {
        fun parse(value: String): LibraryRoute {
            val uri = Uri.parse(value)
            require(uri.scheme == null && uri.authority == null && uri.path == "library" && uri.fragment == null) {
                "Invalid library route"
            }
            require(uri.queryParameterNames.all { it == "contact" }) { "Unsupported library argument" }
            val contacts = uri.getQueryParameters("contact")
            require(contacts.size <= 1) { "Duplicate library contact" }
            return LibraryRoute(contacts.singleOrNull()).also {
                require(it.encode() == value) { "Noncanonical library route" }
            }
        }
    }
}
