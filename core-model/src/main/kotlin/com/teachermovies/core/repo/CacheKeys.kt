package com.teachermovies.core.repo

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * The cache key rule both implementations share (#274): the same subtitle line reaches the panel with
 * whatever padding and line breaks the `.srt` file had, and it must hit one row, so surrounding
 * whitespace goes and runs of whitespace collapse to a single space.
 *
 * Case and punctuation are kept, deliberately: they change what a line means, and a translation cache
 * that folded them together would answer "polish" with the translation of "Polish". (The in-memory
 * `CachingTranslationProvider` in `:assistant` lowercases its own key; the persistent one does not,
 * and losing a hit is cheaper than serving a wrong answer.) The source text is its own primary key
 * (`TranslationCacheEntity`), so a readable `.db` dump stays readable.
 */
internal fun normalizeCacheText(text: String): String = text.trim().replace(WHITESPACE_RUN, " ")
