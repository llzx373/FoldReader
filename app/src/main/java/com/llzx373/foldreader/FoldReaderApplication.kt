package com.llzx373.foldreader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.llzx373.foldreader.core.data.db.DATABASE_MIGRATIONS
import com.llzx373.foldreader.core.data.db.FoldReaderDatabase
import com.llzx373.foldreader.core.data.db.RoomOffsetIndexStore
import com.llzx373.foldreader.core.data.repository.BookPrefsRepository
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.repository.BookshelfRepositoryImpl
import com.llzx373.foldreader.core.data.settings.FileBrowserRootsStore
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.SettingsRepositoryImpl
import com.llzx373.foldreader.core.foldable.FoldableStateProvider
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicContainers
import com.llzx373.foldreader.core.format.BookParsers
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ChapterRules
import com.llzx373.foldreader.core.format.android.load
import com.llzx373.foldreader.core.format.clean.TsCharMap
import com.llzx373.foldreader.core.format.epub.EpubBookParser
import com.llzx373.foldreader.core.format.fb2.Fb2BookParser
import com.llzx373.foldreader.core.format.txt.TxtBookParser
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import com.llzx373.foldreader.feature.importer.toImportResult
import com.llzx373.foldreader.feature.importer.writeCoverFile
import com.llzx373.foldreader.core.pdf.PdfBoxReader
import com.llzx373.foldreader.core.pdf.PdfDocumentInfo
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppContainer(context: Context) {
    /**
     * 外部（「打开方式」/「分享」）送进来、还没被导入对话框消费的文件。
     *
     * 用列表而不是单个 Uri：`ACTION_SEND_MULTIPLE` 一次可能送好几个，进程里排着队等用户逐个确认。
     */
    val pendingImportUris = MutableStateFlow<List<Uri>>(emptyList())
    val activeReaderBookId = MutableStateFlow<Long?>(null)
    val appContext: Context = context.applicationContext
    val foldableStateProvider = FoldableStateProvider(
        context = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    // 迁移必须显式登记：没有 fallbackToDestructiveMigration，缺迁移会直接抛错而不是清库
    val database: FoldReaderDatabase =
        Room.databaseBuilder(context, FoldReaderDatabase::class.java, "foldreader.db")
            .addMigrations(*DATABASE_MIGRATIONS)
            .build()
    /** 非 TXT 格式的压平缓存目录（<contentHash>.txt + .toc sidecar）。 */
    val convertedDir = File(context.filesDir, "converted").apply { mkdirs() }
    /** 封面图片目录（<contentHash>.<ext>），与 converted/ 同生命周期。 */
    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    /** 页边界缓存目录（改版式会生成多份文件，删书时按 bookId 清理）。 */
    val pageBoundsDir = File(context.filesDir, "page_bounds")
    /**
     * 源文件副本目录（`<原文内容哈希>.<ext>`）。
     *
     * 导入时把原文原样复制一份进来，库里只引用这一份：外部「打开方式」给的授权是临时的，
     * 引用它就意味着那本书在任务结束后就打不开了。
     */
    val sourceDir = File(context.filesDir, "source")
    /** 漫画目录：`cache/<hash>/` 是可回收的解压缓存与缩略图，`local/<hash>/` 是用户选择的本地副本。 */
    val comicExtractionStore = com.llzx373.foldreader.core.comic.ComicExtractionStore(
        File(context.filesDir, "comics"),
    )
    val pageDiskCache: com.llzx373.foldreader.core.reader.PageDiskCache =
        com.llzx373.foldreader.core.reader.FilePageDiskCache(pageBoundsDir)
    /** 译本副本目录（M19）：`<bookId>/<lang>/` 下 units.json + unit_*.json + content.txt/.toc。 */
    val translationsDir = File(context.filesDir, "translations").apply { mkdirs() }
    val translationStore = com.llzx373.foldreader.core.translate.TranslationStore(translationsDir)
    /** OCR 模型管理（M21，R7）：模型用户自行下载 + SAF 导入 + 全量 SHA-256 校验，落 filesDir/models/。 */
    val modelManager = com.llzx373.foldreader.core.ai.android.ModelManager(appContext)
    /** 扫描 PDF 的 OCR 文本层缓存目录（M21）：`<bookId>/<pageIndex>.ocr.json`。 */
    val pdfOcrDir = File(context.filesDir, "pdf_ocr")
    val pdfOcrStore = com.llzx373.foldreader.core.ocr.PdfOcrStore(pdfOcrDir)
    /** 漫画翻译产物目录（M22）：`<bookId>/<page>.ocr.json`（气泡缓存）+ `<page>.<lang>.json`（译文）。 */
    val comicTranslateDir = File(context.filesDir, "comic_translate")
    val comicTranslationStore = com.llzx373.foldreader.core.translate.ComicTranslationStore(comicTranslateDir)
    /**
     * ONNX 会话层（M21）。对象本身很轻（会话全部惰性：模型未导入不创建 OrtEnvironment），
     * 但进程级共享一把锁，必须与阅读器同生命周期，所以挂容器单例。
     */
    val ocrEngine: com.llzx373.foldreader.core.ocr.android.OcrEngine by lazy {
        com.llzx373.foldreader.core.ocr.android.OcrEngine(appContext, modelManager)
    }

    /**
     * 当前 OCR 识别语言（M21）：设置值是 ModelCatalog 的 rec 条目 id；空串自动 =
     * 优先中文（中日英混排覆盖最好），未导入则取第一个已导入的。模型未就绪返回 null。
     */
    suspend fun ocrRecSpec(): com.llzx373.foldreader.core.ocr.OcrModelSpec? {
        val pref = settingsRepository.preferences.first().ocrRecLang
        val imported = modelManager.importedRecs()
        if (imported.isEmpty()) return null
        if (pref.isNotBlank()) {
            com.llzx373.foldreader.core.ocr.ModelCatalog.byId(pref)
                ?.takeIf { modelManager.isReady(it) }?.let { return it }
        }
        return imported.firstOrNull { it.id == "rec_ch" } ?: imported.first()
    }

    val bookshelfRepository: BookshelfRepository = BookshelfRepositoryImpl(
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        chapterDao = database.chapterDao(),
        bookmarkDao = database.bookmarkDao(),
        annotationDao = database.annotationDao(),
        sessionDao = database.readingSessionDao(),
        personAppearanceDao = database.personAppearanceDao(),
        convertedDir = convertedDir,
        coversDir = coversDir,
        pageDiskCache = pageDiskCache,
        comicStore = comicExtractionStore,
        sourceDir = sourceDir,
        translationStore = translationStore,
        translationDao = database.translationDao(),
        glossaryTermDao = database.glossaryTermDao(),
        pdfOcrStore = pdfOcrStore,
        comicTranslationStore = comicTranslationStore,
    )
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(context)
    /** AI API key 加密存储（AndroidKeyStore AES/GCM）；明文不出存储边界。 */
    val credentialStore = com.llzx373.foldreader.core.ai.android.CredentialStore(appContext)
    /** AI 出站内容台账：设置页「外发历史」展示的记录来源。 */
    val aiContentGate = com.llzx373.foldreader.core.ai.gate.AiContentGate(
        File(appContext.filesDir, "ai_outbound_history.json"),
    )
    // v2.6 约束：未配置 API key 时不创建任何网络组件。
    // client 惰性单例、进程内共享；Provider 廉价，每次按当前配置新建。
    @Volatile
    private var aiHttpClient: okhttp3.OkHttpClient? = null

    private fun sharedAiHttpClient(): okhttp3.OkHttpClient =
        aiHttpClient ?: synchronized(this) {
            aiHttpClient ?: com.llzx373.foldreader.core.ai.AiProviderFactory
                .defaultClient(timeoutSeconds = 60)
                .also { aiHttpClient = it }
        }

    /**
     * AI 功能入口可见性判据：已启用 + 已配地址 + 已存 key。
     * 只读配置与凭据，不触碰网络组件（OkHttpClient 仍只在真正调用时创建）。
     */
    suspend fun aiConfigured(): Boolean {
        val prefs = settingsRepository.preferences.first()
        return prefs.aiEnabled && prefs.aiBaseUrl.isNotBlank() && credentialStore.readKey() != null
    }

    /**
     * 重建目录：作废偏移索引 → 清空章节 → 用最新规则重扫（此时读的是当前副本）。
     * 书架「重建目录」与 M15「AI 章节规则生成」的选定后重扫共用这条链路。
     */
    suspend fun rebuildBookChapters(bookId: Long) {
        val book = bookshelfRepository.getBook(bookId) ?: return
        offsetIndexStore.invalidate(bookId.toString())
        bookshelfRepository.saveChapters(bookId, emptyList())
        val override = com.llzx373.foldreader.core.format.EncodingDetector.forNameOrNull(book.encoding)
        val scanned = runCatching {
            bookParsers.parserFor(book.format).parseChapters(Uri.parse(book.fileUri), override, bookId)
        }.getOrDefault(emptyList())
        bookshelfRepository.saveChapters(bookId, scanned)
        // 章节变了人物出场索引跟着重算；失败不影响重扫本身
        runCatching { refreshPersonAppearances(bookId) }
    }

    /**
     * 按当前设置装配 AI Provider；未启用 / 未配地址 / 未存 key 时返回 null，
     * 且整个调用链不触碰网络组件（client 只在确认有 key 后才创建）。
     */
    suspend fun aiProvider(): com.llzx373.foldreader.core.ai.AiProvider? {
        val prefs = settingsRepository.preferences.first()
        if (!prefs.aiEnabled || prefs.aiBaseUrl.isBlank()) return null
        val key = credentialStore.readKey() ?: return null
        return com.llzx373.foldreader.core.ai.AiProviderFactory.create(
            com.llzx373.foldreader.core.ai.AiConfig(
                protocol = prefs.aiProtocol,
                baseUrl = prefs.aiBaseUrl,
                apiKey = key,
            ),
            sharedAiHttpClient(),
        )
    }
    /**
     * 装配翻译引擎（M19/M20）：每次按当前配置新建（Provider 廉价），未配置时引擎内
     * provider 为 null、所有翻译入口直接失败返回。引擎无状态，随取随用。
     * 术语注入经 [glossaryRepository] 每单位现取（确认动作即时生效）。
     */
    suspend fun translateEngine(): com.llzx373.foldreader.feature.translate.TranslateEngine =
        com.llzx373.foldreader.feature.translate.TranslateEngine(
            provider = aiProvider(),
            contentGate = aiContentGate,
            store = translationStore,
            translationDao = database.translationDao(),
            preferences = { settingsRepository.preferences.first() },
            glossaryRepository = glossaryRepository,
        )

    /** 术语表仓库（M20）：全局/单书（系列预留）合并注入与候选落表的统一入口。 */
    val glossaryRepository =
        com.llzx373.foldreader.core.translate.GlossaryRepository(database.glossaryTermDao())

    /**
     * 人物候选生成（M20，R6）：取该书人物出场索引 Top N（按提及次数）落为
     * 未确认候选（target 空，确认时补填）。术语 UI 打开单书候选与全书翻译启动时调用。
     */
    suspend fun seedGlossaryCandidatesFromPersons(bookId: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val persons = database.personAppearanceDao().topForBook(bookId, PERSON_CANDIDATE_LIMIT)
            glossaryRepository.upsertCandidates(
                com.llzx373.foldreader.core.data.db.GlossaryTermEntity.SCOPE_BOOK,
                bookId.toString(),
                persons.map { it.name to "" },
            )
        }
    }

    /**
     * 设置页「清除全部 AI 数据」（M19/M20/M21/M22）：译本副本目录整体清空 + 翻译台账与术语表清零 +
     * 扫描 PDF 的 OCR 文本层缓存与漫画翻译产物（气泡缓存 + 译文 + 页台账）清空。不影响 API 凭据与外发历史（各有独立入口），
     * 也不删除 OCR 模型本体（filesDir/models/ 在设置页「模型管理」单独删除）。
     */
    suspend fun clearAiData() = withContext(Dispatchers.IO) {
        translationsDir.listFiles()?.forEach { it.deleteRecursively() }
        pdfOcrStore.deleteAll()
        comicTranslationStore.deleteAll()
        database.translationDao().deleteAll()
        database.comicPageTranslationDao().deleteAll()
        database.glossaryTermDao().deleteAll()
    }

    /**
     * 队列消费侧的一本书内容源：打开 parser 内容 → 读/算单位清单（首算落盘
     * saveUnits，断点续译共享同一份切块边界）→ 逐单位读原文。实时索引未封口时
     * 等封口再切块（切块边界依赖终值 charCount，同阅读器口径）。
     */
    private class ContainerTranslationSource(
        private val book: com.llzx373.foldreader.core.data.db.BookEntity,
        private val content: com.llzx373.foldreader.core.format.BookContent,
        private val chapters: List<com.llzx373.foldreader.core.format.Chapter>,
        private val store: com.llzx373.foldreader.core.translate.TranslationStore,
        private val bookId: Long,
    ) : com.llzx373.foldreader.feature.translate.BookTranslationSource {
        override val bookTitle: String get() = book.title

        override suspend fun units(
            lang: com.llzx373.foldreader.core.ai.AiTargetLang,
        ): List<com.llzx373.foldreader.core.translate.TranslationUnit> =
            withContext(Dispatchers.IO) {
                store.readUnits(bookId, lang.name) ?: run {
                    // 等实时索引封口：awaitCharsAbove 在封口（仍不够 = 真文末）时返回
                    content.awaitCharsAbove(Long.MAX_VALUE)
                    if (!content.isCharCountFinal) return@run emptyList()
                    com.llzx373.foldreader.core.translate.computeUnits(
                        chapters,
                        content.charCount,
                        read = { range -> kotlinx.coroutines.runBlocking { content.read(range) } },
                    ).also { units ->
                        if (units.isNotEmpty()) store.saveUnits(bookId, lang.name, units)
                    }
                }
            }

        override suspend fun readUnitText(
            unit: com.llzx373.foldreader.core.translate.TranslationUnit,
        ): String = runCatching { content.read(unit.charStart until unit.charEnd) }.getOrDefault("")

        override fun close() {
            runCatching { (content as? java.io.Closeable)?.close() }
        }
    }

    /** 打开一本书的翻译内容源（队列/插队直译共用）；打不开（格式不支持/书不存在）返回 null。 */
    suspend fun openTranslationSource(
        bookId: Long,
    ): com.llzx373.foldreader.feature.translate.BookTranslationSource? = withContext(Dispatchers.IO) {
        runCatching {
            val book = bookshelfRepository.getBook(bookId) ?: return@withContext null
            val parser = bookParsers.parserFor(book.format) ?: return@withContext null
            val content = parser.openContent(
                Uri.parse(book.fileUri),
                com.llzx373.foldreader.core.format.EncodingDetector.forNameOrNull(book.encoding),
                bookId,
            )
            ContainerTranslationSource(
                book = book,
                content = content,
                chapters = bookshelfRepository.getChapters(bookId),
                store = translationStore,
                bookId = bookId,
            )
        }.getOrNull()
    }

    /**
     * 全书批量翻译队列（M20）：AppContainer 单例，生产装配走真实引擎与内容源。
     * 进度 StateFlow 同时喂前台服务通知与（后续）阅读器/UI 展示。
     */
    val bookTranslationQueue = com.llzx373.foldreader.feature.translate.BookTranslationQueue(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        sourceFor = { bookId -> openTranslationSource(bookId) },
        translationDao = database.translationDao(),
        translateUnitCall = { bookId, title, unit, text, lang ->
            translateEngine().translateUnit(bookId, title, unit, text, lang)
        },
        currentLang = { settingsRepository.preferences.first().aiTargetLang },
    )

    /**
     * 全书翻译入队（详情页确认入口）：人物候选先落表（幂等 upsert），再入队断点续译。
     * 返回 false = AI 未配置（入口本该隐藏，这里是兜底）。
     */
    suspend fun enqueueBookTranslation(bookId: Long, lang: com.llzx373.foldreader.core.ai.AiTargetLang): Boolean {
        if (!aiConfigured()) return false
        seedGlossaryCandidatesFromPersons(bookId)
        bookTranslationQueue.enqueueBook(bookId, lang)
        // 前台服务托住队列（退桌面/锁屏不断译）；POST_NOTIFICATIONS 被拒时静默降级
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, com.llzx373.foldreader.feature.translate.TranslationService::class.java),
        )
        return true
    }

    // ---- 漫画翻译（M22）----

    /**
     * 一页漫画的气泡供给（引擎/队列共用）：缓存优先——`.ocr.json` 在就直接用
     * （换模型/改提示词重译不重跑 OCR）；缺失才打开内容源取页位图跑
     * 「RT-DETR 气泡检测 + 页级 OCR + 行归并」并落缓存。
     * 模型未就绪（气泡或识别任一缺失）返回空表 = 该页无文字，调用方按失败处理。
     */
    private suspend fun comicBubblesFor(
        bookId: Long,
        pageIndex: Int,
    ): List<com.llzx373.foldreader.core.ocr.OcrBubble> = withContext(Dispatchers.IO) {
        comicTranslationStore.loadOcr(bookId, pageIndex)?.let { return@withContext it }
        if (!modelManager.bubbleReady()) return@withContext emptyList()
        val recSpec = ocrRecSpec() ?: return@withContext emptyList()
        val book = bookshelfRepository.getBook(bookId) ?: return@withContext emptyList()
        val rtl = runCatching {
            bookPrefsRepository.observe(bookId).first().comicDirection ==
                com.llzx373.foldreader.core.data.settings.ComicDirection.RTL
        }.getOrDefault(false)
        // 每次独立打开来源：队列路径没有阅读器代持的位图。OCR 目标分辨率按长边 1600px
        // （再低小字识别率掉得快，再高 RT-DETR 输入也是 640 无益）
        val source = runCatching { openPagedSource(book, null) }.getOrNull()
            ?: return@withContext emptyList()
        try {
            val image = source.loadPage(pageIndex, COMIC_OCR_TARGET_PX, COMIC_OCR_TARGET_PX)
            val bitmap = (image as? com.llzx373.foldreader.core.paged.PagedPageImage.Still)?.bitmap
                ?: return@withContext emptyList()
            val bubbles = ocrEngine.detectBubbles(bitmap, recSpec, rtl)
            if (bubbles.isNotEmpty()) {
                runCatching { comicTranslationStore.saveOcr(bookId, pageIndex, bubbles) }
            }
            bubbles
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * 视觉翻译（M23）取页图像：独立打开内容源取页位图，长边压到 [COMIC_VISION_TARGET_PX]
     * 后 JPEG(q85) 编码为 base64（不带 `data:` 前缀，前缀由各 Provider 按协议拼）。
     */
    private suspend fun comicPageJpegBase64(bookId: Long, pageIndex: Int): String? =
        withContext(Dispatchers.IO) {
            val book = bookshelfRepository.getBook(bookId) ?: return@withContext null
            val source = runCatching { openPagedSource(book, null) }.getOrNull()
                ?: return@withContext null
            try {
                val image = source.loadPage(pageIndex, COMIC_VISION_TARGET_PX, COMIC_VISION_TARGET_PX)
                val bitmap = (image as? com.llzx373.foldreader.core.paged.PagedPageImage.Still)?.bitmap
                    ?: return@withContext null
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } finally {
                runCatching { source.close() }
            }
        }

    /**
     * 装配漫画翻译引擎（M22）：每次按当前配置新建（同 translateEngine 约定）；
     * 未配置时引擎内 provider 为 null、翻译入口直接失败返回。
     * 系列术语层级：漫画主干 `comicSeriesStem`（跨卷共享，R6）。
     */
    suspend fun comicTranslateEngine(): com.llzx373.foldreader.feature.translate.ComicTranslateEngine =
        com.llzx373.foldreader.feature.translate.ComicTranslateEngine(
            provider = aiProvider(),
            contentGate = aiContentGate,
            store = comicTranslationStore,
            pageDao = database.comicPageTranslationDao(),
            preferences = { settingsRepository.preferences.first() },
            glossaryRepository = glossaryRepository,
            seriesKeyFor = { bookId ->
                bookshelfRepository.getBook(bookId)?.title
                    ?.let { com.llzx373.foldreader.core.comic.comicSeriesStem(it) }
            },
            bubblesFor = { bookId, pageIndex -> comicBubblesFor(bookId, pageIndex) },
            pageImageBase64For = { bookId, pageIndex -> comicPageJpegBase64(bookId, pageIndex) },
        )

    /** 阅读器的漫画翻译门面（ViewModel 只跟它打交道）；实例廉价，随取随建。 */
    fun comicTranslationController(bookId: Long): com.llzx373.foldreader.feature.translate.ComicTranslationController =
        com.llzx373.foldreader.feature.translate.ComicTranslationController(
            bookId = bookId,
            bookTitleFor = { bookshelfRepository.getBook(bookId)?.title ?: "" },
            store = comicTranslationStore,
            pageDao = database.comicPageTranslationDao(),
            engineFor = {
                if (aiConfigured()) comicTranslateEngine() else null
            },
        )

    /** 漫画整卷翻译队列（M22）：AppContainer 单例，断点续译/退避/暂停/取消与全书翻译同一骨架。 */
    val comicTranslationQueue = com.llzx373.foldreader.feature.translate.ComicTranslationQueue(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        bookFor = { bookId ->
            bookshelfRepository.getBook(bookId)
                ?.let { it.title to (it.comicPageCount ?: 0) }
        },
        pageDao = database.comicPageTranslationDao(),
        translatePageCall = { bookId, title, pageIndex, lang ->
            comicTranslateEngine().translatePage(bookId, title, pageIndex, lang)
        },
    )

    /**
     * 漫画整卷入队（阅读器菜单入口）：断点续译由队列负责；前台服务托住进程。
     * AI 配置判定在界面层（入口不可见时本不该被调到，队列本身幂等兜底）。
     */
    fun enqueueComicVolumeTranslation(bookId: Long, lang: com.llzx373.foldreader.core.ai.AiTargetLang) {
        comicTranslationQueue.enqueueBook(bookId, lang)
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, com.llzx373.foldreader.feature.translate.ComicTranslationService::class.java),
        )
    }

    /** 清洗配方组装：导入对话框、浏览打开、批量导入共用同一份规则。 */
    val cleanProfileFactory = com.llzx373.foldreader.feature.importer.CleanProfileFactory(settingsRepository)
    val fileBrowserRootsStore = FileBrowserRootsStore(context)
    /** SAF 目录访问（文件浏览器 / 目录批量导入 / 漫画目录容器共用）。 */
    val safTree = com.llzx373.foldreader.core.format.saf.SafTree(appContext)
    val bookPrefsRepository = BookPrefsRepository(
        bookPrefsDao = database.bookPrefsDao(),
        settingsRepository = settingsRepository,
    )
    val offsetIndexDao = database.offsetIndexDao()
    val offsetIndexStore = RoomOffsetIndexStore(offsetIndexDao)
    val parserScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val onBookIndexed: suspend (Long, Long) -> Unit = { bookId, totalChars ->
        bookshelfRepository.getBook(bookId)
            ?.takeIf { it.totalChars != totalChars }
            ?.let { bookshelfRepository.upsertBook(it.copy(totalChars = totalChars)) }
    }
    // 章节规则 = 按书自定义（book_prefs）+ 全局自定义，再叠内置规则（merge 内部追加 DEFAULT）。
    private val chapterRules: suspend (Long?) -> List<Regex> = { bookId ->
        val perBook = bookId?.let { bookPrefsRepository.chapterRules(it) } ?: emptyList()
        ChapterRules.merge(perBook + settingsRepository.preferences.first().customChapterRules)
    }
    // 同一个 fileUri 在库里可能有多行（原版 + 清洗版），所以调用方给了 bookId 就一律以它为准，
    // 只有不知道 bookId 的调用方（如 JVM 单测、旧的 2 参入口）才回落到按 URI 反查。
    private val bookIdForUri: suspend (android.net.Uri, Long?) -> Long? = { uri, bookId ->
        bookId ?: bookshelfRepository.findByFileUri(uri.toString())?.id
    }
    // 四个文本 parser 共用的章节落库回调：落库后顺手把人物出场索引（M13.2）的重算
    // 排到后台。重算异步进行、失败静默——人物索引只是目录面板的增强，不挡章节落库主链路。
    private val onChaptersIndexed: suspend (Long, List<Chapter>) -> Unit = { bookId, chapters ->
        bookshelfRepository.saveChapters(bookId, chapters)
        parserScope.launch { refreshPersonAppearances(bookId) }
    }
    /**
     * 人物出场索引（M13.2）重算：逐章读正文喂规则抽取器，结果整体覆盖落库。
     *
     * 只处理文本型书：漫画没有文本 parser（直接跳过）；PDF 页式章节（目录锚点
     * charStart/charEnd 全 0，见 [applyPdfInfo]）没有字符坐标可扫，清空旧索引后返回。
     * 整段跑在 IO、失败静默（runCatching 兜底），不影响章节落库主流程。
     */
    suspend fun refreshPersonAppearances(bookId: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val book = bookshelfRepository.getBook(bookId) ?: return@withContext
            if (book.format == BookFormat.COMIC) return@withContext
            val chapters = bookshelfRepository.getChapters(bookId)
            if (chapters.none { it.charEnd > it.charStart }) {
                database.personAppearanceDao().replaceForBook(bookId, emptyList())
                return@withContext
            }
            val parser = bookParsers.parserFor(book.format)
            val content = parser.openContent(
                Uri.parse(book.fileUri),
                com.llzx373.foldreader.core.format.EncodingDetector.forNameOrNull(book.encoding),
                bookId,
            )
            try {
                val extractor = com.llzx373.foldreader.core.format.person.PersonNameExtractor()
                chapters.filter { it.charEnd > it.charStart }.forEach { chapter ->
                    extractor.feed(content.read(chapter.charStart..chapter.charEnd), chapter.charStart)
                }
                val appearances = extractor.result().map { mention ->
                    com.llzx373.foldreader.core.data.db.PersonAppearanceEntity(
                        bookId = bookId,
                        name = mention.name,
                        // 与 ReaderLogic.chapterIndexAt 同口径：最后一个 charStart <= offset 的章
                        firstChapterIndex = com.llzx373.foldreader.feature.reader.chapterIndexAt(
                            chapters,
                            mention.firstOffset,
                        ),
                        firstCharOffset = mention.firstOffset,
                        mentionCount = mention.count,
                    )
                }
                database.personAppearanceDao().replaceForBook(bookId, appearances)
            } finally {
                (content as? java.io.Closeable)?.close()
            }
        }
    }
    val txtBookParser = TxtBookParser(
        context = context,
        offsetIndexStore = offsetIndexStore,
        indexScope = parserScope,
        bookIdResolver = bookIdForUri,
        contentUriResolver = { uri, bookId ->
            val cleaned = bookId?.let { bookshelfRepository.getBook(it)?.cleanedFilePath }
            cleaned?.let { Uri.fromFile(java.io.File(it)) } ?: uri
        },
        onBookIndexed = onBookIndexed,
        onChaptersIndexed = onChaptersIndexed,
        chapterRules = chapterRules,
    )
    // 内部 TXT 管线（EPUB/FB2 共用）：压平文件 uri → 文件名即原书 contentHash → 反查 bookId，
    // 使偏移索引缓存落在原书 bookId 上；启发式章节回填在此禁用（真实章节由各格式 parser 回填）
    private val flattenedTxtParser = TxtBookParser(
        context = context,
        offsetIndexStore = offsetIndexStore,
        indexScope = parserScope,
        bookIdResolver = { uri, _ ->
            uri.lastPathSegment
                ?.substringBeforeLast('.')
                ?.let { bookshelfRepository.findByContentHash(it)?.id }
        },
        contentUriResolver = { uri, _ -> uri },
        onBookIndexed = onBookIndexed,
        // 压平文件的章节来自 EPUB/FB2 的 .toc sidecar，这里扫出来的结果由下面那行空回调丢弃。
        // 用空规则集跳过索引扫描期间的逐行正则匹配——那次扫描只剩纯解码，没有白做的活。
        onChaptersIndexed = { _, _ -> },
        chapterRules = { _ -> emptyList() },
    )
    private val openFlattenedContent: suspend (File) -> com.llzx373.foldreader.core.format.BookContent =
        { file -> flattenedTxtParser.openContent(Uri.fromFile(file), Charsets.UTF_8) }
    val epubBookParser = EpubBookParser(
        convertedDir = convertedDir,
        openFlattenedContent = openFlattenedContent,
        openChannel = { uri -> UriChannels.open(context, uri) },
        displayNameOf = { uri -> UriChannels.displayName(context, uri) },
        bookIdResolver = bookIdForUri,
        onChaptersIndexed = onChaptersIndexed,
    )
    val fb2BookParser = Fb2BookParser(
        convertedDir = convertedDir,
        openFlattenedContent = openFlattenedContent,
        openChannel = { uri -> UriChannels.open(context, uri) },
        displayNameOf = { uri -> UriChannels.displayName(context, uri) },
        bookIdResolver = bookIdForUri,
        onChaptersIndexed = onChaptersIndexed,
    )
    /**
     * 文本型 PDF 的「当电子书读」入口：抽正文压平后进 TXT 管线。
     * 扫描件不会产出压平产物，所以这个解析器对扫描件是"存在但用不上"。
     */
    val pdfBookParser = com.llzx373.foldreader.core.pdf.PdfBookParser(
        convertedDir = convertedDir,
        appContext = appContext,
        openFlattenedContent = openFlattenedContent,
        openChannel = { uri -> UriChannels.open(context, uri) },
        displayNameOf = { uri -> UriChannels.displayName(context, uri) },
        bookIdResolver = bookIdForUri,
        onChaptersIndexed = onChaptersIndexed,
    )
    val bookParsers = BookParsers(
        mapOf(
            BookFormat.TXT to txtBookParser,
            BookFormat.EPUB to epubBookParser,
            BookFormat.FB2 to fb2BookParser,
            // PDF 只在文本模式下走这里（扫描件没有压平产物，会走页式阅读器）
            BookFormat.PDF to pdfBookParser,
        ),
    )
    val fontManager = com.llzx373.foldreader.core.reader.FontManager(appContext)
    /**
     * TTS 听书控制器（M13.1）：进程级单例，引擎与状态都不进 Composable/Activity。
     * TtsPlaybackService 只是保活壳（步骤 9 升级前台/MediaSession 时引擎层不动）。
     */
    val ttsController = com.llzx373.foldreader.core.tts.android.ReaderTtsController(appContext)
    /** 阅读器 ↔ 引擎的通信口：ReaderViewModel 订阅它做翻页联动。 */
    val ttsState: kotlinx.coroutines.flow.StateFlow<com.llzx373.foldreader.core.tts.TtsState>
        get() = ttsController.state
    val backupManager = com.llzx373.foldreader.core.backup.BackupManager(
        context = appContext,
        bookshelfRepository = bookshelfRepository,
        settingsRepository = settingsRepository,
        bookPrefsDao = database.bookPrefsDao(),
        readingSessionDao = database.readingSessionDao(),
        glossaryTermDao = database.glossaryTermDao(),
    )
    /** 漫画容器读取（zip 随机访问 / tar·7z·rar 解压缓存 / SAF 目录）。 */
    val comicArchiveFactory = com.llzx373.foldreader.core.comic.ComicArchiveFactory(
        extractionStore = comicExtractionStore,
        openChannel = { uri -> UriChannels.open(context, uri) },
        openDocumentStream = { key ->
            runCatching { context.contentResolver.openInputStream(Uri.parse(key)) }.getOrNull()
        },
        listDocumentChildren = { uri ->
            safTree.listChildrenOfDocument(uri).map { entry ->
                com.llzx373.foldreader.core.comic.archive.ComicDirChild(
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    key = entry.uri.toString(),
                )
            }
        },
    )
    /**
     * 打开页式阅读的内容来源。漫画容器与 PDF 文档在这里分流，
     * 阅读器（PagedReader）对格式完全无感——它只认「第 N 页、要多大、给我一张图」。
     *
     * [password] 只对加密 PDF 有意义；密码错/缺失时抛 `PagedSourcePasswordRequired`，
     * 由阅读器弹密码框重试（密码不落库）。
     */
    suspend fun openPagedSource(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
        password: String? = null,
    ): com.llzx373.foldreader.core.paged.PagedImageSource =
        if (book.format == BookFormat.PDF) {
            val pdf = com.llzx373.foldreader.core.pdf.PdfPagedSource.open(
                context = appContext,
                uri = Uri.parse(book.fileUri),
                password = password,
            )
            // M21：OCR 模型就绪时给 PDF 包上 OCR 文本层——有内嵌文本层的照样先走原生，
            // 扫描件则补上可搜索/可选字的 OCR 文本层（结果按页缓存到 filesDir/pdf_ocr/）。
            if (modelManager.ocrReady()) {
                com.llzx373.foldreader.core.ocr.android.OcrTextLayerSource(
                    delegate = pdf,
                    bookId = book.id,
                    store = pdfOcrStore,
                    recSpecProvider = { ocrRecSpec() },
                    recognize = { bitmap, spec -> ocrEngine.recognizePage(bitmap, spec) },
                )
            } else {
                pdf
            }
        } else {
            val container = book.comicContainer
                ?: throw java.io.IOException("缺少漫画容器信息")
            com.llzx373.foldreader.core.comic.ComicPagedSource(
                comicArchiveFactory.open(Uri.parse(book.fileUri), container, book.contentHash),
            )
        }

    val comicImportUseCase = com.llzx373.foldreader.feature.importer.ComicImportUseCase(
        bookshelfRepository = bookshelfRepository,
        archiveFactory = comicArchiveFactory,
        extractionStore = comicExtractionStore,
        openChannel = { key -> UriChannels.open(context, Uri.parse(key)) },
        displayNameOf = { key -> UriChannels.displayName(context, Uri.parse(key)) },
        sourceDir = File(context.filesDir, "source"),
        hasPersistedRead = { key ->
            context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == key && it.isReadPermission
            }
        },
        coversDir = coversDir,
    )
    /**
     * 同系列候选（前后卷切换用）：
     * 1. 当前文件**所在目录**下能认出来的漫画（可能还没导入）；
     * 2. **库内**同分组或同目录的书（跨目录整理过的也认得出来）。
     *
     * 同目录那条依赖持久化的 SAF 授权，且需要能从 documentId 反推父目录；拿不到就只靠库内那条。
     * 纯匹配与合并逻辑在 [com.llzx373.foldreader.core.comic.mergeComicSeries]（纯函数，有单测）。
     */
    suspend fun comicSeriesCandidates(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
    ): List<com.llzx373.foldreader.core.comic.ComicSeriesCandidate> {
        val current = com.llzx373.foldreader.core.comic.ComicSeriesCandidate(
            // 私有副本的文件名是内容哈希，参与不了系列主干匹配；书名留着原文件名信息
            name = if (book.fileUri.startsWith("file://")) {
                book.title
            } else {
                UriChannels.displayName(appContext, Uri.parse(book.fileUri)) ?: book.title
            },
            uri = book.fileUri,
            bookId = book.id,
            isDirectory = book.comicContainer == ComicContainer.FOLDER,
        )

        // 目录来源：同目录里所有「漫画容器或目录」的同级条目
        val directory = withContext(Dispatchers.IO) {
            runCatching {
                safTree.listSiblingsOfDocument(Uri.parse(book.fileUri))
                    .filter { it.uri.toString() != book.fileUri }
                    .filter { it.isDirectory || ComicContainers.fromExtension(it.name) != null }
                    .map {
                        com.llzx373.foldreader.core.comic.ComicSeriesCandidate(
                            name = it.name,
                            uri = it.uri.toString(),
                            isDirectory = it.isDirectory,
                        )
                    }
            }.getOrDefault(emptyList())
        }

        // 库内来源：优先同分组，其次同目录（fileUri 的父路径相同）
        val all = runCatching { bookshelfRepository.observeBookshelf().first() }.getOrDefault(emptyList())
        val parent = book.fileUri.substringBeforeLast('/', "")
        val library = all
            .filter { it.id != book.id && it.format == BookFormat.COMIC }
            .filter { candidate ->
                (book.groupName != null && candidate.groupName == book.groupName) ||
                    (parent.isNotEmpty() && candidate.fileUri.startsWith(parent))
            }
            .map {
                com.llzx373.foldreader.core.comic.ComicSeriesCandidate(
                    name = it.title,
                    uri = it.fileUri,
                    bookId = it.id,
                    isDirectory = it.comicContainer == ComicContainer.FOLDER,
                )
            }

        return com.llzx373.foldreader.core.comic.mergeComicSeries(current, directory, library)
    }

    /**
     * 按需导入同系列里的一个文件（点未导入的那一卷时用）。
     *
     * 复用正常导入链路（`source = EXTERNAL` + 已持久化的 SAF 授权），
     * 返回书籍 id；重复导入返回已有那本。
     */
    suspend fun importSeriesComic(uri: Uri, isDirectory: Boolean): Long? {
        val container = if (isDirectory) {
            ComicContainer.FOLDER
        } else {
            ComicContainers.fromExtension(UriChannels.displayName(appContext, uri) ?: uri.toString())
                ?: ComicContainer.ZIP
        }
        return when (
            val outcome = comicImportUseCase.register(uri, container, BookSource.EXTERNAL)
        ) {
            is com.llzx373.foldreader.feature.importer.ComicImportUseCase.Outcome.Registered ->
                outcome.bookId
            is com.llzx373.foldreader.feature.importer.ComicImportUseCase.Outcome.Duplicate ->
                outcome.bookId
            is com.llzx373.foldreader.feature.importer.ComicImportUseCase.Outcome.Failure -> null
        }
    }

    /**
     * 文件大小（MB），拿不到返回 null。
     *
     * 只用来在菜单里提示「文件较大，翻页可能偏慢」——`androidx.pdf` 对超大文档有已知的
     * 性能问题，先说清楚比让人以为卡死要好。
     */
    suspend fun fileSizeMb(book: com.llzx373.foldreader.core.data.db.BookEntity): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openAssetFileDescriptor(Uri.parse(book.fileUri), "r")
                    ?.use { it.length }
            }.getOrNull()?.takeIf { it > 0 }?.let { (it / (1024L * 1024L)).toInt() }
        }

    /**
     * 导入后后台预热：把 EPUB/FB2 的整本压平、漫画的顺序容器解压提前做掉，
     * 让首次打开通常直接命中缓存。复用 parserScope；失败静默（预热只是加速）。
     */
    val bookPrewarmQueue = com.llzx373.foldreader.core.format.BookPrewarmQueue(
        scope = parserScope,
        parserFor = { format -> bookParsers.parserFor(format) },
        onPrepared = { bookId -> bookshelfRepository.markContentPrepared(bookId) },
        comicPrepare = { bookId -> comicImportUseCase.prepare(bookId) },
        pdfPrepare = { bookId -> preparePdf(bookId) },
    )
    /**
     * PDF 的后台准备：用 PdfBox 取元数据 / 目录 / 封面 / 页数并回填。
     *
     * PdfBox 解析不了不代表读不了（加密、非常规结构都可能），所以失败时退回"只用沙箱拿页数"：
     * 拿得到就算准备完成，拿不到才让「待解析」角标继续留着等用户输密码。
     */
    private suspend fun preparePdf(bookId: Long) {
        val book = bookshelfRepository.getBook(bookId) ?: return
        // 第一趟：元数据 / 目录 / 封面（不抽正文，省掉一次全量文本抽取）
        val info = withContext(Dispatchers.IO) {
            PdfBoxReader.read(appContext, book.fileUri)
        }
        if (info == null) {
            preparePdfPageCountOnly(book)
            return
        }
        applyPdfInfo(book, info)
        // 第二趟：文本型才抽正文压平（扫描件在这里安静返回 null，页式阅读不受影响）。
        // 压平与章节回填都在 PdfBookParser 里，和 EPUB/FB2 走同一条管线。
        val charCount = withContext(Dispatchers.IO) {
            runCatching { pdfBookParser.prewarmAndCharCount(Uri.parse(book.fileUri), book.id) }.getOrNull()
        }
        bookshelfRepository.updateConvertedFile(
            bookId = book.id,
            cleanedFilePath = charCount?.let { convertedDir.resolve("${book.contentHash}.txt").absolutePath },
            totalChars = charCount ?: 0,
        )
    }

    private suspend fun applyPdfInfo(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
        info: com.llzx373.foldreader.core.pdf.PdfDocumentInfo,
    ) {
        // 标题只在还停留在"文件名"时替换：用户看得懂的标题不该被文档里的脏标题顶掉
        val nameTitle = UriChannels.displayName(appContext, Uri.parse(book.fileUri))
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
        val title = info.title.takeIf { it != null && (nameTitle == null || book.title == nameTitle) }
        bookshelfRepository.backfillPdfMetadata(
            bookId = book.id,
            title = title,
            author = info.author,
            description = info.subject,
            subjects = info.keywords,
        )
        if (info.pageCount > 0 && info.pageCount != book.comicPageCount) {
            bookshelfRepository.updateComicPageCount(book.id, info.pageCount)
        }
        info.cover?.let { cover ->
            val path = runCatching {
                writeCoverFile(coversDir, book.contentHash, cover)
            }.getOrNull()
            if (path != null) bookshelfRepository.updateCoverPath(book.id, path)
        }
        if (info.outline.isNotEmpty()) {
            // PDF 目录的锚点是页序号；charStart/charEnd 写 0，绝不拿页序号冒充字符偏移
            bookshelfRepository.saveChapters(
                book.id,
                info.outline.map { entry ->
                    Chapter(
                        title = entry.title,
                        charStart = 0L,
                        charEnd = 0L,
                        depth = entry.depth,
                        pageIndex = entry.pageIndex?.toLong(),
                    )
                },
            )
        }
    }

    /** 只用沙箱拿页数（PdfBox 解析不了时的退路）。开不出来就抛，交给队列保持「待解析」。 */
    private suspend fun preparePdfPageCountOnly(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
    ) {
        if (book.comicPageCount != null) return
        val source = openPagedSource(book, null)
        try {
            bookshelfRepository.updateComicPageCount(book.id, source.pageCount)
        } finally {
            source.close()
        }
    }
    val pdfImportUseCase = com.llzx373.foldreader.feature.importer.PdfImportUseCase(
        bookshelfRepository = bookshelfRepository,
        openChannel = { key -> UriChannels.open(context, Uri.parse(key)) },
        displayNameOf = { key -> UriChannels.displayName(context, Uri.parse(key)) },
        sourceDir = File(context.filesDir, "source"),
        enqueuePrewarm = { bookId, uriKey, format -> bookPrewarmQueue.enqueue(bookId, uriKey, format) },
    )
    val importBookUseCase = ImportBookUseCase(
        context = context,
        bookshelfRepository = bookshelfRepository,
        convertedParsers = mapOf(
            BookFormat.EPUB to epubBookParser,
            BookFormat.FB2 to fb2BookParser,
        ),
        coversDir = coversDir,
        enqueuePrewarm = bookPrewarmQueue::enqueue,
        comicImport = comicImportUseCase,
        pdfImport = pdfImportUseCase,
    )
    /** 对已导入的书重新跑一遍智能清理（读原始源文件，重写副本并作废派生缓存）。 */
    val recleanBookUseCase = com.llzx373.foldreader.feature.importer.RecleanBookUseCase(
        bookshelfRepository = bookshelfRepository,
        cleanedDir = File(context.filesDir, "cleaned"),
        openChannel = { key -> UriChannels.open(context, Uri.parse(key)) },
        offsetIndexStore = offsetIndexStore,
        pageDiskCache = pageDiskCache,
        traditionalMap = { TsCharMap.load(appContext) },
    )
    val batchImportUseCase = com.llzx373.foldreader.feature.importer.BatchImportUseCase(
        context = context,
        importBook = importBookUseCase,
        bookshelfRepository = bookshelfRepository,
        // 目录树里的「图片目录」按目录漫画登记（引用外部，不复制）
        importComicDirectory = { entry ->
            val outcome = comicImportUseCase.register(
                uri = Uri.parse(entry.uri),
                container = com.llzx373.foldreader.core.comic.ComicContainer.FOLDER,
                source = com.llzx373.foldreader.core.data.db.BookSource.EXTERNAL,
            )
            if (outcome is com.llzx373.foldreader.feature.importer.ComicImportUseCase.Outcome.Registered &&
                outcome.needsPreparation
            ) {
                bookPrewarmQueue.enqueue(outcome.bookId, entry.uri, BookFormat.COMIC)
            }
            outcome.toImportResult()
        },
        // 批量导入没有对话框：清洗档位跟随设置页，与「浏览」打开一致
        profileProvider = cleanProfileFactory::fromSettings,
    )

    /**
     * 闲时回收缓存：页边界（孤儿文件与同书过多版式副本会让 page_bounds/ 只增不减）
     * 与漫画解压缓存。放 IO 线程，不占冷启动主线程。
     */
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        maintenanceScope.launch {
            runCatching {
                val books = bookshelfRepository.observeBookshelf().first()
                com.llzx373.foldreader.core.reader.PageBoundsGc.sweep(
                    pageBoundsDir,
                    books.map { it.id }.toSet(),
                )
                // 本地副本（local/）是用户显式选择的持久数据，sweep 只清可回收的解压缓存
                comicExtractionStore.sweep(books.map { it.contentHash }.toSet())
            }
            // full 变体首启铺底内置模型（M24）：lite 下是零成本空转
            runCatching { modelManager.seedBundledModels() }
        }
    }

    companion object {
        /** M20 人物术语候选：按提及次数取 Top N 落未确认候选。 */
        private const val PERSON_CANDIDATE_LIMIT = 20

        /** M22 漫画气泡识别的页位图目标边长（px）：OCR 降采样上限。 */
        private const val COMIC_OCR_TARGET_PX = 1600

        /** 视觉翻译的页图像边长上限：再大只是白白烧 token，视觉模型输入本身也会缩放。 */
        private const val COMIC_VISION_TARGET_PX = 1024
    }
}

class FoldReaderApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 隔离进程（`android:isolatedProcess="true"`，例如 androidx.pdf 的 PDF 沙箱服务）
        // 会用**同一个 Application 类**再跑一遍 onCreate，但那里没有 UserManager、没有任何权限：
        // 碰 SharedPreferences / 文件 / DB 会直接抛
        // "SharedPreferences cannot be accessed if UserManager is not available"，
        // 进程当场死掉，沙箱服务于是永远连不上（表现为 openDocument 永久挂起）。
        //
        // 所以这里必须在做任何初始化之前退出。**以后往 onCreate 里加东西也要留在这一行后面。**
        if (com.llzx373.foldreader.core.debug.isIsolatedProcess()) return
        // 诊断日志先初始化（崩溃处理器 + return-trace.log 落盘），再装容器
        com.llzx373.foldreader.core.debug.DiagnosticLog.init(this)
        // PdfBox 的字体度量/CMap 等资源随 AAR 放在 assets/ 下，不初始化就取不到
        // （典型症状：PDType1Font 初始化时 "resource ...Times-Roman.afm not found"）
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.foldableStateProvider.activityLifecycleCallbacks)
    }
}
