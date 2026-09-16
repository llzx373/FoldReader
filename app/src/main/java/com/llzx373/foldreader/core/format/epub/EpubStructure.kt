package com.llzx373.foldreader.core.format.epub

import android.util.Xml
import com.llzx373.foldreader.core.format.newPullParser
import java.io.IOException
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

class DrmProtectedException(message: String) : IOException(message)

/** OPF 元数据全字段；creators 保留全部作者条目（含 role/file-as），subjects 可多个。 */
data class EpubMeta(
    val title: String? = null,
    val creators: List<Creator> = emptyList(),
    val language: String? = null,
    val publisher: String? = null,
    val date: String? = null,
    val description: String? = null,
    val subjects: List<String> = emptyList(),
    val identifier: String? = null,
    val rights: String? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
) {
    data class Creator(val name: String, val role: String?, val fileAs: String?)
}

/** 多作者拼展示串：aut/无 role 直列，trl/edt/ill 附中文角色后缀，其余 role 原样附括号。 */
internal fun formatCreators(creators: List<EpubMeta.Creator>): String? =
    creators.joinToString(", ") { creator ->
        when (creator.role?.lowercase()) {
            null, "", "aut" -> creator.name
            "trl" -> "${creator.name}（译）"
            "edt" -> "${creator.name}（编）"
            "ill" -> "${creator.name}（图）"
            else -> "${creator.name}（${creator.role}）"
        }
    }.takeIf { it.isNotBlank() }

/**
 * EPUB 结构解析：container.xml 定位 OPF；OPF 取 metadata/manifest/spine/guide；
 * TOC 优先 EPUB3 NAV（manifest properties="nav"），兜底 EPUB2 NCX（spine toc 属性）。
 * TOC 多级拍平为导航文档序的平铺列表；href 解析为 zip 内文件路径 + 保留 fragment（锚点级章节边界）。
 * 封面：EPUB3 manifest properties="cover-image" → EPUB2 meta name="cover" → guide reference type="cover"。
 * landmarks（EPUB3 nav）与 guide type="text" 等提供正文起点；page-list（nav / NCX）提供纸书页码。
 */
class EpubStructure(
    val meta: EpubMeta,
    /** spine 阅读顺序；file 为 zip 内路径；linear=false 的项仅经链接到达，不参与正文压平。 */
    val spine: List<SpineItem>,
    /** 拍平后的 TOC；null 表示无 TOC（上层退化为按 spine 项分章）。 */
    val toc: List<TocEntry>?,
    /** 封面图片条目的 zip 内路径；未解析到为 null（未做图片有效性校验，见 EpubBookParser）。 */
    val coverFile: String?,
    /** EPUB2 guide 条目（cover/text/toc 等），href 已解析为 zip 内路径 + fragment。 */
    val guide: List<GuideRef> = emptyList(),
    /** EPUB3 landmarks nav 链接（type 为 epub:type 值，如 bodymatter）。 */
    val landmarks: List<NavLink> = emptyList(),
    /** 纸书页码序列（EPUB3 page-list nav 或 EPUB2 NCX pageList），导航文档序。 */
    val pageList: List<TocEntry> = emptyList(),
) {
    val title: String? get() = meta.title
    val creator: String? get() = meta.creators.firstOrNull()?.name

    /** 正文起点目标：landmarks 优先，guide 兜底；认 bodymatter/text/body-start/main-content。 */
    fun preferredStartTarget(): TocEntry? =
        landmarks.firstOrNull { it.type?.lowercase() in PREFERRED_START_TYPES }
            ?.let { TocEntry(it.type.orEmpty(), it.targetFile, it.fragment) }
        ?: guide.firstOrNull { it.type.lowercase() in PREFERRED_START_TYPES }
            ?.let { TocEntry(it.title.orEmpty(), it.targetFile, it.fragment) }

    data class SpineItem(val file: String, val mediaType: String?, val linear: Boolean = true)

    data class TocEntry(val label: String, val targetFile: String, val fragment: String? = null)

    data class GuideRef(val type: String, val title: String?, val targetFile: String, val fragment: String?)

    data class NavLink(val type: String?, val targetFile: String, val fragment: String?)

    companion object {
        private val PREFERRED_START_TYPES = setOf("bodymatter", "text", "body-start", "main-content")

        fun parse(
            zip: ZipFile,
            newParser: () -> XmlPullParser = { Xml.newPullParser() },
        ): EpubStructure {
            val opfPath = readOpfPath(zip, newParser)
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseOpf(zip, opfPath, opfDir, newParser)
            checkEncryption(zip, opf, opfDir, newParser)
            val spine = opf.spineIdrefs.mapNotNull { (idref, linear) ->
                opf.manifest[idref]?.let { SpineItem(it.href, it.mediaType, linear) }
            }
            val guide = opf.guide
            val navItem = opf.manifest.values.firstOrNull { item ->
                item.properties?.split(' ')?.contains("nav") == true
            }
            val ncxItem = opf.ncxId?.let { opf.manifest[it] }
                ?: opf.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            val nav = navItem?.let { parseNav(zip, it, newParser) }
            val ncx = ncxItem?.let { parseNcx(zip, it, newParser) }
            val toc = nav?.toc?.takeIf { it.isNotEmpty() }
                ?: ncx?.toc?.takeIf { it.isNotEmpty() }
            val pageList = nav?.pageList?.takeIf { it.isNotEmpty() }
                ?: ncx?.pageList?.takeIf { it.isNotEmpty() }
                ?: emptyList()
            val coverFile = opf.manifest.values.firstOrNull { item ->
                item.properties?.split(' ')?.contains("cover-image") == true
            }?.href
                ?: opf.metaCoverId?.let { opf.manifest[it]?.href }
                ?: guide.firstOrNull { it.type.equals("cover", ignoreCase = true) }?.targetFile
            return EpubStructure(
                meta = opf.toMeta(),
                spine = spine,
                toc = toc,
                coverFile = coverFile,
                guide = guide,
                landmarks = nav?.landmarks ?: emptyList(),
                pageList = pageList,
            )
        }

        /**
         * DRM 判定：存在 encryption.xml 时解析其 CipherReference 目标——
         * 全部目标为字体（字体混淆，内嵌字体本就不支持，忽略即可）则放行；
         * 任一目标不是字体、无加密目标或解析失败，保守按 DRM 抛 [DrmProtectedException]。
         */
        private fun checkEncryption(
            zip: ZipFile,
            opf: OpfData,
            opfDir: String,
            newParser: () -> XmlPullParser,
        ) {
            val entry = zip.getEntry("META-INF/encryption.xml") ?: return
            val targets = runCatching { parseEncryptionTargets(zip, entry, newParser) }.getOrNull()
            if (targets.isNullOrEmpty() || !targets.all { isFontResource(opf, opfDir, it) }) {
                throw DrmProtectedException("该 EPUB 受 DRM 保护，无法导入")
            }
        }

        /** 收集 encryption.xml 中全部 CipherReference 的 URI 属性。 */
        private fun parseEncryptionTargets(
            zip: ZipFile,
            entry: java.util.zip.ZipEntry,
            newParser: () -> XmlPullParser,
        ): List<String> {
            val uris = ArrayList<String>()
            zip.getInputStream(entry).use { input ->
                val parser = newPullParser(newParser)
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG &&
                        parser.name.substringAfterLast(':').equals("CipherReference", ignoreCase = true)
                    ) {
                        parser.getAttributeValue(null, "URI")?.takeIf { it.isNotBlank() }
                            ?.let { uris.add(it) }
                    }
                    event = parser.next()
                }
            }
            return uris
        }

        /** 加密目标是否字体：manifest media-type 为字体类，或扩展名 .otf/.ttf/.woff/.woff2。 */
        private fun isFontResource(opf: OpfData, opfDir: String, uri: String): Boolean {
            val path = resolveHref("", uri)
            val mediaType = opf.manifest.values
                .firstOrNull { resolveHref(opfDir, it.href) == path }
                ?.mediaType?.lowercase()
            val fontMediaType = mediaType != null &&
                (mediaType.startsWith("font/") ||
                    mediaType == "application/vnd.ms-opentype" ||
                    mediaType.startsWith("application/x-font-"))
            return fontMediaType || path.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS
        }

        private val FONT_EXTENSIONS = setOf("otf", "ttf", "woff", "woff2")

        private fun readOpfPath(zip: ZipFile, newParser: () -> XmlPullParser): String {
            val entry = zip.getEntry("META-INF/container.xml")
                ?: throw IOException("无效的 EPUB：缺少 META-INF/container.xml")
            zip.getInputStream(entry).use { input ->
                val parser = newPullParser(newParser)
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG &&
                        parser.name.equals("rootfile", ignoreCase = true)
                    ) {
                        parser.getAttributeValue(null, "full-path")?.let { return it }
                    }
                    event = parser.next()
                }
            }
            throw IOException("无效的 EPUB：container.xml 缺少 rootfile")
        }

        private class ManifestItem(
            val href: String,
            val mediaType: String?,
            val properties: String?,
        )

        private class OpfData(
            var ncxId: String? = null,
            val manifest: MutableMap<String, ManifestItem> = LinkedHashMap(),
            val spineIdrefs: MutableList<Pair<String, Boolean>> = ArrayList(),
            var metaCoverId: String? = null,
            val guide: MutableList<EpubStructure.GuideRef> = ArrayList(),
        ) {
            var title: String? = null
            val creators = ArrayList<EpubMeta.Creator>()
            var language: String? = null
            var publisher: String? = null
            var date: String? = null
            var description: String? = null
            val subjects = ArrayList<String>()
            var identifier: String? = null
            var rights: String? = null
            var seriesName: String? = null
            var seriesIndex: String? = null

            fun toMeta() = EpubMeta(
                title = title,
                creators = creators,
                language = language,
                publisher = publisher,
                date = date,
                description = description,
                subjects = subjects,
                identifier = identifier,
                rights = rights,
                seriesName = seriesName,
                seriesIndex = seriesIndex,
            )
        }

        private fun parseOpf(
            zip: ZipFile,
            opfPath: String,
            opfDir: String,
            newParser: () -> XmlPullParser,
        ): OpfData {
            val entry = zip.getEntry(opfPath)
                ?: throw IOException("无效的 EPUB：缺少 OPF $opfPath")
            val data = OpfData()
            // 文本捕获：dc:* 元素内容可被实体归一化拆成多段 TEXT，须拼接至 END_TAG
            var captureTag: String? = null
            val captureBuf = StringBuilder()
            var creatorRole: String? = null
            var creatorFileAs: String? = null
            fun startCapture(tag: String) {
                captureTag = tag
                captureBuf.setLength(0)
            }
            zip.getInputStream(entry).use { input ->
                val parser = newPullParser(newParser)
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                            "dc:title" -> if (data.title == null) startCapture("title")
                            "dc:creator" -> {
                                startCapture("creator")
                                creatorRole = parser.getAttributeValue(null, "opf:role")
                                creatorFileAs = parser.getAttributeValue(null, "opf:file-as")
                            }
                            "dc:language" -> if (data.language == null) startCapture("language")
                            "dc:publisher" -> if (data.publisher == null) startCapture("publisher")
                            "dc:date" -> if (data.date == null) startCapture("date")
                            "dc:description" -> if (data.description == null) startCapture("description")
                            "dc:subject" -> startCapture("subject")
                            "dc:identifier" -> if (data.identifier == null) startCapture("identifier")
                            "dc:rights" -> if (data.rights == null) startCapture("rights")
                            "meta" -> when (parser.getAttributeValue(null, "name")) {
                                "cover" -> parser.getAttributeValue(null, "content")
                                    ?.let { data.metaCoverId = it }
                                "calibre:series" -> parser.getAttributeValue(null, "content")
                                    ?.let { data.seriesName = it }
                                "calibre:series_index" -> parser.getAttributeValue(null, "content")
                                    ?.let { data.seriesIndex = it }
                            }
                            "reference" -> {
                                val type = parser.getAttributeValue(null, "type")
                                val href = parser.getAttributeValue(null, "href")
                                if (type != null && href != null) {
                                    val (file, fragment) = splitHref(opfDir, href)
                                    data.guide += EpubStructure.GuideRef(
                                        type = type,
                                        title = parser.getAttributeValue(null, "title"),
                                        targetFile = file,
                                        fragment = fragment,
                                    )
                                }
                            }
                            "spine" -> data.ncxId = parser.getAttributeValue(null, "toc")
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                if (id != null && href != null) {
                                    data.manifest[id] = ManifestItem(
                                        href = resolveHref(opfDir, href),
                                        mediaType = parser.getAttributeValue(null, "media-type"),
                                        properties = parser.getAttributeValue(null, "properties"),
                                    )
                                }
                            }
                            "itemref" -> parser.getAttributeValue(null, "idref")
                                ?.let { idref ->
                                    val linear = parser.getAttributeValue(null, "linear")
                                        ?.equals("no", ignoreCase = true) != true
                                    data.spineIdrefs += idref to linear
                                }
                        }
                        XmlPullParser.TEXT -> if (captureTag != null) captureBuf.append(parser.text)
                        XmlPullParser.END_TAG -> {
                            val tag = captureTag
                            if (tag != null && parser.name.lowercase() == "dc:$tag") {
                                val text = captureBuf.toString().trim()
                                when (tag) {
                                    "title" -> if (text.isNotEmpty()) data.title = text
                                    "creator" -> if (text.isNotEmpty()) {
                                        data.creators += EpubMeta.Creator(text, creatorRole, creatorFileAs)
                                    }
                                    "language" -> if (text.isNotEmpty()) data.language = text
                                    "publisher" -> if (text.isNotEmpty()) data.publisher = text
                                    "date" -> if (text.isNotEmpty()) data.date = text
                                    "description" -> if (text.isNotEmpty()) data.description = text
                                    "subject" -> if (text.isNotEmpty()) data.subjects += text
                                    "identifier" -> if (text.isNotEmpty()) data.identifier = text
                                    "rights" -> if (text.isNotEmpty()) data.rights = text
                                }
                                captureTag = null
                                captureBuf.setLength(0)
                                creatorRole = null
                                creatorFileAs = null
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            return data
        }

        private class NavResult(
            val toc: List<TocEntry>,
            val landmarks: List<EpubStructure.NavLink>,
            val pageList: List<TocEntry>,
        )

        /**
         * EPUB3 NAV：三种 nav 一次扫完——`epub:type="toc"`（章节）、`landmarks`（正文起点等，
         * 链接带 epub:type）、`page-list`（纸书页码）；嵌套 ol/li 均拍平为文档序。
         */
        private fun parseNav(
            zip: ZipFile,
            navItem: ManifestItem,
            newParser: () -> XmlPullParser,
        ): NavResult {
            val entry = zip.getEntry(navItem.href) ?: return NavResult(emptyList(), emptyList(), emptyList())
            val navDir = navItem.href.substringBeforeLast('/', "")
            val toc = ArrayList<TocEntry>()
            val landmarks = ArrayList<EpubStructure.NavLink>()
            val pageList = ArrayList<TocEntry>()
            // navKind 栈：0=不在 nav 内；否则为 epub:type（toc/landmarks/page-list/其他）
            val navStack = ArrayDeque<String>()
            var linkDepth = 0
            var linkHref: String? = null
            var linkType: String? = null
            val linkLabel = StringBuilder()
            zip.getInputStream(entry).use { input ->
                val parser = newPullParser(newParser)
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            val name = parser.name.lowercase()
                            if (linkDepth > 0) {
                                linkDepth++
                            } else if (name == "nav") {
                                navStack.addLast(
                                    parser.getAttributeValue(null, "epub:type").orEmpty(),
                                )
                            } else if (navStack.isNotEmpty() && name == "a") {
                                linkDepth = 1
                                linkHref = parser.getAttributeValue(null, "href")
                                linkType = parser.getAttributeValue(null, "epub:type")
                                linkLabel.setLength(0)
                            }
                        }
                        XmlPullParser.TEXT -> if (linkDepth > 0) linkLabel.append(parser.text)
                        XmlPullParser.END_TAG -> {
                            val name = parser.name.lowercase()
                            if (linkDepth > 0) {
                                linkDepth--
                                if (linkDepth == 0) {
                                    val href = linkHref
                                    val label = linkLabel.toString().trim()
                                    if (href != null && label.isNotEmpty()) {
                                        val (file, fragment) = splitHref(navDir, href)
                                        when (navStack.lastOrNull()) {
                                            "toc" -> toc += TocEntry(label, file, fragment)
                                            "landmarks" -> landmarks +=
                                                EpubStructure.NavLink(linkType, file, fragment)
                                            "page-list" -> pageList += TocEntry(label, file, fragment)
                                        }
                                    }
                                    linkHref = null
                                    linkType = null
                                }
                            } else if (name == "nav" && navStack.isNotEmpty()) {
                                navStack.removeLast()
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            return NavResult(toc, landmarks, pageList)
        }

        private class NcxResult(val toc: List<TocEntry>, val pageList: List<TocEntry>)

        /**
         * EPUB2 NCX：navMap/navPoint/navLabel/text + content src（嵌套 navPoint 按开始标签文档序拍平）；
         * 同时解析 pageList/pageTarget（纸书页码）。
         */
        private fun parseNcx(
            zip: ZipFile,
            ncxItem: ManifestItem,
            newParser: () -> XmlPullParser,
        ): NcxResult {
            val entry = zip.getEntry(ncxItem.href) ?: return NcxResult(emptyList(), emptyList())
            val ncxDir = ncxItem.href.substringBeforeLast('/', "")
            // (开始标签序号, entry)：END 标签是后序弹出，须按开始序号重排成文档序
            val tocEntries = ArrayList<Pair<Int, TocEntry>>()
            val pageEntries = ArrayList<Pair<Int, TocEntry>>()
            val stack = ArrayDeque<NcxPoint>()
            var seq = 0
            zip.getInputStream(entry).use { input ->
                val parser = newPullParser(newParser)
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                            "navpoint" -> stack.addLast(NcxPoint(order = seq++))
                            "pagetarget" -> stack.addLast(NcxPoint(order = seq++, isPage = true))
                            "navlabel" -> stack.lastOrNull()?.inLabel = true
                            "content" -> stack.lastOrNull()?.let { point ->
                                if (point.src == null) {
                                    point.src = parser.getAttributeValue(null, "src")
                                }
                            }
                        }
                        XmlPullParser.TEXT -> stack.lastOrNull()?.let { point ->
                            if (point.inLabel) point.label.append(parser.text)
                        }
                        XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                            "navlabel" -> stack.lastOrNull()?.inLabel = false
                            "navpoint", "pagetarget" -> stack.removeLastOrNull()?.let { point ->
                                val label = point.label.toString().trim()
                                val src = point.src
                                if (src != null && label.isNotEmpty()) {
                                    val (file, fragment) = splitHref(ncxDir, src)
                                    val entry2 = TocEntry(label, file, fragment)
                                    if (point.isPage) pageEntries += point.order to entry2
                                    else tocEntries += point.order to entry2
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            return NcxResult(
                toc = tocEntries.sortedBy { it.first }.map { it.second },
                pageList = pageEntries.sortedBy { it.first }.map { it.second },
            )
        }

        private class NcxPoint(
            val order: Int,
            val isPage: Boolean = false,
            val label: StringBuilder = StringBuilder(),
            var src: String? = null,
            var inLabel: Boolean = false,
        )
    }
}

/** href（可含 #fragment 与 URL 编码）解析为相对 zip 根的路径。 */
internal fun resolveHref(baseDir: String, href: String): String {
    val path = href.substringBefore('#')
    val decoded = runCatching { java.net.URLDecoder.decode(path, Charsets.UTF_8) }.getOrDefault(path)
    val combined = when {
        decoded.startsWith("/") -> decoded.drop(1)
        baseDir.isEmpty() -> decoded
        else -> "$baseDir/$decoded"
    }
    val parts = ArrayDeque<String>()
    for (segment in combined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(segment)
        }
    }
    return parts.joinToString("/")
}

/** href 拆成（zip 内路径, fragment?）；fragment 同样做 URL 解码。 */
internal fun splitHref(baseDir: String, href: String): Pair<String, String?> {
    val rawFragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() }
    val fragment = rawFragment?.let {
        runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.getOrDefault(it)
    }
    return resolveHref(baseDir, href) to fragment
}

