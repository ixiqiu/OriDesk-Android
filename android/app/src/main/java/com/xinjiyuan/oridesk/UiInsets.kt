package com.xinjiyuan.oridesk

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 处理 edge-to-edge 的系统栏遮挡。
 *
 * ## 为什么必须处理
 * Android 15（targetSdk 35）**强制**应用 edge-to-edge：窗口会延伸到状态栏与导航栏
 * 底下。这不是主题写错了 —— 主题本身不是全屏主题，是系统不再允许"不 edge-to-edge"。
 * 症状很具体：网页的移动顶栏被状态栏压住，**最上面那排按钮点不到**。
 *
 * ## 为什么不用 `windowOptOutEdgeToEdgeEnforcement`
 * 那个开关只在 API 35 有效，且会被后续版本移除。用它等于把一个必然复发的 bug
 * 推给未来。正确做法是接受 edge-to-edge，然后**按 insets 给根布局加内边距** ——
 * 这样在 API 26–35 上行为一致。
 *
 * ## 为什么把输入法 insets 也算进去
 * edge-to-edge 下软键盘同样是覆盖式的。不处理的话，设置页填服务器地址时
 * 键盘会盖住输入框与"保存"按钮。
 */
object UiInsets {

    fun apply(activity: Activity, root: View) {
        // 显式声明不由系统自动加 inset，insets 交给我们自己消费。
        // 在 API 35 上这是默认行为，显式调用是为了在 26–34 上也一致。
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                // 取两者较大值：键盘弹出时底部让位给键盘，否则留给导航栏。
                maxOf(bars.bottom, ime.bottom),
            )
            insets
        }

        // 状态栏变成透明浮层后，图标颜色要跟着主题走，否则浅色背景下白图标看不见。
        val night = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        // 触发一次，避免首帧还没收到 insets 时内容贴在状态栏下。
        ViewCompat.requestApplyInsets(root)
    }
}
