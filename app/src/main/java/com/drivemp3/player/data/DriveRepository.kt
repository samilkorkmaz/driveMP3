package com.drivemp3.player.data

import com.drivemp3.player.model.LibraryScope

class DriveRepository(private val api: DriveApi) {

    suspend fun currentUserEmail(accessToken: String): String? =
        api.about(bearer = bearer(accessToken), fields = ABOUT_FIELDS).user?.emailAddress

    /** Direct subfolders of [parentId], for the folder picker. Use [ROOT_FOLDER_ID] to start. */
    suspend fun listSubfolders(accessToken: String, parentId: String): List<DriveFile> =
        fetchAllPages(
            accessToken = accessToken,
            query = "'$parentId' in parents and mimeType = '$MIME_FOLDER' and trashed = false",
            orderBy = "name",
        )

    /**
     * Every MP3 in [scope], following `nextPageToken` to the end.
     *
     * The two scopes filter differently on purpose:
     *
     * - [LibraryScope.AllDrive] filters server-side on MIME type. A name-suffix
     *   filter is impossible here because Drive's `contains` operator only does
     *   prefix matching on `name`, and listing every file in the account to filter
     *   locally would be far too expensive.
     * - [LibraryScope.Folder] lists the folder's children and filters locally, which
     *   does honour the `.mp3` name rule in FR-3.1.3. A folder holds few enough
     *   files that fetching non-audio entries costs nothing.
     */
    suspend fun fetchMp3Files(
        accessToken: String,
        scope: LibraryScope,
        orderBy: String,
    ): List<DriveFile> = when (scope) {
        LibraryScope.AllDrive -> fetchAllPages(
            accessToken = accessToken,
            query = "mimeType = '$MIME_MP3' and trashed = false",
            orderBy = orderBy,
        )

        is LibraryScope.Folder -> fetchAllPages(
            accessToken = accessToken,
            query = "'${scope.folderId}' in parents and trashed = false",
            orderBy = orderBy,
        ).filter { it.isMp3() }
    }

    private suspend fun fetchAllPages(
        accessToken: String,
        query: String,
        orderBy: String,
    ): List<DriveFile> {
        val collected = mutableListOf<DriveFile>()
        var pageToken: String? = null
        var pages = 0

        do {
            val response = api.listFiles(
                bearer = bearer(accessToken),
                query = query,
                fields = FILE_FIELDS,
                pageSize = PAGE_SIZE,
                orderBy = orderBy,
                pageToken = pageToken,
            )
            collected += response.files
            pageToken = response.nextPageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES)

        return collected
    }

    private fun bearer(accessToken: String) = "Bearer $accessToken"

    companion object {
        const val ROOT_FOLDER_ID = "root"

        private const val MIME_MP3 = "audio/mpeg"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"

        private const val FILE_FIELDS = "nextPageToken,files(id,name,mimeType,size,createdTime)"
        private const val ABOUT_FIELDS = "user(displayName,emailAddress)"

        /** Drive's maximum, so 1,000 files arrive in a single round trip. */
        private const val PAGE_SIZE = 1000

        /** Backstop against an unbounded paging loop; 50,000 files is far past scope. */
        private const val MAX_PAGES = 50

        /** FR-3.1.3: MIME type or a `.mp3` name. Everything else is ignored. */
        fun DriveFile.isMp3(): Boolean =
            mimeType == MIME_MP3 || name.endsWith(".mp3", ignoreCase = true)
    }
}
