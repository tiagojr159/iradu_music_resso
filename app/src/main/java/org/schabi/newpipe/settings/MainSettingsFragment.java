package org.schabi.newpipe.settings;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceScreen;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.music.MusicCatalogRepository;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.disposables.Disposable;

public class MainSettingsFragment extends BasePreferenceFragment {
    public static final boolean DEBUG = MainActivity.DEBUG;

    private SettingsActivity settingsActivity;
    private Disposable catalogRefreshDisposable;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        setHasOptionsMenu(true); // Otherwise onCreateOptionsMenu is not called

        // Hide debug preferences in RELEASE build variant
        if (!DEBUG) {
            final PreferenceScreen moreSettings = findPreference("resso_more_settings");
            if (moreSettings != null) {
                moreSettings.removePreference(requirePreference(R.string.debug_pref_screen_key));
            }
        }

        final var refreshCatalogPreference = requirePreference(R.string.resso_refresh_catalog_key);
        refreshCatalogPreference.setOnPreferenceClickListener(preference -> {
            preference.setEnabled(false);
            catalogRefreshDisposable = new MusicCatalogRepository()
                    .refreshWeeklyRecommendations(requireContext())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(catalog -> {
                        preference.setEnabled(true);
                        final int changedTracks = MusicCatalogRepository
                                .getLastChangedTrackCount(requireContext());
                        final int message = changedTracks == 0
                                ? R.string.resso_refresh_catalog_unchanged
                                : R.string.resso_refresh_catalog_success;
                        final String text = changedTracks == 0
                                ? getString(message) : getString(message, changedTracks);
                        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show();
                    }, error -> {
                        preference.setEnabled(true);
                        Toast.makeText(requireContext(), R.string.resso_refresh_catalog_failure,
                                Toast.LENGTH_LONG).show();
                    });
            return true;
        });
    }

    @Override
    public void onCreateOptionsMenu(
            @NonNull final Menu menu,
            @NonNull final MenuInflater inflater
    ) {
        super.onCreateOptionsMenu(menu, inflater);

        // -- Link settings activity and register menu --
        settingsActivity = (SettingsActivity) getActivity();

        inflater.inflate(R.menu.menu_settings_main_fragment, menu);

        final MenuItem menuSearchItem = menu.getItem(0);

        settingsActivity.setMenuSearchItem(menuSearchItem);

        menuSearchItem.setOnMenuItemClickListener(ev -> {
            settingsActivity.setSearchActive(true);
            return true;
        });
    }

    @Override
    public void onDestroy() {
        if (catalogRefreshDisposable != null) {
            catalogRefreshDisposable.dispose();
            catalogRefreshDisposable = null;
        }
        // Unlink activity so that we don't get memory problems
        if (settingsActivity != null) {
            settingsActivity.setMenuSearchItem(null);
            settingsActivity = null;
        }
        super.onDestroy();
    }
}
