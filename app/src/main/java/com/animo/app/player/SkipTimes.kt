package com.animo.app.player

import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Community opening/ending timestamps, keyed by MAL id + episode + episode length.
 *
 * Used only when the stream itself publishes no markers (Server 2, the Hindi catalogue): the
 * timestamps are crowd-sourced against a specific release, so the source's own markers always win.
 * The service matches the submitted episode length within a tolerance, which is what keeps a
 * different cut of the episode from inheriting the wrong times.
 *
 * Measured 2026-11-18: `{"found":true,"results":[{"interval":{"startTime":0,"endTime":96.25},
 * "skipType":"op"}, {"interval":{…},"skipType":"ed"}]}`.
 */
object SkipTimes {

    data class Markers(val intro: LongRange?, val outro: LongRange?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): Markers? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ((root["found"] as? JsonPrimitive)?.contentOrNull != "true") return null
        val results = root["results"] as? JsonArray ?: return null
        var intro: LongRange? = null
        var outro: LongRange? = null
        for (element in results) {
            val result = element as? JsonObject ?: continue
            val interval = result["interval"] as? JsonObject ?: continue
            val start = (interval["startTime"] as? JsonPrimitive)?.doubleOrNull ?: continue
            val end = (interval["endTime"] as? JsonPrimitive)?.doubleOrNull ?: continue
            if (end <= start) continue
            val range = (start * 1000).toLong() until (end * 1000).toLong()
            when ((result["skipType"] as? JsonPrimitive)?.contentOrNull) {
                "op" -> if (intro == null) intro = range
                "ed" -> if (outro == null) outro = range
            }
        }
        return if (intro == null && outro == null) null else Markers(intro, outro)
    }

    suspend fun fetch(
        client: OkHttpClient,
        malId: Int,
        episode: Int,
        episodeLengthMs: Long,
        api: String = ProviderVault.optionalUrl(ProviderKey.SKIP_TIMES_API).orEmpty(),
    ): Markers? = withContext(Dispatchers.IO) {
        if (api.isBlank() || malId <= 0 || episode <= 0 || episodeLengthMs <= 0L) return@withContext null
        val url = "${api.trimEnd('/')}/v2/skip-times/$malId/$episode".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("types[]", "op")
            ?.addQueryParameter("types[]", "ed")
            ?.addQueryParameter("episodeLength", (episodeLengthMs / 1000.0).toString())
            ?.build() ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute().use { response ->
                    if (!response.isSuccessful) null else parse(response.body?.string().orEmpty())
                }
        }.getOrNull()
    }
}
