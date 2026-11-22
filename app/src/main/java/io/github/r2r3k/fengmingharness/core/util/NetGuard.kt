package io.github.r2r3k.fengmingharness.core.util

/**
 * 网络地址的安全检查。
 *
 * 这里只关心一件事：**什么时候可以用明文 http**。
 * 局域网（自家 WiFi/热点）里用 http 无所谓；一旦是公网地址还用 http，
 * 你的 API Key 和聊天内容就是明文在大街上走。
 */
object NetGuard {

    /** 是不是本机或局域网地址 */
    fun isLocalHost(rawUrl: String): Boolean {
        val host = rawUrl.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
            .lowercase()

        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") return true
        if (host.endsWith(".local")) return true

        val parts = host.split('.')
        if (parts.size == 4) {
            val numbers = parts.map { it.toIntOrNull() ?: return false }
            if (numbers.any { it !in 0..255 }) return false
            val a = numbers[0]
            val b = numbers[1]
            return a == 127 ||
                a == 10 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254)
        }
        return false
    }

    /**
     * 返回一句人话的警告；没问题就返回 null。
     * @param what 这东西叫什么，用来拼提示（例如 "Base URL"）
     */
    fun cleartextWarning(rawUrl: String, what: String): String? {
        val url = rawUrl.trim()
        if (!url.startsWith("http://")) return null
        if (isLocalHost(url)) return null
        return "$what 是公网地址却用 http://，你的 Key 和聊天内容会明文传输。" +
            "请把 http:// 换成 https://（如果是自己局域网里的设备，请填局域网 IP，例如 192.168.x.x）"
    }
}
