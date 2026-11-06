package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.NavigationHelper;

public final class LastPlaybackManager {
    private static final String KEY_SERVICE_ID = "iradu_last_playback_service_id";
    private static final String KEY_URL = "iradu_last_playback_url";
    private static final String KEY_TITLE = "iradu_last_playback_title";
    private static final String KEY_UPLOADER = "iradu_last_playback_uploader";
    private static final String KEY_UPLOADER_URL = "iradu_last_playback_uploader_url";
    private static final String KEY_STREAM_TYPE = "iradu_last_playback_stream_type";
    private static final String KEY_DURATION = "iradu_last_playback_duration";
    private static final String KEY_POSITION = "iradu_last_playback_position";
    private static final String KEY_UPDATED_AT = "iradu_last_playback_updated_at";

    private LastPlaybackManager() {
    }

    public static void save(@NonNull final Context context,
                            @NonNull final PlayQueueItem item,
                            final long positionMillis,
                            final long durationMillis) {
        if (item.getUrl().isEmpty()) {
            return;
        }

        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(KEY_SERVICE_ID, item.getServiceId())
                .putString(KEY_URL, item.getUrl())
                .putString(KEY_TITLE, item.getTitle())
                .putString(KEY_UPLOADER, item.getUploader())
                .putString(KEY_UPLOADER_URL, item.getUploaderUrl())
                .putString(KEY_STREAM_TYPE, item.getStreamType().name())
                .putLong(KEY_DURATION, item.getDuration())
                .putLong(KEY_POSITION, Math.max(0, positionMillis))
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public static boolean hasSavedPlayback(@NonNull final Context context) {
        return !PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_URL, "").isEmpty();
    }

    public static void playSavedPlayback(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String url = prefs.getString(KEY_URL, "");
        final String title = prefs.getString(KEY_TITLE, "");
        if (url.isEmpty() || title.isEmpty()) {
            return;
        }

        final StreamType streamType;
        try {
            streamType = StreamType.valueOf(
                    prefs.getString(KEY_STREAM_TYPE, StreamType.AUDIO_STREAM.name()));
        } catch (final IllegalArgumentException e) {
            return;
        }

        final StreamInfoItem item = new StreamInfoItem(
                prefs.getInt(KEY_SERVICE_ID, 0),
                url,
                title,
                streamType);
        item.setUploaderName(prefs.getString(KEY_UPLOADER, ""));
        item.setUploaderUrl(prefs.getString(KEY_UPLOADER_URL, null));
        item.setDuration(prefs.getLong(KEY_DURATION, 0));

        NavigationHelper.playOnBackgroundPlayer(context,
                new SinglePlayQueue(item, prefs.getLong(KEY_POSITION, 0)),
                true);
    }
}
