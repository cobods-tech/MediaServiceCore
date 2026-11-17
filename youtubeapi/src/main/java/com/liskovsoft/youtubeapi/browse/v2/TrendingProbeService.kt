package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.common.helpers.PostDataType
import com.liskovsoft.youtubeapi.common.helpers.QueryBuilder

/**
 * Raw HTTP observation for the CCTV trending-source spike.
 *
 * This deliberately bypasses the production getTrending() path and performs one request only.
 * Callers own scheduling, backoff, parsing, playback checks, and acceptance decisions.
 */
data class TrendingProbeHttpResponse(
    val httpStatus: Int,
    val durationMs: Long,
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

        val call = api.getBrowseResultRaw(query)
        // The diagnostic is anonymous; never attach the app's account authorization.
        RetrofitOkHttpHelper.addAuthSkip(call.request())

        val startedAt = System.nanoTime()
        val response = call.execute()
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000L

        val body = response.body()?.use { it.string() }
        val error = response.errorBody()?.use { it.string() }

        return TrendingProbeHttpResponse(
            httpStatus = response.code(),
            durationMs = durationMs,
            responseBody = body,
            errorBody = error
        )
    }
}
