package com.blaze.assistant.agent

import org.junit.Test
import java.io.File

/**
 * Round 5: what the shelves (skills + specialists) cost per request, on the real bundled library and the built-in
 * specialists plus a user's own (5 skills, 2 specialists). Chars; tokens ≈ chars / 4. Results go to build/shelf-bench.txt.
 * Shelf cost 4/4: lines are `name — use when …` with at most [SkillShelf.LINE_WORDS] words after the dash. The delegate
 * schema is measured as org.json writes it here, where "—" is escaped to six chars (\u2014).
 */
class ShelfBenchTest {
    private val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
    private val bundled = root.listFiles()!!.filter { it.isDirectory }.mapNotNull { SkillMatch.fromDir(it, "bundled", true) }
    private val mine = (1..5).map { SkillMatch.Cand("my-skill-$it", "My own workflow number $it for the weekly sales report from the store emails", emptyList(), "1. …", "user") }
    private val specs = Specialists.builtIn + listOf(
        Specialists.Spec("farming-advisor", "Farming advisor", "Advises smallholder farmers on crops, planting windows, fertiliser and pests for their district, with the extension source.", setOf("web"), custom = true),
        Specialists.Spec("tax-helper", "Tax helper", "Explains ZRA tax forms step by step and checks the numbers before filing.", setOf("web"), custom = true))

    @Test fun measure() {
        val skills = SkillShelf.block(bundled + mine)
        val people = SpecialistShelf.pointer(specs)   // step 2: one line; the list moved into delegate's description
        val delegate = Specialists.tool(specs).toString()
        val sb = StringBuilder("Shelf bench (chars; tokens ≈ chars/4)\n")
        sb.append("skills shelf: ${skills.length} (${bundled.size + mine.size} skills)\n")
        sb.append("specialists in the prompt: ${people.length} (${specs.size} specialists, listed in delegate)\n")
        sb.append("delegate tool schema (every turn): ${delegate.length}\n")
        val lines = (skills.lines() + Specialists.tool(specs).getJSONObject("function").getString("description").lines()).filter { it.startsWith("- ") }
        sb.append("longest line: ${lines.maxOf { it.substringAfter(" \u2014 ").split(' ').size }} words after the dash (cap ${SkillShelf.LINE_WORDS})\n")
        val perRequest = skills.length + people.length + delegate.length
        sb.append("per request (system prompt + delegate schema): $perRequest (≈ ${perRequest / 4} tokens)\n")
        File("build").mkdirs(); File("build/shelf-bench.txt").writeText(sb.toString())
        println(sb)
    }
}
