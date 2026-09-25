package com.teachermovies.mobile.api.fake

import com.teachermovies.mobile.api.AddMagnetOutcome
import com.teachermovies.mobile.api.ApiResult
import com.teachermovies.mobile.api.PairOutcome
import com.teachermovies.mobile.api.TorrentSummary
import com.teachermovies.mobile.api.TvApi
import com.teachermovies.mobile.api.TvStatus

/**
 * Scripted [TvApi] (ADR-0003): each call returns the matching `*Result` property as it is at call
 * time and is appended to [calls], in order, with its arguments. Defaults: a running TV with no
 * torrents that pairs with any PIN and accepts any magnet.
 */
class FakeTvApi(
    var statusResult: ApiResult<TvStatus> =
        ApiResult.Success(
            TvStatus(version = "fake", engine = "running", freeBytes = null, totalBytes = null, torrents = 0),
        ),
    var pairResult: PairOutcome = PairOutcome.Paired("fake-token"),
    var torrentsResult: ApiResult<List<TorrentSummary>> = ApiResult.Success(emptyList()),
    var addMagnetResult: AddMagnetOutcome = AddMagnetOutcome.Added(id = "fake-id", state = "fetching_metadata"),
) : TvApi {
    /** One recorded call. */
    sealed interface Call {
        data class Status(
            val baseUrl: String,
        ) : Call

        data class Pair(
            val baseUrl: String,
            val pin: String,
            val deviceName: String,
        ) : Call

        data class Torrents(
            val baseUrl: String,
            val token: String,
        ) : Call

        data class AddMagnet(
            val baseUrl: String,
            val token: String,
            val magnet: String,
        ) : Call
    }

    private val recorded = mutableListOf<Call>()

    /** Every call so far, oldest first. */
    val calls: List<Call>
        get() = synchronized(recorded) { recorded.toList() }

    private fun record(call: Call) = synchronized(recorded) { recorded += call }

    override suspend fun status(baseUrl: String): ApiResult<TvStatus> {
        record(Call.Status(baseUrl))
        return statusResult
    }

    override suspend fun pair(
        baseUrl: String,
        pin: String,
        deviceName: String,
    ): PairOutcome {
        record(Call.Pair(baseUrl, pin, deviceName))
        return pairResult
    }

    override suspend fun torrents(
        baseUrl: String,
        token: String,
    ): ApiResult<List<TorrentSummary>> {
        record(Call.Torrents(baseUrl, token))
        return torrentsResult
    }

    override suspend fun addMagnet(
        baseUrl: String,
        token: String,
        magnet: String,
    ): AddMagnetOutcome {
        record(Call.AddMagnet(baseUrl, token, magnet))
        return addMagnetResult
    }
}
