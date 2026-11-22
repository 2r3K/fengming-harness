package io.github.r2r3k.fengmingharness.core.provider

import io.github.r2r3k.fengmingharness.ui.settings.ConnectionTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 拉取官方当前可用的模型列表（GET /models），这样以后官方改名、上新模型，
 * 你在设置里点一下"刷新"就能看到，不用等我重新打包。
 */
object ModelCatalog {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = ConnectionTester.normalizeBaseUrl(baseUrl).trimEnd('/') + "/models"
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = JSONObject(response.body?.string().orEmpty())
                    val data: JSONArray = body.optJSONArray("data") ?: JSONArray()
                    val ids = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotEmpty() && id != "null") ids += id
                    }
                    ids.sorted()
                }
            }
        }
}
