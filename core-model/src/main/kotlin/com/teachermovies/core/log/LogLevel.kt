package com.teachermovies.core.log

/**
 * How much a log line matters, least to most severe (ADR-0006). The order is what "INFO and above
 * in release builds, DEBUG in debug builds" means: a filter keeps every level at or after its
 * minimum.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    /** Whether a line at this level passes a filter that keeps [minimum] and everything above it. */
    fun isAtLeast(minimum: LogLevel): Boolean = ordinal >= minimum.ordinal
}
