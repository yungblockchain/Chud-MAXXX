package dev.oxyroid.parser.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import okhttp3.OkHttpClient
import okhttp3.Request

internal class XtreamParserUtils(
    private val json: Json,
    private val okHttpClient: OkHttpClient,
) {
    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> newCall(url: String): T? = withContext(Dispatchers.IO) {
        okHttpClient.newCall(Request.Builder().url(url).build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.byteStream()?.use { input ->
                    json.decodeFromStream<T>(input)
                }
            }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> newCallOrThrow(url: String): T =
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).build())
                .execute()
                .use { response ->
                    check(response.isSuccessful) { "Request failed: ${response.code}" }
                    response.body?.byteStream()?.use { input ->
                        json.decodeFromStream<T>(input)
                    } ?: error("Empty response body")
                }
        }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> newSequenceCall(url: String): Sequence<T> = sequence {
        val call = okHttpClient.newCall(Request.Builder().url(url).build())
        call.execute().use { response ->
            if (!response.isSuccessful) return@use
            response.body?.byteStream()?.use { input ->
                json.decodeToSequence<T>(input).forEach { item ->
                    yield(item)
                }
            }
        }
    }
}
