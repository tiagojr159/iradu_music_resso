package org.schabi.newpipe.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVoiceSearchMatcherTest {
    @Test
    fun `matches song command routed to iradu music`() {
        assertTrue(
            MusicVoiceSearchMatcher.matchesSong(
                "Ok Google, toque Ocean no Iradu Music",
                "Ocean",
                "Alok, Zeeba e IRO",
                "Singles",
                "Alok Hits"
            )
        )
    }

    @Test
    fun `matches explicit playlist command`() {
        assertTrue(
            MusicVoiceSearchMatcher.matchesPlaylist(
                "tocar a playlist Alok Hits no iradu music",
                "Alok Hits",
                "Brasil dominando a pista"
            )
        )
    }

    @Test
    fun `matches accents and artist`() {
        assertTrue(
            MusicVoiceSearchMatcher.matchesSong(
                "reproduza Ze Felipe no Iradu",
                "Malvada",
                "Zé Felipe"
            )
        )
    }

    @Test
    fun `does not select unrelated song`() {
        assertFalse(
            MusicVoiceSearchMatcher.matchesSong(
                "toque Ocean no Iradu Music",
                "Tempo Perdido",
                "Legião Urbana"
            )
        )
    }
}
