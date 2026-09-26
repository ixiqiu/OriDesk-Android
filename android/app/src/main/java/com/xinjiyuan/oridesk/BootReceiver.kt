package com.xinjiyuan.oridesk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启 / 覆盖安装后自动拉起推送服务。
 *
 * 为什么需要：方案 B 的推送依赖一个常驻长连接，重启手机后如果不自动拉起来，
 * 用户会以为"推送坏了"，而实际上只是没启动。这个接收器把这个坑补上。
 *
 * 注意：**能收到 BOOT_COMPLETED 不等于能把前台服务拉起来** ——
 * Android 12+ 限制后台启动前台服务，部分 ROM 更严。所以 [NtfyService.start]
 * 内部吞掉了启动异常；启动失败时用户下次打开应用也会把服务带起来。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant) return

        val prefs = Prefs(context)
        if (!prefs.pushEnabled || !prefs.hasSubscription) {
            Log.i(TAG, "推送未启用或未注册订阅，开机不拉起服务")
            return
        }
        Log.i(TAG, "开机/升级后拉起推送服务")
        NtfyService.start(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
