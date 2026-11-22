package io.github.r2r3k.fengmingharness.core.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Files API：图片只上传一次，拿到 file-api-xxx 编号，之后每轮对话只带编号。
 * 这样既绕开了"单次请求 48MB"的限制，也不用每轮重传图片。
 * 官方规则：图片单文件 ≤ 64MB；账户存储 25GB / 1 万个文件；不设过期就是永久。
 */
object FilesApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun upload(
        baseUrl: String,
        apiKey: String,
        bytes: ByteArray,
        filename: String,
        purpose: String = "user_data",
        mime: String = "image/jpeg",
    ): Result<String> =
        runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("purpose", purpose)
                .addFormDataPart(
                    "file",
                    filename,
                    bytes.toRequestBody(mime.toMediaType()),
                )
                .build()

            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/files")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("上传失败 HTTP ${response.code}：" + text.take(160))
                }
                val id = JSONObject(text).optString("id").orEmpty()
                if (id.isEmpty() || id == "null") error("上传成功但没拿到文件编号")
                id
            }
        }

    fun delete(baseUrl: String, apiKey: String, fileId: String): Result<Unit> = runCatching {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/files/$fileId")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
    }
}
