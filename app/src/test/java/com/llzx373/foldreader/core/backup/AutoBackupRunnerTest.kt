package com.llzx373.foldreader.core.backup

import com.llzx373.foldreader.core.ai.AiProtocol
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.BookshelfSort
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanToggles
import com.llzx373.foldreader.core.format.saf.SafTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 自动备份的触发门槛与失败口径。SAF 真实写入不在单测范围（需要文档提供方）；
 * 纯轮转逻辑见 [BackupFileNamesTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoBackupRunnerTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun runnerOf(settings: SettingsRepository) = AutoBackupRunner(
        context = context,
        settingsRepository = settings,
        exportJsonText = { "{}" },
        safTree = SafTree(context),
    )

    @Test
    fun `未启用时不执行`() = runTest {
        val settings = StubSettingsRepository(ReadingPreferences())

        assertFalse(runnerOf(settings).runIfDue())
    }

    @Test
    fun `已启用但未选目录时不执行`() = runTest {
        val settings = StubSettingsRepository(
            ReadingPreferences(autoBackupEnabled = true),
        )

        assertFalse(runnerOf(settings).runIfDue())
    }

    @Test
    fun `距上次成功不足 24 小时不重复执行`() = runTest {
        val settings = StubSettingsRepository(
            ReadingPreferences(
                autoBackupEnabled = true,
                autoBackupDirUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
                autoBackupLastRunAt = 1_000L,
            ),
        )

        assertFalse(runnerOf(settings).runIfDue(nowMs = 1_000L + 60_000L))
    }

    @Test
    fun `到期但 SAF 授权失效时失败且不更新上次成功时间`() = runTest {
        val settings = StubSettingsRepository(
            ReadingPreferences(
                autoBackupEnabled = true,
                autoBackupDirUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
                autoBackupLastRunAt = 0L,
            ),
        )

        // 没有真实文档提供方，createDocument 必失败——等价于授权被收回
        assertFalse(runnerOf(settings).runIfDue(nowMs = AutoBackupRunner.INTERVAL_MS + 1))
        assertEquals(0L, settings.preferences.first().autoBackupLastRunAt)
    }

    /** 只有自动备份四个字段是真状态，其余设置与本测试无关。 */
    private class StubSettingsRepository(
        initial: ReadingPreferences,
    ) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<ReadingPreferences> = state

        override suspend fun setAutoBackupEnabled(enabled: Boolean) =
            update { copy(autoBackupEnabled = enabled) }
        override suspend fun setAutoBackupDirUri(treeUri: String) =
            update { copy(autoBackupDirUri = treeUri) }
        override suspend fun setAutoBackupKeepCount(keep: Int) =
            update { copy(autoBackupKeepCount = keep) }
        override suspend fun setAutoBackupLastRunAt(timestamp: Long) =
            update { copy(autoBackupLastRunAt = timestamp) }

        private fun update(block: ReadingPreferences.() -> ReadingPreferences) {
            state.value = state.value.block()
        }

        override suspend fun setFontSize(sizeSp: Float) = Unit
        override suspend fun setLineSpacing(multiplier: Float) = Unit
        override suspend fun setMarginLevel(level: Int) = Unit
        override suspend fun setMaxLineChars(chars: Int) = Unit
        override suspend fun setParagraphSpacingEm(spacingEm: Float) = Unit
        override suspend fun setLetterSpacingEm(spacingEm: Float) = Unit
        override suspend fun setTheme(theme: ReadingTheme) = Unit
        override suspend fun setCustomColors(backgroundArgb: Int?, textArgb: Int?) = Unit
        override suspend fun setDarkThemeOption(option: DarkThemeOption) = Unit
        override suspend fun setFontKey(fontKey: String) = Unit
        override suspend fun setDualPageMode(mode: DualPageMode) = Unit
        override suspend fun setWideScreenDualPage(enabled: Boolean) = Unit
        override suspend fun setAvoidCameraCutout(enabled: Boolean) = Unit
        override suspend fun setPageTurnMode(mode: PageTurnMode) = Unit
        override suspend fun setPageTurnHotspotRatio(ratio: Float) = Unit
        override suspend fun setMiddleTapAction(action: TapAction) = Unit
        override suspend fun setMiddleDoubleTapAction(action: TapAction) = Unit
        override suspend fun setVolumeKeyPagingEnabled(enabled: Boolean) = Unit
        override suspend fun setBrightnessGestureEnabled(enabled: Boolean) = Unit
        override suspend fun setSwipeGestureEnabled(enabled: Boolean) = Unit
        override suspend fun setSwipeDistanceDp(distanceDp: Float) = Unit
        override suspend fun setSwipeFlingVelocityDpPerSec(velocityDpPerSec: Float) = Unit
        override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
        override suspend fun setShowChapterTitle(enabled: Boolean) = Unit
        override suspend fun setShowPageProgress(enabled: Boolean) = Unit
        override suspend fun setShowPageNumber(enabled: Boolean) = Unit
        override suspend fun setShowBattery(enabled: Boolean) = Unit
        override suspend fun setShowTime(enabled: Boolean) = Unit
        override suspend fun setReaderBrightness(brightness: Float) = Unit
        override suspend fun setAutoPageEnabled(enabled: Boolean) = Unit
        override suspend fun setAutoPageMode(mode: AutoPageMode) = Unit
        override suspend fun setAutoPageIntervalSec(seconds: Int) = Unit
        override suspend fun setAutoPageSpeedPx(pxPerSecond: Float) = Unit
        override suspend fun setPanelScreenOff(enabled: Boolean) = Unit
        override suspend fun setBookshelfGridView(gridView: Boolean) = Unit
        override suspend fun setBookshelfGridColumns(columns: Int) = Unit
        override suspend fun setBookshelfSort(sort: BookshelfSort) = Unit
        override suspend fun setCustomChapterRules(rules: List<String>) = Unit
        override suspend fun setAdCleanRules(rules: List<String>) = Unit
        override suspend fun setCleanLevel(level: CleanLevel) = Unit
        override suspend fun setCleanToggle(key: String, enabled: Boolean) = Unit
        override suspend fun setCleanProfile(level: CleanLevel, toggles: CleanToggles) = Unit
        override suspend fun setComicDirection(direction: ComicDirection) = Unit
        override suspend fun setComicDualPageCoverAlone(enabled: Boolean) = Unit
        override suspend fun setComicSpreadAutoDetect(enabled: Boolean) = Unit
        override suspend fun setComicFitMode(mode: ComicFitMode) = Unit
        override suspend fun setComicScrollGapDp(gapDp: Int) = Unit
        override suspend fun setAiEnabled(enabled: Boolean) = Unit
        override suspend fun setAiProtocol(protocol: AiProtocol) = Unit
        override suspend fun setAiBaseUrl(baseUrl: String) = Unit
        override suspend fun setAiModelGeneral(model: String) = Unit
        override suspend fun setAiModelTranslation(model: String) = Unit
        override suspend fun setAiModelVision(model: String) = Unit
        override suspend fun setAiTargetLang(targetLang: AiTargetLang) = Unit
        override suspend fun setAiChapterRuleConfirmed(confirmed: Boolean) = Unit
        override suspend fun setAiTranslationConfirmed(confirmed: Boolean) = Unit
        override suspend fun setAiComicTranslateConfirmed(confirmed: Boolean) = Unit
        override suspend fun confirmAiComicVisionForBook(bookId: Long) = Unit
        override suspend fun setAiCleanRecipeConfirmed(confirmed: Boolean) = Unit
        override suspend fun setAiMetadataConfirmed(confirmed: Boolean) = Unit
        override suspend fun setTranslationViewHintShown(shown: Boolean) = Unit
        override suspend fun setTtsSpeechRate(rate: Float) = Unit
        override suspend fun setTtsPitch(pitch: Float) = Unit
        override suspend fun setAiPricePerMillion(price: Double) = Unit
        override suspend fun setOcrRecLang(modelId: String) = Unit
        override suspend fun setWebDavBaseUrl(baseUrl: String) = Unit
        override suspend fun setWebDavUsername(username: String) = Unit
        override suspend fun setWebDavConfirmed(confirmed: Boolean) = Unit
    }
}
