package com.teachermovies.tv.ui

import androidx.annotation.StringRes
import com.teachermovies.tv.R

/**
 * The sections of the shell, in the order the tab row shows them. Declaration order is navigation
 * order: LEFT/RIGHT walks [entries], so a new section is inserted where it belongs, not appended.
 */
enum class Destination(@StringRes val titleRes: Int) {
    Library(R.string.destination_library),
    Downloads(R.string.destination_downloads),
    Settings(R.string.destination_settings),
}
