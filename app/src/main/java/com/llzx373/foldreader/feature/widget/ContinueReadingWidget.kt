package com.llzx373.foldreader.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.llzx373.foldreader.MainActivity
import com.llzx373.foldreader.R
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookWithProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 小部件取数口径（纯函数，可单测）：最近在读的 N 本。
 *
 * 只看「读过」（lastReadAt 非空）且未隐藏的书，按最近阅读倒序——
 * 隐藏书不上桌面（M34 隐私锁的口径延伸到小部件）。
 */
internal fun pickWidgetRecent(books: List<BookWithProgress>, limit: Int): List<BookWithProgress> =
    books.filter { !it.book.hidden && it.book.lastReadAt != null }
        .sortedByDescending { it.book.lastReadAt }
        .take(limit)

/** 行内进度百分比（0-100）；文本按字符偏移、页式（漫画/PDF）按页序号，算不出给 0。 */
internal fun widgetProgressPercent(item: BookWithProgress): Int {
    val book = item.book
    return if (book.format == BookFormat.COMIC || book.format == BookFormat.PDF) {
        val page = item.comicPage ?: return 0
        val count = book.comicPageCount ?: return 0
        if (count <= 0) 0 else (page * 100 / count).coerceIn(0, 100)
    } else {
        val offset = item.charOffset ?: return 0
        if (book.totalChars <= 0) 0 else (offset * 100 / book.totalChars).toInt().coerceIn(0, 100)
    }
}

/**
 * M34 桌面小部件「继续阅读」（RemoteViews 实现，刻意不引 Glance——
 * 它只要三行「书名 + 进度条」，RemoteViews 零新依赖、包体零增长）。
 *
 * 数据源：AppContainer 后台收集书架流（去抖 5s）推 [refresh]；系统 onUpdate 时
 * 也直接从数据库现取一份（进程刚被拉起来时上一份推送可能早已失效）。
 * 点击某行打开 App 并直跳该书；空态点击只打开 App。
 */
class ContinueReadingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container =
                    (context.applicationContext as com.llzx373.foldreader.FoldReaderApplication).container
                val books = container.bookshelfRepository.observeBookshelfWithProgress().first()
                render(context, appWidgetManager, appWidgetIds, pickRecent(books))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK_ID = "com.llzx373.foldreader.widget.OPEN_BOOK_ID"

        /** 小部件行数上限：布局里写死 3 行（RemoteViews 没有动态列表）。 */
        private const val MAX_ROWS = 3

        private val ROW_IDS = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
        private val TITLE_IDS = intArrayOf(R.id.widget_title1, R.id.widget_title2, R.id.widget_title3)
        private val PROGRESS_IDS =
            intArrayOf(R.id.widget_progress1, R.id.widget_progress2, R.id.widget_progress3)

        /** 取最近在读（隐藏书不上桌面），供 AppContainer 推送与 onUpdate 现取共用。 */
        fun pickRecent(books: List<BookWithProgress>): List<BookWithProgress> =
            pickWidgetRecent(books, MAX_ROWS)

        /** 书架数据变化后推送刷新全部「继续阅读」实例。 */
        fun refresh(context: Context, books: List<BookWithProgress>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ContinueReadingWidget::class.java),
            )
            if (ids.isEmpty()) return
            render(context, manager, ids, pickRecent(books))
        }

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            recent: List<BookWithProgress>,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_continue_reading)
            views.setViewVisibility(
                R.id.widget_empty,
                if (recent.isEmpty()) View.VISIBLE else View.GONE,
            )
            ROW_IDS.forEachIndexed { index, rowId ->
                val item = recent.getOrNull(index)
                if (item == null) {
                    views.setViewVisibility(rowId, View.GONE)
                } else {
                    views.setViewVisibility(rowId, View.VISIBLE)
                    views.setTextViewText(TITLE_IDS[index], item.book.title)
                    views.setProgressBar(PROGRESS_IDS[index], 100, widgetProgressPercent(item), false)
                    views.setOnClickPendingIntent(rowId, openBookIntent(context, item.book.id))
                }
            }
            // 头部与空态：点击打开 App（不带书 id）
            val launchIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_header, launchIntent)
            views.setOnClickPendingIntent(R.id.widget_empty, launchIntent)
            manager.updateAppWidget(ids, views)
        }

        private fun openBookIntent(context: Context, bookId: Long): PendingIntent =
            PendingIntent.getActivity(
                context,
                // requestCode 用 bookId：不同行的 PendingIntent 不能互相顶掉 extra
                bookId.toInt(),
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_BOOK_ID, bookId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
