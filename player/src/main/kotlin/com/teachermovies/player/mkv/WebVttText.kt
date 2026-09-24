package com.teachermovies.player.mkv

/**
 * WebVTT cue text to SubRip cue text, for `S_TEXT/WEBVTT` blocks. In Matroska a WebVTT block
 * holds only the cue payload -- the cue settings (position, line, align) live in `BlockAdditions`
 * and the `STYLE`/`REGION` blocks in `CodecPrivate`, neither of which is read -- so all that is
 * left is the markup inside the text: `<i>`, `<b>` and `<u>` are kept (SRT has them, minus any
 * `.class`), every other tag (`<v Speaker>`, `<c.yellow>`, `<ruby>`, `<lang>`, timestamps) is
 * dropped with its text kept, and the character references are decoded.
 */
internal object WebVttText {
    fun toSrt(cueText: String): String {
        val withoutTags =
            TAG.replace(cueText) { match ->
                val closing = match.groupValues[1]
                val name = match.groupValues[2].lowercase()
                if (name in KEPT_TAGS) "<$closing$name>" else ""
            }
        return ENTITY.replace(withoutTags) { ENTITIES[it.value] ?: it.value }
    }

    /** `<tag>`, `</tag>`, `<tag.class annotation>` and `<00:00:01.000>` timestamps. */
    private val TAG = Regex("<(/?)([A-Za-z0-9:.]*?)(?:[.\\s][^>]*)?>")
    private val ENTITY = Regex("&[a-zA-Z]+;")
    private val KEPT_TAGS = setOf("i", "b", "u")
    private val ENTITIES =
        mapOf(
            "&amp;" to "&",
            "&lt;" to "<",
            "&gt;" to ">",
            "&nbsp;" to " ",
            "&lrm;" to "‎",
            "&rlm;" to "‏",
            "&quot;" to "\"",
            "&apos;" to "'",
        )
}
