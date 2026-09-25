package com.teachermovies.mobile.share

import com.teachermovies.mobile.send.SendOutcome

/**
 * What the share dialog shows (#199); produced only by [ShareViewModel]. Two states and nothing
 * else, because the dialog is not a screen the user drives: it reports the one send `ShareActivity`
 * started and closes. The Spanish texts are not here either -- `Enviando a la TV...` belongs to
 * `Sending` and [SendOutcome.message] to [Done], so no screen builds a string of its own.
 */
sealed interface ShareUiState {
    /**
     * Whether the dialog also offers `ABRIR MOVIE ASSISTANT`: only when the magnet went nowhere
     * because no usable TV is paired, which is the one case opening the app can fix.
     */
    val offersOpenApp: Boolean

    /** The magnet is on its way to the TV; nothing has come of it yet. */
    data object Sending : ShareUiState {
        override val offersOpenApp: Boolean
            get() = false
    }

    /** The send is over and [outcome] says what came of it; nothing is in flight any more. */
    data class Done(
        val outcome: SendOutcome,
    ) : ShareUiState {
        override val offersOpenApp: Boolean
            get() = outcome is SendOutcome.NotPaired || outcome is SendOutcome.NeedsPairing
    }
}
