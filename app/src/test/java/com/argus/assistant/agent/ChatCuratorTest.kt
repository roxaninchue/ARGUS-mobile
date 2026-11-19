package com.argus.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 16 Nov 2026: the whole-chat review's request and parsing. */
class ChatCuratorTest {
    @Test fun requestCarriesTheChatAndWhatIsSaved() {
        val r = ChatCurator.request("[user] deploy my site", listOf("deploy-pages: Deploy to Cloudflare Pages"), emptyList(), listOf("coder: Coder"), "")
        val sys = r.getJSONObject(0).getString("content")
        val user = r.getJSONObject(1).getString("content")
        assertTrue("never write rules that forbid or limit the assistant" in sys)
        assertTrue("never restate or contradict general knowledge" in sys)
        assertTrue("- deploy-pages: Deploy to Cloudflare Pages" in user)
        assertTrue("EXISTING WORKFLOWS:\n(none)" in user)
        assertTrue(user.endsWith("CHAT:\n[user] deploy my site"))
    }

    @Test fun parsesCapsAndDropsSecrets() {
        val raw = """<think>hm</think> Sure:
```json
{"skills":[{"name":"Deploy Pages","description":"Deploy a site to Cloudflare Pages","when":"user says deploy","steps":"1. Build with npm run build 2. wrangler pages deploy dist 3. Open the URL"},
           {"name":"two","steps":"1. a long enough step list here"},{"name":"three","steps":"1. another long enough step list"}],
 "workflows":[{"name":"weekly report","trigger":"every Monday","steps":"1. Gather the numbers 2. Write it up"}],
 "memories":[{"kind":"preference","subject":"Finn","text":"Deploys go to Cloudflare Pages"},
             {"kind":"fact","subject":"Finn","text":"token sk-ant-abcdefghijklmnopqrstuvwxyz0123456789"},
             {"kind":"fact","subject":"","text":"no subject"}],
 "specialists":[{"name":"Release manager","why":"ships builds"}]}
```"""
        val a = ChatCurator.parse(raw)
        assertEquals(ChatCurator.MAX_SKILLS, a.count { it.type == "skill" })
        assertEquals("deploy-pages", a.first { it.type == "skill" }.name)
        assertEquals("weekly-report", a.single { it.type == "workflow" }.name)
        assertEquals(listOf("Deploys go to Cloudflare Pages"), a.filter { it.type == "memory" }.map { it.o.getString("text") })
        assertEquals("Release manager", a.single { it.type == "specialist" }.name)
        assertTrue(ChatCurator.title(a.first()).startsWith("Skill: deploy-pages"))
    }

    @Test fun nothingUsable() {
        assertTrue(ChatCurator.parse("nothing to keep").isEmpty())
        assertTrue(ChatCurator.parse("{\"skills\":[],\"memories\":[]}").isEmpty())
        assertFalse(ChatCurator.parse("{\"memories\":[{\"kind\":\"fact\",\"subject\":\"x\",\"text\":\"ok fine\"}]}").isEmpty())
    }
}
