package org.schabi.newpipe.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.util.ExtractorHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;

public final class MusicCatalogRepository {
    private static final String ASSET_FILE = "music_catalog.json";
    private static final String CACHE_FILE = "music_catalog_cache.json";
    private static final String CACHE_UPDATED_AT_KEY = "music_catalog_cache_updated_at";
    private static final String CACHE_LAST_ATTEMPT_KEY = "music_catalog_refresh_last_attempt";
    private static final String CACHE_QUERY_CYCLE_KEY = "music_catalog_refresh_query_cycle";
    private static final String CACHE_VERSION_KEY = "music_catalog_cache_version";
    private static final String LAST_CHANGED_TRACK_COUNT_KEY = "music_catalog_changed_track_count";
    private static final long WEEK_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long RETRY_DELAY_MS = 6L * 60L * 60L * 1000L;
    private static final int CACHE_VERSION = 4;
    private static final int MAX_REFRESHED_SONGS = 12;
    private static final int DEFAULT_SERVICE_ID = ServiceList.YouTube.getServiceId();
    private static final List<String> WEEKLY_PLAYLIST_SLUGS = List.of(
            "top-brasil", "top-global", "viral-hits", "lancamentos",
            "descobertas-da-semana", "novidades-para-voce",
            "indie", "rock", "hip-hop", "k-pop", "sertanejo", "gospel", "piseiro",
            "relax", "happy", "workout", "party", "focus", "romantic", "sad",
            "road-trip", "sleep", "energized", "chill", "nostalgic", "confidence",
            "rainy-day");

    public MusicCatalog loadCatalog(@NonNull final Context context) throws IOException {
        try {
            final File cacheFile = ensureCacheFile(context);
            final String json = readFile(cacheFile);
            return parseCatalog(new JSONObject(json));
        } catch (final JSONException e) {
            throw new IOException("Invalid music catalog JSON", e);
        }
    }

    private File ensureCacheFile(@NonNull final Context context) throws IOException {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final File cacheFile = new File(context.getFilesDir(), CACHE_FILE);
        if (!cacheFile.exists()) {
            copyAssetToFile(context, cacheFile);
            preferences.edit()
                    .putInt(CACHE_VERSION_KEY, CACHE_VERSION)
                    .apply();
        } else if (preferences.getInt(CACHE_VERSION_KEY, 0) < CACHE_VERSION) {
            mergeBundledCatalog(context, cacheFile);
            preferences.edit().putInt(CACHE_VERSION_KEY, CACHE_VERSION).apply();
        }
        return cacheFile;
    }

    private void mergeBundledCatalog(@NonNull final Context context,
                                     @NonNull final File cacheFile) throws IOException {
        try {
            final JSONObject cached = new JSONObject(readFile(cacheFile));
            final JSONObject bundled = new JSONObject(readAsset(context));
            final JSONArray cachedPlaylists = cached.optJSONArray("playlists");
            final JSONArray bundledPlaylists = bundled.optJSONArray("playlists");
            if (cachedPlaylists == null || bundledPlaylists == null) {
                copyAssetToFile(context, cacheFile);
                return;
            }

            final Set<String> existingSlugs = new HashSet<>();
            for (int i = 0; i < cachedPlaylists.length(); i++) {
                final JSONObject playlist = cachedPlaylists.optJSONObject(i);
                if (playlist != null) {
                    existingSlugs.add(playlist.optString("slug"));
                }
            }
            for (int i = 0; i < bundledPlaylists.length(); i++) {
                final JSONObject playlist = bundledPlaylists.optJSONObject(i);
                if (playlist != null && existingSlugs.add(playlist.optString("slug"))) {
                    cachedPlaylists.put(playlist);
                }
            }
            for (final String category : List.of(
                    "featured", "genrePlaylists", "moodPlaylists", "automaticPlaylists")) {
                final JSONArray bundledSlugs = bundled.optJSONArray(category);
                if (bundledSlugs == null) {
                    continue;
                }
                final JSONArray cachedSlugs = cached.optJSONArray(category) == null
                        ? new JSONArray() : cached.optJSONArray(category);
                final Set<String> existing = new HashSet<>();
                for (int i = 0; i < cachedSlugs.length(); i++) {
                    existing.add(cachedSlugs.optString(i));
                }
                for (int i = 0; i < bundledSlugs.length(); i++) {
                    final String slug = bundledSlugs.optString(i);
                    if (existing.add(slug)) {
                        cachedSlugs.put(slug);
                    }
                }
                cached.put(category, cachedSlugs);
            }
            try (FileOutputStream output = new FileOutputStream(cacheFile, false)) {
                output.write(cached.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (final JSONException e) {
            throw new IOException("Invalid music catalog during cache migration", e);
        }
    }

    private String readAsset(@NonNull final Context context) throws IOException {
        try (var inputStream = context.getAssets().open(ASSET_FILE)) {
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static boolean isWeeklyRefreshDue(@NonNull final Context context) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final long now = System.currentTimeMillis();
        return now - preferences.getLong(CACHE_UPDATED_AT_KEY, 0L) >= WEEK_MS
                && now - preferences.getLong(CACHE_LAST_ATTEMPT_KEY, 0L) >= RETRY_DELAY_MS;
    }

    public static int getLastChangedTrackCount(@NonNull final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(LAST_CHANGED_TRACK_COUNT_KEY, 0);
    }

    /**
     * Refreshes the changing recommendation lists from current YouTube music search results.
     *
     * @param context a context used to read and update the cached catalog
     * @return the catalog containing the refreshed recommendation lists
     */
    @NonNull
    public Single<MusicCatalog> refreshWeeklyRecommendations(@NonNull final Context context) {
        final Context appContext = context.getApplicationContext();
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(appContext);
        final int queryCycle = preferences.getInt(CACHE_QUERY_CYCLE_KEY, 0);
        preferences.edit()
                .putLong(CACHE_LAST_ATTEMPT_KEY, System.currentTimeMillis())
                .putInt(CACHE_QUERY_CYCLE_KEY, queryCycle + 1)
                .apply();

        return Single.fromCallable(() -> {
                    final File cacheFile = ensureCacheFile(appContext);
                    return new JSONObject(readFile(cacheFile));
                })
                .flatMap(root -> {
                    final int[] successfulUpdates = {0};
                    return Observable.fromIterable(WEEKLY_PLAYLIST_SLUGS)
                        .concatMapSingle(slug -> fetchPlaylistUpdate(root, slug, queryCycle)
                                .onErrorReturn(error -> new PlaylistUpdate(slug, new JSONArray())))
                        .collect(() -> root, (catalogJson, update) -> {
                            if (update.songs.length() == 0) {
                                return;
                            }
                            successfulUpdates[0]++;
                            final JSONArray playlists = catalogJson.optJSONArray("playlists");
                            if (playlists == null) {
                                return;
                            }
                            for (int i = 0; i < playlists.length(); i++) {
                                final JSONObject playlist = playlists.optJSONObject(i);
                                if (playlist != null
                                        && update.slug.equals(playlist.optString("slug"))) {
                                    playlist.put("songs", update.songs);
                                    break;
                                }
                            }
                        })
                .flatMap(catalogJson -> Single.fromCallable(() -> {
                    if (successfulUpdates[0] == 0) {
                        throw new IOException("Could not refresh any music suggestions");
                    }
                    final File cacheFile = ensureCacheFile(appContext);
                    final int changedTracks = countChangedTracks(
                            new JSONObject(readFile(cacheFile)), catalogJson);
                    try (FileOutputStream output = new FileOutputStream(cacheFile, false)) {
                        output.write(catalogJson.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                    preferences.edit()
                            .putLong(CACHE_UPDATED_AT_KEY, System.currentTimeMillis())
                            .putInt(CACHE_VERSION_KEY, CACHE_VERSION)
                            .putInt(LAST_CHANGED_TRACK_COUNT_KEY, changedTracks)
                            .apply();
                    return parseCatalog(catalogJson);
                }));
                })
                .doOnError(error -> preferences.edit()
                        .putLong(CACHE_LAST_ATTEMPT_KEY, System.currentTimeMillis()).apply());
    }

    private Single<PlaylistUpdate> fetchPlaylistUpdate(
            @NonNull final JSONObject catalogJson,
            @NonNull final String slug,
            final int queryCycle) throws JSONException {
        final JSONObject playlistJson = findPlaylistJson(catalogJson, slug);
        if (playlistJson == null) {
            return Single.just(new PlaylistUpdate(slug, new JSONArray()));
        }
        final List<String> queries = getSearchQueries(parsePlaylist(playlistJson));
        if (queries.isEmpty()) {
            return Single.just(new PlaylistUpdate(slug, new JSONArray()));
        }

        final List<String> rotatedQueries = new ArrayList<>(queries.size());
        final int firstQuery = Math.floorMod(queryCycle, queries.size());
        for (int offset = 0; offset < queries.size(); offset++) {
            rotatedQueries.add(queries.get((firstQuery + offset) % queries.size()));
        }

        return Observable.fromIterable(rotatedQueries)
                .concatMapSingle(query -> fetchPlaylistUpdateForQuery(slug, query)
                        .onErrorReturn(error -> new PlaylistUpdate(slug, new JSONArray())))
                .filter(update -> update.songs.length() > 0)
                .toList()
                .map(updates -> {
                    PlaylistUpdate lastUpdate = new PlaylistUpdate(slug, new JSONArray());
                    for (final PlaylistUpdate update : updates) {
                        lastUpdate = update;
                        if (hasDifferentTracks(playlistJson, update.songs)) {
                            return update;
                        }
                    }
                    return lastUpdate;
                });
    }

    private Single<PlaylistUpdate> fetchPlaylistUpdateForQuery(
            @NonNull final String slug,
            @NonNull final String query) {
        return ExtractorHelper.searchFor(DEFAULT_SERVICE_ID, query,
                        List.of(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
                .map(searchInfo -> {
                    final JSONArray songs = new JSONArray();
                    final Set<String> seenIds = new HashSet<>();
                    for (final InfoItem result : searchInfo.getRelatedItems()) {
                        if (!(result instanceof StreamInfoItem)) {
                            continue;
                        }
                        final MusicSong song = fromStreamInfoItem((StreamInfoItem) result);
                        if (song == null || !seenIds.add(song.youtubeId)) {
                            continue;
                        }
                        final JSONObject songJson = new JSONObject()
                                .put("title", song.title)
                                .put("artist", song.artist)
                                .put("album", "")
                                .put("duration", song.duration)
                                .put("youtubeId", song.youtubeId);
                        songs.put(songJson);
                        if (songs.length() >= MAX_REFRESHED_SONGS) {
                            break;
                        }
                    }
                    return new PlaylistUpdate(slug, songs);
                });
    }

    private boolean hasDifferentTracks(@NonNull final JSONObject playlistJson,
                                       @NonNull final JSONArray candidateSongs) {
        final JSONArray currentSongs = playlistJson.optJSONArray("songs");
        final int currentCount = currentSongs == null ? 0 : currentSongs.length();
        if (currentCount != candidateSongs.length()) {
            return true;
        }
        for (int index = 0; index < currentCount; index++) {
            final JSONObject currentSong = currentSongs.optJSONObject(index);
            final JSONObject candidateSong = candidateSongs.optJSONObject(index);
            final String currentId = currentSong == null ? "" : currentSong.optString("youtubeId");
            final String candidateId = candidateSong == null
                    ? "" : candidateSong.optString("youtubeId");
            if (!currentId.equals(candidateId)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private JSONObject findPlaylistJson(@NonNull final JSONObject catalogJson,
                                        @NonNull final String slug) {
        final JSONArray playlists = catalogJson.optJSONArray("playlists");
        if (playlists == null) {
            return null;
        }
        for (int i = 0; i < playlists.length(); i++) {
            final JSONObject playlist = playlists.optJSONObject(i);
            if (playlist != null && slug.equals(playlist.optString("slug"))) {
                return playlist;
            }
        }
        return null;
    }

    private int countChangedTracks(@NonNull final JSONObject oldCatalog,
                                   @NonNull final JSONObject newCatalog) {
        int changed = 0;
        for (final String slug : WEEKLY_PLAYLIST_SLUGS) {
            final JSONObject oldPlaylist = findPlaylistJson(oldCatalog, slug);
            final JSONObject newPlaylist = findPlaylistJson(newCatalog, slug);
            if (oldPlaylist == null || newPlaylist == null) {
                continue;
            }
            final JSONArray oldSongs = oldPlaylist.optJSONArray("songs");
            final JSONArray newSongs = newPlaylist.optJSONArray("songs");
            final int oldCount = oldSongs == null ? 0 : oldSongs.length();
            final int newCount = newSongs == null ? 0 : newSongs.length();
            final int comparedCount = Math.max(oldCount, newCount);
            for (int i = 0; i < comparedCount; i++) {
                final JSONObject oldSong = oldSongs == null || i >= oldCount
                        ? null : oldSongs.optJSONObject(i);
                final JSONObject newSong = newSongs == null || i >= newCount
                        ? null : newSongs.optJSONObject(i);
                final String oldId = oldSong == null ? "" : oldSong.optString("youtubeId");
                final String newId = newSong == null ? "" : newSong.optString("youtubeId");
                if (!oldId.equals(newId)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static final class PlaylistUpdate {
        private final String slug;
        private final JSONArray songs;

        private PlaylistUpdate(final String slug, final JSONArray songs) {
            this.slug = slug;
            this.songs = songs;
        }
    }

    private void copyAssetToFile(@NonNull final Context context,
                                 @NonNull final File cacheFile) throws IOException {
        try (var inputStream = context.getAssets().open(ASSET_FILE);
             var outputStream = new FileOutputStream(cacheFile, false)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    private String readFile(@NonNull final File file) throws IOException {
        try (var inputStream = new FileInputStream(file)) {
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private MusicCatalog parseCatalog(@NonNull final JSONObject root) throws JSONException {
        final Map<String, MusicPlaylist> bySlug = new LinkedHashMap<>();
        final JSONArray playlistsJson = root.getJSONArray("playlists");
        for (int i = 0; i < playlistsJson.length(); i++) {
            final MusicPlaylist playlist = parsePlaylist(playlistsJson.getJSONObject(i));
            bySlug.put(playlist.slug, playlist);
        }

        return new MusicCatalog(
                root.optString("title", "resso"),
                pickPlaylists(root.optJSONArray("featured"), bySlug),
                pickPlaylists(root.optJSONArray("genrePlaylists"), bySlug),
                pickPlaylists(root.optJSONArray("moodPlaylists"), bySlug),
                pickPlaylists(root.optJSONArray("automaticPlaylists"), bySlug),
                bySlug
        );
    }

    private List<MusicPlaylist> pickPlaylists(final JSONArray slugs,
                                              @NonNull final Map<String, MusicPlaylist> bySlug)
            throws JSONException {
        final List<MusicPlaylist> playlists = new ArrayList<>();
        if (slugs == null) {
            return playlists;
        }

        for (int i = 0; i < slugs.length(); i++) {
            final String slug = slugs.getString(i);
            final MusicPlaylist playlist = bySlug.get(slug);
            if (playlist != null) {
                playlists.add(playlist);
            }
        }
        return playlists;
    }

    private MusicPlaylist parsePlaylist(@NonNull final JSONObject playlistJson)
            throws JSONException {
        final List<MusicSong> songs = new ArrayList<>();
        final JSONArray songsJson = playlistJson.getJSONArray("songs");
        for (int i = 0; i < songsJson.length(); i++) {
            songs.add(parseSong(songsJson.getJSONObject(i)));
        }

        return new MusicPlaylist(
                playlistJson.getString("slug"),
                playlistJson.getString("title"),
                playlistJson.optString("subtitle"),
                playlistJson.optString("description"),
                playlistJson.optString("accentColor", "#FF0A7A"),
                songs
        );
    }

    private MusicSong parseSong(@NonNull final JSONObject songJson) throws JSONException {
        final String youtubeId = songJson.getString("youtubeId");
        return new MusicSong(
                songJson.getString("title"),
                songJson.getString("artist"),
                songJson.optString("album"),
                songJson.optString("duration"),
                youtubeId,
                DEFAULT_SERVICE_ID,
                StreamType.VIDEO_STREAM
        );
    }

    @NonNull
    public static List<MusicSong> buildSongBatch(@NonNull final List<MusicSong> songs,
                                                 final int startIndex,
                                                 final int count,
                                                 final int cycleSeed) {
        if (songs.isEmpty() || count <= 0) {
            return List.of();
        }

        final int normalizedStart = normalizeIndex(startIndex, songs.size());
        final List<MusicSong> batch = new ArrayList<>(count);
        int cursor = normalizedStart;
        int seed = cycleSeed;

        while (batch.size() < count) {
            final ArrayList<MusicSong> cycle = new ArrayList<>(songs);
            if (cycle.size() > 1) {
                Collections.rotate(cycle, -((cursor + seed) % cycle.size()));
            }

            for (final MusicSong song : cycle) {
                batch.add(song);
                if (batch.size() >= count) {
                    break;
                }
            }

            cursor = 0;
            seed++;
        }

        return batch;
    }

    public static int normalizeIndex(final int index, final int size) {
        if (size <= 0) {
            return 0;
        }

        final int normalized = index % size;
        return normalized < 0 ? normalized + size : normalized;
    }

    /**
     * Resolves both legacy positional IDs and stable YouTube IDs used by car media clients.
     * @param playlist catalog playlist to search
     * @param songKey legacy playlist index or stable YouTube ID
     * @return the current playlist index, or -1 when the song is no longer present
     */
    public static int resolveSongIndex(@NonNull final MusicPlaylist playlist,
                                       @NonNull final String songKey) {
        try {
            final int legacyIndex = Integer.parseInt(songKey);
            if (legacyIndex >= 0 && legacyIndex < playlist.songs.size()) {
                return legacyIndex;
            }
        } catch (final NumberFormatException ignored) {
            // New IDs use the stable YouTube ID so catalog refreshes cannot shift selections.
        }
        for (int index = 0; index < playlist.songs.size(); index++) {
            if (playlist.songs.get(index).youtubeId.equals(songKey)) {
                return index;
            }
        }
        return -1;
    }

    @NonNull
    public static List<String> getSearchQueries(@NonNull final MusicPlaylist playlist) {
        final String currentYear = String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
        final List<String> rockQueries = getRockQueries(playlist.slug);
        if (!rockQueries.isEmpty()) {
            return rockQueries;
        }

        final List<String> raveQueries = getRaveQueries(playlist.slug);
        if (!raveQueries.isEmpty()) {
            return raveQueries;
        }

        switch (playlist.slug) {
            case "top-brasil":
                return List.of(
                        "top brasil hits " + currentYear,
                        "funk pop brasil " + currentYear,
                        "sertanejo pop brasil");
            case "top-global":
                return List.of(
                        "top global hits " + currentYear,
                        "global pop playlist " + currentYear,
                        "viral international hits");
            case "viral-hits":
                return List.of("viral hits " + currentYear, "tiktok viral songs",
                        "trending songs playlist");
            case "lancamentos":
                return List.of(
                        "new music releases " + currentYear,
                        "novidades musicais " + currentYear,
                        "latest songs playlist");
            case "indie":
                return List.of(
                        "indie playlist 2026",
                        "indie folk alternative songs",
                        "dreamy indie pop");
            case "rock":
                return List.of(
                        "rock hits playlist",
                        "alternative rock 2026",
                        "classic and modern rock");
            case "hip-hop":
                return List.of("hip hop playlist 2026", "rap curation", "trap hip hop songs");
            case "k-pop":
                return List.of("kpop hits 2026", "k-pop playlist", "korean pop trending");
            case "sertanejo":
                return List.of(
                        "sertanejo 2026",
                        "piseiro sertanejo hits",
                        "modao sertanejo playlist");
            case "piseiro":
                return List.of(
                        "piseiro João Gomes Zé Vaqueiro Rey Vaqueiro",
                        "piseiro hits João Gomes Zé Vaqueiro",
                        "Rey Vaqueiro piseiro");
            case "gospel":
                return List.of(
                        "gospel louvor 2026",
                        "adoracao playlist",
                        "musicas gospel mais tocadas");
            case "relax":
                return List.of("relaxing songs playlist", "chill acoustic relax", "calm music mix");
            case "happy":
                return List.of("happy upbeat songs", "feel good pop playlist", "good vibes music");
            case "workout":
                return List.of(
                        "workout songs playlist",
                        "gym motivation music",
                        "high energy training songs");
            case "party":
                return List.of("party hits playlist", "dance party songs", "club bangers mix");
            case "focus":
                return List.of(
                        "focus music playlist",
                        "study music lyrics",
                        "productive songs mix");
            case "romantic":
                return List.of("romantic love songs", "romantic playlist", "love ballads mix");
            case "sad":
                return List.of("sad songs playlist", "heartbreak songs", "melancholic music mix");
            case "road-trip":
                return List.of("road trip songs", "driving playlist", "open road music mix");
            case "sleep":
                return List.of(
                        "sleep songs playlist",
                        "soft calm sleep music",
                        "night chill songs");
            case "daily-mix":
                return List.of("daily mix songs", "personalized music mix", "mixed hits playlist");
            case "descobertas-da-semana":
                return List.of(
                        "new songs this week " + currentYear,
                        "new discoveries playlist",
                        "hidden gems music");
            case "novidades-para-voce":
                return List.of(
                        "recommended new songs",
                        "music for you playlist",
                        "fresh picks playlist");
            case "tocadas-recentemente":
                return List.of(
                        "recently played style songs",
                        "repeat worthy songs",
                        "current favorites mix");
            case "favoritas":
                return List.of("favorite songs playlist", "best loved hits", "all time favorites");
            default:
                return List.of(
                        playlist.title + " playlist",
                        playlist.subtitle,
                        playlist.description);
        }
    }

    @NonNull
    private static List<String> getRockQueries(@NonNull final String slug) {
        switch (slug) {
            case "rock-classicos":
                return List.of(
                        "classic rock playlist",
                        "70s 80s rock hits",
                        "legendary rock songs");
            case "rock-alternativo":
                return List.of(
                        "alternative rock playlist",
                        "modern alternative rock",
                        "indie alternative rock songs");
            case "hard-rock":
                return List.of("hard rock playlist", "hard rock hits", "arena rock anthems");
            case "metal-essentials":
                return List.of(
                        "metal essentials playlist",
                        "heavy metal classics",
                        "metalcore and heavy riffs");
            case "punk-rock":
                return List.of("punk rock playlist", "pop punk anthems", "skate punk hits");
            case "grunge":
                return List.of("grunge playlist", "90s grunge essentials", "seattle grunge rock");
            case "indie-rock":
                return List.of("indie rock playlist", "indie rock anthems", "garage indie rock");
            case "rock-nacional":
                return List.of(
                        "rock nacional playlist",
                        "classicos do rock brasileiro",
                        "rock brasil anos 80 90");
            case "pop-rock":
                return List.of("pop rock playlist", "pop rock hits", "radio rock songs");
            case "guitar-heroes":
                return List.of(
                        "guitar hero songs",
                        "best guitar solos rock",
                        "shred guitar classics");
            default:
                return List.of();
        }
    }

    @NonNull
    private static List<String> getRaveQueries(@NonNull final String slug) {
        switch (slug) {
            case "balada-eletronica":
                return List.of(
                        "electronic party playlist",
                        "balada eletronica",
                        "dance music night mix");
            case "alok-hits":
                return List.of("alok hits playlist", "alok remix set", "brazilian edm hits");
            case "rave-essentials":
                return List.of(
                        "rave essentials playlist",
                        "festival edm mix",
                        "big room rave songs");
            case "edm-festival":
                return List.of(
                        "edm festival playlist",
                        "mainstage edm anthems",
                        "tomorrowland style mix");
            case "house-party":
                return List.of(
                        "house party playlist",
                        "deep house dance mix",
                        "club house songs");
            case "progressive-house":
                return List.of(
                        "progressive house playlist",
                        "melodic progressive house",
                        "festival progressive mix");
            case "techno-nights":
                return List.of(
                        "techno playlist",
                        "dark techno mix",
                        "warehouse techno set");
            case "trance-energy":
                return List.of(
                        "trance playlist",
                        "uplifting trance mix",
                        "trance energy anthems");
            default:
                return List.of();
        }
    }

    @Nullable
    public static MusicSong fromStreamInfoItem(@NonNull final StreamInfoItem item) {
        final String youtubeId = extractYoutubeId(item.getUrl());
        if (youtubeId == null || youtubeId.isEmpty()) {
            return null;
        }

        return new MusicSong(
                item.getName(),
                item.getUploaderName() == null ? "" : item.getUploaderName(),
                "",
                formatDuration(item.getDuration()),
                youtubeId,
                item.getServiceId(),
                item.getStreamType()
        );
    }

    @Nullable
    public static String extractYoutubeId(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        final Uri uri = Uri.parse(url);
        final String queryId = uri.getQueryParameter("v");
        if (queryId != null && !queryId.isEmpty()) {
            return queryId;
        }

        final List<String> segments = uri.getPathSegments();
        if ("youtu.be".equalsIgnoreCase(uri.getHost()) && !segments.isEmpty()) {
            return segments.get(0);
        }

        final int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < url.length()) {
            return url.substring(lastSlash + 1);
        }
        return null;
    }

    @NonNull
    public static String formatDuration(final long durationSeconds) {
        if (durationSeconds <= 0) {
            return "";
        }

        final long hours = durationSeconds / 3600;
        final long minutes = (durationSeconds % 3600) / 60;
        final long seconds = durationSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    public static final class MusicCatalog {
        public final String title;
        public final List<MusicPlaylist> featured;
        public final List<MusicPlaylist> genrePlaylists;
        public final List<MusicPlaylist> moodPlaylists;
        public final List<MusicPlaylist> automaticPlaylists;
        private final Map<String, MusicPlaylist> bySlug;

        private MusicCatalog(final String title,
                             final List<MusicPlaylist> featured,
                             final List<MusicPlaylist> genrePlaylists,
                             final List<MusicPlaylist> moodPlaylists,
                             final List<MusicPlaylist> automaticPlaylists,
                             final Map<String, MusicPlaylist> bySlug) {
            this.title = title;
            this.featured = featured;
            this.genrePlaylists = genrePlaylists;
            this.moodPlaylists = moodPlaylists;
            this.automaticPlaylists = automaticPlaylists;
            this.bySlug = bySlug;
        }

        public MusicPlaylist getPlaylist(@NonNull final String slug) {
            return bySlug.get(slug);
        }

        public List<MusicPlaylist> getAllPlaylists() {
            final LinkedHashSet<MusicPlaylist> ordered = new LinkedHashSet<>();
            ordered.addAll(featured);
            ordered.addAll(genrePlaylists);
            ordered.addAll(moodPlaylists);
            ordered.addAll(automaticPlaylists);
            return new ArrayList<>(ordered);
        }
    }

    public static final class MusicPlaylist {
        public final String slug;
        public final String title;
        public final String subtitle;
        public final String description;
        public final String accentColor;
        public final List<MusicSong> songs;

        private MusicPlaylist(final String slug,
                              final String title,
                              final String subtitle,
                              final String description,
                              final String accentColor,
                              final List<MusicSong> songs) {
            this.slug = slug;
            this.title = title;
            this.subtitle = subtitle;
            this.description = description;
            this.accentColor = accentColor;
            this.songs = songs;
        }

        public String getHeroImageUrl() {
            return songs.isEmpty() ? null : songs.get(0).getThumbnailUrl();
        }

        @ColorInt
        public int getAccentColor() {
            try {
                return android.graphics.Color.parseColor(accentColor);
            } catch (final IllegalArgumentException ignored) {
                return android.graphics.Color.parseColor("#FF0A7A");
            }
        }
    }

    public static final class MusicSong implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String title;
        public final String artist;
        public final String album;
        public final String duration;
        public final String youtubeId;
        public final int serviceId;
        @NonNull
        public final StreamType streamType;

        private MusicSong(final String title,
                          final String artist,
                          final String album,
                          final String duration,
                          final String youtubeId,
                          final int serviceId,
                          @NonNull final StreamType streamType) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.duration = duration;
            this.youtubeId = youtubeId;
            this.serviceId = serviceId;
            this.streamType = streamType;
        }

        public String getUrl() {
            return "https://www.youtube.com/watch?v=" + youtubeId;
        }

        public String getThumbnailUrl() {
            return "https://i.ytimg.com/vi/" + youtubeId + "/hqdefault.jpg";
        }
    }
}
