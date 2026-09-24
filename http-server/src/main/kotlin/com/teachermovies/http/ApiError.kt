package com.teachermovies.http

import kotlinx.serialization.Serializable

/** Body of every error response: `{"error":"<code>","message":"..."}`. */
@Serializable
data class ApiError(
    val error: String,
    val message: String,
)
