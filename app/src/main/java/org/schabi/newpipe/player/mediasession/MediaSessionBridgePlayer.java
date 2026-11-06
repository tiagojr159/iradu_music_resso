package org.schabi.newpipe.player.mediasession;

import android.net.Uri;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player.Commands;
import androidx.media3.common.SimpleBasePlayer;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.util.image.ImageStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class MediaSessionBridgePlayer extends SimpleBasePlayer {
    private final Supplier<Player> playerSupplier;
    private final Runnable playFallback;
    private final BooleanSupplier showArtworkSupplier;

    public MediaSessionBridgePlayer(@NonNull final Supplier<Player> playerSupplier,
                                    @NonNull final Runnable playFallback,
                                    @NonNull final BooleanSupplier showArtworkSupplier) {
        super(Looper.getMainLooper());
        this.playerSupplier = playerSupplier;
        this.playFallback = playFallback;
        this.showArtworkSupplier = showArtworkSupplier;
    }

    public void refresh() {
        invalidateState();
    }

    @Override
    protected State getState() {
        final Player appPlayer = playerSupplier.get();
        final Commands commands = new Commands.Builder()
                .addAll(
                        androidx.media3.common.Player.COMMAND_PLAY_PAUSE,
                        androidx.media3.common.Player.COMMAND_PREPARE,
                        androidx.media3.common.Player.COMMAND_STOP,
                        androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS,
                        androidx.media3.common.Player.COMMAND_SEEK_BACK,
                        androidx.media3.common.Player.COMMAND_SEEK_FORWARD,
                        androidx.media3.common.Player.COMMAND_SET_REPEAT_MODE,
                        androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE,
                        androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_GET_TIMELINE,
                        androidx.media3.common.Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
                        androidx.media3.common.Player.COMMAND_GET_METADATA
                )
                .build();

        final long seekIncrementMs = appPlayer != null
                ? PlayerHelper.retrieveSeekDurationFromPreferences(appPlayer) : 10_000L;
        final State.Builder builder = new State.Builder()
                .setAvailableCommands(commands)
                .setSeekBackIncrementMs(seekIncrementMs)
                .setSeekForwardIncrementMs(seekIncrementMs)
                .setPlaybackState(toMedia3PlaybackState(appPlayer))
                .setPlayWhenReady(appPlayer != null && appPlayer.getPlayWhenReady(),
                        androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setRepeatMode(appPlayer == null
                        ? androidx.media3.common.Player.REPEAT_MODE_OFF
                        : appPlayer.getRepeatMode())
                .setShuffleModeEnabled(appPlayer != null && appPlayer.getShuffleModeEnabled())
                .setPlaylist(buildPlaylist(appPlayer))
                .setCurrentMediaItemIndex(currentIndex(appPlayer))
                .setContentPositionMs(currentPosition(appPlayer))
                .setContentBufferedPositionMs(
                        PositionSupplier.getConstant(bufferedPosition(appPlayer)))
                .setTotalBufferedDurationMs(
                        PositionSupplier.getConstant(totalBufferedDuration(appPlayer)));

        return builder.build();
    }

    private int toMedia3PlaybackState(@Nullable final Player appPlayer) {
        if (appPlayer == null) {
            return androidx.media3.common.Player.STATE_IDLE;
        }
        return switch (appPlayer.getCurrentState()) {
            case Player.STATE_BLOCKED, Player.STATE_BUFFERING, Player.STATE_PREFLIGHT ->
                    androidx.media3.common.Player.STATE_BUFFERING;
            case Player.STATE_COMPLETED -> androidx.media3.common.Player.STATE_ENDED;
            case Player.STATE_PLAYING, Player.STATE_PAUSED, Player.STATE_PAUSED_SEEK ->
                    androidx.media3.common.Player.STATE_READY;
            default -> androidx.media3.common.Player.STATE_IDLE;
        };
    }

    private List<MediaItemData> buildPlaylist(@Nullable final Player appPlayer) {
        final PlayQueue playQueue = appPlayer == null ? null : appPlayer.getPlayQueue();
        if (playQueue == null || playQueue.size() == 0) {
            return List.of(new MediaItemData.Builder("empty")
                    .setMediaItem(new MediaItem.Builder()
                            .setMediaId("empty")
                            .setMediaMetadata(new MediaMetadata.Builder()
                                    .setTitle("")
                                    .setIsPlayable(false)
                                    .build())
                            .build())
                    .setDurationUs(C.TIME_UNSET)
                    .build());
        }

        final List<MediaItemData> items = new ArrayList<>();
        for (int i = 0; i < playQueue.size(); i++) {
            final PlayQueueItem item = playQueue.getItem(i);
            final String mediaId = item.getServiceId() + ":" + item.getUrl();
            final MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .setTitle(item.getTitle())
                    .setArtist(item.getUploader())
                    .setSubtitle(item.getUploader())
                    .setDurationMs(item.getDuration() > 0 ? item.getDuration() * 1000L : null)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC);
            if (showArtworkSupplier.getAsBoolean()) {
                Optional.ofNullable(ImageStrategy.choosePreferredImage(item.getThumbnails()))
                        .map(Uri::parse)
                        .ifPresent(metadata::setArtworkUri);
            }

            final MediaItem mediaItem = new MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(item.getUrl())
                    .setMediaMetadata(metadata.build())
                    .build();
            items.add(new MediaItemData.Builder(i)
                    .setMediaItem(mediaItem)
                    .setMediaMetadata(mediaItem.mediaMetadata)
                    .setIsSeekable(item.getDuration() > 0)
                    .setDurationUs(item.getDuration() > 0
                            ? item.getDuration() * C.MICROS_PER_SECOND
                            : C.TIME_UNSET)
                    .build());
        }
        return items;
    }

    private int currentIndex(@Nullable final Player appPlayer) {
        return appPlayer != null && appPlayer.getPlayQueue() != null
                ? appPlayer.getPlayQueue().getIndex() : 0;
    }

    private long currentPosition(@Nullable final Player appPlayer) {
        return appPlayer != null && !appPlayer.exoPlayerIsNull()
                ? Math.max(appPlayer.getExoPlayer().getCurrentPosition(), 0) : 0;
    }

    private long bufferedPosition(@Nullable final Player appPlayer) {
        return appPlayer != null && !appPlayer.exoPlayerIsNull()
                ? Math.max(appPlayer.getExoPlayer().getBufferedPosition(), 0) : 0;
    }

    private long totalBufferedDuration(@Nullable final Player appPlayer) {
        return appPlayer != null && !appPlayer.exoPlayerIsNull()
                ? Math.max(appPlayer.getExoPlayer().getTotalBufferedDuration(), 0) : 0;
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(final boolean playWhenReady) {
        final Player appPlayer = playerSupplier.get();
        if (appPlayer != null) {
            if (playWhenReady) {
                appPlayer.play();
            } else {
                appPlayer.pause();
            }
        } else if (playWhenReady) {
            playFallback.run();
        }
        refresh();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        final Player appPlayer = playerSupplier.get();
        if (appPlayer != null) {
            appPlayer.onPrepare();
        } else {
            playFallback.run();
        }
        refresh();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        final Player appPlayer = playerSupplier.get();
        if (appPlayer != null) {
            appPlayer.getService().destroyPlayerAndStopService();
        }
        refresh();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(final int mediaItemIndex,
                                            final long positionMs,
                                            final int seekCommand) {
        final Player appPlayer = playerSupplier.get();
        if (appPlayer != null) {
            if (seekCommand == androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS
                    || seekCommand
                    == androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                appPlayer.playPrevious();
            } else if (seekCommand == androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT
                    || seekCommand
                    == androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                appPlayer.playNext();
            } else if (seekCommand == androidx.media3.common.Player.COMMAND_SEEK_BACK) {
                appPlayer.fastRewind();
            } else if (seekCommand == androidx.media3.common.Player.COMMAND_SEEK_FORWARD) {
                appPlayer.fastForward();
            } else if (appPlayer.getPlayQueue() != null
                    && seekCommand == androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM) {
                appPlayer.selectQueueItem(appPlayer.getPlayQueue().getItem(mediaItemIndex));
            } else {
                appPlayer.seekTo(positionMs);
            }
        }
        refresh();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(final int repeatMode) {
        final Player appPlayer = playerSupplier.get();
        if (appPlayer != null) {
            appPlayer.setRepeatMode(repeatMode);
        }
        refresh();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetShuffleModeEnabled(final boolean shuffleModeEnabled) {
        final Player appPlayer = playerSupplier.get();
        if (appPlayer != null) {
            appPlayer.setShuffleModeEnabled(shuffleModeEnabled);
        }
        refresh();
        return Futures.immediateVoidFuture();
    }
}
