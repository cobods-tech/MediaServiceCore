package com.liskovsoft.youtubeapi.browse.v2

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.common.helpers.PostDataType
import com.liskovsoft.youtubeapi.common.helpers.QueryBuilder

/**
 * Raw HTTP observation for the CCTV trending-source spike.
 *
 * This deliberately bypasses the production getTrending() path and performs one request only.
 * Callers own scheduling, backoff, playback checks, and acceptance decisions.
 */
data class TrendingProbeHttpResponse(
    val startedAtEpochMs: Long,
    val httpStatus: Int,
    val durationMs: Long,
    val itemCount: Int,
    val missingVideoIdCount: Int,
    val visitorDataPresent: Boolean,
    val videoIds: List<String>,
    val responseBody: String?,
    val errorBody: String?
)

object TrendingProbeService {
    private val api by lazy { RetrofitHelper.create(BrowseApi::class.java) }

    /**
     * Fetch one candidate browse destination for one region.
     *
     * [visitorData] must be the same value for related destination, shelf-more, and continuation
     * requests in a probe session. [languageCode] defaults to Simplified Chinese independently
     * from [regionCode], which controls InnerTube client.gl / acceptRegion.
     */
    @JvmStatic
    @JvmOverloads
    fun browse(
        browseId: String,
        regionCode: String,
        visitorData: String,
        params: String? = null,
        languageCode: String = "zh-CN",
        client: AppClient = AppClient.WEB
    ): TrendingProbeHttpResponse {
        require(browseId.isNotBlank()) { "browseId must not be blank" }
        require(regionCode.matches(Regex("[A-Z]{2}"))) { "regionCode must be ISO-3166 alpha-2 uppercase" }
        require(visitorData.isNotBlank()) { "visitorData must be supplied for a stable probe session" }
        require(languageCode.isNotBlank()) { "languageCode must not be blank" }

        val query = QueryBuilder(client)
            .setType(PostDataType.Browse)
            .setBrowseId(browseId)
            .setParams(params)
            .setCountry(regionCode)
            .setLanguage(languageCode)
            .setVisitorData(visitorData)
            .build()

        return execute(query, visitorData.isNotBlank())
    }

    /**
     * Fetch a response-provided continuation token while preserving the same region, language,
     * visitor identity, and client used by the initial request.
     */
    @JvmStatic
    @JvmOverloads
    fun continueBrowse(
        continuation: String,
        regionCode: String,
        visitorData: String,
        languageCode: String = "zh-CN",
        client: AppClient = AppClient.WEB
    ): TrendingProbeHttpResponse {
        require(continuation.isNotBlank()) { "continuation must not be blank" }
        require(regionCode.matches(Regex("[A-Z]{2}"))) { "regionCode must be ISO-3166 alpha-2 uppercase" }
        require(visitorData.isNotBlank()) { "visitorData must be supplied for a stable probe session" }
        require(languageCode.isNotBlank()) { "languageCode must not be blank" }

        val query = QueryBuilder(client)
            .setType(PostDataType.Browse)
            .setContinuationId(continuation)
            .setCountry(regionCode)
            .setLanguage(languageCode)
            .setVisitorData(visitorData)
            .build()

        return execute(query, visitorData.isNotBlank())
    }

    private fun execute(query: String, visitorDataPresent: Boolean): TrendingProbeHttpResponse {
        val call = api.getBrowseResultRaw(query)
        // The diagnostic is anonymous; never attach the app's account authorization.
        RetrofitOkHttpHelper.addAuthSkip(call.request())

        val startedAtEpochMs = System.currentTimeMillis()
        val startedAtNanos = System.nanoTime()
        val response = call.execute()
        val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

        val body = response.body()?.use { it.string() }
        val error = response.errorBody()?.use { it.string() }
        val (ids, missingIds) = extractVideoIds(body ?: error)

        return TrendingProbeHttpResponse(
            startedAtEpochMs = startedAtEpochMs,
            httpStatus = response.code(),
            durationMs = durationMs,
            itemCount = ids.size + missingIds,
            missingVideoIdCount = missingIds,
            visitorDataPresent = visitorDataPresent,
            videoIds = ids,
            responseBody = body,
            errorBody = error
        )
    }

    /**
     * Counts videoRenderer/lockup videoId fields without assuming a particular Explore layout.
     * This is a diagnostic count, not a claim that every returned video is a ranked item.
     */
    private fun extractVideoIds(raw: String?): Pair<List<String>, Int> {
        if (raw.isNullOrBlank()) return emptyList<String>() to 0
        return try {
            val ids = LinkedHashSet<String>()
            var missing = 0
            fun visit(element: JsonElement?) {
                if (element == null || element.isJsonNull) return
                when {
                    element.isJsonObject -> {
                        val obj = element.asJsonObject
                        if (obj.has("videoId")) {
                            val id = runCatching { obj.get("videoId").asString }.getOrNull()
                            if (id.isNullOrBlank()) missing++ else ids.add(id)
                        }
                        obj.entrySet().forEach { visit(it.value) }
                    }
                    element.isJsonArray -> element.asJsonArray.forEach { visit(it) }
                }
            }
            visit(JsonParser().parse(raw))
            ids.toList() to missing
        } catch (_: Exception) {
            emptyList<String>() to 0
        }
    }
}
