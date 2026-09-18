package com.llzx373.foldreader.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/**
 * 测试用的最小 PDF：现场用 PdfBox 生成，不往仓库塞二进制样张。
 * 放在 androidTest 里是因为生成过程依赖 PdfBox 的资源初始化（真机/模拟器上才会跑到）。
 */
internal fun writeSamplePdf(context: Context, pageCount: Int, name: String = "sample.pdf"): File {
    val file = File(context.cacheDir, name)
    PDDocument().use { document ->
        repeat(pageCount) { index ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 18f)
                content.newLineAtOffset(72f, 700f)
                content.showText("FoldReader page ${index + 1}")
                content.endText()
            }
        }
        document.documentInformation.title = "冒烟样张"
        document.documentInformation.author = "FoldReader"
        document.save(file)
    }
    return file
}

/**
 * 带元数据与两级内嵌目录的样张，用来验证 PdfBox 那条提取链路
 * （元数据 / 目录树层级 / 目录项 → 页序号 / 封面渲染）。
 *
 * 目录刻意做成：第一章(第 2 页) → 1.1 小节(第 3 页)，第二章(第 5 页)，
 * 这样能同时验到"嵌套层级"和"锚点解析"。每页都带一段正文，所以它同时是"文本型 PDF"。
 */
internal fun writeSamplePdfWithOutline(context: Context, name: String = "outline.pdf"): File {
    val file = File(context.cacheDir, name)
    PDDocument().use { document ->
        val pages = (1..6).map { index ->
            PDPage().also { page ->
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 18f)
                    content.newLineAtOffset(72f, 700f)
                    content.showText("Outline sample page $index")
                    content.endText()
                    // 正文要够长，否则会被文本密度判定当成扫描件（阈值是按真实文档定的）
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 11f)
                    content.newLineAtOffset(72f, 660f)
                    FILLER.forEach { line ->
                        content.showText(line)
                        content.newLineAtOffset(0f, -14f)
                    }
                    content.endText()
                }
            }
        }

        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        val chapter1 = outlineItem("第一章", pages[1])
        outline.addLast(chapter1)
        chapter1.addLast(outlineItem("1.1 小节", pages[2]))
        outline.addLast(outlineItem("第二章", pages[4]))

        document.documentInformation.title = "带目录的样张"
        document.documentInformation.author = "FoldReader 作者"
        document.documentInformation.subject = "提取链路验证"
        document.documentInformation.keywords = "pdf,outline,测试"
        document.save(file)
    }
    return file
}

/**
 * 只有图形、没有文字的样张——就是扫描件的形状：渲染得出来，但一个字都抽不出。
 * 用来验证「扫描件不产出压平产物」（否则书架上会多一本能打开但全是空白的假电子书）。
 */
internal fun writeScanLikePdf(context: Context, pageCount: Int = 3, name: String = "scan.pdf"): File {
    val file = File(context.cacheDir, name)
    PDDocument().use { document ->
        repeat(pageCount) { index ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.setNonStrokingColor(60, 60, 60)
                content.addRect(72f, 200f, 400f, 500f)
                content.fill()
            }
        }
        document.save(file)
    }
    return file
}

/** 凑够"每页字数"的填充文本：和阈值挂钩的是密度，不是某句话。 */
private val FILLER = listOf(
    "This paragraph exists so the page carries enough extractable text to be",
    "recognised as a text based document rather than a scanned image. The",
    "threshold is a per page average over the whole document, and it decides",
    "only the default reading mode, never locks the book into one of them.",
    "Reading it in text mode should give reflowable paragraphs, searchable",
    "text, bookmarks and reading statistics without any of that being",
    "reimplemented for PDF.",
)

private fun outlineItem(title: String, page: PDPage): PDOutlineItem =
    PDOutlineItem().apply {
        this.title = title
        destination = PDPageFitWidthDestination().apply { this.page = page }
    }

