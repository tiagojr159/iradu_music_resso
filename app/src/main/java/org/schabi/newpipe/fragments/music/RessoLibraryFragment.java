package org.schabi.newpipe.fragments.music;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.util.NavigationHelper;

public final class RessoLibraryFragment extends BaseMusicCatalogFragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_resso_library, container, false);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        try {
            loadCatalogOrNotify();

            ((TextView) rootView.findViewById(R.id.resso_library_title))
                    .setText(R.string.resso_library_title);

            rootView.findViewById(R.id.resso_library_favorites).setOnClickListener(
                    v -> {
                        ViewUtils.animatePressFeedback(v);
                        NavigationHelper.openBookmarksFragment(getFM());
                    });
            rootView.findViewById(R.id.resso_library_recent).setOnClickListener(
                    v -> {
                        ViewUtils.animatePressFeedback(v);
                        NavigationHelper.openStatisticFragment(getFM());
                    });
            rootView.findViewById(R.id.resso_library_downloads).setOnClickListener(
                    v -> {
                        ViewUtils.animatePressFeedback(v);
                        NavigationHelper.openDownloads(activity);
                    });

            if (catalog != null) {
                final LinearLayout row = rootView.findViewById(R.id.resso_library_mix_row);
                addWideCards(row, catalog.getAllPlaylists().subList(0,
                        Math.min(3, catalog.getAllPlaylists().size())));
            }
        } catch (final Throwable e) {
            ErrorUtil.showUiErrorSnackbar(this, "Initializing Resso library", e);
        }
    }
}
