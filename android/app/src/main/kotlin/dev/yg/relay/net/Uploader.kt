package dev.yg.relay.net

import dev.yg.relay.model.Batch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** 一次上传的结果。调用方据此决定「确认出队 / 保留重试 / 停下来等人修配置」。 */
sealed interface UploadResult {
    /** 2xx：服务端已收下（重复的会被它自己去重）。 */
    data object Ok : UploadResult

    /** 401/403：令牌不对，重试多少次都没用，必须让用户去改设置。 */
    data object Unauthorized : UploadResult

    /** 网络错误、5xx、408/429：值得过一会儿再来。 */
    data object Retry : UploadResult

    /** 4xx 里那些改都改不了的错误（比如地址打错被重定向成 404）。 */
    data class Fatal(val message: String) : UploadResult
}

/**
 * HTTP 上传。
 *
 * 刻意做成「一次调用一次请求」的薄封装：重试策略在 WorkManager 那边，
 * 这里只负责把结果翻译成三种语义（成功 / 该重试 / 别重试了）。
 * 正文用 gzip：一条通知几百字节，但一次批量可能几十条，压缩比通常 3~5 倍。
 */
class Uploader(private val client: OkHttpClient = defaultClient()) {

    suspend fun upload(serverUrl: String, token: String, batch: Batch): UploadResult =
        withContext(Dispatchers.IO) {
            val url = serverUrl.trimEnd('/') + INGEST_PATH
            val payload = json.encodeToString(Batch.serializer(), batch)
            val body = gzip(payload.toByteArray(Charsets.UTF_8)).toRequestBody(JSON_MEDIA)

            val request = try {
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Encoding", "gzip")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .post(body)
                    .build()
            } catch (error: IllegalArgumentException) {
                return@withContext UploadResult.Fatal("服务器地址不合法：${error.message.orEmpty()}")
            }

            runCatching { client.newCall(request).execute() }.fold(
                onSuccess = { response ->
                    response.use { r ->
                        when {
                            r.isSuccessful -> UploadResult.Ok
                            r.code == 401 || r.code == 403 -> UploadResult.Unauthorized
                            r.code == 408 || r.code == 429 || r.code >= 500 -> UploadResult.Retry
                            r.code >= 400 -> UploadResult.Fatal("服务器返回 ${r.code}")
                            else -> UploadResult.Retry
                        }
                    }
                },
                onFailure = { UploadResult.Retry },
            )
        }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    companion object {
        const val INGEST_PATH = "/api/v1/ingest"
        private const val USER_AGENT = "yg-relay-android/1.0"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 服务器可能跑在很慢的家宽/小机器上，写超时给宽一点。
            .writeTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
