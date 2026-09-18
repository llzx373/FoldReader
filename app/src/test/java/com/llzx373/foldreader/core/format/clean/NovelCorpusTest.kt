package com.llzx373.foldreader.core.format.clean

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 语料驱动的端到端测试。
 *
 * 每个用例目录下 `input.txt` 是脏数据、`expected.txt` 是规格；`expected.txt` 由人写，
 * 不由实现反推。`negatives/` 是反向用例：清洗后必须**逐字节等于原文**。
 */
class NovelCorpusTest {

    private val root: File = resolveCorpusRoot()

    private fun cases(): List<File> =
        root.listFiles { f -> f.isDirectory && f.name != NEGATIVES_DIR }
            ?.sortedBy { it.name }
            ?.toList()
            ?: emptyList()

    private fun negativeCases(): List<File> =
        File(root, NEGATIVES_DIR)
            .listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.toList()
            ?: emptyList()

    @Test
    fun `语料库非空且每个用例都具备 input 与 expected`() {
        if (!root.isDirectory) fail("语料目录不存在：${root.absolutePath}")
        val all = cases() + negativeCases()
        if (all.isEmpty()) fail("语料目录下没有任何用例：${root.absolutePath}")
        for (case in all) {
            if (!File(case, "input.txt").isFile) fail("${case.name} 缺少 input.txt")
            if (!File(case, "expected.txt").isFile) fail("${case.name} 缺少 expected.txt")
        }
    }

    @Test
    fun `每个用例清洗结果与 expected 逐字符一致`() {
        for (case in cases()) {
            val input = File(case, "input.txt").readText()
            val expected = File(case, "expected.txt").readText()
            val actual = NovelCleaner.clean(input, CleanProfileFixtures.fromFile(case))
            assertEquals(diffMessage(case, expected, actual), expected, actual)
        }
    }

    @Test
    fun `每个用例清洗两次结果不变（幂等）`() {
        for (case in cases()) {
            val profile = CleanProfileFixtures.fromFile(case)
            val once = NovelCleaner.clean(File(case, "input.txt").readText(), profile)
            val twice = NovelCleaner.clean(once, profile)
            assertEquals("${case.name} 不幂等", once, twice)
        }
    }

    @Test
    fun `反向用例清洗后与原文完全一致`() {
        val negatives = negativeCases()
        if (negatives.isEmpty()) fail("negatives/ 下没有任何用例")
        for (case in negatives) {
            val input = File(case, "input.txt").readText()
            val expected = File(case, "expected.txt").readText()
            assertEquals("${case.name} 的 expected 必须等于 input（反向用例的约定）", input, expected)
            val actual = NovelCleaner.clean(input, CleanProfileFixtures.fromFile(case))
            assertEquals(diffMessage(case, input, actual), input, actual)
        }
    }

    /** 失败时指出首个差异所在行，不然只看到一长串文本很难定位。 */
    private fun diffMessage(case: File, expected: String, actual: String): String {
        val expectedLines = expected.split('\n')
        val actualLines = actual.split('\n')
        val firstDiff = (0 until maxOf(expectedLines.size, actualLines.size)).firstOrNull { i ->
            expectedLines.getOrNull(i) != actualLines.getOrNull(i)
        } ?: return "用例 ${case.name} 不一致"
        return buildString {
            appendLine("用例 ${case.name} 第 ${firstDiff + 1} 行不一致")
            appendLine("  expected: ${expectedLines.getOrNull(firstDiff)?.quoted()}")
            appendLine("  actual  : ${actualLines.getOrNull(firstDiff)?.quoted()}")
            appendLine("  （期望 ${expectedLines.size} 行，实际 ${actualLines.size} 行）")
        }
    }

    private fun String?.quoted(): String = if (this == null) "<无此行>" else "\"$this\""

    private fun resolveCorpusRoot(): File {
        val url = javaClass.getResource("/$CORPUS_DIR_NAME")
        if (url != null && url.protocol == "file") return File(url.toURI())
        return File("src/test/resources/$CORPUS_DIR_NAME")
    }

    private companion object {
        const val CORPUS_DIR_NAME = "novel-corpus"
        const val NEGATIVES_DIR = "negatives"
    }
}

/** 从用例句读取 `profile.txt` 构造档位；不写则用标准档。 */
internal object CleanProfileFixtures {

    private val FIELDS: List<Field> = listOf(
        Field("unifyChars") { t, v -> t.copy(unifyChars = v) },
        Field("trimLines") { t, v -> t.copy(trimLines = v) },
        Field("collapseSpaces") { t, v -> t.copy(collapseSpaces = v) },
        Field("canonicalIndent") { t, v -> t.copy(canonicalIndent = v) },
        Field("filterNoise") { t, v -> t.copy(filterNoise = v) },
        Field("exciseInlineNoise") { t, v -> t.copy(exciseInlineNoise = v) },
        Field("maskRuns") { t, v -> t.copy(maskRuns = v) },
        Field("reflowParagraphs") { t, v -> t.copy(reflowParagraphs = v) },
        Field("collapseBlankLines") { t, v -> t.copy(collapseBlankLines = v) },
        Field("repairChapters") { t, v -> t.copy(repairChapters = v) },
        Field("dedupeChapterTitles") { t, v -> t.copy(dedupeChapterTitles = v) },
        Field("normalizePunctuation") { t, v -> t.copy(normalizePunctuation = v) },
        Field("normalizeRepeatedPunctuation") { t, v -> t.copy(normalizeRepeatedPunctuation = v) },
        Field("normalizeQuotes") { t, v -> t.copy(normalizeQuotes = v) },
        Field("traditionalToSimplified") { t, v -> t.copy(traditionalToSimplified = v) },
    )

    fun fromFile(caseDir: File): CleanProfile {
        val file = File(caseDir, "profile.txt")
        if (!file.isFile) return CleanProfile(level = CleanLevel.STANDARD)
        var level = CleanLevel.STANDARD
        val on = mutableListOf<String>()
        val off = mutableListOf<String>()
        for (raw in file.readLines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            when (key) {
                "level" -> level = runCatching { CleanLevel.valueOf(value.uppercase()) }
                    .getOrElse { error("${caseDir.name} 的 profile.txt 里档位非法：$value") }

                "on" -> on += splitNames(value)
                "off" -> off += splitNames(value)
                else -> error("${caseDir.name} 的 profile.txt 里有未知键：$key")
            }
        }
        var toggles = CleanToggles.preset(if (level == CleanLevel.CUSTOM) CleanLevel.STANDARD else level)
        for (name in on) toggles = apply(toggles, name, true, caseDir)
        for (name in off) toggles = apply(toggles, name, false, caseDir)
        return CleanProfile(level = level, toggles = toggles)
    }

    private fun apply(toggles: CleanToggles, name: String, value: Boolean, dir: File): CleanToggles {
        val field = FIELDS.firstOrNull { it.name == name }
            ?: error("${dir.name} 的 profile.txt 里有未知开关：$name")
        return field.set(toggles, value)
    }

    private fun splitNames(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private class Field(
        val name: String,
        val set: (CleanToggles, Boolean) -> CleanToggles,
    )
}
