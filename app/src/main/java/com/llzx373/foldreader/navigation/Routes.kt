package com.llzx373.foldreader.navigation

object Routes {
    const val BOOKSHELF = "bookshelf"
    const val READER = "reader/{bookId}?anchor={anchor}"
    const val SETTINGS = "settings"

    fun reader(bookId: Long, anchor: Long? = null): String =
        if (anchor != null) "reader/$bookId?anchor=$anchor" else "reader/$bookId"
}
