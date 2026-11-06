package org.schabi.newpipe.fragments.music;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ServiceHelper;

import java.util.List;

public final class RessoExploreFragment extends BaseMusicCatalogFragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_resso_explore, container, false);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        try {
            if (!loadCatalogOrNotify() || catalog == null) {
                return;
            }

            rootView.findViewById(R.id.resso_search_bar).setOnClickListener(v ->
                    NavigationHelper.openSearchFragment(getFM(),
                            ServiceHelper.getSelectedServiceId(requireContext()), ""));

            final LinearLayout categories = rootView.findViewById(R.id.resso_category_cloud);
            categories.removeAllViews();
            for (final var playlist : catalog.getAllPlaylists()) {
                inflateChip(categories, playlist.title, v -> openPlaylist(playlist.slug));
            }

            loadSavedPlaylistsRow(rootView.findViewById(R.id.resso_my_playlists_row));

            addSquareCards(rootView.findViewById(R.id.resso_top_brasil_row),
                    findPlaylists(catalog.featured, 0, 4));
            addSquareCards(rootView.findViewById(R.id.resso_moods_row),
                    findPlaylists(catalog.moodPlaylists, 0, 6));
            addWideCards(rootView.findViewById(R.id.resso_auto_row),
                    findPlaylists(catalog.automaticPlaylists, 0, 5));
        } catch (final Throwable e) {
            ErrorUtil.showUiErrorSnackbar(this, "Initializing Resso explore", e);
        }
    }

    private List<org.schabi.newpipe.music.MusicCatalogRepository.MusicPlaylist> findPlaylists(
            @NonNull final List<org.schabi.newpipe.music.MusicCatalogRepository.MusicPlaylist> data,
            final int start,
            final int maxCount) {
        return data.subList(start, Math.min(data.size(), start + maxCount));
    }
}
