/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * Part of NewPipe
 *
 * License: GPL-3.0+
 */

package org.schabi.newpipe.player;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import androidx.preference.PreferenceManager;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.schabi.newpipe.R;
import org.schabi.newpipe.ktx.BundleKt;
import org.schabi.newpipe.player.mediabrowser.MediaBrowserImpl;
import org.schabi.newpipe.player.mediabrowser.MediaBrowserPlaybackPreparer;
import org.schabi.newpipe.player.mediasession.MediaSessionBridgePlayer;
import org.schabi.newpipe.player.notification.NotificationPlayerUi;
import org.schabi.newpipe.player.notification.NotificationUtil;
import org.schabi.newpipe.util.AppLog;
import org.schabi.newpipe.util.ThemeHelper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One service for all players and the app's AndroidX Media3 library/session entry point.
 */
public final class PlayerService extends MediaLibraryService {
    private static final String TAG = PlayerService.class.getSimpleName();
    private static final boolean DEBUG = Player.DEBUG;
    private static final String ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead";
    private static final String ACTION_TOGGLE_ANDROID_AUTO_ARTWORK =
            "org.schabi.newpipe.action.TOGGLE_ANDROID_AUTO_ARTWORK";
    private static final String ACTION_TOGGLE_CAR_SHUFFLE =
            "org.schabi.newpipe.action.TOGGLE_CAR_SHUFFLE";
    private static final String ACTION_TOGGLE_CAR_REPEAT_ONE =
            "org.schabi.newpipe.action.TOGGLE_CAR_REPEAT_ONE";
    private static final String URI_MEDIA_ID_PREFIX = "iradu_uri:";
    private static final String CONTENT_STYLE_BROWSABLE_HINT =
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final String CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final int CONTENT_STYLE_LIST_ITEM = 1;
    private static final int CONTENT_STYLE_GRID_ITEM = 2;

    public static final String SHOULD_START_FOREGROUND_EXTRA = "should_start_foreground_extra";
    public static final String BIND_PLAYER_HOLDER_ACTION = "bind_player_holder_action";

    private MediaBrowserImpl mediaBrowserImpl;
    private MediaBrowserPlaybackPreparer mediaBrowserPlaybackPreparer;
    private MediaSessionBridgePlayer mediaSessionPlayer;
    private MediaLibrarySession mediaLibrarySession;

    @Nullable
    private Player player;

    private final IBinder mBinder = new PlayerService.LocalBinder(this);

    @Nullable
    private Consumer<Player> onPlayerStartedOrStopped = null;

    //region Service lifecycle
    @Override
    public void onCreate() {
        super.onCreate();

        if (DEBUG) {
            Log.d(TAG, "onCreate() called");
        }
        ThemeHelper.setTheme(this);

        mediaBrowserImpl = new MediaBrowserImpl(
                this,
                parentId -> {
                    if (mediaLibrarySession != null) {
                        mediaLibrarySession.notifyChildrenChanged(parentId, 0, null);
                    }
                });
        mediaSessionPlayer = new MediaSessionBridgePlayer(
                () -> player,
                () -> mediaBrowserPlaybackPreparer.onPrepareFromSearch("", true, Bundle.EMPTY),
                this::isAndroidAutoArtworkEnabled);
        mediaBrowserPlaybackPreparer = new MediaBrowserPlaybackPreparer(
                this,
                (error, errorCode) -> reportCarPlaybackError(
                        "Preparação da reprodução (código " + errorCode + ")",
                        error.toString(), null),
                () -> { },
                playWhenReady -> {
                    if (player != null) {
                        player.onPrepare();
                    }
                }
        );
        mediaLibrarySession = new MediaLibrarySession.Builder(
                this,
                mediaSessionPlayer,
                new MediaLibraryCallback()
        )
                .setId(getString(R.string.app_name_plain))
                .build();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (DEBUG) {
            Log.d(TAG, "onStartCommand() called with: intent = [" + intent
                    + "], extras = [" + BundleKt.toDebugString(intent.getExtras())
                    + "], flags = [" + flags + "], startId = [" + startId + "]");
        }

        super.onStartCommand(intent, flags, startId);

        if (intent != null && intent.getBooleanExtra(SHOULD_START_FOREGROUND_EXTRA, false)) {
            ensurePlayer();

            player.UIs().get(NotificationPlayerUi.class)
                    .ifPresent(NotificationPlayerUi::createNotificationAndStartForeground);
        }

        if (intent != null
                && MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(intent.getAction())) {
            ensurePlayer();
            mediaBrowserPlaybackPreparer.onPrepareFromSearch(
                    intent.getStringExtra(SearchManager.QUERY),
                    true,
                    intent.getExtras());
            mediaSessionPlayer.refresh();
            return START_NOT_STICKY;
        }

        if (player == null) {
            Log.d(TAG, "onStartCommand() got a useless intent, closing the service");
            NotificationUtil.startForegroundWithDummyNotification(this);
            destroyPlayerAndStopService();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            final PlayerType oldPlayerType = player.getPlayerType();
            player.handleIntent(intent);
            player.handleIntentPost(oldPlayerType);
            mediaSessionPlayer.refresh();
        }

        return START_NOT_STICKY;
    }

    private void ensurePlayer() {
        if (player != null) {
            return;
        }

        player = new Player(this, mediaSessionPlayer);
        mediaSessionPlayer.refresh();

        if (onPlayerStartedOrStopped != null) {
            onPlayerStartedOrStopped.accept(player);
        }
    }

    public void stopForImmediateReusing() {
        if (DEBUG) {
            Log.d(TAG, "stopForImmediateReusing() called");
        }

        if (player != null && !player.exoPlayerIsNull()) {
            player.smoothStopForImmediateReusing();
            mediaSessionPlayer.refresh();
        }
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (player != null && !player.videoPlayerSelected()) {
            return;
        }
        onDestroy();
        Runtime.getRuntime().halt(0);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) {
            Log.d(TAG, "destroy() called");
        }
        cleanup();

        mediaBrowserPlaybackPreparer.dispose();
        mediaBrowserImpl.dispose();
        mediaLibrarySession.release();
        mediaSessionPlayer.release();

        super.onDestroy();
    }

    private void cleanup() {
        if (player != null) {
            if (onPlayerStartedOrStopped != null) {
                onPlayerStartedOrStopped.accept(null);
            }
            player.destroy();
            player = null;
        }

        mediaSessionPlayer.refresh();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    public void destroyPlayerAndStopService() {
        if (DEBUG) {
            Log.d(TAG, "destroyPlayerAndStopService() called");
        }

        cleanup();
        stopService(new Intent(this, PlayerService.class));
    }

    @Override
    protected void attachBaseContext(final Context base) {
        super.attachBaseContext(AudioServiceLeakFix.preventLeakOf(base));
    }
    //endregion

    //region Bind
    @Override
    public IBinder onBind(final Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onBind() called with: intent = [" + intent
                    + "], extras = [" + BundleKt.toDebugString(intent.getExtras()) + "]");
        }

        if (intent != null && BIND_PLAYER_HOLDER_ACTION.equals(intent.getAction())) {
            return mBinder;
        }

        return super.onBind(intent);
    }

    public static class LocalBinder extends Binder {
        private final WeakReference<PlayerService> playerService;

        LocalBinder(final PlayerService playerService) {
            this.playerService = new WeakReference<>(playerService);
        }

        public PlayerService getService() {
            return playerService.get();
        }
    }

    @Nullable
    public Player getPlayer() {
        return player;
    }

    public void setPlayerListener(@Nullable final Consumer<Player> listener) {
        this.onPlayerStartedOrStopped = listener;
        if (listener != null) {
            listener.accept(player);
        }
    }

    private boolean isAndroidAutoArtworkEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                getString(R.string.android_auto_video_artwork_key), true);
    }

    private void toggleAndroidAutoArtwork() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(getString(R.string.android_auto_video_artwork_key),
                        !isAndroidAutoArtworkEnabled())
                .apply();
        mediaSessionPlayer.refresh();
    }

    private CommandButton createAndroidAutoArtworkButton() {
        final boolean enabled = isAndroidAutoArtworkEnabled();
        final SessionCommand command = new SessionCommand(
                ACTION_TOGGLE_ANDROID_AUTO_ARTWORK, Bundle.EMPTY);
        return new CommandButton.Builder(CommandButton.ICON_SETTINGS)
                .setDisplayName(getString(enabled
                        ? R.string.android_auto_video_artwork_enabled
                        : R.string.android_auto_video_artwork_disabled))
                .setSessionCommand(command)
                .build();
    }

    private List<CommandButton> createAndroidAutoButtons() {
        final boolean shuffled = player != null && player.getShuffleModeEnabled();
        final boolean repeating = player != null && player.getRepeatMode()
                == androidx.media3.common.Player.REPEAT_MODE_ONE;
        return List.of(createAndroidAutoArtworkButton(),
                createCarToggleButton(ACTION_TOGGLE_CAR_SHUFFLE,
                        shuffled ? "Ordem aleatória ativada" : "Ordem aleatória",
                        shuffled ? CommandButton.ICON_SHUFFLE_ON : CommandButton.ICON_SHUFFLE_OFF),
                createCarToggleButton(ACTION_TOGGLE_CAR_REPEAT_ONE,
                        repeating ? "Repetição ativada" : "Repetir música",
                        repeating ? CommandButton.ICON_REPEAT_ONE : CommandButton.ICON_REPEAT_OFF));
    }

    private CommandButton createCarToggleButton(final String action,
                                               final String label,
                                               final int icon) {
        return new CommandButton.Builder(icon)
                .setDisplayName(label)
                .setSessionCommand(new SessionCommand(action, Bundle.EMPTY))
                .build();
    }
    //endregion

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(
            @NonNull final MediaSession.ControllerInfo controllerInfo) {
        Log.i(TAG, "onGetSession: package=" + controllerInfo.getPackageName()
                + ", uid=" + controllerInfo.getUid()
                + ", trusted=" + controllerInfo.isTrusted()
                + ", hints=" + BundleKt.toDebugString(controllerInfo.getConnectionHints()));
        return mediaLibrarySession;
    }

    @NonNull
    public MediaLibrarySession getMediaLibrarySession() {
        return mediaLibrarySession;
    }

    private ListenableFuture<List<MediaItem>> resolveRequestedMediaItems(
            @NonNull final List<MediaItem> mediaItems) {
        final SettableFuture<List<MediaItem>> future = SettableFuture.create();
        if (mediaItems.isEmpty()) {
            future.set(List.of());
            return future;
        }
        resolveRequestedMediaItemsRecursive(mediaItems, 0, new ArrayList<>(), future);
        return future;
    }

    private void resolveRequestedMediaItemsRecursive(
            @NonNull final List<MediaItem> source,
            final int index,
            @NonNull final List<MediaItem> resolvedItems,
            @NonNull final SettableFuture<List<MediaItem>> future) {
        if (index >= source.size()) {
            future.set(List.copyOf(resolvedItems));
            return;
        }

        final MediaItem requestedItem = source.get(index);
        final String requestedMediaId = requestedItem.mediaId;
        if (requestedMediaId != null && !requestedMediaId.isBlank()) {
            try {
                mediaBrowserImpl.getItem(requestedMediaId).subscribe(
                        item -> {
                            resolvedItems.add(item);
                            resolveRequestedMediaItemsRecursive(source, index + 1,
                                    resolvedItems, future);
                        },
                        throwable -> {
                            Log.w(TAG, "Could not resolve Android Auto mediaId="
                                    + requestedMediaId + "; trying URI/title fallback", throwable);
                            searchFallbackItem(source, index, resolvedItems, future);
                        });
            } catch (final RuntimeException e) {
                Log.e(TAG, "Synchronous failure resolving Android Auto mediaId="
                        + requestedMediaId, e);
                searchFallbackItem(source, index, resolvedItems, future);
            }
            return;
        }

        searchFallbackItem(source, index, resolvedItems, future);
    }

    private void searchFallbackItem(@NonNull final List<MediaItem> source,
                                    final int index,
                                    @NonNull final List<MediaItem> resolvedItems,
                                    @NonNull final SettableFuture<List<MediaItem>> future) {
        final MediaItem requestedItem = source.get(index);
        final Uri requestedUri = requestedItem.localConfiguration == null
                ? null : requestedItem.localConfiguration.uri;
        if (requestedUri != null) {
            Log.w(TAG, "Falling back to selected media URI after mediaId resolution failed: "
                    + requestedUri);
            resolvedItems.add(requestedItem.buildUpon()
                    .setMediaId(URI_MEDIA_ID_PREFIX + requestedUri)
                    .build());
            resolveRequestedMediaItemsRecursive(source, index + 1, resolvedItems, future);
            return;
        }
        final CharSequence title = requestedItem.mediaMetadata.title;
        final String query = title == null ? "" : title.toString().trim();
        if (query.isEmpty()) {
            Log.w(TAG, "Android Auto sent an item without mediaId or title; index=" + index);
            resolveRequestedMediaItemsRecursive(source, index + 1, resolvedItems, future);
            return;
        }

        Log.i(TAG, "Resolving Android Auto selection by title fallback: " + query);
        try {
            mediaBrowserImpl.search(query).subscribe(
                    items -> {
                        if (!items.isEmpty()) {
                            resolvedItems.add(items.get(0));
                        }
                        resolveRequestedMediaItemsRecursive(source, index + 1,
                                resolvedItems, future);
                    },
                    throwable -> {
                        reportCarPlaybackError("Busca de faixa", "Falha ao buscar '" + query + "'",
                                throwable);
                        resolveRequestedMediaItemsRecursive(source, index + 1,
                                resolvedItems, future);
                    });
        } catch (final RuntimeException e) {
            reportCarPlaybackError("Busca de faixa", "Falha ao buscar '" + query + "'", e);
            resolveRequestedMediaItemsRecursive(source, index + 1, resolvedItems, future);
        }
    }

    private final class MediaLibraryCallback implements MediaLibrarySession.Callback {
        @Override
        public MediaSession.ConnectionResult onConnect(
                @NonNull final MediaSession session,
                @NonNull final MediaSession.ControllerInfo controller) {
            Log.i(TAG, "Media controller connected: package=" + controller.getPackageName()
                    + ", uid=" + controller.getUid()
                    + ", trusted=" + controller.isTrusted()
                    + ", knownCaller=" + mediaBrowserImpl.isKnownCaller(
                            controller.getPackageName(), controller.getUid()));
            final MediaSession.ConnectionResult.AcceptedResultBuilder resultBuilder =
                    new MediaSession.ConnectionResult.AcceptedResultBuilder(session);
            if (ANDROID_AUTO_PACKAGE.equals(controller.getPackageName())) {
                final SessionCommands availableSessionCommands = MediaSession.ConnectionResult
                        .DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(new SessionCommand(ACTION_TOGGLE_ANDROID_AUTO_ARTWORK, Bundle.EMPTY))
                        .add(new SessionCommand(ACTION_TOGGLE_CAR_SHUFFLE, Bundle.EMPTY))
                        .add(new SessionCommand(ACTION_TOGGLE_CAR_REPEAT_ONE, Bundle.EMPTY))
                        .build();
                resultBuilder
                        .setAvailableSessionCommands(availableSessionCommands)
                        .setCustomLayout(createAndroidAutoButtons());
            }
            return resultBuilder.build();
        }

        @Override
        public boolean onMediaButtonEvent(@NonNull final MediaSession session,
                                          @NonNull final MediaSession.ControllerInfo controllerInfo,
                                          @NonNull final Intent intent) {
            Log.d(TAG, "Media button event from " + controllerInfo.getPackageName()
                    + ": " + intent.getAction());
            return false;
        }

        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                @NonNull final MediaLibrarySession session,
                @NonNull final MediaSession.ControllerInfo controller,
                @Nullable final LibraryParams params) {
            Log.i(TAG, "Library root requested by " + controller.getPackageName()
                    + ", params=" + libraryParamsToDebugString(params));
            return Futures.immediateFuture(LibraryResult.ofItem(
                    mediaBrowserImpl.getRootItem(),
                    withAndroidAutoContentStyle(params)));
        }

        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                @NonNull final MediaLibrarySession session,
                @NonNull final MediaSession.ControllerInfo controller,
                @NonNull final String mediaId) {
            Log.d(TAG, "Item requested by " + controller.getPackageName()
                    + ": mediaId=" + mediaId);
            final SettableFuture<LibraryResult<MediaItem>> future = SettableFuture.create();
            mediaBrowserImpl.getItem(mediaId).subscribe(
                    item -> future.set(LibraryResult.ofItem(item, null)),
                    throwable -> {
                        Log.e(TAG, "Failed to resolve media item " + mediaId, throwable);
                        AppLog.INSTANCE.record(
                                PlayerService.this, "Android Auto / item", mediaId, throwable);
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
                    });
            return future;
        }

        @Override
        public ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
        onGetChildren(@NonNull final MediaLibrarySession session,
                      @NonNull final MediaSession.ControllerInfo controller,
                      @NonNull final String parentId,
                      final int page,
                      final int pageSize,
                      @Nullable final LibraryParams params) {
            final SettableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
                    future = SettableFuture.create();
            Log.d(TAG, "Children requested by " + controller.getPackageName()
                    + " for parentId=" + parentId + ", page=" + page
                    + ", pageSize=" + pageSize
                    + ", params=" + libraryParamsToDebugString(params));
            mediaBrowserImpl.loadChildren(parentId).subscribe(
                    items -> future.set(LibraryResult.ofItemList(applyPaging(items, page, pageSize),
                            withAndroidAutoContentStyle(params))),
                    throwable -> {
                        Log.e(TAG, "Failed to load children for " + parentId, throwable);
                        AppLog.INSTANCE.record(
                                PlayerService.this, "Android Auto / lista", parentId, throwable);
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO));
                    });
            return future;
        }

        @Override
        public ListenableFuture<LibraryResult<Void>> onSubscribe(
                @NonNull final MediaLibrarySession session,
                @NonNull final MediaSession.ControllerInfo controller,
                @NonNull final String parentId,
                @Nullable final LibraryParams params) {
            Log.d(TAG, "Subscribe requested by " + controller.getPackageName()
                    + ": parentId=" + parentId);
            return Futures.immediateFuture(LibraryResult.ofVoid(params));
        }

        @Override
        public ListenableFuture<LibraryResult<Void>> onUnsubscribe(
                @NonNull final MediaLibrarySession session,
                @NonNull final MediaSession.ControllerInfo controller,
                @NonNull final String parentId) {
            return Futures.immediateFuture(LibraryResult.ofVoid());
        }

        @Override
        public ListenableFuture<LibraryResult<Void>> onSearch(
                @NonNull final MediaLibrarySession session,
                @NonNull final MediaSession.ControllerInfo controller,
                @NonNull final String query,
                @Nullable final LibraryParams params) {
            final SettableFuture<LibraryResult<Void>> future = SettableFuture.create();
            Log.i(TAG, "Search requested by " + controller.getPackageName()
                    + ": " + query
                    + ", params=" + libraryParamsToDebugString(params));
            mediaBrowserImpl.search(query).subscribe(
                    items -> {
                        session.notifySearchResultChanged(controller, query, items.size(), params);
                        future.set(LibraryResult.ofVoid(params));
                    },
                    throwable -> {
                        Log.e(TAG, "Failed to prepare search results for " + query, throwable);
                        AppLog.INSTANCE.record(
                                PlayerService.this, "Android Auto / busca", query, throwable);
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO, params));
                    });
            return future;
        }

        @Override
        public ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
        onGetSearchResult(@NonNull final MediaLibrarySession session,
                          @NonNull final MediaSession.ControllerInfo controller,
                          @NonNull final String query,
                          final int page,
                          final int pageSize,
                          @Nullable final LibraryParams params) {
            final SettableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
                    future = SettableFuture.create();
            Log.i(TAG, "Search result requested by " + controller.getPackageName()
                    + ": query=" + query + ", page=" + page
                    + ", pageSize=" + pageSize
                    + ", params=" + libraryParamsToDebugString(params));
            mediaBrowserImpl.search(query).subscribe(
                    items -> future.set(LibraryResult.ofItemList(applyPaging(items, page, pageSize),
                            params)),
                    throwable -> {
                        Log.e(TAG, "Failed to search media for " + query, throwable);
                        AppLog.INSTANCE.record(
                                PlayerService.this, "Android Auto / busca", query, throwable);
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO));
                    });
            return future;
        }

        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
                @NonNull final MediaSession session,
                @NonNull final MediaSession.ControllerInfo controller,
                @NonNull final List<MediaItem> mediaItems) {
            Log.i(TAG, "Add media items requested by " + controller.getPackageName()
                    + ", count=" + mediaItems.size());
            return resolveRequestedMediaItems(mediaItems);
        }

        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                @NonNull final MediaSession session,
                @NonNull final MediaSession.ControllerInfo controller,
                @NonNull final List<MediaItem> mediaItems,
                final int startIndex,
                final long startPositionMs) {
            Log.i(TAG, "Set media items requested by " + controller.getPackageName()
                    + ", count=" + mediaItems.size()
                    + ", startIndex=" + startIndex
                    + ", startPositionMs=" + startPositionMs);
            final SettableFuture<MediaSession.MediaItemsWithStartPosition> future =
                    SettableFuture.create();
            final ListenableFuture<List<MediaItem>> resolvedItemsFuture =
                    resolveRequestedMediaItems(mediaItems);
            resolvedItemsFuture.addListener(() -> {
                try {
                    final List<MediaItem> resolvedItems = resolvedItemsFuture.get();
                    if (resolvedItems.isEmpty()) {
                        final IllegalStateException error = new IllegalStateException(
                                "Nenhuma faixa foi resolvida para a seleção do Android Auto");
                        reportCarPlaybackError("Seleção de faixa", error.getMessage(), error);
                        future.setException(error);
                        return;
                    }

                    final int index = startIndex >= 0 && startIndex < resolvedItems.size()
                            ? startIndex : 0;
                    final MediaItem selectedItem = resolvedItems.get(index);
                    Log.i(TAG, "Android Auto selected media item: controller="
                            + controller.getPackageName() + ", mediaId=" + selectedItem.mediaId
                            + ", title=" + selectedItem.mediaMetadata.title
                            + ", uri=" + selectedItem.localConfiguration);
                    final String carSettingAction = selectedItem.mediaId == null ? null
                            : mediaBrowserImpl.getCarSettingAction(selectedItem.mediaId);
                    if (carSettingAction != null) {
                        handleCarSettingAction(carSettingAction, session, controller);
                        future.set(new MediaSession.MediaItemsWithStartPosition(
                                mediaSessionPlayer.getCurrentMediaItems(),
                                mediaSessionPlayer.getCurrentMediaItemIndex(),
                                mediaSessionPlayer.getCurrentPositionMs()));
                        return;
                    }
                    if (selectedItem.mediaId != null
                            && selectedItem.mediaId.startsWith(URI_MEDIA_ID_PREFIX)) {
                        final android.net.Uri streamUri = android.net.Uri.parse(
                                selectedItem.mediaId.substring(URI_MEDIA_ID_PREFIX.length()));
                        mediaBrowserPlaybackPreparer.onPrepareFromUri(
                                streamUri, true, Bundle.EMPTY);
                    } else if (selectedItem.mediaId != null
                            && !selectedItem.mediaId.isBlank()) {
                        mediaBrowserPlaybackPreparer.onPrepareFromMediaId(
                                selectedItem.mediaId,
                                true,
                                Bundle.EMPTY);
                    } else {
                        final CharSequence title = selectedItem.mediaMetadata.title;
                        mediaBrowserPlaybackPreparer.onPrepareFromSearch(
                                title == null ? "" : title.toString(),
                                true,
                                Bundle.EMPTY);
                    }
                    future.set(new MediaSession.MediaItemsWithStartPosition(
                            resolvedItems,
                            index,
                            startPositionMs));
                } catch (final Exception e) {
                    Log.e(TAG, "Failed to handle setMediaItems", e);
                    reportCarPlaybackError("Seleção de faixa",
                            "O carro não conseguiu abrir a música selecionada", e);
                    future.setException(e);
                }
            }, Runnable::run);
            return future;
        }

        private void handleCarSettingAction(
                @NonNull final String action,
                @NonNull final MediaSession session,
                @NonNull final MediaSession.ControllerInfo controller) {
            if (MediaBrowserImpl.ACTION_CAR_ARTWORK.equals(action)) {
                toggleAndroidAutoArtwork();
            } else {
                ensurePlayer();
                if (MediaBrowserImpl.ACTION_CAR_SHUFFLE.equals(action)) {
                    player.toggleShuffleModeEnabled();
                } else if (MediaBrowserImpl.ACTION_CAR_REPEAT.equals(action)) {
                    player.toggleRepeatOne();
                }
            }
            mediaSessionPlayer.refresh();
            mediaBrowserImpl.notifyCarSettingsChanged();
            session.setCustomLayout(controller, createAndroidAutoButtons());
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                @NonNull final MediaSession session,
                @NonNull final MediaSession.ControllerInfo controller,
                @NonNull final androidx.media3.session.SessionCommand customCommand,
                @NonNull final Bundle args) {
            if (ACTION_TOGGLE_ANDROID_AUTO_ARTWORK.equals(customCommand.customAction)) {
                toggleAndroidAutoArtwork();
            } else if (ACTION_TOGGLE_CAR_SHUFFLE.equals(customCommand.customAction)) {
                ensurePlayer();
                player.toggleShuffleModeEnabled();
            } else if (ACTION_TOGGLE_CAR_REPEAT_ONE.equals(customCommand.customAction)) {
                ensurePlayer();
                player.toggleRepeatOne();
            } else {
                sendBroadcast(new Intent(customCommand.customAction));
            }
            mediaSessionPlayer.refresh();
            return session.setCustomLayout(controller, createAndroidAutoButtons());
        }
    }

    private void reportCarPlaybackError(final String stage, final String details,
                                       @Nullable final Throwable throwable) {
        final String message = "Android Auto playback failure at " + stage + ": " + details;
        if (throwable == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, throwable);
        }
        AppLog.INSTANCE.record(this, "Android Auto / " + stage, details, throwable);
        final String visibleDetails = (stage + ": " + details).length() > 180
                ? (stage + ": " + details).substring(0, 177) + "..."
                : stage + ": " + details;
        new android.os.Handler(getMainLooper()).post(() -> Toast.makeText(
                getApplicationContext(),
                "Iradu Music parece não estar funcionando no momento. "
                        + visibleDetails,
                Toast.LENGTH_LONG).show());
    }

    private static List<MediaItem> applyPaging(final List<MediaItem> items,
                                               final int page,
                                               final int pageSize) {
        if (page < 0 || pageSize <= 0) {
            return items;
        }
        final int fromIndex = page * pageSize;
        if (fromIndex >= items.size()) {
            return List.of();
        }
        return items.subList(fromIndex, Math.min(fromIndex + pageSize, items.size()));
    }

    @NonNull
    private static LibraryParams withAndroidAutoContentStyle(@Nullable final LibraryParams params) {
        final Bundle extras = params == null || params.extras == null
                ? new Bundle()
                : new Bundle(params.extras);
        extras.putInt(
                CONTENT_STYLE_BROWSABLE_HINT,
                CONTENT_STYLE_GRID_ITEM);
        extras.putInt(
                CONTENT_STYLE_PLAYABLE_HINT,
                CONTENT_STYLE_LIST_ITEM);

        return new LibraryParams.Builder()
                .setExtras(extras)
                .setRecent(params != null && params.isRecent)
                .setOffline(params != null && params.isOffline)
                .setSuggested(params != null && params.isSuggested)
                .build();
    }

    private static String libraryParamsToDebugString(@Nullable final LibraryParams params) {
        if (params == null) {
            return "null";
        }
        return "LibraryParams{"
                + "recent=" + params.isRecent
                + ", offline=" + params.isOffline
                + ", suggested=" + params.isSuggested
                + ", extras=" + BundleKt.toDebugString(params.extras)
                + "}";
    }
}
