package com.teachermovies.mobile.share

/**
 * Tells what text another app handed to the phone app, given only the three strings an `Intent`
 * carries for it: its action, `EXTRA_TEXT` and data URI. Pure Kotlin -- the two actions are
 * spelled out here instead of read off `android.content.Intent`, so nothing above `ShareActivity`
 * touches an Android type and this stays testable on the JVM.
 */
object SharedText {
    /** `Intent.ACTION_SEND`: a `text/plain` share from another app. */
    const val ACTION_SEND = "android.intent.action.SEND"

    /** `Intent.ACTION_VIEW`: the user opened a `magnet:` link. */
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /**
     * Returns [extraText] for [ACTION_SEND] and [dataString] for [ACTION_VIEW], `null` for any
     * other action including a null one. The text comes back exactly as it was shared, with no
     * trimming and no filtering: whether it holds a magnet the TV can take is
     * `SharedLinkParser`'s call, made by `MagnetSender`.
     */
    fun from(
        action: String?,
        extraText: String?,
        dataString: String?,
    ): String? =
        when (action) {
            ACTION_SEND -> extraText
            ACTION_VIEW -> dataString
            else -> null
        }
}
