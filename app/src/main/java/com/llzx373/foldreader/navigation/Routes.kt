package com.llzx373.foldreader.navigation

object Routes {
    const val BOOKSHELF = "bookshelf"
    const val READER = "reader/{bookId}"
    const val SETTINGS = "settings"

    fun reader(bookId: Long) = "reader/$bookId"
}
