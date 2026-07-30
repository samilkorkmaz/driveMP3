package com.drivemp3.player.data

import kotlinx.serialization.Serializable

@Serializable
data class DriveFileListResponse(
    val nextPageToken: String? = null,
    val files: List<DriveFile> = emptyList(),
)

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    /** Drive encodes int64 fields as strings. Absent for files with no binary payload. */
    val size: String? = null,
    /** RFC 3339, e.g. `2026-01-14T09:31:07.000Z`. */
    val createdTime: String? = null,
)

@Serializable
data class AboutResponse(
    val user: DriveUser? = null,
)

@Serializable
data class DriveUser(
    val displayName: String? = null,
    val emailAddress: String? = null,
)
