package com.teachermovies.core.settings

/**
 * How the remote behaves on the player screen. [TEACHER] is the Teacher Movies mode: LEFT/DOWN go
 * back N phrases (English/Spanish), UP opens the captured-line menu. [NORMAL] is a plain player:
 * LEFT/RIGHT seek 10 s and the assistant keys do nothing. Holding UP toggles between the two.
 */
enum class PlayerMode {
    NORMAL,
    TEACHER,
    ;

    /** The other mode. */
    fun toggled(): PlayerMode = if (this == TEACHER) NORMAL else TEACHER
}
