package org.schabi.newpipe.fragments.music;

import static org.schabi.newpipe.ktx.ViewUtils.animatePressFeedback;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.local.bookmark.MergedPlaylistManager;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;
import org.schabi.newpipe.local.playlist.RemotePlaylistManager;
import org.schabi.newpipe.database.playlist.PlaylistLocalItem;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.music.MusicCatalogRepository;
import org.schabi.newpipe.player.playqueue.InfiniteMusicPlayQueue;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.image.CoilHelper;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

public abstract class BaseMusicCatalogFragment extends BaseFragment {
    protected final MusicCatalogRepository repository = new MusicCatalogRepository();
    @Nullable
    protected MusicCatalogRepository.MusicCatalog catalog;

    protected boolean loadCatalogOrNotify() {
        try {
            catalog = repository.loadCatalog(requireContext());
            return true;
        } catch (final Throwable e) {
            Toast.makeText(requireContext(), R.string.resso_catalog_error, Toast.LENGTH_SHORT)
                    .show();
            ErrorUtil.showUiErrorSnackbar(this, "Loading music catalog", e);
            return false;
        }
    }

    protected void openPlaylist(@NonNull final String slug) {
        final var fragment = MusicPlaylistDetailFragment.newInstance(slug);
        getFM().beginTransaction()
                .setCustomAnimations(R.animator.custom_fade_in, R.animator.custom_fade_out,
                        R.animator.custom_fade_in, R.animator.custom_fade_out)
                .replace(R.id.fragment_holder, fragment)
                .addToBackStack(null)
                .commit();
    }

    protected void playPlaylist(@NonNull final MusicCatalogRepository.MusicPlaylist playlist) {
        playPlaylistFrom(playlist, 0);
    }

    protected void playPlaylistFrom(@NonNull final MusicCatalogRepository.MusicPlaylist playlist,
                                    final int startIndex) {
        if (playlist.songs.isEmpty()) {
            return;
        }

        final int safeIndex = Math.max(0, Math.min(startIndex, playlist.songs.size() - 1));
        NavigationHelper.playOnBackgroundPlayer(
                requireContext(),
                new InfiniteMusicPlayQueue(playlist, safeIndex),
                true);
    }

    protected View inflateChip(@NonNull final LinearLayout container,
                               @NonNull final String title,
                               @NonNull final View.OnClickListener clickListener) {
        final View chip = LayoutInflater.from(container.getContext())
                .inflate(R.layout.item_music_chip, container, false);
        ((TextView) chip.findViewById(R.id.music_chip_text)).setText(title);
        chip.setOnClickListener(v -> {
            animatePressFeedback(v);
            clickListener.onClick(v);
        });
        container.addView(chip);
        return chip;
    }

    protected void addSquareCards(@NonNull final LinearLayout container,
                                  @NonNull final List<MusicCatalogRepository.MusicPlaylist> data) {
        container.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(container.getContext());
        for (final var playlist : data) {
            final View card = inflater.inflate(R.layout.item_music_square_card, container, false);
            ((TextView) card.findViewById(R.id.music_card_title)).setText(playlist.title);
            ((TextView) card.findViewById(R.id.music_card_subtitle)).setText(playlist.subtitle);
            final ImageView imageView = card.findViewById(R.id.music_card_image);
            CoilHelper.INSTANCE.loadThumbnail(imageView, playlist.getHeroImageUrl());
            card.setOnClickListener(v -> {
                animatePressFeedback(v);
                openPlaylist(playlist.slug);
            });
            container.addView(card);
        }
    }

    protected void addWideCards(@NonNull final LinearLayout container,
                                @NonNull final List<MusicCatalogRepository.MusicPlaylist> data) {
        container.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(container.getContext());
        for (final var playlist : data) {
            final View card = inflater.inflate(R.layout.item_music_wide_card, container, false);
            ((TextView) card.findViewById(R.id.music_card_title)).setText(playlist.title);
            ((TextView) card.findViewById(R.id.music_card_subtitle)).setText(playlist.subtitle);
            final ImageView imageView = card.findViewById(R.id.music_card_image);
            CoilHelper.INSTANCE.loadThumbnail(imageView, playlist.getHeroImageUrl());
            card.setOnClickListener(v -> {
                animatePressFeedback(v);
                openPlaylist(playlist.slug);
            });
            container.addView(card);
        }
    }

    protected void loadSavedPlaylistsRow(@NonNull final LinearLayout container) {
        final var database = NewPipeDatabase.getInstance(requireContext());
        MergedPlaylistManager.getMergedOrderedPlaylists(
                        new LocalPlaylistManager(database),
                        new RemotePlaylistManager(database))
                .observeOn(AndroidSchedulers.mainThread())
                .firstElement()
                .subscribe(playlists -> addSavedPlaylistRows(container, playlists),
                        throwable -> ErrorUtil.showUiErrorSnackbar(
                                this, "Loading saved playlists", throwable));
    }

    private void addSavedPlaylistRows(@NonNull final LinearLayout container,
                                      @NonNull final List<PlaylistLocalItem> playlists) {
        container.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(container.getContext());
        for (final PlaylistLocalItem playlist : playlists) {
            final View row = inflater.inflate(R.layout.item_music_playlist_row, container, false);
            ((TextView) row.findViewById(R.id.music_playlist_row_title))
                    .setText(playlist.getOrderingName());
            final TextView subtitle = row.findViewById(R.id.music_playlist_row_subtitle);
            subtitle.setText(buildPlaylistSubtitle(playlist));
            final ImageView imageView = row.findViewById(R.id.music_playlist_row_image);
            CoilHelper.INSTANCE.loadThumbnail(imageView, playlist.getThumbnailUrl());
            row.setOnClickListener(v -> {
                animatePressFeedback(v);
                openSavedPlaylist(playlist);
            });
            container.addView(row);
        }
    }

    @NonNull
    private String buildPlaylistSubtitle(@NonNull final PlaylistLocalItem playlist) {
        if (playlist instanceof PlaylistMetadataEntry metadataEntry) {
            return metadataEntry.getStreamCount() + " músicas";
        }
        if (playlist instanceof PlaylistRemoteEntity remoteEntity
                && remoteEntity.getStreamCount() != null) {
            return remoteEntity.getStreamCount() + " músicas";
        }
        return getString(R.string.tab_bookmarks_short);
    }

    private void openSavedPlaylist(@NonNull final PlaylistLocalItem playlist) {
        if (playlist instanceof PlaylistMetadataEntry) {
            final var fragment = MusicPlaylistDetailFragment.newInstanceSavedPlaylist(
                    playlist.getUid(), playlist.getOrderingName());
            getFM().beginTransaction()
                    .setCustomAnimations(R.animator.custom_fade_in, R.animator.custom_fade_out,
                            R.animator.custom_fade_in, R.animator.custom_fade_out)
                    .replace(R.id.fragment_holder, fragment)
                    .addToBackStack(null)
                    .commit();
        } else if (playlist instanceof PlaylistRemoteEntity) {
            final PlaylistRemoteEntity remote = (PlaylistRemoteEntity) playlist;
            NavigationHelper.openPlaylistFragment(getFM(), remote.getServiceId(),
                    remote.getUrl(), remote.getOrderingName());
        }
    }
}
