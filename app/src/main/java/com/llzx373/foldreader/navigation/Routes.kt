package com.llzx373.foldreader.navigation

object Routes {
    const val BOOKSHELF = "bookshelf"
    const val READER = "reader/{bookId}?anchor={anchor}"
    const val FILE_BROWSER = "file_browser"
    const val SETTINGS = "settings"

    const val ARG_BOOK_ID = "bookId"
    const val ARG_ANCHOR = "anchor"

    fun reader(bookId: Long, anchor: Long? = null): String =
        if (anchor != null) "reader/$bookId?anchor=$anchor" else "reader/$bookId"
}
