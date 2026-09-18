package com.llzx373.foldreader.feature.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.llzx373.foldreader.core.format.clean.CleanLevel

/**
 * 清洗档位的选择项与文案。导入对话框与「智能整理」对话框共用同一份——
 * 两处都要能当场选档位，文案不能各写一遍然后慢慢漂移。
 */
@Composable
internal fun ImportLevelOption(
    label: String,
    hint: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun importLevelLabel(level: CleanLevel): String = when (level) {
    CleanLevel.CONSERVATIVE -> "保守"
    CleanLevel.STANDARD -> "标准（默认）"
    CleanLevel.AGGRESSIVE -> "激进"
    CleanLevel.CUSTOM -> "自定义"
}

internal fun importLevelHint(level: CleanLevel): String = when (level) {
    CleanLevel.CONSERVATIVE -> "字符/空白归一 + 广告行过滤，不改段落结构"
    CleanLevel.STANDARD -> "再加段落重组、空行规整、章节标题修复、标点规整"
    CleanLevel.AGGRESSIVE -> "再用重复标点折叠等改写幅度更大的规则"
    CleanLevel.CUSTOM -> "由设置页的规则明细决定"
}
