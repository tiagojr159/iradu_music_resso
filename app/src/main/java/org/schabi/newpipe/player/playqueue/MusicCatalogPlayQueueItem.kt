package org.schabi.newpipe.player.playqueue

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.music.MusicCatalogRepository.MusicSong
import org.schabi.newpipe.music.MusicVoiceSearchMatcher
import org.schabi.newpipe.util.ExtractorHelper

/** A catalog queue item that can recover when YouTube requires sign-in for its selected URL. */
class MusicCatalogPlayQueueItem(song: MusicSong) : PlayQueueItem(song.toStreamInfoItem()) {
    private val catalogSong = song

    override fun getStream(context: Context): Single<StreamInfo> = super.getStream(context).onErrorResumeNext { originalError ->
        if (!requiresSignIn(originalError)) {
            return@onErrorResumeNext Single.error<StreamInfo>(originalError)
        }

        Log.w(TAG, "Catalog URL requires sign-in; searching for a playable match: ${catalogSong.title}")
        findPlayableAlternative(context, originalError)
    }

    private fun findPlayableAlternative(context: Context, originalError: Throwable): Single<StreamInfo> {
        val query = "${catalogSong.title} ${catalogSong.artist}".trim()

        return ExtractorHelper.searchFor(
            catalogSong.serviceId,
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            ""
        )
            .flatMap { search ->
                val candidates = search.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .filter { candidate ->
                        candidate.url != catalogSong.url &&
                            MusicVoiceSearchMatcher.matchesSong(
                                catalogSong.title,
                                candidate.name,
                                candidate.uploaderName
                            )
                    }
                    .sortedByDescending { candidate ->
                        MusicVoiceSearchMatcher.matchesSong(
                            catalogSong.title,
                            candidate.name,
                            candidate.uploaderName
                        )
                    }
                    .take(MAX_ALTERNATIVES)

                Observable.fromIterable(candidates)
                    .concatMapMaybe { candidate ->
                        ExtractorHelper.getStreamInfo(
                            context,
                            candidate.serviceId,
                            candidate.url,
                            false
                        )
                            .toMaybe()
                            .onErrorComplete()
                    }
                    .firstOrError()
                    .doOnSuccess { streamInfo ->
                        Log.i(
                            TAG,
                            "Recovered catalog track '${catalogSong.title}' with an alternate " +
                                "YouTube stream: ${streamInfo.getUrl()}"
                        )
                    }
                    .onErrorResumeNext { Single.error<StreamInfo>(originalError) }
            }
            .onErrorResumeNext { Single.error<StreamInfo>(originalError) }
            .subscribeOn(Schedulers.io())
    }

    private fun requiresSignIn(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                message.contains("Please sign in", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    companion object {
        private const val TAG = "MusicCatalogPlayback"
        private const val MAX_ALTERNATIVES = 8
    }
}

private fun MusicSong.toStreamInfoItem(): StreamInfoItem = StreamInfoItem(serviceId, url, title, streamType).apply {
    uploaderName = artist
    uploaderUrl = ""
    duration = parseDurationSeconds(this@toStreamInfoItem.duration)
    thumbnails = listOf(
        Image(this@toStreamInfoItem.thumbnailUrl, 720, 1280, Image.ResolutionLevel.HIGH)
    )
}

private fun parseDurationSeconds(duration: String): Long {
    var total = 0L
    for (part in duration.trim().split(":")) {
        total = total * 60L + (part.toLongOrNull() ?: return 0L)
    }
    return total
}
