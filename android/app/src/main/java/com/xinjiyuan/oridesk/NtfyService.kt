package com.xinjiyuan.oridesk

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ntfy 订阅前台服务（**方案 B**，决策 13 / 契约 §4.2）。
 *
 * 这是"我们自己的 App 收推送"的全部实现。选 B 而不是依赖官方 ntfy App，
 * 首要原因是**角标**：Android 的通知角标按 App 图标归属，通知由别的 App 发出，
 * 角标就只长在它的图标上，我们的图标永远是空的。
 *
 * ## 连接方式
 * `GET {ntfyServer}/{topic}/json`，逐行读 ndjson（官方推荐的订阅方式），
 * 只处理 `event == "message"`，忽略 `open` / `keepalive`。
 *
 * ## 断线补齐（本文件最重要的一段逻辑）
 * 重连时带 `since=<上一条消息 id>`。ntfy 默认缓存消息 12 小时，
 * 因此手机被 ROM 杀掉、锁屏断网、切基站之后，错过的推送**能补回来**。
 * 这正是选 ntfy 而不是裸 WebSocket 的主要收益：在"进程被杀是常态"的环境里，
 * "能补齐"比"够实时"重要得多。
 * 首次连接**不带** `since`：否则会把整个缓存重放一遍，变成一次通知轰炸。
 *
 * ## 已知代价（决策时明确接受）
 * 国产 ROM 会杀后台，本服务可能被清掉。缓解手段只有引导用户加入电池优化白名单
 * （见设置页），无法根除。前台服务类型用 `specialUse` 而非 `dataSync`：
 * Android 15 对 dataSync 有"每 24 小时累计 6 小时"的上限，对 7×24 推送是致命的
 * （见 AndroidManifest.xml 的说明）。
 */
class NtfyService : Service() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var currentBackoffMs = INITIAL_BACKOFF_MS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        prefs = Prefs(this)
    }

    private lateinit var prefs: Prefs

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.pushEnabled || !prefs.hasSubscription) {
            Log.i(TAG, "推送未启用或未注册订阅，服务不启动")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) return START_STICKY

        goForeground(getString(R.string.notif_service_text))
        running.set(true)
        worker = Thread({ loop() }, "ntfy-stream").also { it.start() }
        // START_STICKY：被系统回收后自动重建。真正的存活仍受 ROM 后台策略影响。
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val notification = Notifications.buildServiceNotification(this, text)
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.SERVICE_NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            // 例如用户在系统设置里禁用了本应用的前台服务。不能让进程崩在这里 ——
            // 否则表现为"一开推送就闪退"，比"推送不工作"更难查。
            Log.e(TAG, "startForeground 失败", t)
            stopSelf()
        }
    }

    private fun loop() {
        while (running.get()) {
            try {
                connectAndRead()
                // 正常返回（连接被服务端关闭）：立刻重连，不惩罚性退避。
                currentBackoffMs = INITIAL_BACKOFF_MS
            } catch (t: InterruptedException) {
                return
            } catch (t: Throwable) {
                if (!running.get()) return
                Log.w(TAG, "订阅连接中断，${currentBackoffMs}ms 后重连", t)
                updateForeground(getString(R.string.notif_service_text) + "（重连中…）")
                try {
                    Thread.sleep(currentBackoffMs.toLong())
                } catch (e: InterruptedException) {
                    return
                }
                // 指数退避但封顶：服务器长时间不可用时不要变成忙轮询，
                // 也不能退避到"恢复后半小时才重连"。
                currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** 打开长连接并按行读取。连接存续期间一直阻塞在此。 */
    private fun connectAndRead() {
        val server = prefs.ntfyServer ?: return
        val topic = prefs.topic ?: return
        val since = prefs.lastMessageId

        val url = buildString {
            append(server.trimEnd('/'))
            append('/').append(topic).append("/json")
            // 首次连接不带 since：否则会把 12 小时缓存全部重放成通知。
            if (!since.isNullOrEmpty()) append("?since=").append(since)
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            // 读超时留长：keepalive 间隔由服务端决定，太短会反复误判断线。
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/x-ndjson")
            prefs.ntfyToken?.takeIf { it.isNotEmpty() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }

        try {
            val status = conn.responseCode
            if (status == 401 || status == 403) {
                // 令牌无效：这是配置问题，不是网络抖动。继续疯狂重连没有意义，
                // 记日志并退避（用户改好配置后会自然恢复）。
                throw IllegalStateException("ntfy 鉴权失败（HTTP $status），请检查访问令牌")
            }
            if (status !in 200..299) {
                throw IllegalStateException("ntfy 返回 HTTP $status")
            }

            updateForeground(getString(R.string.notif_service_text))
            currentBackoffMs = INITIAL_BACKOFF_MS

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null && running.get()) {
                    if (line.isNotBlank()) handleLine(line)
                    line = reader.readLine()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun handleLine(line: String) {
        val payload = try {
            JSONObject(line)
        } catch (t: Throwable) {
            Log.w(TAG, "无法解析 ntfy 消息，已跳过")
            return
        }
        if (payload.optString("event") != "message") return

        val id = payload.optString("id").takeIf { it.isNotEmpty() }
        // 幂等：since 回补时可能重复收到同一条。
        if (id != null && id == prefs.lastMessageId) return

        val title = payload.optString("title").ifBlank { getString(R.string.app_name) }
        val body = payload.optString("message")
        val ticketId = resolveTicketId(title, payload.optString("click"))

        // 角标取服务端的 awaiting（契约 §3.1：与收件箱 chips 同口径）。
        // 取不到就退化成 1：宁可角标数字不准，也不能因为一次 API 失败就不发通知。
        val badge = fetchBadge(fallback = 1)

        Notifications.showTicket(this, ticketId, title, body, badge)

        if (id != null) prefs.lastMessageId = id
    }

    /**
     * 定位工单号。
     *
     * 优先用服务端下发的 `click`（`oridesk://ticket/N`）——它是**服务端**的权威表述；
     * 退而从标题里的 `T#N` 提取。两者都拿不到就返回 0，通知照发，点开进首页。
     */
    private fun resolveTicketId(title: String, click: String): Long {
        UrlRules.parseTicketId(click)?.let { return it }
        return TICKET_IN_TITLE.find(title)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun fetchBadge(fallback: Int): Int {
        val server = prefs.serverUrl
        if (server.isEmpty()) return fallback
        return try {
            val json = ApiClient(server).badge()
            json.optInt("awaiting", fallback).takeIf { it >= 0 } ?: fallback
        } catch (t: Throwable) {
            Log.i(TAG, "取角标失败，本次用 $fallback 兜底", t)
            fallback
        }
    }

    private fun updateForeground(text: String) {
        try {
            val notification = Notifications.buildServiceNotification(this, text)
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, Notifications.SERVICE_NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            Log.w(TAG, "更新前台通知失败", t)
        }
    }

    companion object {
        private const val TAG = "NtfyService"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20 * 60 * 1000
        private const val INITIAL_BACKOFF_MS = 1_000
        private const val MAX_BACKOFF_MS = 60_000

        private val TICKET_IN_TITLE = Regex("""T#(\d+)""")

        /** 启动订阅服务。未配置/未启用时服务会自行退出。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NtfyService::class.java),
                )
            } catch (t: Throwable) {
                // Android 12+ 在应用处于后台时禁止启动前台服务。
                // 这是"开机自启"路径上的真实可能，不能让开机广播接收器崩掉。
                Log.w(TAG, "启动推送服务失败（可能被后台启动限制拦截）", t)
            }
        }

        /** 停止订阅服务（用户关掉推送开关时调用）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, NtfyService::class.java))
        }
    }
}
