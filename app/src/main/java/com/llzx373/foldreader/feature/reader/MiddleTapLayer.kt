package com.llzx373.foldreader.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.llzx373.foldreader.core.data.settings.TapAction
import kotlin.math.roundToInt

/**
 * 中间点击层：给中间区补上「双击」。
 *
 * 为什么是**叠一层**而不是给根手势加 `onDoubleTap`：
 * `detectTapGestures(onDoubleTap = …)` 会让它收到的**每一次单击**都等一个双击超时（约 300ms）
 * 才派发。加在根手势上，左右翻页也跟着慢——这正是之前否掉「双击缩放」的原因。
 * 把带双击的检测器限制在中间区这一层里，左右翻页与底边翻页条都保持抬手即响应。
 *
 * 这一层铺的是 [middleZoneRect]，也就是「点击后判定为中间区」的那块矩形，
 * 所以它不会盖住任何翻页热区。不配双击动作（[doubleTapAction] 为 [TapAction.NONE]，
 * 或该阅读器执行不了）时整层不挂，连中间区也没有额外延迟。
 */
@Composable
fun MiddleTapLayer(
    hotspotRatio: Float,
    doubleTapAction: TapAction,
    onTap: (offset: Offset, isDouble: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val rect = middleZoneRect(
            widthPx = constraints.maxWidth.toFloat(),
            heightPx = constraints.maxHeight.toFloat(),
            hotspotRatio = hotspotRatio,
        )
        if (rect.width <= 0f || rect.height <= 0f) return@BoxWithConstraints
        // pointerInput 的 key 不变时不会重启（捕获值会是旧的）：把回调与左边界用
        // rememberUpdatedState 挂住，读到的永远是本次组合的值。
        val currentOnTap by rememberUpdatedState(onTap)
        val currentLeft by rememberUpdatedState(rect.left)
        Box(
            modifier = Modifier
                .offset { IntOffset(rect.left.roundToInt(), 0) }
                .width(with(LocalDensity.current) { rect.width.toDp() })
                .height(with(LocalDensity.current) { rect.height.toDp() })
                .pointerInput(hotspotRatio, doubleTapAction) {
                    detectTapGestures(
                        // 本层内的坐标是相对中间区的，交回去之前换成根容器坐标，
                        // 让上层照旧用 tapZoneOf 判分区（底边规则等都在那里）
                        onTap = { currentOnTap(Offset(it.x + currentLeft, it.y), false) },
                        onDoubleTap = { currentOnTap(Offset(it.x + currentLeft, it.y), true) },
                    )
                },
        )
    }
}
