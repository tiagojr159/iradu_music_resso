package org.schabi.newpipe.music

import java.text.Normalizer

object MusicVoiceSearchMatcher {
    private val appName = "\\b(iradu music|iradu)\\b".toRegex()
    private val leadingCommand =
        "^(hey google|ok google)?\\s*(toque|tocar|reproduza|reproduzir|play|abra|abrir)\\s+"
            .toRegex()
    private val mediaWords =
        "^(?:(a|o|uma|um)\\s+)?(musica|playlist|lista)(?:\\s+de)?\\s+".toRegex()
    private val trailingDestination = "\\s+(no|na|pelo|pela|em)\\s*$".toRegex()

    fun normalizeQuery(value: String): String {
        var normalized = normalize(value).replace(appName, " ").trim()
        normalized = normalized.replace(trailingDestination, "").trim()
        normalized = normalized.replace(leadingCommand, "").trim()
        return normalized.replace(mediaWords, "").trim()
    }

    fun matchesPlaylist(
        query: String,
        title: String,
        subtitle: String = "",
        description: String = ""
    ): Boolean {
        val needle = normalizeQuery(query)
        if (needle.isBlank()) return false
        val normalizedTitle = normalize(title)
        val searchable = normalize("$title $subtitle $description")
        return needle == normalizedTitle || needle.contains(normalizedTitle) ||
            searchable.contains(needle)
    }

    fun matchesSong(
        query: String,
        title: String,
        artist: String = "",
        album: String = "",
        playlist: String = ""
    ): Boolean {
        val needle = normalizeQuery(query)
        if (needle.isBlank()) return false
        val normalizedTitle = normalize(title)
        val searchable = normalize("$title $artist $album $playlist")
        return searchable.contains(needle) || needle.contains(normalizedTitle)
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase()
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()
    }
}
