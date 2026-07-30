package com.drivemp3.player.model

/** FR-3.3.1: the two sort keys, both taken from Drive metadata only. */
enum class SortField {
    /** Drive's `createdTime` — "upload date". */
    CreatedTime,

    /** Drive's raw `name` string. No ID3 parsing anywhere. */
    Name,
}

/** FR-3.3.2. */
enum class SortDirection {
    Ascending,
    Descending,
    ;

    fun toggled(): SortDirection = if (this == Ascending) Descending else Ascending
}

data class SortOrder(
    val field: SortField = SortField.CreatedTime,
    val direction: SortDirection = SortDirection.Descending,
) {
    /**
     * The equivalent Drive `orderBy` value. Display order comes from SQL, but
     * passing this to Drive keeps paging deterministic across requests.
     */
    val driveOrderBy: String
        get() {
            // `this.field`, not `field`: inside a getter, bare `field` is the
            // reserved backing-field keyword rather than this class's property.
            val key = when (this.field) {
                SortField.CreatedTime -> "createdTime"
                SortField.Name -> "name"
            }
            return if (direction == SortDirection.Descending) "$key desc" else key
        }
}
