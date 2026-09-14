package com.llzx373.foldreader.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.reader.dayOfMonthOf
import com.llzx373.foldreader.core.reader.formatDurationZh
import com.llzx373.foldreader.core.reader.monthOf

/** 设置页「阅读统计」区：本周/本月时长 + 近 7 天圆角柱状图。 */
@Composable
fun ReadingStatsSection(stats: SettingsViewModel.ReadingStatsUi) {
    val zone = java.time.ZoneId.systemDefault()
    Text(
        text = "阅读统计",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "本周 ${formatDurationZh(stats.weekMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "本月 ${formatDurationZh(stats.monthMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
    if (stats.last7Days.isNotEmpty()) {
        WeeklyBarChart(
            buckets = stats.last7Days,
            zone = zone,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun WeeklyBarChart(
    buckets: List<Pair<Long, Long>>,
    zone: java.time.ZoneId,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val todayColor = MaterialTheme.colorScheme.tertiary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
        ) {
            val maxMs = (buckets.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
            val slot = size.width / buckets.size
            val barWidth = slot * 0.52f
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
            buckets.forEachIndexed { index, (_, ms) ->
                val x = index * slot + (slot - barWidth) / 2f
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = corner,
                )
                if (ms > 0L) {
                    val h = (size.height * (ms.toFloat() / maxMs)).coerceAtLeast(barWidth * 0.3f)
                    drawRoundRect(
                        color = if (index == buckets.lastIndex) todayColor else barColor,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = corner,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            buckets.forEach { (dayStart, _) ->
                Text(
                    text = "${monthOf(dayStart, zone)}/${dayOfMonthOf(dayStart, zone)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
