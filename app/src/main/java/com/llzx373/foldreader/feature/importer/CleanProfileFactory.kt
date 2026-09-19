package com.llzx373.foldreader.feature.importer

import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanToggles
import kotlinx.coroutines.flow.first

/**
 * 组装一次导入/整理要用的清洗配方。
 *
 * 这套规则原先只长在书架的导入对话框里，于是「浏览」打开与「目录批量导入」拿到的是默认的
 * [CleanProfile.NONE]——同一个文件换个入口进来结果就不一样。规则收在这里一处：
 *
 * - [forLevel]：给导入对话框用。[level] 为 null 表示这次不清理，繁简由用户当次勾选决定。
 * - [fromSettings]：给没有对话框的入口用（浏览、批量导入），跟随设置页的档位与繁简偏好。
 */
class CleanProfileFactory(private val settingsRepository: SettingsRepository) {

    suspend fun forLevel(level: CleanLevel?, convertTraditional: Boolean): CleanProfile {
        if (level == null) return CleanProfile.NONE
        return cleanProfileOf(settingsRepository.preferences.first(), level, convertTraditional)
    }

    suspend fun fromSettings(): CleanProfile {
        val prefs = settingsRepository.preferences.first()
        return cleanProfileOf(prefs, prefs.cleanLevel, prefs.cleanToggles.traditionalToSimplified)
    }
}

/**
 * 纯映射：偏好 + 本次档位/繁简选择 → 清洗配方（不碰 DataStore，可直接单测）。
 *
 * - `level == CUSTOM` 用用户逐项调过的 [ReadingPreferences.cleanToggles]，其余档位取预设。
 * - 繁简与档位**正交**：它是用户取向（有人读繁、有人读简），不随"洗得多干净"走。
 * - 自定义广告正则始终编译进配方；写坏的那条丢掉，不让它炸掉整次导入。
 */
internal fun cleanProfileOf(
    prefs: ReadingPreferences,
    level: CleanLevel,
    convertTraditional: Boolean,
): CleanProfile {
    val base = if (level == CleanLevel.CUSTOM) prefs.cleanToggles else CleanToggles.preset(level)
    return CleanProfile(
        level = level,
        toggles = base.copy(traditionalToSimplified = convertTraditional),
        adPatterns = prefs.adCleanRules.mapNotNull { runCatching { Regex(it) }.getOrNull() },
    )
}
