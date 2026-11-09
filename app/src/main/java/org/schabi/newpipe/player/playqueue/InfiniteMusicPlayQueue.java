package org.schabi.newpipe.player.playqueue;

import androidx.annotation.NonNull;

import org.schabi.newpipe.music.MusicCatalogRepository;

import java.util.List;
import java.util.stream.Collectors;

public final class InfiniteMusicPlayQueue extends PlayQueue {
    private static final int INITIAL_BATCH_SIZE = 18;
    private static final int FETCH_BATCH_SIZE = 12;

    @NonNull
    private final List<MusicCatalogRepository.MusicSong> sourceSongs;
    private int nextCursor;
    private int cycleSeed;

    public InfiniteMusicPlayQueue(
            @NonNull final MusicCatalogRepository.MusicPlaylist playlist) {
        this(playlist, 0);
    }

    public InfiniteMusicPlayQueue(
            @NonNull final MusicCatalogRepository.MusicPlaylist playlist,
            final int startIndex) {
        this(playlist.songs, startIndex);
    }

    public InfiniteMusicPlayQueue(
            @NonNull final List<MusicCatalogRepository.MusicSong> songs,
            final int startIndex) {
        super(0, createItems(songs, startIndex, INITIAL_BATCH_SIZE, 0));
        sourceSongs = List.copyOf(songs);
        nextCursor = MusicCatalogRepository.normalizeIndex(startIndex, sourceSongs.size());
        cycleSeed = 0;
        advanceWindow(INITIAL_BATCH_SIZE);
    }

    @Override
    public boolean isComplete() {
        return sourceSongs.isEmpty();
    }

    @Override
    public synchronized void fetch() {
        if (sourceSongs.isEmpty()) {
            return;
        }

        append(createItems(sourceSongs, nextCursor, FETCH_BATCH_SIZE, cycleSeed));
        advanceWindow(FETCH_BATCH_SIZE);
    }

    private void advanceWindow(final int amount) {
        if (sourceSongs.isEmpty()) {
            return;
        }

        nextCursor = MusicCatalogRepository.normalizeIndex(nextCursor + amount, sourceSongs.size());
        cycleSeed += Math.max(1, (amount + sourceSongs.size() - 1) / sourceSongs.size());
    }

    @NonNull
    private static List<PlayQueueItem> createItems(
            @NonNull final List<MusicCatalogRepository.MusicSong> songs,
            final int startIndex,
            final int count,
            final int cycleSeed) {
        return MusicCatalogRepository.buildSongBatch(songs, startIndex, count, cycleSeed)
                .stream()
                .map(InfiniteMusicPlayQueue::toQueueItem)
                .collect(Collectors.toList());
    }

    @NonNull
    private static PlayQueueItem toQueueItem(
            @NonNull final MusicCatalogRepository.MusicSong song) {
        return new MusicCatalogPlayQueueItem(song);
    }

    private static long parseDurationSeconds(@NonNull final String duration) {
        final String[] parts = duration.trim().split(":");
        long total = 0L;
        for (final String part : parts) {
            try {
                total = (total * 60L) + Long.parseLong(part);
            } catch (final NumberFormatException ignored) {
                return 0L;
            }
        }
        return total;
    }
}
