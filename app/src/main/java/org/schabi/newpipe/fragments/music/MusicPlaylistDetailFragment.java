package org.schabi.newpipe.fragments.music;

import static org.schabi.newpipe.ktx.ViewUtils.animatePressFeedback;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.music.MusicCatalogRepository;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerService;
import org.schabi.newpipe.player.event.PlayerServiceExtendedEventListener;
import org.schabi.newpipe.player.helper.IraduEqualizerDialog;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.image.CoilHelper;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class MusicPlaylistDetailFragment extends BaseMusicCatalogFragment
        implements PlayerServiceExtendedEventListener {
    private static final String PLAYLIST_SLUG_KEY = "playlist_slug";
    private static final String SAVED_PLAYLIST_ID_KEY = "saved_playlist_id";
    private static final String SAVED_PLAYLIST_NAME_KEY = "saved_playlist_name";
    private static final int LOAD_MORE_THRESHOLD_PX = 480;
    private static final int SEARCH_BATCH_MIN_APPEND = 8;
    private static final Random RANDOM = new Random();
    private static final long ANIMATION_DURATION_MS = 650L;

    @Nullable
    private MusicCatalogRepository.MusicPlaylist playlist;
    @Nullable
    private Long savedPlaylistId;
    @Nullable
    private String savedPlaylistName;
    @Nullable
    private NestedScrollView scrollView;
    @Nullable
    private LinearLayout songList;
    @Nullable
    private Disposable loadMoreDisposable;
    @Nullable
    private Page nextSearchPage;
    private int searchQueryIndex;
    private boolean initialSearchLoaded;
    private boolean hasMoreRemoteSongs;
    private boolean isLoadingMore;
    @NonNull
    private final List<MusicCatalogRepository.MusicSong> displayedSongs = new ArrayList<>();
    @NonNull
    private final Set<String> loadedSongIds = new LinkedHashSet<>();
    @NonNull
    private final Map<String, SongRowHolder> songRows = new HashMap<>();
    @NonNull
    private final List<MusicCatalogRepository.MusicSong> savedPlaylistSongs = new ArrayList<>();
    @NonNull
    private final PlayerHolder playerHolder = PlayerHolder.getInstance();
    @Nullable
    private String currentPlayingSongId;
    @Nullable
    private Player activePlayer;
    private int currentPlaybackState = Player.STATE_PREFLIGHT;
    private int currentProgressMs = -1;
    private int currentDurationMs = -1;

    public static MusicPlaylistDetailFragment newInstance(@NonNull final String slug) {
        final MusicPlaylistDetailFragment fragment = new MusicPlaylistDetailFragment();
        final Bundle args = new Bundle();
        args.putString(PLAYLIST_SLUG_KEY, slug);
        fragment.setArguments(args);
        return fragment;
    }

    public static MusicPlaylistDetailFragment newInstanceSavedPlaylist(
            final long playlistId,
            @Nullable final String name) {
        final MusicPlaylistDetailFragment fragment = new MusicPlaylistDetailFragment();
        final Bundle args = new Bundle();
        args.putLong(SAVED_PLAYLIST_ID_KEY, playlistId);
        args.putString(SAVED_PLAYLIST_NAME_KEY, name);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_music_playlist_detail, container, false);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        try {
            if (getArguments() == null) {
                return;
            }

            final boolean isSavedPlaylist = getArguments().containsKey(SAVED_PLAYLIST_ID_KEY);
            if (isSavedPlaylist) {
                initSavedPlaylist(rootView);
                return;
            }

            if (!loadCatalogOrNotify() || catalog == null) {
                return;
            }

            final String slug = getArguments().getString(PLAYLIST_SLUG_KEY, "");
            playlist = catalog.getPlaylist(slug);
            if (playlist == null) {
                return;
            }

            setTitle(playlist.title);

            ((TextView) rootView.findViewById(R.id.music_detail_title))
                    .setText(playlist.title);
            ((TextView) rootView.findViewById(R.id.music_detail_subtitle))
                    .setText(playlist.subtitle);
            ((TextView) rootView.findViewById(R.id.music_detail_description))
                    .setText(playlist.description);

            final ImageView heroImage = rootView.findViewById(R.id.music_detail_image);
            CoilHelper.INSTANCE.loadThumbnail(heroImage, playlist.getHeroImageUrl());

            rootView.findViewById(R.id.music_detail_play_button)
                    .setOnClickListener(v -> {
                        animatePressFeedback(v);
                        if (!playlist.songs.isEmpty()) {
                            playSongsFrom(playlist.songs, 0, playlist.songs.get(0));
                        }
                    });
            rootView.findViewById(R.id.music_detail_shuffle_button)
                    .setOnClickListener(v -> {
                        animatePressFeedback(v);
                        playPlaylistShuffled();
                    });
            rootView.findViewById(R.id.music_detail_equalizer_button)
                    .setOnClickListener(v -> {
                        animatePressFeedback(v);
                        openEqualizer();
                    });
            rootView.findViewById(R.id.music_detail_enqueue_button)
                    .setOnClickListener(v -> {
                        animatePressFeedback(v);
                        enqueueSongs(displayedSongs);
                    });

            songList = rootView.findViewById(R.id.music_detail_song_list);
            songList.removeAllViews();
            displayedSongs.clear();
            loadedSongIds.clear();
            songRows.clear();
            nextSearchPage = null;
            searchQueryIndex = 0;
            initialSearchLoaded = false;
            hasMoreRemoteSongs = true;
            isLoadingMore = false;

            appendSongs(playlist.songs);

            scrollView = rootView.findViewById(R.id.music_detail_scroll);
            scrollView.setOnScrollChangeListener((NestedScrollView v, final int scrollX,
                    final int scrollY, final int oldScrollX, final int oldScrollY) -> {
                if (scrollY > oldScrollY && shouldLoadMore(v)) {
                    loadMoreSongsForTheme();
                }
            });
            requestMoreSongsIfScreenIsShort();
        } catch (final Throwable e) {
            ErrorUtil.showUiErrorSnackbar(
                    this, "Initializing music playlist detail", e);
        }
    }

    private void initSavedPlaylist(@NonNull final View rootView) {
        if (getArguments() == null) {
            return;
        }

        savedPlaylistId = getArguments().getLong(SAVED_PLAYLIST_ID_KEY);
        savedPlaylistName = getArguments().getString(SAVED_PLAYLIST_NAME_KEY, "");

        final var database = NewPipeDatabase.getInstance(requireContext());
        final var playlistManager = new LocalPlaylistManager(database);
        final List<PlaylistStreamEntry> streams = playlistManager.getPlaylistStreams(
                savedPlaylistId).firstOrError().blockingGet();

        savedPlaylistSongs.clear();
        for (final PlaylistStreamEntry entry : streams) {
            final var song = MusicCatalogRepository.fromStreamInfoItem(entry.toStreamInfoItem());
            if (song != null) {
                savedPlaylistSongs.add(song);
            }
        }

        setTitle(savedPlaylistName == null ? "" : savedPlaylistName);
        ((TextView) rootView.findViewById(R.id.music_detail_title))
                .setText(savedPlaylistName);
        ((TextView) rootView.findViewById(R.id.music_detail_subtitle))
                .setText(getString(R.string.tab_bookmarks_short));
        ((TextView) rootView.findViewById(R.id.music_detail_description))
                .setText(R.string.resso_favorite_music_desc);

        final ImageView heroImage = rootView.findViewById(R.id.music_detail_image);
        if (!savedPlaylistSongs.isEmpty()) {
            CoilHelper.INSTANCE.loadThumbnail(
                    heroImage,
                    savedPlaylistSongs.get(0).getThumbnailUrl());
        }

        rootView.findViewById(R.id.music_detail_play_button)
                .setOnClickListener(v -> {
                    animatePressFeedback(v);
                    playSavedPlaylist(savedPlaylistSongs, 0);
                });
        rootView.findViewById(R.id.music_detail_shuffle_button)
                .setOnClickListener(v -> {
                    animatePressFeedback(v);
                    playSavedPlaylistShuffled();
                });
        rootView.findViewById(R.id.music_detail_equalizer_button)
                .setOnClickListener(v -> {
                    animatePressFeedback(v);
                    openEqualizer();
                });
        rootView.findViewById(R.id.music_detail_enqueue_button)
                .setOnClickListener(v -> {
                    animatePressFeedback(v);
                    enqueueSongs(savedPlaylistSongs);
                });

        songList = rootView.findViewById(R.id.music_detail_song_list);
        songList.removeAllViews();
        displayedSongs.clear();
        loadedSongIds.clear();
        songRows.clear();
        appendSongs(savedPlaylistSongs);
        scrollView = rootView.findViewById(R.id.music_detail_scroll);
    }

    @Override
    public void onResume() {
        super.onResume();
        playerHolder.tryBindIfNeeded(requireContext());
        playerHolder.setListener(this);
        refreshSongRowStates();
    }

    @Override
    public void onPause() {
        playerHolder.setListener(null);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (loadMoreDisposable != null) {
            loadMoreDisposable.dispose();
            loadMoreDisposable = null;
        }
        for (final SongRowHolder holder : songRows.values()) {
            holder.stopAnimation();
        }
        songRows.clear();
        scrollView = null;
        songList = null;
    }

    private boolean shouldLoadMore(@NonNull final NestedScrollView nestedScrollView) {
        if (nestedScrollView.getChildCount() == 0) {
            return false;
        }

        final View content = nestedScrollView.getChildAt(0);
        return nestedScrollView.getScrollY() + nestedScrollView.getHeight()
                >= content.getHeight() - LOAD_MORE_THRESHOLD_PX;
    }

    private void loadMoreSongsForTheme() {
        if (playlist == null
                || songList == null
                || isLoadingMore
                || !hasMoreRemoteSongs) {
            return;
        }

        final List<String> queries = MusicCatalogRepository.getSearchQueries(playlist);
        if (queries.isEmpty() || searchQueryIndex >= queries.size()) {
            hasMoreRemoteSongs = false;
            return;
        }

        isLoadingMore = true;
        final String query = queries.get(searchQueryIndex);
        final int serviceId = ServiceList.YouTube.getServiceId();
        final List<String> contentFilter =
                List.of(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS);

        final Single<SearchChunk> request = initialSearchLoaded && nextSearchPage != null
                ? ExtractorHelper.getMoreSearchItems(
                        serviceId, query, contentFilter, "", nextSearchPage)
                .map(result -> new SearchChunk(
                        result.getItems(),
                        result.getNextPage(),
                        result.hasNextPage()))
                : ExtractorHelper.searchFor(serviceId, query, contentFilter, "")
                .map(result -> new SearchChunk(
                        result.getRelatedItems(),
                        result.getNextPage(),
                        result.hasNextPage()));

        loadMoreDisposable = request.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (playlist == null) {
                        return;
                    }

                    initialSearchLoaded = true;
                    nextSearchPage = result.getNextPage();

                    final List<MusicCatalogRepository.MusicSong> newSongs = result.getItems()
                            .stream()
                            .filter(StreamInfoItem.class::isInstance)
                            .map(StreamInfoItem.class::cast)
                            .map(MusicCatalogRepository::fromStreamInfoItem)
                            .filter(song -> song != null)
                            .map(song -> (MusicCatalogRepository.MusicSong) song)
                            .filter(song -> !loadedSongIds.contains(song.youtubeId))
                            .collect(Collectors.toList());

                    appendSongs(newSongs);

                    if (!result.hasNextPage()) {
                        searchQueryIndex++;
                        initialSearchLoaded = false;
                        nextSearchPage = null;
                    }

                    if (newSongs.size() < SEARCH_BATCH_MIN_APPEND
                            && searchQueryIndex < queries.size()) {
                        isLoadingMore = false;
                        loadMoreSongsForTheme();
                        return;
                    }

                    hasMoreRemoteSongs = searchQueryIndex < queries.size()
                            || (result.hasNextPage() && nextSearchPage != null);
                    isLoadingMore = false;
                    requestMoreSongsIfScreenIsShort();
                }, throwable -> {
                    isLoadingMore = false;
                    hasMoreRemoteSongs = searchQueryIndex + 1 < queries.size();
                    searchQueryIndex++;
                    initialSearchLoaded = false;
                    nextSearchPage = null;
                    if (!hasMoreRemoteSongs) {
                        ErrorUtil.showUiErrorSnackbar(
                                this, "Loading themed songs", throwable);
                    }
                });
    }

    private void appendSongs(
            @NonNull final List<MusicCatalogRepository.MusicSong> songsToAppend) {
        if (songList == null || songsToAppend.isEmpty()) {
            return;
        }

        final LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (final MusicCatalogRepository.MusicSong song : songsToAppend) {
            if (!loadedSongIds.add(song.youtubeId)) {
                continue;
            }

            displayedSongs.add(song);

            final View row = inflater.inflate(
                    R.layout.item_music_song_row, songList, false);
            final SongRowHolder holder = new SongRowHolder(row, song);
            holder.titleView.setText(song.title);
            holder.metaView.setText(song.artist + (song.album.isEmpty() ? "" : " - " + song.album));
            holder.durationView.setText(song.duration);
            CoilHelper.INSTANCE.loadThumbnail(
                    holder.imageView,
                    song.getThumbnailUrl());
            songRows.put(song.youtubeId, holder);
            row.setOnClickListener(v -> {
                animatePressFeedback(v);
                playSongRow(song);
            });
            holder.enqueueView.setOnClickListener(v -> {
                animatePressFeedback(v);
                enqueueSong(song);
            });
            songList.addView(row);
            updateSongRow(holder);
        }
    }

    private void requestMoreSongsIfScreenIsShort() {
        if (scrollView == null || !hasMoreRemoteSongs) {
            return;
        }

        scrollView.post(() -> {
            if (scrollView != null && shouldLoadMore(scrollView)) {
                loadMoreSongsForTheme();
            }
        });
    }

    private void playPlaylistShuffled() {
        if (playlist == null || playlist.songs.isEmpty()) {
            return;
        }

        final List<MusicCatalogRepository.MusicSong> shuffledSongs =
                new ArrayList<>(playlist.songs);
        Collections.shuffle(shuffledSongs, RANDOM);
        playSongsFrom(shuffledSongs, 0, shuffledSongs.get(0));
    }

    private void playSavedPlaylistShuffled() {
        if (savedPlaylistSongs.isEmpty()) {
            return;
        }

        final List<MusicCatalogRepository.MusicSong> shuffledSongs =
                new ArrayList<>(savedPlaylistSongs);
        Collections.shuffle(shuffledSongs, RANDOM);
        playSavedPlaylist(shuffledSongs, 0);
    }

    private void playSavedPlaylist(@NonNull final List<MusicCatalogRepository.MusicSong> songs,
                                   final int startIndex) {
        if (songs.isEmpty()) {
            return;
        }

        final int safeIndex = Math.max(0, Math.min(startIndex, songs.size() - 1));
        final MusicCatalogRepository.MusicSong song = songs.get(safeIndex);
        playSongsFrom(songs, safeIndex, song);
    }

    private void playDisplayedSongsFrom(@NonNull final MusicCatalogRepository.MusicSong song) {
        final int index = displayedSongs.indexOf(song);
        if (index < 0) {
            return;
        }

        playSongsFrom(displayedSongs, index, song);
    }

    private void playSongRow(@NonNull final MusicCatalogRepository.MusicSong song) {
        final int songIndex = displayedSongs.indexOf(song);
        if (songIndex < 0) {
            return;
        }

        playSongsFrom(displayedSongs, songIndex, song);
    }

    private void enqueueSong(@NonNull final MusicCatalogRepository.MusicSong song) {
        NavigationHelper.enqueueOnPlayer(requireContext(),
                new SinglePlayQueue(toStreamInfoItem(song)));
    }

    private void enqueueSongs(
            @NonNull final List<MusicCatalogRepository.MusicSong> songs) {
        if (songs.isEmpty()) {
            return;
        }

        NavigationHelper.enqueueOnPlayer(requireContext(),
                new SinglePlayQueue(buildPlaybackItems(songs, 0), 0));
    }

    private void openEqualizer() {
        if (activePlayer == null) {
            Toast.makeText(requireContext(), R.string.iradu_equalizer_no_player,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        IraduEqualizerDialog.show(requireContext(), activePlayer);
    }

    private void playSongsFrom(@NonNull final List<MusicCatalogRepository.MusicSong> songs,
                               final int index,
                               @NonNull final MusicCatalogRepository.MusicSong song) {
        if (songs.isEmpty()) {
            return;
        }

        currentPlayingSongId = song.youtubeId;
        currentProgressMs = 0;
        currentDurationMs = (int) parseDurationSeconds(song.duration) * 1000;
        currentPlaybackState = Player.STATE_BUFFERING;
        refreshSongRowStates();

        NavigationHelper.playMusicOnBackgroundPlayer(
                requireContext(),
                new SinglePlayQueue(buildPlaybackItems(songs, 0), index));
    }

    @NonNull
    private List<StreamInfoItem> buildPlaybackItems(
            @NonNull final List<MusicCatalogRepository.MusicSong> songs,
            final int startIndex) {
        final List<StreamInfoItem> items = new ArrayList<>();
        for (int i = 0; i < songs.size(); i++) {
            final int songIndex = (startIndex + i) % songs.size();
            items.add(toStreamInfoItem(songs.get(songIndex)));
        }
        return items;
    }

    @NonNull
    private StreamInfoItem toStreamInfoItem(
            @NonNull final MusicCatalogRepository.MusicSong song) {
        final StreamInfoItem item = new StreamInfoItem(
                song.serviceId,
                song.getUrl(),
                song.title,
                song.streamType);
        item.setUploaderName(song.artist);
        item.setUploaderUrl("");
        item.setDuration(parseDurationSeconds(song.duration));
        item.setThumbnails(List.of(new Image(song.getThumbnailUrl(), 720, 1280,
                Image.ResolutionLevel.HIGH)));
        return item;
    }

    private long parseDurationSeconds(@NonNull final String duration) {
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

    private void refreshSongRowStates() {
        for (final SongRowHolder holder : songRows.values()) {
            updateSongRow(holder);
        }
    }

    private void updateSongRow(@NonNull final SongRowHolder holder) {
        final boolean isActive = holder.song.youtubeId.equals(currentPlayingSongId);
        holder.rowView.setBackgroundResource(isActive
                ? R.drawable.resso_song_row_active_background
                : R.drawable.resso_song_row_background);
        holder.titleView.setTextColor(isActive ? 0xFFFFF2F8 : 0xFFFFFFFF);
        holder.metaView.setTextColor(isActive ? 0xFFF1D6E8 : 0xFFB9B5CA);
        holder.durationView.setTextColor(isActive ? 0xFFFF0A7A : 0xFFFF0A7A);

        if (isActive) {
            final int remainingMs = resolveRemainingMs();
            if (remainingMs >= 0) {
                holder.remainingView.setText(formatDuration(remainingMs));
                holder.remainingView.setVisibility(View.VISIBLE);
            } else {
                holder.remainingView.setVisibility(View.GONE);
            }

            holder.indicatorView.setVisibility(View.VISIBLE);
            if (currentPlaybackState == Player.STATE_PLAYING
                    || currentPlaybackState == Player.STATE_BUFFERING) {
                holder.startAnimation();
            } else {
                holder.stopAnimation();
                holder.indicatorView.setAlpha(1f);
            }
        } else {
            holder.remainingView.setVisibility(View.GONE);
            holder.indicatorView.setVisibility(View.GONE);
            holder.stopAnimation();
        }
    }

    private int resolveRemainingMs() {
        if (currentDurationMs <= 0 || currentProgressMs < 0) {
            return -1;
        }

        return Math.max(0, currentDurationMs - currentProgressMs);
    }

    @Nullable
    private String extractSongId(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        final int watchIndex = url.indexOf("v=");
        if (watchIndex >= 0) {
            final String videoId = url.substring(watchIndex + 2);
            final int ampersandIndex = videoId.indexOf('&');
            return ampersandIndex >= 0 ? videoId.substring(0, ampersandIndex) : videoId;
        }

        final int shortIndex = url.indexOf("youtu.be/");
        if (shortIndex >= 0) {
            final String videoId = url.substring(shortIndex + "youtu.be/".length());
            final int separatorIndex = videoId.indexOf('?');
            return separatorIndex >= 0 ? videoId.substring(0, separatorIndex) : videoId;
        }

        return null;
    }

    @NonNull
    private String formatDuration(final int durationMs) {
        final int totalSeconds = Math.max(0, durationMs / 1000);
        final int minutes = totalSeconds / 60;
        final int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void applyCurrentItem(@Nullable final PlayQueue queue,
                                  @Nullable final PlayQueueItem currentItem) {
        currentPlayingSongId = currentItem == null ? null : extractSongId(currentItem.getUrl());

        if (currentDurationMs < 0 && currentItem != null && currentItem.getDuration() > 0) {
            currentDurationMs = (int) currentItem.getDuration() * 1000;
        }

        if (currentPlayingSongId == null && queue != null && queue.getItem() != null) {
            currentPlayingSongId = extractSongId(queue.getItem().getUrl());
        }
        refreshSongRowStates();
    }

    @Override
    public void onServiceConnected(@NonNull final PlayerService playerService) {
        // no-op
    }

    @Override
    public void onPlayerConnected(@NonNull final Player player, final boolean playAfterConnect) {
        activePlayer = player;
        currentPlaybackState = player.getCurrentState();
        applyCurrentItem(player.getPlayQueue(), player.getCurrentItem());
    }

    @Override
    public void onPlayerDisconnected() {
        activePlayer = null;
        currentPlayingSongId = null;
        currentProgressMs = -1;
        currentDurationMs = -1;
        currentPlaybackState = Player.STATE_PREFLIGHT;
        refreshSongRowStates();
    }

    @Override
    public void onServiceDisconnected() {
        // no-op
    }

    @Override
    public void onViewCreated() {
        // no-op
    }

    @Override
    public void onFullscreenStateChanged(final boolean fullscreen) {
        // no-op
    }

    @Override
    public void onScreenRotationButtonClicked() {
        // no-op
    }

    @Override
    public void onMoreOptionsLongClicked() {
        // no-op
    }

    @Override
    public void onPlayerError(final PlaybackException error,
                              final boolean isCatchableException) {
        // no-op
    }

    @Override
    public void hideSystemUiIfNeeded() {
        // no-op
    }

    @Override
    public void onQueueUpdate(final PlayQueue queue) {
        applyCurrentItem(queue, queue == null ? null : queue.getItem());
    }

    @Override
    public void onPlaybackUpdate(final int state,
                                 final int repeatMode,
                                 final boolean shuffled,
                                 final PlaybackParameters parameters) {
        currentPlaybackState = state;
        refreshSongRowStates();
    }

    @Override
    public void onProgressUpdate(final int currentProgress,
                                 final int duration,
                                 final int bufferPercent) {
        currentProgressMs = currentProgress;
        currentDurationMs = duration;
        refreshSongRowStates();
    }

    @Override
    public void onMetadataUpdate(final StreamInfo info, final PlayQueue queue) {
        currentPlayingSongId = extractSongId(info.getUrl());
        if (info.getDuration() > 0) {
            currentDurationMs = (int) info.getDuration() * 1000;
        }
        applyCurrentItem(queue, queue == null ? null : queue.getItem());
    }

    @Override
    public void onServiceStopped() {
        onPlayerDisconnected();
    }

    private static final class SongRowHolder {
        @NonNull
        private final View rowView;
        @NonNull
        private final ImageView imageView;
        @NonNull
        private final ImageView indicatorView;
        @NonNull
        private final ImageView enqueueView;
        @NonNull
        private final TextView titleView;
        @NonNull
        private final TextView metaView;
        @NonNull
        private final TextView durationView;
        @NonNull
        private final TextView remainingView;
        @NonNull
        private final MusicCatalogRepository.MusicSong song;
        @Nullable
        private final ObjectAnimator indicatorAnimator;

        private SongRowHolder(@NonNull final View rowView,
                              @NonNull final MusicCatalogRepository.MusicSong song) {
            this.rowView = rowView;
            this.song = song;
            imageView = rowView.findViewById(R.id.music_song_image);
            indicatorView = rowView.findViewById(R.id.music_song_indicator);
            enqueueView = rowView.findViewById(R.id.music_song_enqueue_button);
            titleView = rowView.findViewById(R.id.music_song_title);
            metaView = rowView.findViewById(R.id.music_song_meta);
            durationView = rowView.findViewById(R.id.music_song_duration);
            remainingView = rowView.findViewById(R.id.music_song_remaining);
            indicatorAnimator = ObjectAnimator.ofFloat(indicatorView, View.ALPHA, 0.35f, 1f);
            indicatorAnimator.setDuration(ANIMATION_DURATION_MS);
            indicatorAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            indicatorAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        }

        private void startAnimation() {
            if (indicatorAnimator != null && !indicatorAnimator.isStarted()) {
                indicatorAnimator.start();
            }
        }

        private void stopAnimation() {
            if (indicatorAnimator != null) {
                indicatorAnimator.cancel();
            }
            indicatorView.setAlpha(1f);
        }
    }

    private static final class SearchChunk {
        @NonNull
        private final List<InfoItem> items;
        @Nullable
        private final Page nextPage;
        private final boolean hasNextPage;

        private SearchChunk(@NonNull final List<InfoItem> items,
                            @Nullable final Page nextPage,
                            final boolean hasNextPage) {
            this.items = items;
            this.nextPage = nextPage;
            this.hasNextPage = hasNextPage;
        }

        @NonNull
        private List<InfoItem> getItems() {
            return items;
        }

        @Nullable
        private Page getNextPage() {
            return nextPage;
        }

        private boolean hasNextPage() {
            return hasNextPage;
        }
    }
}
