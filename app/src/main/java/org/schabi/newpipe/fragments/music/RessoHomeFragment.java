package org.schabi.newpipe.fragments.music;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.music.MusicCatalogRepository;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.image.CoilHelper;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class RessoHomeFragment extends BaseMusicCatalogFragment {
    @Nullable
    private Disposable weeklyRefreshDisposable;
    @NonNull
    private static final List<String> ROCK_SECTION_SLUGS = List.of(
            "rock-classicos",
            "rock-alternativo",
            "hard-rock",
            "metal-essentials",
            "punk-rock",
            "grunge",
            "indie-rock",
            "rock-nacional",
            "pop-rock",
            "guitar-heroes");

    @NonNull
    private static final List<String> RAVE_SECTION_SLUGS = List.of(
            "balada-eletronica",
            "alok-hits",
            "rave-essentials",
            "edm-festival",
            "house-party",
            "progressive-house",
            "techno-nights",
            "trance-energy");

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_resso_home, container, false);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        try {
            if (!loadCatalogOrNotify() || catalog == null) {
                return;
            }

            rootView.findViewById(R.id.resso_search_bar).setOnClickListener(v -> {
                ViewUtils.animatePressFeedback(v);
                NavigationHelper.openSearchFragment(getFM(),
                        ServiceHelper.getSelectedServiceId(requireContext()), "");
            });

            renderHero(rootView);
            renderCatalogSections(rootView);

            if (MusicCatalogRepository.isWeeklyRefreshDue(requireContext())) {
                weeklyRefreshDisposable = repository.refreshWeeklyRecommendations(requireContext())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(updatedCatalog -> {
                            if (!isAdded() || getView() != rootView) {
                                return;
                            }
                            catalog = updatedCatalog;
                            renderCatalogSections(rootView);
                            renderHero(rootView);
                        }, error -> { /* Keep the bundled recommendations when offline. */ });
            }
        } catch (final Throwable e) {
            ErrorUtil.showUiErrorSnackbar(this, "Initializing Resso home", e);
        }
    }

    @Override
    public void onDestroyView() {
        if (weeklyRefreshDisposable != null) {
            weeklyRefreshDisposable.dispose();
            weeklyRefreshDisposable = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        final View rootView = getView();
        if (rootView != null && isAdded()) {
            try {
                catalog = repository.loadCatalog(requireContext());
                renderHero(rootView);
                renderCatalogSections(rootView);
            } catch (final Exception e) {
                ErrorUtil.showUiErrorSnackbar(this, "Reloading Resso catalog", e);
            }
        }
    }

    private void renderHero(@NonNull final View rootView) {
        if (catalog == null || catalog.featured.isEmpty()) {
            return;
        }
        final var hero = catalog.featured.get(0);
        ((TextView) rootView.findViewById(R.id.resso_hero_title)).setText(hero.title);
        ((TextView) rootView.findViewById(R.id.resso_hero_subtitle)).setText(hero.subtitle);
        final ImageView heroImage = rootView.findViewById(R.id.resso_hero_image);
        CoilHelper.INSTANCE.loadThumbnail(heroImage, hero.getHeroImageUrl());
        rootView.findViewById(R.id.resso_hero_button).setOnClickListener(v -> {
            ViewUtils.animatePressFeedback(v);
            openPlaylist(hero.slug);
        });
    }

    private void renderCatalogSections(@NonNull final View rootView) {
        final LinearLayout quickGenres = rootView.findViewById(R.id.resso_quick_genres);
        quickGenres.removeAllViews();
        for (final var playlist : catalog.genrePlaylists.subList(0,
                Math.min(6, catalog.genrePlaylists.size()))) {
            inflateChip(quickGenres, playlist.title, v -> openPlaylist(playlist.slug));
        }

        addWideCards(rootView.findViewById(R.id.resso_radios_row),
                catalog.automaticPlaylists.subList(0,
                        Math.min(4, catalog.automaticPlaylists.size())));
        addSquareCards(rootView.findViewById(R.id.resso_playlists_row),
                catalog.moodPlaylists.subList(0, Math.min(6, catalog.moodPlaylists.size())));
        addSquareCards(rootView.findViewById(R.id.resso_charts_row),
                catalog.featured.subList(0, Math.min(4, catalog.featured.size())));
        loadSavedPlaylistsRow(rootView.findViewById(R.id.resso_my_playlists_row));
        addSquareCards(rootView.findViewById(R.id.resso_rock_row),
                findPlaylistsBySlugs(ROCK_SECTION_SLUGS));
        addSquareCards(rootView.findViewById(R.id.resso_rave_row),
                findPlaylistsBySlugs(RAVE_SECTION_SLUGS));
    }

    @NonNull
    private List<org.schabi.newpipe.music.MusicCatalogRepository.MusicPlaylist>
            findPlaylistsBySlugs(
            @NonNull final List<String> slugs) {
        final List<org.schabi.newpipe.music.MusicCatalogRepository.MusicPlaylist> playlists =
                new ArrayList<>();
        if (catalog == null) {
            return playlists;
        }

        for (final String slug : slugs) {
            final org.schabi.newpipe.music.MusicCatalogRepository.MusicPlaylist playlist =
                    catalog.getPlaylist(slug);
            if (playlist != null) {
                playlists.add(playlist);
            }
        }

        return playlists;
    }
}
