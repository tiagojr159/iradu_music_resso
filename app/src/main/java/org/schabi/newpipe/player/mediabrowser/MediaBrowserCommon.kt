package org.schabi.newpipe.player.mediabrowser

import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

internal const val ID_AUTHORITY = BuildConfig.APPLICATION_ID
internal const val ID_ROOT = "root"
internal const val ID_BOOKMARKS = "playlists"
internal const val ID_HISTORY = "history"
internal const val ID_RECENT = "recent"
internal const val ID_FAVORITES = "favorites"
internal const val ID_ALL_SONGS = "all_songs"
internal const val ID_ARTISTS = "artists"
internal const val ID_ALBUMS = "albums"
internal const val ID_GENRES = "genres"
internal const val ID_SEARCH = "search"
internal const val ID_CATALOG = "iradu_catalog"
internal const val ID_INFO_ITEM = "item"

internal const val ID_LOCAL = "local"
internal const val ID_REMOTE = "remote"
internal const val ID_URL = "url"
internal const val ID_STREAM = "stream"
internal const val ID_PLAYLIST = "playlist"
internal const val ID_CHANNEL = "channel"

internal fun infoItemTypeToString(type: InfoType): String {
    return when (type) {
        InfoType.STREAM -> ID_STREAM
        InfoType.PLAYLIST -> ID_PLAYLIST
        InfoType.CHANNEL -> ID_CHANNEL
        else -> error("Unexpected value: $type")
    }
}

internal fun infoItemTypeFromString(type: String): InfoType {
    return when (type) {
        ID_STREAM -> InfoType.STREAM
        ID_PLAYLIST -> InfoType.PLAYLIST
        ID_CHANNEL -> InfoType.CHANNEL
        else -> error("Unexpected value: $type")
    }
}

internal fun parseError(mediaId: String): ContentNotAvailableException {
    return ContentNotAvailableException("Failed to parse media ID $mediaId")
}
