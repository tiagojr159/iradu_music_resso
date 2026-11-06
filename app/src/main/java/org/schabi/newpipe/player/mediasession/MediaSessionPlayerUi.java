package org.schabi.newpipe.player.mediasession;

import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION;

import android.content.Intent;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player.RepeatMode;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.ui.PlayerUi;

public class MediaSessionPlayerUi extends PlayerUi {
    @NonNull
    private final MediaSessionBridgePlayer mediaSessionPlayer;

    public MediaSessionPlayerUi(@NonNull final Player player,
                                @NonNull final MediaSessionBridgePlayer mediaSessionPlayer) {
        super(player);
        this.mediaSessionPlayer = mediaSessionPlayer;
    }

    @Override
    public void initPlayer() {
        super.initPlayer();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void destroyPlayer() {
        super.destroyPlayer();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void initPlayback() {
        super.initPlayback();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onThumbnailLoaded(@Nullable final Bitmap bitmap) {
        super.onThumbnailLoaded(bitmap);
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onBlocked() {
        super.onBlocked();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onPlaying() {
        super.onPlaying();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onBuffering() {
        super.onBuffering();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onPaused() {
        super.onPaused();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onPausedSeek() {
        super.onPausedSeek();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onCompleted() {
        super.onCompleted();
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onRepeatModeChanged(@RepeatMode final int repeatMode) {
        super.onRepeatModeChanged(repeatMode);
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled);
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onBroadcastReceived(final Intent intent) {
        super.onBroadcastReceived(intent);
        if (ACTION_RECREATE_NOTIFICATION.equals(intent.getAction())) {
            mediaSessionPlayer.refresh();
        }
    }

    @Override
    public void onMetadataChanged(@NonNull final StreamInfo info) {
        super.onMetadataChanged(info);
        mediaSessionPlayer.refresh();
    }

    @Override
    public void onPlayQueueEdited() {
        super.onPlayQueueEdited();
        mediaSessionPlayer.refresh();
    }
}
