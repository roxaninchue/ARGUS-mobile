package com.argus.assistant.agent


import com.argus.assistant.BrandName
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfKnowledgeTest {
    @Test fun familyNamesEveryAppAndHowTheyConnect() {
        val f = SelfKnowledge.FAMILY
        listOf("${BrandName.NAME} for Android", "${BrandName.NAME} Desktop", "${BrandName.NAME} CLI", "${BrandName.NAME} account", "${BrandName.NAME} Link", "QR").forEach { assertTrue(it, f.contains(it)) }
    }
    @Test fun toolIsDescribed() {
        val fn = SelfKnowledge.tool().getJSONObject("function")
        assertTrue(fn.getString("name") == "about_blaze")
        assertTrue(fn.getString("description").contains("${BrandName.NAME}"))
    }
}
