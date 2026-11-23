package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 0.55.11: video titles shown as song title + artist (SongTitle). */
class SongTitleTest {
    private fun c(t: String, ch: String = "") = SongTitle.clean(t, ch).let { it.title to it.artist }

    @Test fun splitsArtistAndTitleAndDropsVideoWords() {
        assertEquals("Bengicela (feat. JAZZWRLD)" to "MaWhoo, GL Ceejay & Thukuthela",
            c("MaWhoo, GL Ceejay & Thukuthela - Bengicela feat. JAZZWRLD (Official Lyric Video)", "MaWhoo"))
        assertEquals("Ngishutheni" to "Goon Flavour, Master KG & Eemoh",
            c("Goon Flavour, Master KG & Eemoh - Ngishutheni (Official Music Video)", "Wanitwa Mos Entertainment"))
        assertEquals("Fast Fast (feat. Roscosteazy, Optimist Music ZA)" to "031Choppa, Al Xapo, Benzoo",
            c("031Choppa, Al Xapo, Benzoo - Fast Fast [Ft. Roscosteazy, Optimist Music ZA]", "031CHOPPA"))
        assertEquals("Amapiano To The World" to "DJ Phaphane", c("DJ Phaphane - Amapiano To The World | Paris Nights", "DJ Phaphane"))
        assertEquals("Mark Zuckerberg" to "Mulest Vankay, Pcee, Scotts Maphuma",
            c("Mulest Vankay, Pcee, Scotts Maphuma - Mark Zuckerberg (Official Audio)", "Mulest Vankay"))
    }

    @Test fun commonNoise() {
        assertEquals("Blinding Lights" to "The Weeknd", c("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals("Blinding Lights" to "The Weeknd", c("The Weeknd - Blinding Lights [Lyrics]", "7clouds"))
        assertEquals("Blinding Lights" to "The Weeknd", c("The Weeknd - Blinding Lights Official Audio", ""))
        assertEquals("Blinding Lights" to "The Weeknd", c("The Weeknd - Blinding Lights (HD)", ""))
        assertEquals("Blinding Lights" to "The Weeknd", c("The Weeknd - Blinding Lights 4K", ""))
        assertEquals("Blinding Lights" to "The Weeknd", c("The Weeknd - Blinding Lights | YouTube", ""))
        assertEquals("Blinding Lights" to "The Weeknd", c("The Weeknd - \"Blinding Lights\" (Official Music Video) #shorts #pop", ""))
        assertEquals("Last Last" to "Burna Boy", c("Last Last", "Burna Boy - Topic"))
        assertEquals("Love Nwantiti" to "CKay", c("Love Nwantiti (Visualizer)", "CKayVEVO"))
        assertEquals("Song" to "Some Artist", c("Song (Prod. by Someone)", "SomeArtist"))
    }

    @Test fun keepsVersionsAndHandles() {
        // "Song - Remastered 2011" is a title, not "Artist - Title".
        assertEquals("Here Comes the Sun - Remastered 2019" to "The Beatles", c("Here Comes the Sun - Remastered 2019", "The Beatles - Topic"))
        assertEquals("Song (Live)" to "Band", c("Song (Live)", "Band"))
        // A handle on the left: the channel is the artist.
        assertEquals("Big Flexa (feat. C'buda M)" to "Costa Titch", c("@costatitchworld - Big Flexa ft. C'buda M", "Costa Titch"))
        assertEquals("Sweet Song" to "Some Artist", c("Sweet Song", "@SomeArtist"))
    }

    @Test fun musicMetadataOnlyStrips() {
        val m = SongTitle.clean("Say You Love Me - Live (Official Audio)", "Shekhinah & Brandon Dhludhlu", fromMusic = true)
        assertEquals("Say You Love Me - Live", m.title)
        assertEquals("Shekhinah & Brandon Dhludhlu", m.artist)
    }

    @Test fun artistNames() {
        assertEquals("Burna Boy", SongTitle.artist("Burna Boy - Topic"))
        assertEquals("Burna Boy", SongTitle.artist("BurnaBoyVEVO"))
        assertEquals("Tems", SongTitle.artist("Tems Official"))
        assertEquals("DJ Phaphane", SongTitle.artist("DJ Phaphane"))
        assertFalse(SongTitle.artist("Somebody").contains("VEVO"))
    }

    @Test fun neverEmpty() {
        assertEquals("(Official Video)" to "", c("(Official Video)", ""))
        assertEquals("Intro" to "X", c("Intro", "X"))
    }

    @Test fun tracksAndArt() {
        val own = Track("k", "/m/a.mp3", "My Demo (Official Video)", "Me", source = "download")
        assertEquals("My Demo (Official Video)", SongTitle.forTrack(own).title)
        val dl = Track("k", "/m/a.m4a", "Tems - Free Mind (Official Video)", "TemsVEVO", source = "download", videoId = "abc")
        assertEquals("Free Mind" to "Tems", SongTitle.forTrack(dl).let { it.title to it.artist })
        assertEquals("https://lh3.googleusercontent.com/x=w544-h544-l90-rj", SongTitle.art("https://lh3.googleusercontent.com/x=w60-h60-l90-rj"))
        assertEquals("https://yt3.ggpht.com/x=s544", SongTitle.art("https://yt3.ggpht.com/x=s88-c-k"))
        assertEquals("https://i.ytimg.com/vi/a/mqdefault.jpg", SongTitle.art("https://i.ytimg.com/vi/a/mqdefault.jpg"))
    }
}
