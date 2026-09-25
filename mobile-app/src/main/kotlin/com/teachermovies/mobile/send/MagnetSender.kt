package com.teachermovies.mobile.send

import com.teachermovies.mobile.api.AddMagnetOutcome
import com.teachermovies.mobile.api.ApiFailure
import com.teachermovies.mobile.api.TvApi
import com.teachermovies.mobile.data.PairedTvStore
import com.teachermovies.mobile.share.SharedLinkParser
import kotlinx.coroutines.flow.first

/**
 * Sends a magnet link to the paired TV (#198): the one path the downloads screen's field uses and
 * the share intent filter will reuse. [send] never throws -- [TvApi] answers with sealed results and
 * every one of them becomes a [SendOutcome] -- and never calls the API when there is no magnet to
 * send or no TV to send it to. A 401 clears [store], which is what returns the app to pairing.
 */
class MagnetSender(
    private val api: TvApi,
    private val store: PairedTvStore,
) {
    /** Pulls the magnet out of [text] (typed or shared) and posts it to the paired TV. */
    suspend fun send(text: String?): SendOutcome {
        val magnet = SharedLinkParser.extractMagnet(text) ?: return SendOutcome.NoMagnet
        val tv = store.pairedTv.first() ?: return SendOutcome.NotPaired
        return when (val outcome = api.addMagnet(tv.baseUrl, tv.token, magnet)) {
            is AddMagnetOutcome.Added -> {
                SendOutcome.Sent(tv.instanceName)
            }

            is AddMagnetOutcome.AlreadyExists -> {
                SendOutcome.AlreadyOnTv(tv.instanceName)
            }

            AddMagnetOutcome.InvalidMagnet -> {
                SendOutcome.Rejected
            }

            is AddMagnetOutcome.Failed -> {
                if (outcome.failure == ApiFailure.Unauthorized) {
                    store.clear()
                    SendOutcome.NeedsPairing
                } else {
                    SendOutcome.Unreachable
                }
            }
        }
    }
}
