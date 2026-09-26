package com.xinjiyuan.oridesk

import android.util.Log
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 移动端端点客户端（契约 §2、§3 的 E1/E2/E3/E6）。
 *
 * ## 认证：复用 WebView 的会话
 * 没有独立认证。所有请求带 WebView 登录后拿到的 `sessionid` cookie（决策 4）。
 *
 * ## 最容易踩的坑：HttpURLConnection 不会碰 CookieManager
 * `android.webkit.CookieManager` **不是** `java.net.CookieHandler`，框架不会自动
 * 把响应的 `Set-Cookie` 存进去，也不会自动带上 cookie。所以本类必须手动做两件事：
 *   1. 发请求前从 CookieManager 读 cookie 塞进 `Cookie` 头；
 *   2. 收到响应后把 `Set-Cookie` 写回 CookieManager。
 * 漏掉第 2 步的典型症状：E1 明明下发了 `csrftoken`，写操作却永远 403 `csrf_failed`，
 * 而且看起来"cookie 明明在 WebView 里有"。
 *
 * ## CSRF
 * 契约 §2.3：保留 CSRF，用 `X-CSRFToken` 头。App 先调 E1（该端点带
 * `@ensure_csrf_cookie`，决策 D9）拿到 cookie；万一令牌过期（403 `csrf_failed`），
 * **重取一次再重试一次**，这是契约规定的 App 行为。
 *
 * 所有方法都是阻塞的，调用方必须放到后台线程（见 [Async]）。
 */
class ApiClient(private val server: String) {

    class ApiException(
        val code: String,
        val userMessage: String,
        val httpStatus: Int,
    ) : Exception(userMessage)

    private val base: String = server.trimEnd('/')

    /** 会话是否还在（粗判：cookie 里有 sessionid）。用于决定是否显示登录引导。 */
    val hasSessionCookie: Boolean
        get() = CookieManager.getInstance().getCookie(base)?.contains("sessionid=") == true

    // ---------------------------------------------------------------- E1
    fun badge(): JSONObject = request("GET", "/api/mobile/badge/", null, needCsrf = false)

    // ---------------------------------------------------------------- E2
    fun listSubscriptions(): JSONObject =
        request("GET", "/api/mobile/subscriptions/", null, needCsrf = false)

    // ---------------------------------------------------------------- E3
    /**
     * 注册/续订本设备。
     *
     * [existingTopic] 是**服务端此前下发给本机**的 topic，回显它可实现幂等续订
     * （契约 §10.1）。传 null 表示首次注册，由服务端生成新 topic。
     * 注意 App **不能自造** topic —— 自造的值服务端查不到属主，会返回 404。
     */
    fun createSubscription(deviceLabel: String, existingTopic: String?): JSONObject {
        val body = JSONObject().apply {
            put("device_label", deviceLabel)
            if (!existingTopic.isNullOrEmpty()) put("topic", existingTopic)
        }
        return request("POST", "/api/mobile/subscriptions/", body, needCsrf = true)
    }

    // ---------------------------------------------------------------- E4
    /**
     * 开关某台设备的服务端推送。
     *
     * 用户在设置里关掉推送时调用，让后端别再往这个 topic 发 —— 否则服务端会
     * 继续为一个没人听的频道发布通知，既浪费也在运维上看不出设备已下线。
     */
    fun setSubscriptionEnabled(subscriptionId: Long, enabled: Boolean): JSONObject {
        val body = JSONObject().apply { put("enabled", enabled) }
        return request(
            "PATCH",
            "/api/mobile/subscriptions/$subscriptionId/",
            body,
            needCsrf = true,
        )
    }

    // ---------------------------------------------------------------- E6
    fun testPush(subscriptionId: Long): JSONObject =
        request(
            "POST",
            "/api/mobile/subscriptions/$subscriptionId/test/",
            JSONObject(),
            needCsrf = true,
        )

    // ---------------------------------------------------------------- 底层
    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        needCsrf: Boolean,
    ): JSONObject {
        val url = base + path
        var attempt = 0
        while (true) {
            attempt++
            val conn = open(url, method, body, needCsrf)
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                absorbCookies(url, conn)
                val text = readBody(conn, status)

                if (status in 200..299) {
                    return if (text.isBlank()) JSONObject() else JSONObject(text)
                }

                val error = parseError(text, status)
                // 契约 §3.0：403 csrf_failed 时重取令牌并重试一次（只一次，避免死循环）。
                if (status == 403 && error.code == "csrf_failed" && needCsrf && attempt == 1) {
                    Log.i(TAG, "CSRF 令牌失效，重新获取后重试一次")
                    warmUpCsrf(url)
                    continue
                }
                throw error
            } catch (e: ApiException) {
                throw e
            } catch (t: Throwable) {
                throw ApiException("network_error", "网络请求失败：${t.message ?: t.javaClass.simpleName}", -1)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun open(url: String, method: String, body: JSONObject?, needCsrf: Boolean) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // 不自动跟随重定向：未登录时服务端若返回 302，跟随会拿到登录页 HTML，
            // 那正是我们要避免的"把 HTML 当 JSON"。
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            cookieHeader(url)?.let { setRequestProperty("Cookie", it) }
            if (needCsrf) csrfToken(url)?.let { setRequestProperty("X-CSRFToken", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

    private fun readBody(conn: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun cookieHeader(url: String): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }

    private fun csrfToken(url: String): String? =
        cookieHeader(url)
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("csrftoken=") }
            ?.substringAfter('=')

    /**
     * 把响应里的 `Set-Cookie` 写回 CookieManager。
     *
     * 这一步不做的话，E1 下发的 `csrftoken` 就丢了，后续写操作必然 403。
     */
    private fun absorbCookies(url: String, conn: HttpURLConnection) {
        val manager = CookieManager.getInstance()
        conn.headerFields?.forEach { (name, values) ->
            if (name != null && name.equals("Set-Cookie", ignoreCase = true)) {
                values?.forEach { manager.setCookie(url, it) }
            }
        }
        manager.flush()
    }

    /** 调一次 E1 强制服务端重新下发 CSRF cookie。失败也无妨：重试会给出真实错误。 */
    private fun warmUpCsrf(url: String) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                cookieHeader(url)?.let { setRequestProperty("Cookie", it) }
            }
            try {
                conn.responseCode
                absorbCookies(url, conn)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "重新获取 CSRF 令牌失败", t)
        }
    }

    private fun parseError(text: String, status: Int): ApiException {
        var code: String? = null
        var message: String? = null
        try {
            val envelope = JSONObject(text).optJSONObject("error")
            code = envelope?.optString("code")?.takeIf { it.isNotBlank() }
            message = envelope?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            // 服务端返回的不是 JSON（例如反代直接吐了 HTML 错误页）。
            // 不要把它当成正常响应，也不要把 HTML 展示给用户。
        }
        return when (status) {
            401 -> ApiException("unauthorized", "登录状态已失效，请重新登录。", status)
            404 -> ApiException("not_found", "对象不存在或无权访问。", status)
            405 -> ApiException("method_not_allowed", "请求方式不被支持。", status)
            else -> ApiException(
                code ?: "http_$status",
                message ?: "请求失败（HTTP $status）",
                status,
            )
        }
    }

    private companion object {
        const val TAG = "ApiClient"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
