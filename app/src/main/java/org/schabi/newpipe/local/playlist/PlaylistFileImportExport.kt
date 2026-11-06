/*
 * SPDX-FileCopyrightText: 2026 Iradu Music contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:JvmName("PlaylistFileImportExport")

package org.schabi.newpipe.local.playlist

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.OffsetDateTime
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper

const val PLAYLIST_FILE_MIME_TYPE = "application/json"
const val PLAYLIST_IMPORT_FILE_MIME_TYPE = "*/*"

private const val FORMAT = "iradu_music_playlists"
private const val VERSION = 1

data class ExportedPlaylist(
    val name: String,
    val streams: List<ExportedStream>
)

data class ExportedStream(
    val serviceId: Int,
    val url: String,
    val title: String,
    val streamType: String,
    val duration: Long,
    val uploader: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val viewCount: Long?,
    val textualUploadDate: String?,
    val uploadDate: String?,
    val isUploadDateApproximation: Boolean?
)

fun toExportedPlaylist(name: String?, streams: List<PlaylistStreamEntry>): ExportedPlaylist {
    return ExportedPlaylist(
        name = name.orEmpty(),
        streams = streams.map { entry ->
            val stream = entry.streamEntity
            ExportedStream(
                serviceId = stream.serviceId,
                url = stream.url,
                title = stream.title,
                streamType = stream.streamType.name,
                duration = stream.duration,
                uploader = stream.uploader,
                uploaderUrl = stream.uploaderUrl,
                thumbnailUrl = stream.thumbnailUrl,
                viewCount = stream.viewCount,
                textualUploadDate = stream.textualUploadDate,
                uploadDate = stream.uploadDate?.toString(),
                isUploadDateApproximation = stream.isUploadDateApproximation
            )
        }
    )
}

fun toStreamEntity(stream: ExportedStream): StreamEntity {
    return StreamEntity(
        serviceId = stream.serviceId,
        url = stream.url,
        title = stream.title,
        streamType = runCatching { StreamType.valueOf(stream.streamType) }
            .getOrDefault(StreamType.VIDEO_STREAM),
        duration = stream.duration,
        uploader = stream.uploader,
        uploaderUrl = stream.uploaderUrl,
        thumbnailUrl = stream.thumbnailUrl,
        viewCount = stream.viewCount,
        textualUploadDate = stream.textualUploadDate,
        uploadDate = stream.uploadDate?.let {
            runCatching { OffsetDateTime.parse(it) }.getOrNull()
        },
        isUploadDateApproximation = stream.isUploadDateApproximation
    )
}

@Throws(IOException::class)
fun exportPlaylistsToFile(playlists: List<ExportedPlaylist>, file: StoredFileHelper) {
    SharpOutputStream(file.openAndTruncateStream()).use { output ->
        val writer = JsonWriter.indent("  ").on(output).`object`()
            .value("format", FORMAT)
            .value("version", VERSION)
            .array("playlists")

        playlists.forEach { playlist ->
            writer.`object`()
                .value("name", playlist.name)
                .array("streams")

            playlist.streams.forEach { stream ->
                writer.`object`()
                    .value("serviceId", stream.serviceId)
                    .value("url", stream.url)
                    .value("title", stream.title)
                    .value("streamType", stream.streamType)
                    .value("duration", stream.duration)
                    .value("uploader", stream.uploader)
                    .value("uploaderUrl", stream.uploaderUrl)
                    .value("thumbnailUrl", stream.thumbnailUrl)
                    .value("viewCount", stream.viewCount)
                    .value("textualUploadDate", stream.textualUploadDate)
                    .value("uploadDate", stream.uploadDate)
                    .value("isUploadDateApproximation", stream.isUploadDateApproximation)
                    .end()
            }

            writer.end().end()
        }

        writer.end().end().done()
    }
}

fun toWhatsAppPlaylistText(playlist: ExportedPlaylist): String = buildString {
    appendLine("#EXTM3U")
    appendLine("#IRADU-PLAYLIST:1")
    appendLine("#PLAYLIST:${playlist.name.replace('\n', ' ').replace('\r', ' ')}")
    playlist.streams.forEach { stream ->
        val title = stream.title.replace('\n', ' ').replace('\r', ' ')
        appendLine("#IRADU-SERVICE:${stream.serviceId}")
        appendLine("#IRADU-TYPE:${stream.streamType}")
        appendLine("#EXTINF:${stream.duration.coerceAtLeast(0)},$title")
        appendLine(stream.url)
    }
}

@Throws(IOException::class)
fun importPlaylistsFromFile(file: StoredFileHelper): List<ExportedPlaylist> {
    SharpInputStream(file.stream).use { input ->
        val bytes = input.readBytes()
        val content = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (!content.trimStart().startsWith("{")) {
            return importWhatsAppPlaylist(content)
        }

        val root = JsonParser.`object`().from(ByteArrayInputStream(bytes))
        if (root.get("format") != FORMAT) {
            throw IOException("Unsupported playlist file format")
        }

        val playlists = root.getArrayOrEmpty("playlists")
        return playlists.mapNotNull { playlistValue ->
            val playlistObject = playlistValue as? JsonObject ?: return@mapNotNull null
            val streams = playlistObject.getArrayOrEmpty("streams")
                .mapNotNull { streamValue ->
                    (streamValue as? JsonObject)?.toExportedStream()
                }

            ExportedPlaylist(
                name = playlistObject.getStringOrEmpty("name"),
                streams = streams
            )
        }.filter { it.streams.isNotEmpty() }
    }
}

private fun importWhatsAppPlaylist(content: String): List<ExportedPlaylist> {
    val lines = content.lineSequence().map(String::trim).toList()
    if ("#IRADU-PLAYLIST:1" !in lines) {
        throw IOException("Unsupported playlist text format")
    }

    var name = "Playlist compartilhada"
    var serviceId: Int? = null
    var streamType = StreamType.VIDEO_STREAM.name
    var title: String? = null
    var duration = -1L
    val streams = mutableListOf<ExportedStream>()

    for (line in lines) {
        when {
            line.startsWith("#PLAYLIST:") -> name = line.removePrefix("#PLAYLIST:")

            line.startsWith("#IRADU-SERVICE:") -> {
                serviceId = line.removePrefix("#IRADU-SERVICE:").toIntOrNull()
            }

            line.startsWith("#IRADU-TYPE:") -> {
                streamType = line.removePrefix("#IRADU-TYPE:")
            }

            line.startsWith("#EXTINF:") -> {
                val metadata = line.removePrefix("#EXTINF:")
                val comma = metadata.indexOf(',')
                if (comma >= 0) {
                    duration = metadata.substring(0, comma).toLongOrNull() ?: -1L
                    title = metadata.substring(comma + 1).takeIf(String::isNotBlank)
                }
            }

            line.isNotBlank() && !line.startsWith("#") -> {
                val currentTitle = title
                val currentServiceId = serviceId
                if (currentTitle != null && currentServiceId != null) {
                    streams.add(
                        ExportedStream(
                            serviceId = currentServiceId,
                            url = line,
                            title = currentTitle,
                            streamType = streamType,
                            duration = duration,
                            uploader = "",
                            uploaderUrl = null,
                            thumbnailUrl = null,
                            viewCount = null,
                            textualUploadDate = null,
                            uploadDate = null,
                            isUploadDateApproximation = null
                        )
                    )
                }
                serviceId = null
                streamType = StreamType.VIDEO_STREAM.name
                title = null
                duration = -1L
            }
        }
    }

    if (streams.isEmpty()) {
        throw IOException("No playable tracks found in the shared playlist")
    }
    return listOf(ExportedPlaylist(name, streams))
}

private fun JsonObject.toExportedStream(): ExportedStream? {
    val url = getStringOrEmpty("url")
    val title = getStringOrEmpty("title")
    if (url.isBlank() || title.isBlank()) {
        return null
    }

    return ExportedStream(
        serviceId = getNumber("serviceId")?.toInt() ?: 0,
        url = url,
        title = title,
        streamType = getStringOrDefault("streamType", StreamType.VIDEO_STREAM.name),
        duration = getNumber("duration")?.toLong() ?: -1L,
        uploader = getStringOrEmpty("uploader"),
        uploaderUrl = getNullableString("uploaderUrl"),
        thumbnailUrl = getNullableString("thumbnailUrl"),
        viewCount = getNumber("viewCount")?.toLong(),
        textualUploadDate = getNullableString("textualUploadDate"),
        uploadDate = getNullableString("uploadDate"),
        isUploadDateApproximation = get("isUploadDateApproximation") as? Boolean
    )
}

private fun JsonObject.getArrayOrEmpty(key: String): JsonArray {
    return get(key) as? JsonArray ?: JsonArray()
}

private fun JsonObject.getStringOrEmpty(key: String): String {
    return getNullableString(key).orEmpty()
}

private fun JsonObject.getStringOrDefault(key: String, defaultValue: String): String {
    return getNullableString(key) ?: defaultValue
}

private fun JsonObject.getNullableString(key: String): String? {
    return get(key) as? String
}

private fun JsonObject.getNumber(key: String): Number? {
    return get(key) as? Number
}
