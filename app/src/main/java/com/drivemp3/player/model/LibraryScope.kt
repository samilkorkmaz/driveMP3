package com.drivemp3.player.model

/**
 * Which part of Drive the library covers (FR-3.1.2).
 *
 * Deliberately carries no display strings — the UI resolves labels so they stay
 * translatable.
 */
sealed interface LibraryScope {

    /** Key used both for persistence and as the Room `scopeId` partition. */
    val storageKey: String

    /** Every MP3 in the account, at any depth. */
    data object AllDrive : LibraryScope {
        override val storageKey: String = ALL_DRIVE_KEY
    }

    /** One folder's direct children. See [com.drivemp3.player.data.DriveRepository]. */
    data class Folder(val folderId: String, val folderName: String) : LibraryScope {
        override val storageKey: String get() = folderId
    }

    companion object {
        /** Not a valid Drive file id, so it cannot collide with a folder key. */
        const val ALL_DRIVE_KEY = "__all_drive__"
    }
}
