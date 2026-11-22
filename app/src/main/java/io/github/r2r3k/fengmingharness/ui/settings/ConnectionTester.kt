package io.github.r2r3k.fengmingharness.ui.settings

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 只做一件事：用一个很小、很快的请求验证 baseUrl + key 是否可用。
 * 返回的字符串是直接展示给用户看的话，不要往里塞异常类名。
 */
object ConnectionTester {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun test(baseUrl: String, apiKey: String): String {
        val url = normalizeBaseUrl(baseUrl)
        val key = apiKey.trim()

        // 先把最常见的手滑拦住，别等报错
        if (url.startsWith("sk-") || url.startsWith("sk_")) {
            return "❌ 你把 API Key 贴到 Base URL 里了，网址应该填 https://api.deepseek.com"
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "❌ Base URL 要以 http:// 或 https:// 开头"
        }
        if (key.isEmpty()) {
            return "❌ 还没填 API Key"
        }

        return try {
            val request = Request.Builder()
                .url("$url/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> "✅ 连接成功"
                    response.code == 401 -> "❌ Key 无效或已过期（401），去 DeepSeek 后台核对一下"
                    response.code == 402 -> "❌ 账户余额不足（402），去平台充点钱"
                    response.code == 403 -> "❌ 这个 Key 没有权限（403）"
                    response.code == 404 -> "❌ 网址不对（404），DeepSeek 官方是 https://api.deepseek.com"
                    response.code == 429 -> "❌ 请求太频繁（429），歇一会儿再试"
                    response.code in 500..599 -> "❌ DeepSeek 服务端出问题了（${response.code}），过会儿再试"
                    else -> "❌ 连接失败（${response.code}）"
                }
            }
        } catch (e: UnknownHostException) {
            "❌ 找不到这个网址，检查拼写和手机网络"
        } catch (e: SocketTimeoutException) {
            "❌ 连接超时，换个网络试试"
        } catch (e: SSLException) {
            "❌ 安全连接失败，可能是网络被拦截了"
        } catch (e: IllegalArgumentException) {
            "❌ 网址格式不对，应该形如 https://api.deepseek.com"
        } catch (e: Exception) {
            "❌ 连接失败：${e.message ?: "未知错误"}"
        }
    }

    /** 用户手打网址常常漏 http://，这里帮他补上 */
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return "https://api.deepseek.com"
        while (s.endsWith("/")) s = s.dropLast(1)
        if (!s.startsWith("http://") && !s.startsWith("https://") && !s.startsWith("sk-")) {
            s = "https://$s"
        }
        return s
    }
}
