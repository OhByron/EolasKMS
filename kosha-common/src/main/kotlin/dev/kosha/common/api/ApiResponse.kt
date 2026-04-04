package dev.kosha.common.api

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val data: T,
    val meta: PageMeta? = null,
    val links: Links? = null,
)

data class PageMeta(
    val page: Int,
    val size: Int,
    val total: Long,
)

data class Links(
    val self: String,
    val next: String? = null,
    val prev: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
)
