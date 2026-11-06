package org.schabi.newpipe.fragments.list.search;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.util.ServiceHelper;

public final class RockSearchFragment extends SearchFragment {
    private static final String QUERY = "melhores rock";
    private static final String[] CONTENT_FILTER =
            {YoutubeSearchQueryHandlerFactory.VIDEOS};

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        setDailySearchCacheEnabled(true);
        configureQuery(ServiceHelper.getSelectedServiceId(context), QUERY, CONTENT_FILTER, false);
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setUseDefaultStateSaving(false);
    }

    @Override
    public void onResume() {
        configureQuery(ServiceHelper.getSelectedServiceId(requireContext()),
                QUERY, CONTENT_FILTER, false);
        super.onResume();
    }
}
