package com.xinjiyuan.oridesk

import android.net.Uri

/**
 * URL 规则：服务器地址校验、工单网页地址、深链解析。
 *
 * 集中在单独一个文件是因为这三件事必须**互相对齐**：深链里带的工单号最终要拼成
 * 网页地址，两处各写一套解析就会在某个边界上不一致（比如尾斜杠、大小写 scheme）。
 */
object UrlRules {

    sealed class Result {
        data class Ok(val url: String) : Result()
        data class Err(val message: String) : Result()
    }

    /**
     * 校验并规范化用户填的服务器地址。
     *
     * **只接受 https**（契约 §7.3）。这不是洁癖：生产配置里
     * `SESSION_COOKIE_SECURE=True`，明文 HTTP 下会话 cookie 根本不会下发，
     * 现象是"怎么都登录不上"，而用户很难想到原因是自己少打了一个 s。
     * 在入口就明确拒绝，比事后排查便宜得多。
     */
    fun normalizeServer(input: String): Result {
        val raw = input.trim()
        if (raw.isEmpty()) return Result.Err("请填写服务器地址")
        if (raw.contains(' ')) return Result.Err("服务器地址不能包含空格")

        val lower = raw.lowercase()
        if (!lower.startsWith("https://")) {
            return Result.Err(
                if (lower.startsWith("http://")) {
                    "必须使用 https://（明文 HTTP 下无法登录）"
                } else {
                    "服务器地址必须以 https:// 开头"
                },
            )
        }

        val parsed = try {
            Uri.parse(raw)
        } catch (t: Throwable) {
            return Result.Err("服务器地址格式不正确")
        }

        val host = parsed.host
        if (host.isNullOrBlank()) return Result.Err("服务器地址缺少主机名")

        // 去掉尾斜杠，保证后续拼接不会出现 "//tickets"。
        val normalized = raw.trimEnd('/')
        return Result.Ok(normalized)
    }

    /** 工单的网页地址（用于 WebView 内导航，以及通知点开后的兜底目标）。 */
    fun ticketWebUrl(server: String, ticketId: Long): String =
        "${server.trimEnd('/')}/tickets/$ticketId/"

    /**
     * 深链（决策 D3）。
     *
     * 用自定义 scheme 而不是 https App Link：App Link 需要在清单里写死 host，
     * 而服务器地址是运行时才填的（决策 10），编译期不可能知道。
     */
    fun ticketDeepLink(ticketId: Long): String = "oridesk://ticket/$ticketId"

    /** 从 `oridesk://ticket/{id}` 解析工单号；不是本应用的深链则返回 null。 */
    fun parseTicketId(uri: Uri?): Long? {
        if (uri == null) return null
        if (!"oridesk".equals(uri.scheme, ignoreCase = true)) return null
        if (!"ticket".equals(uri.host, ignoreCase = true)) return null
        return uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** 供 ntfy 载荷里的 `click` 字段使用（后端发来的就是 `oridesk://ticket/N`）。 */
    fun parseTicketId(url: String?): Long? {
        if (url.isNullOrBlank()) return null
        return try {
            parseTicketId(Uri.parse(url))
        } catch (t: Throwable) {
            null
        }
    }
}
