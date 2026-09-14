package com.llzx373.foldreader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.core.foldable.FoldingPosture
import com.llzx373.foldreader.core.foldable.HingeOrientation
import com.llzx373.foldreader.core.foldable.Posture
import com.llzx373.foldreader.core.foldable.WidthCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(foldableUiState: FoldableUiState) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("设置") })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Text("设置项将在后续里程碑完善")
            Text(
                text = "当前姿态：${postureLabel(foldableUiState.posture)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "铰链方向：${orientationLabel(foldableUiState.posture)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "宽度类别：${widthCategoryLabel(foldableUiState.widthCategory)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun postureLabel(posture: FoldingPosture): String = when (posture.posture) {
    Posture.CLOSED -> "CLOSED（无铰链/直板）"
    Posture.FLAT -> "FLAT（完全展开）"
    Posture.HALF_OPENED -> "HALF_OPENED（半折悬停）"
}

private fun orientationLabel(posture: FoldingPosture): String = when (posture.hingeOrientation) {
    HingeOrientation.VERTICAL -> "VERTICAL（竖铰链）"
    HingeOrientation.HORIZONTAL -> "HORIZONTAL（横铰链）"
    null -> "无"
}

private fun widthCategoryLabel(category: WidthCategory): String = when (category) {
    WidthCategory.COMPACT -> "紧凑（底部导航）"
    WidthCategory.MEDIUM -> "中等（侧边导航）"
    WidthCategory.EXPANDED -> "展开（侧边导航）"
}
