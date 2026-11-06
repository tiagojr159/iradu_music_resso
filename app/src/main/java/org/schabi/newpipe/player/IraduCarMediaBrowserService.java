package org.schabi.newpipe.player;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.session.legacy.MediaBrowserCompat;
import androidx.media3.session.legacy.MediaBrowserServiceCompat;
import androidx.media3.session.legacy.MediaDescriptionCompat;
import androidx.media3.session.legacy.MediaSessionCompat;
import androidx.media3.session.legacy.PlaybackStateCompat;

import org.schabi.newpipe.music.MusicCatalogRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Legacy browse facade used by Android Auto to display the local JSON catalog. */
public final class IraduCarMediaBrowserService extends MediaBrowserServiceCompat {
    private static final String ROOT_ID = "iradu_car_root";
    private static final String PLAYLIST_PREFIX = "iradu_playlist:";
    private static final String SONG_PREFIX = "iradu_song:";

    private MediaSessionCompat mediaSession;
    private MusicCatalogRepository.MusicCatalog catalog;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            catalog = new MusicCatalogRepository().loadCatalog(this);
        } catch (final IOException ignored) {
            catalog = null;
        }

        mediaSession = new MediaSessionCompat(this, "iradu music Android Auto",
                null, null, Bundle.EMPTY);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlayFromMediaId(final String mediaId, final Bundle extras) {
                final String query = queryForMediaId(mediaId);
                if (query != null) {
                    playQuery(query, extras);
                }
            }

            @Override
            public void onPlayFromSearch(final String query, final Bundle extras) {
                playQuery(query, extras);
            }

            @Override
            public void onPlay() {
                playQuery("", Bundle.EMPTY);
            }
        }, new Handler(getMainLooper()));
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                        | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                .build());
        mediaSession.setActive(true);
        setSessionToken(mediaSession.getSessionToken());
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull final String clientPackageName,
                                 final int clientUid,
                                 @Nullable final Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(@NonNull final String parentId,
                               @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
        if (catalog == null) {
            result.sendResult(Collections.emptyList());
            return;
        }

        final List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        if (ROOT_ID.equals(parentId)) {
            for (final MusicCatalogRepository.MusicPlaylist playlist
                    : catalog.getAllPlaylists()) {
                items.add(playlistItem(playlist));
            }
        } else if (parentId.startsWith(PLAYLIST_PREFIX)) {
            final String slug = parentId.substring(PLAYLIST_PREFIX.length());
            final MusicCatalogRepository.MusicPlaylist playlist = catalog.getPlaylist(slug);
            if (playlist != null) {
                for (int index = 0; index < playlist.songs.size(); index++) {
                    items.add(songItem(playlist, playlist.songs.get(index), index));
                }
            }
        }
        result.sendResult(items);
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
        }
        super.onDestroy();
    }

    private MediaBrowserCompat.MediaItem playlistItem(
            final MusicCatalogRepository.MusicPlaylist playlist) {
        final MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
                .setMediaId(PLAYLIST_PREFIX + playlist.slug)
                .setTitle(playlist.title)
                .setSubtitle(playlist.subtitle)
                .setDescription(playlist.description);
        if (playlist.getHeroImageUrl() != null) {
            description.setIconUri(Uri.parse(playlist.getHeroImageUrl()));
        }
        return new MediaBrowserCompat.MediaItem(description.build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem songItem(
            final MusicCatalogRepository.MusicPlaylist playlist,
            final MusicCatalogRepository.MusicSong song,
            final int index) {
        final MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(SONG_PREFIX + playlist.slug + ":" + index)
                .setTitle(song.title)
                .setSubtitle(song.artist)
                .setDescription(song.album)
                .setMediaUri(Uri.parse(song.getUrl()))
                .setIconUri(Uri.parse(song.getThumbnailUrl()))
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    @Nullable
    private String queryForMediaId(@Nullable final String mediaId) {
        if (mediaId == null || catalog == null) {
            return null;
        }
        if (mediaId.startsWith(PLAYLIST_PREFIX)) {
            final MusicCatalogRepository.MusicPlaylist playlist = catalog.getPlaylist(
                    mediaId.substring(PLAYLIST_PREFIX.length()));
            return playlist == null ? null : playlist.title;
        }
        if (mediaId.startsWith(SONG_PREFIX)) {
            final String value = mediaId.substring(SONG_PREFIX.length());
            final int separator = value.lastIndexOf(':');
            if (separator > 0) {
                final MusicCatalogRepository.MusicPlaylist playlist = catalog.getPlaylist(
                        value.substring(0, separator));
                try {
                    final int index = Integer.parseInt(value.substring(separator + 1));
                    if (playlist != null && index >= 0 && index < playlist.songs.size()) {
                        return playlist.songs.get(index).title;
                    }
                } catch (final NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private void playQuery(@Nullable final String query, @Nullable final Bundle extras) {
        final Intent intent = new Intent(this, PlayerService.class)
                .setAction(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query == null ? "" : query);
        if (extras != null) {
            intent.putExtras(extras);
        }
        ContextCompat.startForegroundService(this, intent);
    }
}
