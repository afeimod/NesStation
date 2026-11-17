package com.nesstation.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.ui.fsd.FsdCustomBackground

/**
 * 全局背景状态 —— 单一可信来源。
 *
 * 用户在主设置里选择的背景图片 / 视频从这里读取：
 *  - [AppGlobalBackground]（由根布局渲染）绘制在最底层；
 *  - 各页面检测 [AppBackgroundState.active]，为 true 时跳过自己的默认背景层
 *    （FSD 深蓝 / 像素风 / 纯色），让全局背景透出来；
 *  - 设置页保存背景后立即调用 [AppBackgroundState.update] 同步，无需等待 ON_RESUME。
 */
object AppBackgroundState {
    var uri by mutableStateOf("")
        private set
    var isVideo by mutableStateOf(false)
        private set

    val active: Boolean get() = uri.isNotBlank()

    fun update(u: String, v: Boolean) {
        uri = u
        isVideo = v
    }

    /** 从 SharedPreferences 重新加载（应用启动 / 从系统返回时）。 */
    fun reload(context: Context) {
        val pl = PadLayoutStore.load(context)
        uri = pl.homeBackgroundUri
        isVideo = pl.homeBackgroundIsVideo
    }
}

/** 仅在组合根处调用一次：首次加载全局背景，并在 ON_RESUME 时刷新。 */
@Composable
fun rememberAppBackgroundState() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppBackgroundState.reload(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                AppBackgroundState.reload(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * 全局背景（图片 / 视频）。仅在 [AppBackgroundState.active] 时绘制；
 * 否则不渲染，由各页面自绘默认背景。
 */
@Composable
fun AppGlobalBackground(modifier: Modifier = Modifier) {
    if (!AppBackgroundState.active) return
    FsdCustomBackground(
        uriString = AppBackgroundState.uri,
        isVideo = AppBackgroundState.isVideo,
        modifier = modifier
    )
}