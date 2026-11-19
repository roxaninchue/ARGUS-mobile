package com.argus.assistant.connect

import com.argus.assistant.connect.ArgusAppsModel.Auth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Settings › Apps & connectors: the gateway's app list (argus-connect GET /v1/me/apps) parsed, searched and grouped. */
class ArgusAppsModelTest {

    private val sample = """
    {"apps":[
     {"id":"gmail","name":"Gmail","category":"Email","description":"Read, search, send and organise Gmail.","setup":"OAuth","free":"free",
      "auth":{"oauth":{"provider":"google","scopes":["x"],"ready":true},"token":null,"fields":[],"none":false},"test":"get_profile",
      "tools":[{"name":"a"},{"name":"b"}],"enabled":true,"disabled_tools":[],"connected":true,
      "connection":{"mode":"oauth","meta":{"via":"oauth"},"updated_at":1}},
     {"id":"cloudflare","name":"Cloudflare","category":"Developer","description":"Workers and DNS.",
      "auth":{"oauth":null,"token":{"label":"API token","optional":false},"fields":[{"key":"account_id","label":"Account ID","secret":false,"optional":true}],"none":false},
      "test":null,"tools":[{"name":"a"}],"enabled":false,"connected":false,"connection":null},
     {"id":"notion","name":"Notion","category":"Notes & docs","description":"Pages.",
      "auth":{"oauth":{"provider":"notion","ready":false},"token":{"label":"Internal integration secret"},"fields":[],"none":false},
      "tools":[],"enabled":false,"connected":false},
     {"id":"spotify","name":"Spotify","category":"Media","description":"Play music.",
      "auth":{"oauth":{"provider":"spotify","ready":false},"token":null,"fields":[],"none":false},"tools":[],"enabled":false,"connected":false},
     {"id":"open_meteo","name":"Open-Meteo Weather","category":"Weather","description":"Forecasts.",
      "auth":{"oauth":null,"token":null,"fields":[],"none":true},"tools":[{},{},{}],"enabled":true,"connected":false},
     {"id":"Bad Id","name":"x"},
     {"id":"newapp","name":"New App","category":"Developer","icon":"https://example.com/i.png","auth":{}}
    ]}
    """.trimIndent()

    @Test fun parsesEveryValidApp() {
        val apps = ArgusAppsModel.parse(sample)
        assertEquals(listOf("gmail", "cloudflare", "notion", "spotify", "open_meteo", "newapp"), apps.map { it.id })
        val g = apps[0]
        assertTrue(g.connected); assertEquals("oauth", g.via); assertEquals(2, g.tools); assertTrue(g.hasTest)
        assertEquals(Auth.OAUTH, g.auth); assertTrue(g.ready)
        val cf = apps[1]
        assertEquals(Auth.FIELDS, cf.auth); assertEquals("API token", cf.tokenLabel); assertEquals("account_id", cf.fields.single().key)
        assertTrue(cf.fields.single().optional); assertFalse(cf.hasTest)
        assertEquals("https://example.com/i.png", apps.last().iconUrl)
        assertEquals("Other", ArgusAppsModel.parse("{\"apps\":[{\"id\":\"x\"}]}").single().category)
    }

    @Test fun oauthNotSetUpFallsBackToPastingAKey() {
        val apps = ArgusAppsModel.parse(sample).associateBy { it.id }
        assertEquals(Auth.TOKEN, apps.getValue("notion").auth)
        assertEquals("Connect", ArgusAppsModel.pill(apps.getValue("notion")))
        assertEquals("Soon", ArgusAppsModel.pill(apps.getValue("spotify")))
        assertEquals("Connected", ArgusAppsModel.pill(apps.getValue("gmail")))
        assertEquals("On", ArgusAppsModel.pill(apps.getValue("open_meteo")))
        assertTrue(apps.getValue("open_meteo").ready)
    }

    @Test fun brokenOrEmptyAnswersGiveNoApps() {
        assertTrue(ArgusAppsModel.parse("").isEmpty())
        assertTrue(ArgusAppsModel.parse("not json").isEmpty())
        assertTrue(ArgusAppsModel.parse("{\"error\":{\"code\":\"unauthorized\"}}").isEmpty())
    }

    @Test fun searchAndGrouping() {
        val apps = ArgusAppsModel.parse(sample)
        val g = ArgusAppsModel.grouped(apps)
        assertEquals("Email", g.first().first)   // the group with something connected leads
        assertEquals(listOf("Developer", "Media", "Notes & docs", "Weather"), g.drop(1).map { it.first })
        assertEquals(listOf("Cloudflare", "New App"), g.first { it.first == "Developer" }.second.map { it.name })
        assertEquals(listOf("open_meteo"), ArgusAppsModel.grouped(apps, "forecast").flatMap { it.second }.map { it.id })
        assertEquals(listOf("cloudflare", "newapp"), ArgusAppsModel.grouped(apps, " developer ").flatMap { it.second }.map { it.id })
        assertTrue(ArgusAppsModel.grouped(apps, "zzz").isEmpty())
    }

    @Test fun subLine() {
        val apps = ArgusAppsModel.parse(sample).associateBy { it.id }
        assertEquals("Read, search, send and organise Gmail.\n2 tools \u00b7 signed in", ArgusAppsModel.sub(apps.getValue("gmail")))
        assertEquals("Forecasts.\n3 tools \u00b7 no account needed", ArgusAppsModel.sub(apps.getValue("open_meteo")))
        assertEquals("Workers and DNS.\n1 tool", ArgusAppsModel.sub(apps.getValue("cloudflare")))
    }

    @Test fun iconsAndLetters() {
        assertEquals("connect-icons/gmail.png", ArgusAppsModel.iconAsset("gmail"))
        assertEquals("", ArgusAppsModel.iconAsset("web"))
        assertEquals("O", ArgusAppsModel.initial("Open-Meteo"))
        assertEquals("?", ArgusAppsModel.initial("  "))
    }

    /** Every bundled icon is listed in BUNDLED and every BUNDLED id has its file. */
    @Test fun bundledIconsMatchTheAssets() {
        val dir = listOf(File("src/main/assets/connect-icons"), File("app/src/main/assets/connect-icons")).first { it.isDirectory }
        val files = dir.listFiles()!!.filter { it.extension == "png" }.map { it.nameWithoutExtension }.toSet()
        assertEquals(ArgusAppsModel.BUNDLED, files)
        assertTrue(files.size >= 60)
    }

    @Test fun previewListIsValid() {
        assertTrue(ArgusAppsModel.preview.size >= 3)
        assertTrue(ArgusAppsModel.preview.all { it.id in ArgusAppsModel.BUNDLED })
    }
}
