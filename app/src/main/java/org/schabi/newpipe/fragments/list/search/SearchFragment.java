package org.schabi.newpipe.fragments.list.search;

import static androidx.recyclerview.widget.ItemTouchHelper.Callback.makeMovementFlags;
import static org.schabi.newpipe.extractor.utils.Utils.isBlank;
import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.util.ExtractorHelper.showMetaInfoInTextView;
import static java.util.Arrays.asList;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.TooltipCompat;
import androidx.collection.SparseArrayCompat;
import androidx.core.text.HtmlCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.evernote.android.state.State;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentSearchBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.MetaInfo;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.peertube.linkHandler.PeertubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.fragments.list.BaseListFragment;
import org.schabi.newpipe.ktx.AnimationType;
import org.schabi.newpipe.ktx.ExceptionUtils;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.KeyboardUtil;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.StreamTypeUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class SearchFragment extends BaseListFragment<SearchInfo, ListExtractor.InfoItemsPage<?>>
        implements BackPressable {
    /*//////////////////////////////////////////////////////////////////////////
    // Search
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * The suggestions will only be fetched from network if the query meet this threshold (>=).
     * (local ones will be fetched regardless of the length)
     */
    private static final int THRESHOLD_NETWORK_SUGGESTION = 1;
    // Very long queries (like curated multi-channel searches) can cause the YouTube suggestions
    // endpoint to return non-JSON error pages. For those, skip remote suggestions altogether.
    private static final int MAX_REMOTE_SUGGESTION_QUERY_LENGTH = 64;
    private static final int MAX_REMOTE_SUGGESTION_QUERY_WORDS = 6;
    private static final int RECENT_RESULTS_DAYS = 5;
    private static final long AUTO_SEARCH_DEBOUNCE_MS = 3000L;
    private static final long DAILY_SEARCH_CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(1);
    private static final Pattern RELATIVE_NUMBER_PATTERN = Pattern.compile(
            "(\\d+)\\s*(min|mins|minute|minutes|minuto|minutos|h|hr|hrs|hour|hours|hora|horas"
                    + "|day|days|dia|dias)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * How much time have to pass without emitting a item (i.e. the user stop typing)
     * to fetch/show the suggestions, in milliseconds.
     */
    private static final int SUGGESTIONS_DEBOUNCE = 120; //ms
    private final PublishSubject<String> suggestionPublisher = PublishSubject.create();

    @State
    int filterItemCheckedId = -1;

    @State
    protected int serviceId = Constants.NO_SERVICE_ID;

    // these three represents the current search query
    @State
    String searchString;

    /**
     * No content filter should add like contentFilter = all
     * be aware of this when implementing an extractor.
     */
    @State
    String[] contentFilter = new String[0];

    @State
    String sortFilter;

    // these represents the last search
    @State
    String lastSearchedString;

    @State
    String searchSuggestion;

    @State
    boolean isCorrectedSearch;

    @State
    MetaInfo[] metaInfo;

    @State
    boolean wasSearchFocused = false;

    @State
    boolean onlyRecentResults = false;

    private boolean dailySearchCacheEnabled = false;

    private final SparseArrayCompat<String> menuItemToFilterName = new SparseArrayCompat<>();
    private StreamingService service;
    @Nullable
    private Page nextPage;
    private boolean showLocalSuggestions = true;
    private boolean showRemoteSuggestions = true;

    private Disposable searchDisposable;
    private Disposable suggestionDisposable;
    private final CompositeDisposable disposables = new CompositeDisposable();

    private SuggestionListAdapter suggestionListAdapter;
    private HistoryRecordManager historyRecordManager;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private FragmentSearchBinding searchBinding;

    private View searchToolbarContainer;
    private EditText searchEditText;
    private View searchClear;
    private View searchClearIcon;

    private boolean suggestionsPanelVisible = false;
    private boolean suppressAutoSearch = false;
    private final Handler autoSearchHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSearchRunnable = this::submitSearchFromInput;

    /*////////////////////////////////////////////////////////////////////////*/

    /**
     * TextWatcher to remove rich-text formatting on the search EditText when pasting content
     * from the clipboard.
     */
    private TextWatcher textWatcher;

    public static SearchFragment getInstance(final int serviceId, final String searchString) {
        final SearchFragment searchFragment = new SearchFragment();
        searchFragment.setQuery(serviceId, searchString, new String[0], "");

        if (!TextUtils.isEmpty(searchString)) {
            searchFragment.setSearchOnResume();
        }

        return searchFragment;
    }

    public static SearchFragment getRecentInstance(final int serviceId, final String searchString) {
        final SearchFragment searchFragment = getInstance(serviceId, searchString);
        searchFragment.onlyRecentResults = true;
        return searchFragment;
    }

    protected final void configureQuery(final int configuredServiceId,
                                        final String configuredSearchString,
                                        final boolean recentOnly) {
        configureQuery(configuredServiceId, configuredSearchString, new String[0], recentOnly);
    }

    protected final void configureQuery(final int configuredServiceId,
                                        final String configuredSearchString,
                                        final String[] configuredContentFilter,
                                        final boolean recentOnly) {
        final String[] supportedContentFilter =
                getSupportedContentFilter(configuredServiceId, configuredContentFilter);
        final boolean changed = serviceId != configuredServiceId
                || !TextUtils.equals(searchString, configuredSearchString)
                || !Arrays.equals(contentFilter, supportedContentFilter)
                || onlyRecentResults != recentOnly;
        setQuery(configuredServiceId, configuredSearchString, supportedContentFilter, "");
        onlyRecentResults = recentOnly;
        if (changed && infoListAdapter != null) {
            infoListAdapter.clearStreamItemList();
            nextPage = null;
            setSearchOnResume();
        } else if (!TextUtils.isEmpty(configuredSearchString)) {
            setSearchOnResume();
        }
    }

    private String[] getSupportedContentFilter(final int configuredServiceId,
                                               final String[] configuredContentFilter) {
        if (configuredContentFilter.length == 0) {
            return configuredContentFilter;
        }

        try {
            final String[] availableFilters = NewPipe.getService(configuredServiceId)
                    .getSearchQHFactory()
                    .getAvailableContentFilter();
            for (final String availableFilter : availableFilters) {
                if (availableFilter.equals(configuredContentFilter[0])) {
                    return configuredContentFilter;
                }
            }
        } catch (final Exception ignored) {
            // If the service is unavailable here, fall back to an unfiltered search.
        }

        return new String[0];
    }

    protected final void setDailySearchCacheEnabled(final boolean enabled) {
        dailySearchCacheEnabled = enabled;
    }

    /**
     * Set wasLoading to true so when the fragment onResume is called, the initial search is done.
     */
    private void setSearchOnResume() {
        wasLoading.set(true);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        showLocalSuggestions = NewPipeSettings.showLocalSearchSuggestions(activity, prefs);
        showRemoteSuggestions = NewPipeSettings.showRemoteSearchSuggestions(activity, prefs);

        suggestionListAdapter = new SuggestionListAdapter();
        historyRecordManager = new HistoryRecordManager(context);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View rootView, final Bundle savedInstanceState) {
        searchBinding = FragmentSearchBinding.bind(rootView);
        super.onViewCreated(rootView, savedInstanceState);
        searchBinding.swipeRefreshLayout.setOnRefreshListener(this::reloadContent);

        updateService();
        // Add the service name to search string hint
        // to make it more obvious which platform is being searched.
        if (service != null) {
            searchEditText.setHint(
                    getString(R.string.search_with_service_name,
                            service.getServiceInfo().getName()));
        }
        showSearchOnStart();
        initSearchListeners();
    }

    private void updateService() {
        try {
            service = NewPipe.getService(serviceId);
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Getting service for id " + serviceId, e);
        }
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Log.d(TAG, "onStart() called");
        }
        super.onStart();

        updateService();
    }

    @Override
    public void onPause() {
        super.onPause();

        wasSearchFocused = searchEditText.hasFocus();

        if (searchDisposable != null) {
            searchDisposable.dispose();
        }
        if (suggestionDisposable != null) {
            suggestionDisposable.dispose();
        }
        disposables.clear();
        hideKeyboardSearch();
    }

    @Override
    public void onResume() {
        if (DEBUG) {
            Log.d(TAG, "onResume() called");
        }
        super.onResume();

        if (suggestionDisposable == null || suggestionDisposable.isDisposed()) {
            initSuggestionObserver();
        }

        if (!TextUtils.isEmpty(searchString)) {
            if (wasLoading.getAndSet(false)) {
                search(searchString, contentFilter, sortFilter);
                return;
            } else if (infoListAdapter.getItemsList().isEmpty()) {
                if (savedState == null) {
                    search(searchString, contentFilter, sortFilter);
                    return;
                } else if (!isLoading.get() && !wasSearchFocused && lastPanelError == null) {
                    infoListAdapter.clearStreamItemList();
                    showEmptyState();
                }
            }
        }

        handleSearchSuggestion();

        showMetaInfoInTextView(metaInfo == null ? null : Arrays.asList(metaInfo),
                searchBinding.searchMetaInfoTextView, searchBinding.searchMetaInfoSeparator,
                disposables);

        if (TextUtils.isEmpty(searchString) || wasSearchFocused) {
            showKeyboardSearch();
            showSuggestionsPanel();
        } else {
            hideKeyboardSearch();
            hideSuggestionsPanel();
        }
        wasSearchFocused = false;
    }

    @Override
    public void onDestroyView() {
        if (DEBUG) {
            Log.d(TAG, "onDestroyView() called");
        }
        unsetSearchListeners();

        searchBinding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (searchDisposable != null) {
            searchDisposable.dispose();
        }
        if (suggestionDisposable != null) {
            suggestionDisposable.dispose();
        }
        disposables.clear();
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == ReCaptchaActivity.RECAPTCHA_REQUEST) {
            if (resultCode == Activity.RESULT_OK
                    && !TextUtils.isEmpty(searchString)) {
                search(searchString, contentFilter, sortFilter);
            } else {
                Log.e(TAG, "ReCaptcha failed");
            }
        } else {
            Log.e(TAG, "Request code from activity not supported [" + requestCode + "]");
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        searchBinding.suggestionsList.setAdapter(suggestionListAdapter);
        // animations are just strange and useless, since the suggestions keep changing too much
        searchBinding.suggestionsList.setItemAnimator(null);
        new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull final RecyclerView recyclerView,
                                        @NonNull final RecyclerView.ViewHolder viewHolder) {
                return getSuggestionMovementFlags(viewHolder);
            }

            @Override
            public boolean onMove(@NonNull final RecyclerView recyclerView,
                                  @NonNull final RecyclerView.ViewHolder viewHolder,
                                  @NonNull final RecyclerView.ViewHolder viewHolder1) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder, final int i) {
                onSuggestionItemSwiped(viewHolder);
            }
        }).attachToRecyclerView(searchBinding.suggestionsList);

        searchToolbarContainer = activity.findViewById(R.id.toolbar_search_container);
        searchEditText = searchToolbarContainer.findViewById(R.id.toolbar_search_edit_text);
        searchClear = searchToolbarContainer.findViewById(R.id.toolbar_search_clear);
        searchClearIcon = searchToolbarContainer.findViewById(R.id.toolbar_search_clear_icon);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void writeTo(final Queue<Object> objectsToSave) {
        super.writeTo(objectsToSave);
        objectsToSave.add(nextPage);
    }

    @Override
    public String generateSuffix() {
        final String query = searchString == null ? "" : searchString;
        final String filters = Arrays.toString(contentFilter);
        final String sort = sortFilter == null ? "" : sortFilter;
        final String key = serviceId + "|" + onlyRecentResults + "|" + query + "|"
                + filters + "|" + sort;
        return "." + Integer.toHexString(key.hashCode()) + super.generateSuffix();
    }

    @Override
    public void readFrom(@NonNull final Queue<Object> savedObjects) throws Exception {
        super.readFrom(savedObjects);
        nextPage = (Page) savedObjects.poll();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle bundle) {
        searchString = searchEditText != null
                ? getSearchEditString().trim()
                : searchString;
        super.onSaveInstanceState(bundle);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init's
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void reloadContent() {
        if (searchBinding != null) {
            searchBinding.swipeRefreshLayout.setRefreshing(true);
        }
        if (!TextUtils.isEmpty(searchString) || (searchEditText != null
                && !isSearchEditBlank())) {
            search(!TextUtils.isEmpty(searchString)
                    ? searchString
                    : getSearchEditString(), this.contentFilter, "");
        } else {
            if (searchEditText != null) {
                searchEditText.setText("");
                showKeyboardSearch();
            }
            hideErrorPanel();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        final ActionBar supportActionBar = activity.getSupportActionBar();
        if (supportActionBar != null) {
            supportActionBar.setDisplayShowTitleEnabled(false);
            supportActionBar.setDisplayHomeAsUpEnabled(true);
        }

        int itemId = 0;
        boolean checkedCurrentFilter = false;
        final Context c = getContext();

        if (service == null) {
            Log.w(TAG, "onCreateOptionsMenu() called with null service");
            updateService();
        }

        for (final String filter : service.getSearchQHFactory().getAvailableContentFilter()) {
            if (filter.equals(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)) {
                final MenuItem musicItem = menu.add(2,
                        itemId++,
                        0,
                        "YouTube Music");
                musicItem.setEnabled(false);
            } else if (filter.equals(PeertubeSearchQueryHandlerFactory.SEPIA_VIDEOS)) {
                final MenuItem sepiaItem = menu.add(2,
                        itemId++,
                        0,
                        "Sepia Search");
                sepiaItem.setEnabled(false);
            }
            menuItemToFilterName.put(itemId, filter);
            final MenuItem item = menu.add(1,
                    itemId++,
                    0,
                    ServiceHelper.getTranslatedFilterString(filter, c));
            if (isCurrentContentFilter(filter)) {
                item.setChecked(true);
                filterItemCheckedId = item.getItemId();
                checkedCurrentFilter = true;
            }
        }
        menu.setGroupCheckable(1, true, true);

        if (checkedCurrentFilter) {
            restoreFilterChecked(menu, filterItemCheckedId);
        } else {
            checkFirstFilterItem(menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final var filter = Collections.singletonList(menuItemToFilterName.get(item.getItemId()));
        changeContentFilter(item, filter);
        return true;
    }

    private void restoreFilterChecked(final Menu menu, final int itemId) {
        if (itemId != -1) {
            final MenuItem item = menu.findItem(itemId);
            if (item == null) {
                return;
            }

            item.setChecked(true);
        }
    }

    private boolean isCurrentContentFilter(final String filter) {
        if (contentFilter.length == 0) {
            return "all".equals(filter);
        }
        return contentFilter.length == 1 && filter.equals(contentFilter[0]);
    }

    private void checkFirstFilterItem(final Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem item = menu.getItem(i);
            if (item.getGroupId() == 1) {
                item.setChecked(true);
                filterItemCheckedId = item.getItemId();
                return;
            }
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Search
    //////////////////////////////////////////////////////////////////////////*/

    private void showSearchOnStart() {
        if (DEBUG) {
            Log.d(TAG, "showSearchOnStart() called, searchQuery → "
                    + searchString
                    + ", lastSearchedQuery → "
                    + lastSearchedString);
        }
        searchEditText.setText(searchString);

        if (TextUtils.isEmpty(searchString)
                || isSearchEditBlank()) {
            searchToolbarContainer.setTranslationX(100);
            searchToolbarContainer.setAlpha(0.0f);
            searchToolbarContainer.setVisibility(View.VISIBLE);
            searchToolbarContainer.animate()
                    .translationX(0)
                    .alpha(1.0f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            searchToolbarContainer.setTranslationX(0);
            searchToolbarContainer.setAlpha(1.0f);
            searchToolbarContainer.setVisibility(View.VISIBLE);
        }
    }

    private void initSearchListeners() {
        if (DEBUG) {
            Log.d(TAG, "initSearchListeners() called");
        }
        final View.OnClickListener clearClickListener = v -> {
            if (DEBUG) {
                Log.d(TAG, "onClick() called with: v = [" + v + "]");
            }
            clearSearchInput();
        };
        searchClear.setOnClickListener(clearClickListener);
        if (searchClearIcon != null) {
            searchClearIcon.setOnClickListener(clearClickListener);
        }

        TooltipCompat.setTooltipText(searchClear, getString(R.string.clear));

        searchEditText.setOnClickListener(v -> {
            if (DEBUG) {
                Log.d(TAG, "onClick() called with: v = [" + v + "]");
            }
            if ((showLocalSuggestions || showRemoteSuggestions) && !isErrorPanelVisible()) {
                showSuggestionsPanel();
            }
            if (DeviceUtils.isTv(getContext())) {
                showKeyboardSearch();
            }
        });

        searchEditText.setOnFocusChangeListener((final View v, final boolean hasFocus) -> {
            if (DEBUG) {
                Log.d(TAG, "onFocusChange() called with: "
                        + "v = [" + v + "], hasFocus = [" + hasFocus + "]");
            }
            if ((showLocalSuggestions || showRemoteSuggestions)
                    && hasFocus && !isErrorPanelVisible()) {
                showSuggestionsPanel();
            }
        });

        suggestionListAdapter.setListener(new SuggestionListAdapter.OnSuggestionItemSelected() {
            @Override
            public void onSuggestionItemSelected(final SuggestionItem item) {
                search(item.query, contentFilter, sortFilter);
                searchEditText.setText(item.query);
            }

            @Override
            public void onSuggestionItemInserted(final SuggestionItem item) {
                searchEditText.setText(item.query);
                searchEditText.setSelection(searchEditText.getText().length());
            }

            @Override
            public void onSuggestionItemLongClick(final SuggestionItem item) {
                if (item.fromHistory) {
                    showDeleteSuggestionDialog(item);
                }
            }
        });

        if (textWatcher != null) {
            searchEditText.removeTextChangedListener(textWatcher);
        }
        textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start,
                                          final int count, final int after) {
                // Do nothing, old text is already clean
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start,
                                      final int before, final int count) {
                // Changes are handled in afterTextChanged; CharSequence cannot be changed here.
            }

            @Override
            public void afterTextChanged(final Editable s) {
                // Remove rich text formatting
                for (final CharacterStyle span : s.getSpans(0, s.length(), CharacterStyle.class)) {
                    s.removeSpan(span);
                }

                final String newText = getSearchEditString().trim();
                suggestionPublisher.onNext(newText);
                autoSearchHandler.removeCallbacks(autoSearchRunnable);
                if (!suppressAutoSearch && !newText.isEmpty()
                        && !newText.equals(searchString)) {
                    autoSearchHandler.postDelayed(autoSearchRunnable, AUTO_SEARCH_DEBOUNCE_MS);
                }
            }
        };
        searchEditText.addTextChangedListener(textWatcher);
        searchEditText.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                submitSearchFromInput();
                return true;
            }
            return false;
        });
        searchEditText.setOnEditorActionListener(
                (final TextView v, final int actionId, final KeyEvent event) -> {
                    if (DEBUG) {
                        Log.d(TAG, "onEditorAction() called with: v = [" + v + "], "
                                + "actionId = [" + actionId + "], event = [" + event + "]");
                    }
                    if (actionId == EditorInfo.IME_ACTION_PREVIOUS) {
                        hideKeyboardSearch();
                        return true;
                    }

                    final boolean isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH
                            || actionId == EditorInfo.IME_ACTION_DONE
                            || actionId == EditorInfo.IME_ACTION_GO;
                    final boolean isEnterKey = event != null
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN;

                    if (isSearchAction || isEnterKey) {
                        submitSearchFromInput();
                        return true;
                    }
                    return false;
                });

        if (suggestionDisposable == null || suggestionDisposable.isDisposed()) {
            initSuggestionObserver();
        }
    }

    private void unsetSearchListeners() {
        if (DEBUG) {
            Log.d(TAG, "unsetSearchListeners() called");
        }
        searchClear.setOnClickListener(null);
        searchClear.setOnLongClickListener(null);
        searchClear.setOnTouchListener(null);
        if (searchClearIcon != null) {
            searchClearIcon.setOnClickListener(null);
            searchClearIcon.setOnTouchListener(null);
        }
        searchEditText.setOnClickListener(null);
        searchEditText.setOnFocusChangeListener(null);
        searchEditText.setOnKeyListener(null);
        searchEditText.setOnEditorActionListener(null);

        if (textWatcher != null) {
            searchEditText.removeTextChangedListener(textWatcher);
        }
        textWatcher = null;
        autoSearchHandler.removeCallbacks(autoSearchRunnable);
    }

    private void clearSearchInput() {
        autoSearchHandler.removeCallbacks(autoSearchRunnable);
        searchBinding.correctSuggestion.setVisibility(View.GONE);
        if (searchDisposable != null) {
            searchDisposable.dispose();
        }
        suppressAutoSearch = true;
        searchEditText.setText("");
        suppressAutoSearch = false;
        searchString = "";
        lastSearchedString = "";
        nextPage = null;
        infoListAdapter.clearStreamItemList();
        suggestionListAdapter.submitList(null);
        showMetaInfoInTextView(null, searchBinding.searchMetaInfoTextView,
                searchBinding.searchMetaInfoSeparator, disposables);
        hideLoading();
        hideSuggestionsPanel();
        searchEditText.requestFocus();
        showKeyboardSearch();
    }

    private void submitSearchFromInput() {
        final String trimmedQuery = getSearchEditString().trim();
        autoSearchHandler.removeCallbacks(autoSearchRunnable);
        suppressAutoSearch = true;
        searchEditText.setText(trimmedQuery);
        searchEditText.setSelection(trimmedQuery.length());
        suppressAutoSearch = false;
        search(trimmedQuery, new String[0], "");
    }

    private void showSuggestionsPanel() {
        if (DEBUG) {
            Log.d(TAG, "showSuggestionsPanel() called");
        }
        suggestionsPanelVisible = true;
        animate(searchBinding.suggestionsPanel, true, 200,
                AnimationType.LIGHT_SLIDE_AND_ALPHA);
    }

    private void hideSuggestionsPanel() {
        if (DEBUG) {
            Log.d(TAG, "hideSuggestionsPanel() called");
        }
        suggestionsPanelVisible = false;
        animate(searchBinding.suggestionsPanel, false, 200,
                AnimationType.LIGHT_SLIDE_AND_ALPHA);
    }

    private void showKeyboardSearch() {
        if (DEBUG) {
            Log.d(TAG, "showKeyboardSearch() called");
        }
        KeyboardUtil.showKeyboard(activity, searchEditText);
    }

    private void hideKeyboardSearch() {
        if (DEBUG) {
            Log.d(TAG, "hideKeyboardSearch() called");
        }

        KeyboardUtil.hideKeyboard(activity, searchEditText);
    }

    private void showDeleteSuggestionDialog(final SuggestionItem item) {
        if (activity == null || historyRecordManager == null || searchEditText == null) {
            return;
        }
        final String query = item.query;
        new AlertDialog.Builder(activity)
                .setTitle(query)
                .setMessage(R.string.delete_item_search_history)
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    final Disposable onDelete = historyRecordManager.deleteSearchHistory(query)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(
                                    howManyDeleted -> suggestionPublisher
                                            .onNext(getSearchEditString()),
                                    throwable -> showSnackBarError(new ErrorInfo(throwable,
                                            UserAction.DELETE_FROM_HISTORY,
                                            "Deleting item failed")));
                    disposables.add(onDelete);
                })
                .show();
    }

    @Override
    public boolean onBackPressed() {
        if (suggestionsPanelVisible
                && !infoListAdapter.getItemsList().isEmpty()
                && !isLoading.get()) {
            hideSuggestionsPanel();
            hideKeyboardSearch();
            searchEditText.setText(lastSearchedString);
            return true;
        }
        return false;
    }


    private Observable<List<SuggestionItem>> getLocalSuggestionsObservable(
            final String query, final int similarQueryLimit) {
        return historyRecordManager
                .getRelatedSearches(query, similarQueryLimit, 25)
                .toObservable()
                .map(searchHistoryEntries ->
                        searchHistoryEntries.stream()
                                .map(entry -> new SuggestionItem(true, entry))
                                .collect(Collectors.toList()));
    }

    private Observable<List<SuggestionItem>> getRemoteSuggestionsObservable(final String query) {
        return ExtractorHelper
                .suggestionsFor(serviceId, query)
                .toObservable()
                .map(strings -> {
                    final List<SuggestionItem> result = new ArrayList<>();
                    for (final String entry : strings) {
                        result.add(new SuggestionItem(false, entry));
                    }
                    return result;
                })
                .onErrorReturnItem(Collections.emptyList());
    }

    private void initSuggestionObserver() {
        if (DEBUG) {
            Log.d(TAG, "initSuggestionObserver() called");
        }
        if (suggestionDisposable != null) {
            suggestionDisposable.dispose();
        }

        suggestionDisposable = suggestionPublisher
                .debounce(SUGGESTIONS_DEBOUNCE, TimeUnit.MILLISECONDS)
                .startWithItem(searchString == null ? "" : searchString)
                .switchMap(query -> {
                    // Only show remote suggestions if they are enabled in settings and
                    // the query length is at least THRESHOLD_NETWORK_SUGGESTION
                    final boolean shallShowRemoteSuggestionsNow = showRemoteSuggestions
                            && query.length() >= THRESHOLD_NETWORK_SUGGESTION
                            && query.length() <= MAX_REMOTE_SUGGESTION_QUERY_LENGTH
                            && query.trim().split("\\s+").length
                            <= MAX_REMOTE_SUGGESTION_QUERY_WORDS;

                    if (showLocalSuggestions && shallShowRemoteSuggestionsNow) {
                        return Observable.zip(
                                getLocalSuggestionsObservable(query, 3),
                                getRemoteSuggestionsObservable(query),
                                (local, remote) -> {
                                    remote.removeIf(remoteItem -> local.stream().anyMatch(
                                            localItem -> localItem.equals(remoteItem)));
                                    local.addAll(remote);
                                    return local;
                                })
                                .materialize();
                    } else if (showLocalSuggestions) {
                        return getLocalSuggestionsObservable(query, 25)
                                .materialize();
                    } else if (shallShowRemoteSuggestionsNow) {
                        return getRemoteSuggestionsObservable(query)
                                .materialize();
                    } else {
                        return Single.fromCallable(Collections::<SuggestionItem>emptyList)
                                .toObservable()
                                .materialize();
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        listNotification -> {
                            if (listNotification.isOnNext()) {
                                if (listNotification.getValue() != null) {
                                    handleSuggestions(listNotification.getValue());
                                }
                            } else if (listNotification.isOnError()
                                    && listNotification.getError() != null
                                    && !ExceptionUtils.isInterruptedCaused(
                                    listNotification.getError())) {
                                showSnackBarError(new ErrorInfo(listNotification.getError(),
                                        UserAction.GET_SUGGESTIONS, searchString, serviceId));
                            }
                        }, throwable -> showSnackBarError(new ErrorInfo(
                                throwable, UserAction.GET_SUGGESTIONS, searchString, serviceId)));
    }

    @Override
    protected void doInitialLoadLogic() {
        // no-op
    }

    /**
     * Perform a search.
     * @param theSearchString the trimmed search string
     * @param theContentFilter the content filter to use. FIXME: unused param
     * @param theSortFilter FIXME: unused param
     */
    private void search(@NonNull final String theSearchString,
                        final String[] theContentFilter,
                        final String theSortFilter) {
        if (DEBUG) {
            Log.d(TAG, "search() called with: query = [" + theSearchString + "]");
        }
        if (theSearchString.isEmpty()) {
            return;
        }

        // Check if theSearchString is a URL which can be opened by NewPipe directly
        // and open it if possible.
        try {
            final StreamingService streamingService = NewPipe.getServiceByUrl(theSearchString);
            showLoading();
            disposables.add(Observable
                    .fromCallable(() -> NavigationHelper.getIntentByLink(activity,
                            streamingService, theSearchString))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(intent -> {
                        getFM().popBackStackImmediate();
                        activity.startActivity(intent);
                    }, throwable -> showTextError(getString(R.string.unsupported_url))));
            return;
        } catch (final Exception ignored) {
            // Exception occurred, it's not a url
        }

        // prepare search
        lastSearchedString = this.searchString;
        this.searchString = theSearchString;
        infoListAdapter.clearStreamItemList();
        hideSuggestionsPanel();
        showMetaInfoInTextView(null, searchBinding.searchMetaInfoTextView,
                searchBinding.searchMetaInfoSeparator, disposables);
        hideKeyboardSearch();

        // store search query if search history is enabled
        disposables.add(historyRecordManager.onSearched(serviceId, theSearchString)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        ignored -> {
                        },
                        throwable -> showSnackBarError(new ErrorInfo(throwable, UserAction.SEARCHED,
                                theSearchString, serviceId))
                ));

        // load search results
        suggestionPublisher.onNext(theSearchString);
        startLoading(false);
    }

    @Override
    public void startLoading(final boolean forceLoad) {
        super.startLoading(forceLoad);
        disposables.clear();
        if (searchDisposable != null) {
            searchDisposable.dispose();
        }
        if (!forceLoad && dailySearchCacheEnabled) {
            final SearchInfo cachedResult = readDailySearchCache();
            if (cachedResult != null) {
                isLoading.set(false);
                handleResult(cachedResult);
                return;
            }
        }
        searchDisposable = ExtractorHelper.searchFor(serviceId,
                searchString,
                Arrays.asList(contentFilter),
                sortFilter)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnEvent((searchResult, throwable) -> isLoading.set(false))
                .subscribe(result -> {
                    if (dailySearchCacheEnabled) {
                        writeDailySearchCache(result);
                    }
                    handleResult(result);
                }, this::onItemError);

    }

    @Override
    protected void loadMoreItems() {
        if (!Page.isValid(nextPage)) {
            return;
        }
        isLoading.set(true);
        showListFooter(true);
        if (searchDisposable != null) {
            searchDisposable.dispose();
        }
        searchDisposable = ExtractorHelper.getMoreSearchItems(
                serviceId,
                searchString,
                asList(contentFilter),
                sortFilter,
                nextPage)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnEvent((nextItemsResult, throwable) -> isLoading.set(false))
                .subscribe(this::handleNextItems, this::onItemError);
    }

    @Override
    protected boolean hasMoreItems() {
        return Page.isValid(nextPage);
    }

    @Override
    protected void onItemSelected(final InfoItem selectedItem) {
        super.onItemSelected(selectedItem);
        hideKeyboardSearch();
    }

    private void onItemError(final Throwable exception) {
        if (exception instanceof SearchExtractor.NothingFoundException) {
            infoListAdapter.clearStreamItemList();
            showEmptyState();
        } else {
            showError(new ErrorInfo(exception, UserAction.SEARCHED, searchString, serviceId,
                    getOpenInBrowserUrlForErrors()));
        }
    }

    @Nullable
    private String getOpenInBrowserUrlForErrors() {
        if (TextUtils.isEmpty(searchString)) {
            return null;
        }
        try {
            return service.getSearchQHFactory().getUrl(searchString,
                    Arrays.asList(contentFilter), sortFilter);
        } catch (final NullPointerException | ParsingException ignored) {
            return null;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private void changeContentFilter(final MenuItem item, final List<String> theContentFilter) {
        filterItemCheckedId = item.getItemId();
        item.setChecked(true);

        if (service != null) {
            final boolean isNotFiltered = theContentFilter.isEmpty()
                    || "all".equals(theContentFilter.get(0));
            if (isNotFiltered) {
                searchEditText.setHint(
                        getString(R.string.search_with_service_name,
                                service.getServiceInfo().getName()));
            } else {
                searchEditText.setHint(getString(R.string.search_with_service_name_and_filter,
                        service.getServiceInfo().getName(),
                        item.getTitle()));
            }
        }

        contentFilter = theContentFilter.toArray(new String[0]);

        if (!TextUtils.isEmpty(searchString)) {
            search(searchString, contentFilter, sortFilter);
        }
    }

    private void setQuery(final int theServiceId,
                          final String theSearchString,
                          final String[] theContentFilter,
                          final String theSortFilter) {
        serviceId = theServiceId;
        searchString = theSearchString;
        contentFilter = theContentFilter;
        sortFilter = theSortFilter;
    }

    private String getSearchEditString() {
        return searchEditText.getText().toString();
    }

    private boolean isSearchEditBlank() {
        return isBlank(getSearchEditString());
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Suggestion Results
    //////////////////////////////////////////////////////////////////////////*/

    public void handleSuggestions(@NonNull final List<SuggestionItem> suggestions) {
        if (DEBUG) {
            Log.d(TAG, "handleSuggestions() called with: suggestions = [" + suggestions + "]");
        }
        suggestionListAdapter.submitList(suggestions,
                () -> {
                    if (searchBinding != null) {
                        searchBinding.suggestionsList.scrollToPosition(0);
                    }
                });

        if (suggestionsPanelVisible && isErrorPanelVisible()) {
            hideLoading();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void hideLoading() {
        super.hideLoading();
        showListFooter(false);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Search Results
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void handleResult(@NonNull final SearchInfo result) {
        final List<Throwable> exceptions = result.getErrors();
        if (!exceptions.isEmpty()
                && !(exceptions.size() == 1
                && exceptions.get(0) instanceof SearchExtractor.NothingFoundException)) {
            showSnackBarError(new ErrorInfo(result.getErrors(), UserAction.SEARCHED,
                    searchString, serviceId, getOpenInBrowserUrlForErrors()));
        }

        searchSuggestion = result.getSearchSuggestion();
        if (searchSuggestion != null) {
            searchSuggestion = searchSuggestion.trim();
        }
        isCorrectedSearch = result.isCorrectedSearch();

        // List<MetaInfo> cannot be bundled without creating some containers
        metaInfo = result.getMetaInfo().toArray(new MetaInfo[0]);
        showMetaInfoInTextView(result.getMetaInfo(), searchBinding.searchMetaInfoTextView,
                searchBinding.searchMetaInfoSeparator, disposables);

        handleSearchSuggestion();

        lastSearchedString = searchString;
        nextPage = result.getNextPage();

        if (infoListAdapter.getItemsList().isEmpty()) {
            if (!result.getRelatedItems().isEmpty()) {
                infoListAdapter.addInfoItemList(filterRecentResults(result.getRelatedItems()));
            } else {
                infoListAdapter.clearStreamItemList();
                showEmptyState();
                return;
            }
        }

        super.handleResult(result);
    }

    private void handleSearchSuggestion() {
        if (TextUtils.isEmpty(searchSuggestion)) {
            searchBinding.correctSuggestion.setVisibility(View.GONE);
        } else {
            final String helperText = getString(isCorrectedSearch
                    ? R.string.search_showing_result_for
                    : R.string.did_you_mean);

            final String highlightedSearchSuggestion =
                    "<b><i>" + Html.escapeHtml(searchSuggestion) + "</i></b>";
            final String text = String.format(helperText, highlightedSearchSuggestion);
            searchBinding.correctSuggestion.setText(HtmlCompat.fromHtml(text,
                    HtmlCompat.FROM_HTML_MODE_LEGACY));

            searchBinding.correctSuggestion.setOnClickListener(v -> {
                searchBinding.correctSuggestion.setVisibility(View.GONE);
                search(searchSuggestion, contentFilter, sortFilter);
                searchEditText.setText(searchSuggestion);
            });

            searchBinding.correctSuggestion.setOnLongClickListener(v -> {
                searchEditText.setText(searchSuggestion);
                searchEditText.setSelection(searchSuggestion.length());
                showKeyboardSearch();
                return true;
            });

            searchBinding.correctSuggestion.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void handleNextItems(final ListExtractor.InfoItemsPage<?> result) {
        stopRefreshing();
        showListFooter(false);
        infoListAdapter.addInfoItemList(filterRecentResults(result.getItems()));

        if (!result.getErrors().isEmpty()) {
            // nextPage should be non-null at this point, because it refers to the page
            // whose results are handled here, but let's check it anyway
            if (nextPage == null) {
                showSnackBarError(new ErrorInfo(result.getErrors(), UserAction.SEARCHED,
                        "\"" + searchString + "\" → nextPage == null", serviceId,
                        getOpenInBrowserUrlForErrors()));
            } else {
                showSnackBarError(new ErrorInfo(result.getErrors(), UserAction.SEARCHED,
                        "\"" + searchString + "\" → pageUrl: " + nextPage.getUrl() + ", "
                                + "pageIds: " + nextPage.getIds() + ", "
                                + "pageCookies: " + nextPage.getCookies(),
                        serviceId, getOpenInBrowserUrlForErrors()));
            }
        }

        // keep the reassignment of nextPage after the error handling to ensure that nextPage
        // still holds the correct value during the error handling
        nextPage = result.getNextPage();
        super.handleNextItems(result);
    }

    @Nullable
    private SearchInfo readDailySearchCache() {
        final File cacheFile = getDailySearchCacheFile();
        if (cacheFile == null || !cacheFile.isFile()
                || System.currentTimeMillis() - cacheFile.lastModified()
                > DAILY_SEARCH_CACHE_MAX_AGE_MS) {
            return null;
        }

        try (ObjectInputStream inputStream =
                     new ObjectInputStream(new FileInputStream(cacheFile))) {
            final Object cached = inputStream.readObject();
            return cached instanceof SearchInfo ? (SearchInfo) cached : null;
        } catch (final Exception e) {
            if (DEBUG) {
                Log.w(TAG, "Unable to read daily search cache", e);
            }
            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();
            return null;
        }
    }

    private void writeDailySearchCache(@NonNull final SearchInfo result) {
        final File cacheFile = getDailySearchCacheFile();
        if (cacheFile == null) {
            return;
        }

        final File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }

        try (ObjectOutputStream outputStream =
                     new ObjectOutputStream(new FileOutputStream(cacheFile))) {
            outputStream.writeObject(result);
        } catch (final Exception e) {
            if (DEBUG) {
                Log.w(TAG, "Unable to write daily search cache", e);
            }
        }
    }

    @Nullable
    private File getDailySearchCacheFile() {
        if (activity == null || TextUtils.isEmpty(searchString)) {
            return null;
        }
        final String rawKey = serviceId + "_" + onlyRecentResults + "_"
                + searchString + "_" + Arrays.toString(contentFilter) + "_" + sortFilter;
        return new File(new File(activity.getFilesDir(), "daily_search_cache"),
                Uri.encode(rawKey) + ".bin");
    }

    private List<InfoItem> filterRecentResults(final List<? extends InfoItem> items) {
        if (!onlyRecentResults) {
            return new ArrayList<>(items);
        }

        return items.stream()
                .filter(this::isRecentStreamItem)
                .collect(Collectors.toList());
    }

    private boolean isRecentStreamItem(final InfoItem item) {
        if (!(item instanceof StreamInfoItem)) {
            return false;
        }

        final StreamInfoItem streamItem = (StreamInfoItem) item;
        if (StreamTypeUtil.isLiveStream(streamItem.getStreamType())) {
            return true;
        }

        final DateWrapper uploadDate = streamItem.getUploadDate();
        if (uploadDate == null) {
            return isTextualUploadDateRecent(streamItem.getTextualUploadDate());
        }

        return !uploadDate.offsetDateTime()
                .isBefore(OffsetDateTime.now().minusDays(RECENT_RESULTS_DAYS));
    }

    private boolean isTextualUploadDateRecent(@Nullable final String textualUploadDate) {
        if (TextUtils.isEmpty(textualUploadDate)) {
            // If we can't determine it, keep it to avoid showing almost-empty tabs.
            return true;
        }

        final String text = textualUploadDate.trim().toLowerCase();
        if (text.contains("yesterday") || text.contains("ontem")) {
            return true;
        }

        // Explicit old units -> not recent
        if (text.contains("week") || text.contains("weeks")
                || text.contains("semana") || text.contains("semanas")
                || text.contains("month") || text.contains("months")
                || text.contains("mes") || text.contains("mês") || text.contains("meses")
                || text.contains("year") || text.contains("years")
                || text.contains("ano") || text.contains("anos")) {
            return false;
        }

        final Matcher matcher = RELATIVE_NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            // Unknown format: keep.
            return true;
        }

        final int number;
        try {
            number = Integer.parseInt(matcher.group(1));
        } catch (final NumberFormatException e) {
            return true;
        }

        final String unit = matcher.group(2).toLowerCase();
        if (unit.startsWith("day") || unit.startsWith("dia")) {
            return number <= RECENT_RESULTS_DAYS;
        }

        // minutes/hours are always within 5 days
        return true;
    }

    @Override
    public void handleError() {
        super.handleError();
        stopRefreshing();
        hideSuggestionsPanel();
        hideKeyboardSearch();
    }

    @Override
    public void showEmptyState() {
        super.showEmptyState();
        stopRefreshing();
    }

    private void stopRefreshing() {
        if (searchBinding != null) {
            searchBinding.swipeRefreshLayout.setRefreshing(false);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Suggestion item touch helper
    //////////////////////////////////////////////////////////////////////////*/

    public int getSuggestionMovementFlags(@NonNull final RecyclerView.ViewHolder viewHolder) {
        final int position = viewHolder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION) {
            return 0;
        }

        final SuggestionItem item = suggestionListAdapter.getCurrentList().get(position);
        return item.fromHistory ? makeMovementFlags(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) : 0;
    }

    public void onSuggestionItemSwiped(@NonNull final RecyclerView.ViewHolder viewHolder) {
        final int position = viewHolder.getBindingAdapterPosition();
        final String query = suggestionListAdapter.getCurrentList().get(position).query;
        final Disposable onDelete = historyRecordManager.deleteSearchHistory(query)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        howManyDeleted -> suggestionPublisher
                                .onNext(getSearchEditString()),
                        throwable -> showSnackBarError(new ErrorInfo(throwable,
                                UserAction.DELETE_FROM_HISTORY, "Deleting item failed")));
        disposables.add(onDelete);
    }
}
