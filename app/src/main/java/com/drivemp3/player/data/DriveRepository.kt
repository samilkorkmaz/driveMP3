package com.drivemp3.player.data

class DriveRepository(private val api: DriveApi) {

    suspend fun currentUserEmail(accessToken: String): String? =
        api.about(bearer = bearer(accessToken), fields = ABOUT_FIELDS).user?.emailAddress

    /** First page of MP3 files across the whole Drive. Folder scoping and full paging land in v0.2. */
    suspend fun listMp3Files(accessToken: String): List<DriveFile> =
        api.listFiles(
            bearer = bearer(accessToken),
            query = MP3_QUERY,
            fields = FILE_FIELDS,
            pageSize = PAGE_SIZE,
            orderBy = "createdTime desc",
            pageToken = null,
        ).files

    private fun bearer(accessToken: String) = "Bearer $accessToken"

    companion object {
        /**
         * v0.1 matches on MIME type alone.
         *
         * The spec (FR-3.1.3) also allows matching names ending in `.mp3`, but
         * Drive's `contains` operator only does *prefix* matching on `name`, so
         * `name contains '.mp3'` does not reliably match `song.mp3`. From v0.2
         * queries are scoped to one folder, where every file can be listed and
         * filtered on `name.endsWith(".mp3")` client-side instead.
         */
        const val MP3_QUERY = "mimeType = 'audio/mpeg' and trashed = false"

        const val FILE_FIELDS = "nextPageToken,files(id,name,size,createdTime)"
        const val ABOUT_FIELDS = "user(displayName,emailAddress)"
        const val PAGE_SIZE = 100
    }
}
