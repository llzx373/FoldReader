package com.llzx373.foldreader.core.foldable

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.compose.ui.geometry.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class FoldableStateProvider(
    context: Context,
    scope: CoroutineScope,
) {
    private val tracker = WindowInfoTracker.getOrCreate(context)
    private val resumedActivity = MutableStateFlow<Activity?>(null)

    val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivity.value = activity
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumedActivity.value == activity) resumedActivity.value = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val posture: StateFlow<FoldingPosture> = resumedActivity
        .flatMapLatest { activity ->
            if (activity == null) flowOf<WindowLayoutInfo?>(null)
            else tracker.windowLayoutInfo(activity)
        }
        .map { layoutInfo ->
            layoutInfo?.displayFeatures
                ?.filterIsInstance<FoldingFeature>()
                ?.firstOrNull()
                ?.toHingeInfo()
                .toFoldingPosture()
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), FoldingPosture.Closed)
}

private fun FoldingFeature.toHingeInfo(): HingeInfo = HingeInfo(
    bounds = Rect(
        bounds.left.toFloat(),
        bounds.top.toFloat(),
        bounds.right.toFloat(),
        bounds.bottom.toFloat(),
    ),
    orientation = when (orientation) {
        FoldingFeature.Orientation.VERTICAL -> HingeOrientation.VERTICAL
        else -> HingeOrientation.HORIZONTAL
    },
    state = when (state) {
        FoldingFeature.State.HALF_OPENED -> HingeState.HALF_OPENED
        else -> HingeState.FLAT
    },
)
