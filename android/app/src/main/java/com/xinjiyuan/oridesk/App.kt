package com.xinjiyuan.oridesk

import android.app.Application
import android.webkit.CookieManager

/**
 * 应用入口。
 *
 * 通知渠道必须在**任何通知发出之前**建好，否则 Android 8+ 会直接丢弃通知
 * （而且是静默丢弃，不抛异常）。放在 Application 里建，比放在 Activity 里建更稳：
 * 推送服务可能在用户从没打开过界面的情况下就被开机广播拉起来。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        // 会话 cookie 由 WebView 与 ApiClient 共用，这里只确保开关打开。
        CookieManager.getInstance().setAcceptCookie(true)
    }
}
