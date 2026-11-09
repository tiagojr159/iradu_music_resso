package org.schabi.newpipe.music;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** Updates recommendation playlists once per week while the device has a network connection. */
public final class MusicCatalogRefreshWorker extends Worker {
    private static final String UNIQUE_WORK_NAME = "resso_weekly_music_catalog_refresh";

    public MusicCatalogRefreshWorker(@NonNull final Context context,
                                     @NonNull final WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!MusicCatalogRepository.isWeeklyRefreshDue(getApplicationContext())) {
            return Result.success();
        }
        try {
            new MusicCatalogRepository()
                    .refreshWeeklyRecommendations(getApplicationContext())
                    .blockingGet();
            return Result.success();
        } catch (final Exception error) {
            return Result.retry();
        }
    }

    public static void schedule(@NonNull final Context context) {
        final Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        final PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                MusicCatalogRefreshWorker.class, 7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP, request);
    }
}
