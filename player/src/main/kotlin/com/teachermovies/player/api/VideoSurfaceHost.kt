package com.teachermovies.player.api

import android.widget.FrameLayout

/**
 * The half of a [Player] that draws the picture: where the video surface lives.
 *
 * Kept apart from [Player] on purpose. A surface is a window, and only a screen has one -- the
 * assistant reading cues, a ViewModel, and every JVM test above `:player` program against [Player]
 * and never want a `View`. Splitting it is also what keeps the video surface inside `:player`: the
 * libVLC adapter creates an `org.videolan.libvlc.util.VLCVideoLayout` internally and hands out a
 * plain [FrameLayout] here, so no `org.videolan` type crosses this package and `:app-tv` never
 * imports one (ADR-0001 §2, `docs/modules/player.md`).
 *
 * The one implementation is `com.teachermovies.player.vlc.VlcPlayer`; `AppContainer` hands the same
 * instance out typed as [Player] and as [VideoSurfaceHost] (ADR-0003). `FakePlayer` deliberately
 * does not implement this interface -- a JVM test has no window to draw into.
 */
interface VideoSurfaceHost {
    /**
     * Starts drawing into [view], filling it. Calling it again moves the picture to the new [view]
     * and drops the previous one, so a screen can call it from its layout pass without checking.
     */
    fun attach(view: FrameLayout)

    /** Stops drawing and leaves the [attach]ed [FrameLayout] empty; a no-op when nothing is attached. */
    fun detach()
}
