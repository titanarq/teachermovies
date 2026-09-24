package com.teachermovies.assistant.speech

/** Pure rules shared by every [Speaker] implementation, kept here so they are JVM-testable. */
object SpeechRequests {
    private const val UTTERANCE_PREFIX = "teachermovies-utterance-"

    /** A stable id for utterance number [seq]: the same [seq] always gives the same id, distinct ones never collide. */
    fun utteranceId(seq: Long): String = UTTERANCE_PREFIX + seq

    /**
     * Whether [text] has anything to say: `false` for blank text and for text made only of
     * punctuation, symbols and subtitle artefacts such as `-`, `...` or `♪`. Anything with at
     * least one letter or digit is speakable.
     */
    fun isSpeakable(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }
}
