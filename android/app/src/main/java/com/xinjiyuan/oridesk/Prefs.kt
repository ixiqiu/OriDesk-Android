package com.xinjiyuan.oridesk

import android.content.Context

/**
 * 本地配置（契约 §4.3）。
 *
 * 分级存储，不是一刀切：
 * - **ntfy topic 与 token 走 SecureStore 加密**。topic 本质就是密码 —— ntfy 没有
 *   注册机制，谁猜到 topic 谁就能收到推送。
 * - 服务器地址、设备名、开关明文存。它们不是凭据，加密只会让排查变难；
 *   而且服务器地址本来就要显示在设置页上（决策 10：运行时填写，不硬编码）。
 *
 * 注意：这里**不存会话 cookie**。会话归 WebView 的 CookieManager 管，
 * 挪一份出来的话，注销登录后本地还留着一份可用的凭据。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** OriDesk 服务器地址（已校验为 https，无尾斜杠）。空串表示尚未配置。 */
    var serverUrl: String
        get() = sp.getString(KEY_SERVER, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SERVER, value).apply()

    /** 展示给用户辨认的设备名（合同 §3.3 的 device_label）。 */
    var deviceLabel: String
        get() = sp.getString(KEY_LABEL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LABEL, value).apply()

    /** 用户是否希望接收推送。false 时服务不启动，且不注册订阅。 */
    var pushEnabled: Boolean
        get() = sp.getBoolean(KEY_PUSH, false)
        set(value) = sp.edit().putBoolean(KEY_PUSH, value).apply()

    /** ntfy topic（服务端 E3 下发）。**密文存储**。 */
    var topic: String?
        get() = SecureStore.decrypt(sp.getString(KEY_TOPIC, null))
        set(value) {
            sp.edit().putString(KEY_TOPIC, value?.takeIf { it.isNotEmpty() }?.let { SecureStore.encrypt(it) })
                .apply()
        }

    /** ntfy 访问令牌（可选，实例开启鉴权时需要）。**密文存储**。 */
    var ntfyToken: String?
        get() = SecureStore.decrypt(sp.getString(KEY_TOKEN, null))
        set(value) {
            sp.edit().putString(KEY_TOKEN, value?.takeIf { it.isNotEmpty() }?.let { SecureStore.encrypt(it) })
                .apply()
        }

    /** 订阅所属的 ntfy 实例地址（服务端下发，**未必**与 OriDesk 服务器地址相同）。 */
    var ntfyServer: String?
        get() = sp.getString(KEY_NTFY_SERVER, null)?.takeIf { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_NTFY_SERVER, value).apply()

    /** 服务端返回的订阅 id，E6 测试推送要用。 */
    var subscriptionId: Long
        get() = sp.getLong(KEY_SUB_ID, 0L)
        set(value) = sp.edit().putLong(KEY_SUB_ID, value).apply()

    /**
     * 最后收到的 ntfy 消息 id。
     *
     * 重连时带上 `since=<它>` 就能把断线期间错过的推送**补齐** —— 这是选 ntfy
     * 而不是裸 WebSocket 的主要收益（契约 §4.2），也是"进程被 ROM 杀掉"常态下
     * 唯一能救回漏报的机制。
     */
    var lastMessageId: String?
        get() = sp.getString(KEY_LAST_MSG, null)?.takeIf { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_LAST_MSG, value).apply()

    val isServerConfigured: Boolean get() = serverUrl.isNotEmpty()

    val hasSubscription: Boolean
        get() = !topic.isNullOrEmpty() && !ntfyServer.isNullOrEmpty()

    /**
     * 清空订阅相关的全部字段。
     *
     * 换服务器时必须调用：topic 是**上一个服务器**签发的，对新的 ntfy 实例无效，
     * 留着它会让 App 订阅一个永远不会有消息的频道，表现为"推送静默失效"。
     */
    fun clearSubscription() {
        sp.edit()
            .remove(KEY_TOPIC)
            .remove(KEY_TOKEN)
            .remove(KEY_NTFY_SERVER)
            .remove(KEY_SUB_ID)
            .remove(KEY_LAST_MSG)
            .apply()
    }

    private companion object {
        const val NAME = "oridesk_prefs"
        const val KEY_SERVER = "server_url"
        const val KEY_LABEL = "device_label"
        const val KEY_PUSH = "push_enabled"
        const val KEY_TOPIC = "ntfy_topic_enc"
        const val KEY_TOKEN = "ntfy_token_enc"
        const val KEY_NTFY_SERVER = "ntfy_server"
        const val KEY_SUB_ID = "subscription_id"
        const val KEY_LAST_MSG = "last_message_id"
    }
}
