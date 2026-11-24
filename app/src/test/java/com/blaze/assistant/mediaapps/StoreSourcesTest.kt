package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.Licence
import com.blaze.assistant.mediaapps.store.StoreSources
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.1 Store sources and licence labels. 0.55.11: music is YouTube only. 0.55.13: video too (Internet Archive, Blender removed). */
class StoreSourcesTest {
    @Test fun licenceLabels() {
        assertEquals("CC BY 3.0", Licence.label("http://creativecommons.org/licenses/by/3.0/"))
        assertEquals("CC BY-NC-SA 3.0", Licence.label("http://creativecommons.org/licenses/by-nc-sa/3.0/us/"))
        assertEquals("Public domain", Licence.label("http://creativecommons.org/licenses/publicdomain/"))
        assertEquals("Public domain", Licence.label("http://creativecommons.org/publicdomain/mark/1.0/"))
        assertEquals("CC0 (public domain)", Licence.label("https://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals("", Licence.label(""))
        assertTrue(Licence.isFree("https://creativecommons.org/licenses/by/4.0/"))
        assertFalse(Licence.isFree("https://example.com/all-rights-reserved"))
        assertFalse(Licence.isFree(""))
    }

    @Test fun youtubeIsAPluginFoundByName() {
        assertNull(StoreSources.plugin("com.example.NotThere"))
        // v0.33.0: YouTube is built in and first in both stores
        assertEquals(listOf("youtube"), StoreSources.forKind("audio").map { it.id })   // 0.55.11: music is YouTube only
        assertNull(StoreSources.get("jamendo")); assertNull(StoreSources.get("archive-audio"))
        assertEquals(listOf("youtube"), StoreSources.forKind("video").map { it.id })
        assertNull(StoreSources.get("archive-films")); assertNull(StoreSources.get("blender"))
    }
}
