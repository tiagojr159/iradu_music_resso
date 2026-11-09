package org.schabi.newpipe.player.mediabrowser

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.text.Normalizer
import java.util.function.Consumer
import org.schabi.newpipe.MainActivity.DEBUG
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.history.model.StreamHistoryEntry
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.stream.StreamStatisticsEntry
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.bookmark.MergedPlaylistManager
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.local.playlist.RemotePlaylistManager
import org.schabi.newpipe.music.MusicCatalogRepository
import org.schabi.newpipe.music.MusicCatalogRepository.MusicPlaylist
import org.schabi.newpipe.music.MusicCatalogRepository.MusicSong
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * This class is used to cleanly separate the Service implementation (in
 * [org.schabi.newpipe.player.PlayerService]) and the media browser implementation (in this file).
 *
 * @param notifyChildrenChanged takes the parent id of the children that changed
 */
class MediaBrowserImpl(
    private val context: Context,
    // parentId
    private val notifyChildrenChanged: Consumer<String>
) {
    private val packageValidator = PackageValidator(context)
    private val database = NewPipeDatabase.getInstance(context)
    private val catalogRepository = MusicCatalogRepository()
    private var disposables = CompositeDisposable()

    init {
        // this will listen to changes in the bookmarks until this MediaBrowserImpl is dispose()d
        disposables.add(
            getMergedPlaylists().subscribe { notifyChildrenChanged.accept(ID_BOOKMARKS) }
        )
    }

    //region Cleanup
    fun dispose() {
        disposables.dispose()
    }
    //endregion

    fun isKnownCaller(clientPackageName: String, clientUid: Int): Boolean {
        return packageValidator.isKnownCaller(clientPackageName, clientUid)
    }

    fun getRootItem(): MediaItem {
        return createRootMediaItem(
            ID_ROOT,
            context.getString(R.string.app_name_plain),
            R.drawable.ic_tubular_white
        )
    }

    fun getItem(mediaId: String): Single<MediaItem> {
        if (mediaId == ID_ROOT) {
            return Single.just(getRootItem())
        }
        val path = mediaId.toUri().pathSegments
        if (path.firstOrNull() == ID_CATALOG_SHUFFLE) {
            return resolveCatalogShuffleItem(mediaId, path)
        }
        if (path.firstOrNull() == ID_CAR_SETTINGS) {
            return resolveCarSettingItem(mediaId, path)
        }
        if (path.firstOrNull() == ID_CATALOG) {
            return resolveCatalogItem(mediaId, path)
        }
        if (path.firstOrNull() == ID_BOOKMARKS) {
            return resolveBookmarkItem(mediaId, path)
        }
        return loadChildren(ID_ROOT)
            .flatMap { rootItems ->
                rootItems.firstOrNull { it.mediaId == mediaId }?.let { Single.just(it) }
                    ?: resolvePlayableItem(mediaId)
            }
            .subscribeOn(Schedulers.io())
    }

    private fun resolveBookmarkItem(mediaId: String, path: List<String>): Single<MediaItem> {
        val parentId = when (path.size) {
            // The bookmarks folder itself is one of the root children.
            1 -> ID_ROOT

            // A saved playlist is a child of the bookmarks folder.
            3 -> ID_BOOKMARKS

            // A song is a child of its saved playlist. Load that playlist's entries so the
            // selected media ID resolves to the same stream and queue index used for playback.
            4 -> buildMediaId()
                .appendPath(ID_BOOKMARKS)
                .appendPath(path[1])
                .appendPath(path[2])
                .build().toString()

            else -> return Single.error(parseError(mediaId))
        }

        return loadChildren(parentId)
            .flattenAsFlowable { it }
            .filter { it.mediaId == mediaId }
            .firstOrError()
            .subscribeOn(Schedulers.io())
    }

    private fun resolveCatalogItem(mediaId: String, path: List<String>): Single<MediaItem> {
        return Single.fromCallable {
            val catalog = catalogRepository.loadCatalog(context)
            val playlist = path.getOrNull(1)?.let(catalog::getPlaylist)
                ?: throw parseError(mediaId)
            when (path.size) {
                2 -> createCatalogPlaylistMediaItem(playlist)

                3 -> {
                    val index = MusicCatalogRepository.resolveSongIndex(playlist, path[2])
                    if (index < 0) throw parseError(mediaId)
                    val song = playlist.songs.getOrNull(index) ?: throw parseError(mediaId)
                    createCatalogSongMediaItem(playlist, song, index)
                }

                else -> throw parseError(mediaId)
            }
        }.subscribeOn(Schedulers.io())
    }

    private fun resolveCatalogShuffleItem(mediaId: String, path: List<String>): Single<MediaItem> = Single.fromCallable {
        val playlistSlug = path.getOrNull(1)?.takeIf { path.size == 2 }
            ?: throw parseError(mediaId)
        val playlist = catalogRepository.loadCatalog(context).getPlaylist(playlistSlug)
            ?: throw parseError(mediaId)
        createCatalogShuffleMediaItem(playlist)
    }.subscribeOn(Schedulers.io())

    private fun resolveCarSettingItem(mediaId: String, path: List<String>): Single<MediaItem> = Single.fromCallable {
        val action = path.getOrNull(1)?.takeIf { path.size == 2 }
            ?: throw parseError(mediaId)
        createCarSettingItem(action)
    }.subscribeOn(Schedulers.io())

    fun getCarSettingAction(mediaId: String): String? {
        val path = mediaId.toUri().pathSegments
        return path.getOrNull(1)?.takeIf {
            path.size == 2 && path.firstOrNull() == ID_CAR_SETTINGS &&
                it in CAR_SETTING_ACTIONS
        }
    }

    fun notifyCarSettingsChanged() {
        notifyChildrenChanged.accept(ID_CAR_SETTINGS)
    }

    //region onLoadChildren
    fun loadChildren(parentId: String): Single<List<MediaItem>> {
        if (DEBUG) {
            Log.d(TAG, "onLoadChildren($parentId)")
        }
        return onLoadChildren(parentId).subscribeOn(Schedulers.io())
    }

    private fun onLoadChildren(parentId: String): Single<List<MediaItem>> {
        try {
            if (parentId == ID_ROOT) {
                return Single.just(
                    listOf(
                        createRootMediaItem(
                            ID_CATALOG,
                            "Playlists do iradu music",
                            R.drawable.ic_headset
                        ),
                        createRootMediaItem(
                            ID_RECENT,
                            context.resources.getString(R.string.resso_recently_played),
                            R.drawable.ic_history_white
                        ),
                        createRootMediaItem(
                            ID_BOOKMARKS,
                            context.resources.getString(R.string.tab_bookmarks_short),
                            R.drawable.ic_bookmark_white
                        ),
                        createRootMediaItem(
                            ID_FAVORITES,
                            context.resources.getString(R.string.resso_favorite_music),
                            R.drawable.ic_heart
                        ),
                        createRootMediaItem(
                            ID_ALL_SONGS,
                            context.resources.getString(R.string.resso_all_music),
                            R.drawable.ic_headset
                        ),
                        createRootMediaItem(
                            ID_ARTISTS,
                            "Artistas",
                            R.drawable.ic_headset
                        ),
                        createRootMediaItem(
                            ID_ALBUMS,
                            "Albuns",
                            R.drawable.ic_headset
                        ),
                        createRootMediaItem(
                            ID_GENRES,
                            "Generos",
                            R.drawable.ic_headset
                        ),
                        createRootMediaItem(
                            ID_SEARCH,
                            "Busca",
                            R.drawable.ic_search
                        ),
                        createRootMediaItem(
                            ID_CAR_SETTINGS,
                            context.getString(R.string.resso_car_settings_title),
                            R.drawable.ic_settings
                        )
                    )
                )
            }

            val parentIdUri = parentId.toUri()
            val path = ArrayList(parentIdUri.pathSegments)

            if (path.isEmpty()) {
                throw parseError(parentId)
            }

            when (path.removeAt(0)) {
                ID_BOOKMARKS -> {
                    if (path.isEmpty()) {
                        return populateBookmarks()
                    }
                    if (path.size == 2) {
                        val localOrRemote = path[0]
                        val playlistId = path[1].toLong()
                        if (localOrRemote == ID_LOCAL) {
                            return populateLocalPlaylist(playlistId)
                        } else if (localOrRemote == ID_REMOTE) {
                            return populateRemotePlaylist(playlistId)
                        }
                    }
                    Log.w(TAG, "Unknown playlist URI: $parentId")
                    throw parseError(parentId)
                }

                ID_RECENT -> return populateRecent()

                ID_FAVORITES -> return populateFavorites()

                ID_ALL_SONGS -> return populateAllSongs()

                ID_ARTISTS -> {
                    if (path.isEmpty()) {
                        return populateCatalogGroups(ID_ARTISTS) { it.artist }
                    }
                    if (path.size == 1) {
                        return populateCatalogSongsByGroup(ID_ARTISTS, path[0]) { it.artist }
                    }
                    throw parseError(parentId)
                }

                ID_ALBUMS -> {
                    if (path.isEmpty()) {
                        return populateCatalogGroups(ID_ALBUMS) { it.album }
                    }
                    if (path.size == 1) {
                        return populateCatalogSongsByGroup(ID_ALBUMS, path[0]) { it.album }
                    }
                    throw parseError(parentId)
                }

                ID_GENRES -> {
                    if (path.isEmpty()) {
                        return populateCatalogPlaylists()
                    }
                    if (path.size == 1) {
                        return populateCatalogPlaylist(path[0])
                    }
                    throw parseError(parentId)
                }

                ID_SEARCH -> return populateSearchSuggestions()

                ID_CATALOG -> {
                    if (path.isEmpty()) {
                        return populateCatalogPlaylists()
                    }
                    if (path.size == 1) {
                        return populateCatalogPlaylist(path[0])
                    }
                    throw parseError(parentId)
                }

                ID_CATALOG_SHUFFLE -> {
                    if (path.size != 1) throw parseError(parentId)
                    return Single.just(
                        listOf(
                            createCatalogShuffleMediaItem(
                                catalogRepository.loadCatalog(context).getPlaylist(path[0])
                                    ?: throw parseError(parentId)
                            )
                        )
                    )
                }

                ID_CAR_SETTINGS -> {
                    if (path.isEmpty()) throw parseError(parentId)
                    return Single.just(path.map(::createCarSettingItem))
                }

                ID_HISTORY -> return populateHistory()

                else -> throw parseError(parentId)
            }
        } catch (e: ContentNotAvailableException) {
            return Single.error(e)
        }
    }

    private fun createRootMediaItem(
        mediaId: String?,
        folderName: String?,
        @DrawableRes iconResId: Int
    ): MediaItem {
        val resources = context.resources
        val iconUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(resources.getResourcePackageName(iconResId))
            .appendPath(resources.getResourceTypeName(iconResId))
            .appendPath(resources.getResourceEntryName(iconResId))
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId ?: ID_ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(folderName)
                    .setArtworkUri(iconUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                    .build()
            )
            .build()
    }

    private fun createPlaylistMediaItem(playlist: PlaylistLocalItem): MediaItem {
        return MediaItem.Builder()
            .setMediaId(createMediaIdForInfoItem(playlist is PlaylistRemoteEntity, playlist.uid))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(playlist.orderingName)
                    .setArtworkUri(imageUriOrNullIfDisabled(playlist.thumbnailUrl))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                    .build()
            )
            .build()
    }

    private fun createCatalogPlaylistMediaItem(playlist: MusicPlaylist): MediaItem {
        return MediaItem.Builder()
            .setMediaId(
                buildMediaId().appendPath(ID_CATALOG).appendPath(playlist.slug)
                    .build().toString()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(playlist.title)
                    .setSubtitle(playlist.subtitle)
                    .setDescription(playlist.description)
                    .setArtworkUri(imageUriOrNullIfDisabled(playlist.heroImageUrl))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                    .build()
            )
            .build()
    }

    private fun createCatalogShuffleMediaItem(playlist: MusicPlaylist): MediaItem {
        val iconUri = Uri.parse(
            "android.resource://${context.packageName}/${R.drawable.ic_shuffle}"
        )
        return MediaItem.Builder()
            .setMediaId(
                buildMediaId().appendPath(ID_CATALOG_SHUFFLE).appendPath(playlist.slug)
                    .build().toString()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.resso_car_shuffle_playlist))
                    .setSubtitle(playlist.title)
                    .setArtworkUri(iconUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    private fun createCarSettingItem(action: String): MediaItem {
        val titleRes = when (action) {
            ACTION_CAR_SHUFFLE -> R.string.resso_car_settings_shuffle
            ACTION_CAR_REPEAT -> R.string.resso_car_settings_repeat
            ACTION_CAR_ARTWORK -> R.string.resso_car_settings_artwork
            else -> throw parseError(action)
        }
        val mediaId = buildMediaId().appendPath(ID_CAR_SETTINGS).appendPath(action).build()
        return MediaItem.Builder()
            .setMediaId(mediaId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(titleRes))
                    .setSubtitle(context.getString(R.string.resso_car_settings_tap_to_toggle))
                    .setArtworkUri(
                        Uri.parse(
                            "android.resource://${context.packageName}/${R.drawable.ic_settings}"
                        )
                    )
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    private fun createCatalogSongMediaItem(
        playlist: MusicPlaylist,
        song: MusicSong,
        index: Int
    ): MediaItem {
        val mediaId = buildMediaId()
            .appendPath(ID_CATALOG)
            .appendPath(playlist.slug)
            .appendPath(song.youtubeId)
            .build().toString()
        return playableMediaItem(
            mediaId,
            song.title,
            song.artist,
            song.thumbnailUrl,
            parseDurationSeconds(song.duration),
            song.url,
            playlist.title
        )
    }

    private fun createCatalogGroupMediaItem(parentId: String, name: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(buildMediaId().appendPath(parentId).appendPath(name).build().toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                    .build()
            )
            .build()
    }

    private fun createInfoItemMediaItem(item: InfoItem): MediaItem? {
        val subtitle = when (item.infoType) {
            InfoType.STREAM -> (item as StreamInfoItem).uploaderName
            InfoType.PLAYLIST -> (item as PlaylistInfoItem).uploaderName
            InfoType.CHANNEL -> (item as ChannelInfoItem).description
            else -> return null
        }

        return playableMediaItem(
            createMediaIdForInfoItem(item),
            item.name,
            subtitle,
            ImageStrategy.choosePreferredImage(item.thumbnails),
            (item as? StreamInfoItem)?.duration,
            item.url,
            when (item.infoType) {
                InfoType.PLAYLIST -> item.name
                else -> null
            }
        )
    }

    private fun buildMediaId(): Uri.Builder {
        return Uri.Builder().authority(ID_AUTHORITY)
    }

    private fun buildPlaylistMediaId(playlistType: String?): Uri.Builder {
        return buildMediaId()
            .appendPath(ID_BOOKMARKS)
            .appendPath(playlistType)
    }

    private fun buildLocalPlaylistItemMediaId(isRemote: Boolean, playlistId: Long): Uri.Builder {
        return buildPlaylistMediaId(if (isRemote) ID_REMOTE else ID_LOCAL)
            .appendPath(playlistId.toString())
    }

    private fun buildInfoItemMediaId(item: InfoItem): Uri.Builder {
        return buildMediaId()
            .appendPath(ID_INFO_ITEM)
            .appendPath(infoItemTypeToString(item.infoType))
            .appendPath(item.serviceId.toString())
            .appendQueryParameter(ID_URL, item.url)
    }

    private fun createMediaIdForInfoItem(isRemote: Boolean, playlistId: Long): String {
        return buildLocalPlaylistItemMediaId(isRemote, playlistId)
            .build().toString()
    }

    private fun createLocalPlaylistStreamMediaItem(
        playlistId: Long,
        item: PlaylistStreamEntry,
        index: Int
    ): MediaItem {
        return playableMediaItem(
            createMediaIdForPlaylistIndex(false, playlistId, index),
            item.streamEntity.title,
            item.streamEntity.uploader,
            item.streamEntity.thumbnailUrl,
            item.streamEntity.duration,
            item.streamEntity.url,
            context.getString(R.string.tab_bookmarks_short)
        )
    }

    private fun createRemotePlaylistStreamMediaItem(
        playlistId: Long,
        item: StreamInfoItem,
        index: Int
    ): MediaItem {
        return playableMediaItem(
            createMediaIdForPlaylistIndex(true, playlistId, index),
            item.name,
            item.uploaderName,
            ImageStrategy.choosePreferredImage(item.thumbnails),
            item.duration,
            item.url,
            context.getString(R.string.tab_bookmarks_short)
        )
    }

    private fun createMediaIdForPlaylistIndex(
        isRemote: Boolean,
        playlistId: Long,
        index: Int
    ): String {
        return buildLocalPlaylistItemMediaId(isRemote, playlistId)
            .appendPath(index.toString())
            .build().toString()
    }

    private fun createMediaIdForInfoItem(item: InfoItem): String {
        return buildInfoItemMediaId(item).build().toString()
    }

    private fun playableMediaItem(
        mediaId: String,
        title: String?,
        subtitle: String?,
        thumbnailUrl: String?,
        durationSeconds: Long? = null,
        mediaUrl: String? = null,
        album: String? = null
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setAlbumTitle(album)
                    .setSubtitle(subtitle)
                    .setDurationMs(durationSeconds?.takeIf { it > 0 }?.times(1000))
                    .setArtworkUri(imageUriOrNullIfDisabled(thumbnailUrl))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    private fun populateHistory(): Single<List<MediaItem>> {
        val history = database.streamHistoryDAO().history.firstOrError()
        return history.map { items ->
            items.map { this.createHistoryMediaItem(it) }
        }
    }

    private fun populateRecent(): Single<List<MediaItem>> {
        val history = database.streamHistoryDAO().history.firstOrError()
        return history.map { items ->
            items.take(12).map { this.createHistoryMediaItem(it) }
        }
    }

    private fun populateFavorites(): Single<List<MediaItem>> {
        return database.streamHistoryDAO().getStatistics().firstOrError()
            .map { items ->
                items.sortedWith(
                    compareByDescending<StreamStatisticsEntry> { it.watchCount }
                        .thenByDescending { it.latestAccessDate }
                ).take(24).map(this::createFavoriteMediaItem)
            }
    }

    private fun populateAllSongs(): Single<List<MediaItem>> {
        return database.streamDAO().getAll().firstOrError()
            .map { items ->
                items.sortedBy { it.title.lowercase() }.map(this::createAllSongsMediaItem)
            }
    }

    private fun createHistoryMediaItem(streamHistoryEntry: StreamHistoryEntry): MediaItem {
        val mediaId = buildMediaId()
            .appendPath(ID_HISTORY)
            .appendPath(streamHistoryEntry.streamId.toString())
            .build().toString()
        return playableMediaItem(
            mediaId,
            streamHistoryEntry.streamEntity.title,
            streamHistoryEntry.streamEntity.uploader,
            streamHistoryEntry.streamEntity.thumbnailUrl,
            streamHistoryEntry.streamEntity.duration,
            streamHistoryEntry.streamEntity.url,
            context.getString(R.string.resso_recently_played)
        )
    }

    private fun createFavoriteMediaItem(item: StreamStatisticsEntry): MediaItem {
        return playableMediaItem(
            createMediaIdForInfoItem(item.toStreamInfoItem()),
            item.streamEntity.title,
            item.streamEntity.uploader,
            item.streamEntity.thumbnailUrl,
            item.streamEntity.duration,
            item.streamEntity.url,
            context.getString(R.string.resso_favorite_music)
        )
    }

    private fun createAllSongsMediaItem(item: StreamEntity): MediaItem {
        return playableMediaItem(
            createMediaIdForInfoItem(item.toStreamInfoItem()),
            item.title,
            item.uploader,
            item.thumbnailUrl,
            item.duration,
            item.url,
            context.getString(R.string.resso_all_music)
        )
    }

    private fun getMergedPlaylists(): Flowable<MutableList<PlaylistLocalItem>> {
        return MergedPlaylistManager.getMergedOrderedPlaylists(
            LocalPlaylistManager(database),
            RemotePlaylistManager(database)
        )
    }

    private fun populateBookmarks(): Single<List<MediaItem>> {
        val playlists = getMergedPlaylists().firstOrError()
        return playlists.map { playlist ->
            playlist.map { this.createPlaylistMediaItem(it) }
        }
    }

    private fun populateLocalPlaylist(playlistId: Long): Single<List<MediaItem>> {
        val playlist = LocalPlaylistManager(database).getPlaylistStreams(playlistId).firstOrError()
        return playlist.map { items ->
            items.mapIndexed { index, item ->
                createLocalPlaylistStreamMediaItem(playlistId, item, index)
            }
        }
    }

    private fun populateRemotePlaylist(playlistId: Long): Single<List<MediaItem>> {
        return RemotePlaylistManager(database).getPlaylist(playlistId).firstOrError()
            .flatMap { ExtractorHelper.getPlaylistInfo(it.serviceId, it.url, false) }
            .map {
                // ignore it.errors, i.e. ignore errors about specific items, since there would
                // be no way to show the error properly in Android Auto anyway
                it.relatedItems.mapIndexed { index, item ->
                    createRemotePlaylistStreamMediaItem(playlistId, item, index)
                }
            }
    }

    private fun populateCatalogPlaylists(): Single<List<MediaItem>> {
        return Single.fromCallable {
            catalogRepository.loadCatalog(context).allPlaylists
                .map(this::createCatalogPlaylistMediaItem)
        }
    }

    private fun populateCatalogPlaylist(slug: String): Single<List<MediaItem>> {
        return Single.fromCallable {
            val playlist = catalogRepository.loadCatalog(context).getPlaylist(slug)
                ?: throw parseError(slug)
            listOf(createCatalogShuffleMediaItem(playlist)) +
                playlist.songs.mapIndexed { index, song ->
                    createCatalogSongMediaItem(playlist, song, index)
                }
        }
    }

    private fun populateCatalogGroups(
        parentId: String,
        groupSelector: (MusicSong) -> String?
    ): Single<List<MediaItem>> {
        return Single.fromCallable {
            catalogRepository.loadCatalog(context).allPlaylists
                .flatMap { it.songs }
                .mapNotNull { groupSelector(it)?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .sortedBy { normalizeForSearch(it) }
                .map { createCatalogGroupMediaItem(parentId, it) }
        }
    }

    private fun populateCatalogSongsByGroup(
        parentId: String,
        groupName: String,
        groupSelector: (MusicSong) -> String?
    ): Single<List<MediaItem>> {
        return Single.fromCallable {
            val normalizedGroup = normalizeForSearch(groupName)
            catalogRepository.loadCatalog(context).allPlaylists
                .flatMap { playlist ->
                    playlist.songs.mapIndexedNotNull { index, song ->
                        val candidate = groupSelector(song)?.let(this::normalizeForSearch)
                        if (candidate == normalizedGroup) {
                            createCatalogSongMediaItem(playlist, song, index)
                        } else {
                            null
                        }
                    }
                }
                .distinctBy { it.mediaId }
        }
    }
    //endregion

    //region Search
    fun search(query: String): Single<List<MediaItem>> {
        if (DEBUG) {
            Log.d(TAG, "onSearch($query)")
        }
        return searchCatalog(query).flatMap { localItems ->
            if (localItems.isNotEmpty()) {
                Single.just(localItems)
            } else {
                searchMusicBySongTitle(query)
                    // Ignore item-specific errors: Android Auto cannot present them here.
                    .map { it.relatedItems.mapNotNull(this::createInfoItemMediaItem) }
            }
        }
            .subscribeOn(Schedulers.io())
    }

    private fun searchCatalog(query: String): Single<List<MediaItem>> {
        return Single.fromCallable {
            val normalizedQuery = normalizeForSearch(query)
            if (normalizedQuery.isBlank()) {
                return@fromCallable emptyList()
            }
            catalogRepository.loadCatalog(context).allPlaylists.flatMap { playlist ->
                playlist.songs.mapIndexedNotNull { index, song ->
                    val haystack = normalizeForSearch(
                        "${song.title} ${song.artist} ${song.album} ${playlist.title}"
                    )
                    if (haystack.contains(normalizedQuery)) {
                        createCatalogSongMediaItem(playlist, song, index)
                    } else {
                        null
                    }
                }
            }.distinctBy { it.mediaId }
        }
    }

    private fun searchMusicBySongTitle(query: String?): Single<SearchInfo> {
        val serviceId = ServiceHelper.getSelectedServiceId(context)
        return ExtractorHelper.searchFor(serviceId, query, listOf(), "")
    }

    private fun resolvePlayableItem(mediaId: String): Single<MediaItem> {
        return loadPlayableCollections()
            .flattenAsFlowable { it }
            .filter { it.mediaId == mediaId }
            .firstOrError()
    }

    private fun loadPlayableCollections(): Single<List<MediaItem>> {
        return Single.zip(
            populateRecent().onErrorReturnItem(emptyList()),
            populateFavorites().onErrorReturnItem(emptyList()),
            populateAllSongs().onErrorReturnItem(emptyList()),
            populateAllCatalogSongs().onErrorReturnItem(emptyList())
        ) { recent, favorites, allSongs, catalogSongs ->
            recent + favorites + allSongs + catalogSongs
        }
    }

    private fun populateAllCatalogSongs(): Single<List<MediaItem>> {
        return Single.fromCallable {
            catalogRepository.loadCatalog(context).allPlaylists.flatMap { playlist ->
                playlist.songs.mapIndexed { index, song ->
                    createCatalogSongMediaItem(playlist, song, index)
                }
            }
        }
    }

    private fun populateSearchSuggestions(): Single<List<MediaItem>> {
        return populateAllCatalogSongs().map { it.take(24) }
    }

    private fun parseDurationSeconds(duration: String?): Long? {
        if (duration.isNullOrBlank()) return null
        var total = 0L
        for (part in duration.trim().split(":")) {
            val value = part.toLongOrNull() ?: return null
            total = total * 60L + value
        }
        return total
    }

    private fun normalizeForSearch(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase()
            .trim()
    }
    //endregion

    companion object {
        private val TAG: String = MediaBrowserImpl::class.java.getSimpleName()
        private const val ID_CATALOG_SHUFFLE = "catalog_shuffle"
        private const val ID_CAR_SETTINGS = "car_settings"
        const val ACTION_CAR_SHUFFLE = "shuffle"
        const val ACTION_CAR_REPEAT = "repeat_one"
        const val ACTION_CAR_ARTWORK = "artwork"
        private val CAR_SETTING_ACTIONS = setOf(
            ACTION_CAR_SHUFFLE,
            ACTION_CAR_REPEAT,
            ACTION_CAR_ARTWORK
        )

        fun imageUriOrNullIfDisabled(url: String?): Uri? {
            return if (ImageStrategy.shouldLoadImages()) {
                url?.toUri()
            } else {
                null
            }
        }
    }
}
