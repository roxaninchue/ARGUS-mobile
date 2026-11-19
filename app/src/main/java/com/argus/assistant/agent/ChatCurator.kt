package com.blaze.assistant.agent

import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner 16 Nov 2026: the whole-chat review. When a chat has gone quiet, one call on the cheapest model
 * ([MindReview.cheap]) reads the chat's [ChatDigest] next to what is already saved (skills, workflows, specialists,
 * memory) and answers with what to add or merge: skills, workflows, memories, and specialist suggestions.
 *
 * It complements the model, never constrains it: it only records what the user taught, preferred or decided, and
 * procedures that actually worked in this chat. It writes no rules that limit what the assistant may do and nothing
 * that contradicts general knowledge. Saved items go through the same stores as the turn review (Learning skills,
 * SkillPacks workflows, Mind), and "Ask before saving" ([Settings.learnApproval]) holds them as Learning.Pending.
 * A specialist is only ever suggested: the user taps it and the main model creates it properly (create_specialist).
 * Each change carries its undo. Request building and parsing are pure (ChatCuratorTest).
 */
object ChatCurator {
    const val MAX_SKILLS = 2
    const val MAX_WORKFLOWS = 1
    const val MAX_SPECIALISTS = 1
    const val MAX_MEMORIES = 8

    const val PROMPT = "You look after what ${BrandName.NAME}, the user's phone assistant, keeps between chats. Read one whole chat and decide what is worth keeping. " +
        "Most chats teach little: then return empty lists.\n" +
        "Keep only what this chat shows:\n" +
        "- skills: a method that worked here (or the user taught) and will be asked again. Numbered steps with the checks that mattered and the pitfalls hit (failed tools, errors) and how they were solved.\n" +
        "- workflows: a recurring multi-step task with a clear trigger (\"every Monday…\", \"when I say deploy…\").\n" +
        "- memories: durable facts and preferences about the user, their people, projects and decisions, each with the conditions they attached.\n" +
        "- specialists: only if the chat shows a whole field of repeated work that no existing specialist covers.\n" +
        "Rules: add to what the assistant already knows, never restate or contradict general knowledge, and never write rules that forbid or limit the assistant. " +
        "If something matches an existing item, use its exact name so it is merged, and give the full new version. Keep the user's certainty; mark inferences \"(inferred)\". " +
        "Never secrets, passwords, keys, codes, card or ID numbers, or one-off values.\n" +
        "Answer ONLY a JSON object:\n" +
        "{\"skills\":[{\"name\":\"kebab-name\",\"description\":\"one line\",\"when\":\"when to use it\",\"steps\":\"1. … 2. …\"}],\n" +
        " \"workflows\":[{\"name\":\"kebab-name\",\"trigger\":\"when it runs\",\"steps\":\"1. … 2. …\",\"checks\":\"how to tell it worked\"}],\n" +
        " \"memories\":[{\"kind\":\"profile|preference|person|project|decision|fact\",\"subject\":\"who or what\",\"text\":\"one line\",\"related\":\"\"}],\n" +
        " \"specialists\":[{\"name\":\"Short name\",\"why\":\"what it would do, one line\"}]}"

    data class Action(val type: String, val name: String, val o: JSONObject)

    /** One saved change, with what undoes it ([undo] null = nothing to undo, e.g. a suggestion). */
    data class Change(val type: String, val title: String, val undo: JSONObject?)

    fun request(digest: String, skills: List<String>, workflows: List<String>, specialists: List<String>, memory: String): JSONArray {
        fun list(l: List<String>) = l.take(60).joinToString("\n") { "- $it" }.ifBlank { "(none)" }
        val user = "EXISTING SKILLS:\n${list(skills)}\n\nEXISTING WORKFLOWS:\n${list(workflows)}\n\nEXISTING SPECIALISTS:\n${list(specialists)}\n\n" +
            "EXISTING MEMORY:\n${memory.ifBlank { "(nothing yet)" }}\n\nCHAT:\n$digest"
        return JSONArray().put(JSONObject().put("role", "system").put("content", PROMPT)).put(JSONObject().put("role", "user").put("content", user))
    }

    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    /** The model's answer -> actions, capped per type; anything unusable or secret-looking is dropped. */
    fun parse(raw: String): List<Action> {
        val s = raw.substringAfterLast("</think>")
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return emptyList()
        val o = try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { return emptyList() }
        fun arr(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) } } ?: emptyList()
        fun clean(x: JSONObject) = (0 until (x.names()?.length() ?: 0)).none { i -> Learn.secret(x.optString(x.names()!!.optString(i))) }
        val out = ArrayList<Action>()
        arr("skills").filter { slug(it.optString("name")).isNotEmpty() && it.optString("steps").length >= 20 && clean(it) }
            .take(MAX_SKILLS).forEach { out += Action("skill", slug(it.optString("name")), it) }
        arr("workflows").filter { slug(it.optString("name")).isNotEmpty() && it.optString("trigger").isNotBlank() && it.optString("steps").length >= 20 && clean(it) }
            .take(MAX_WORKFLOWS).forEach { out += Action("workflow", slug(it.optString("name")), it) }
        arr("memories").filter { it.optString("text").trim().length >= 3 && it.optString("subject").isNotBlank() && clean(it) }
            .take(MAX_MEMORIES).forEach { out += Action("memory", it.optString("subject").trim().take(60), it) }
        arr("specialists").filter { it.optString("name").trim().length >= 3 && clean(it) }
            .take(MAX_SPECIALISTS).forEach { out += Action("specialist", it.optString("name").trim().take(40), it) }
        return out
    }

    /** The Inbox / approval line for an action. */
    fun title(a: Action): String = when (a.type) {
        "skill" -> "Skill: ${a.name} \u2014 ${a.o.optString("description").take(80)}"
        "workflow" -> "Workflow: ${a.name} \u2014 ${a.o.optString("trigger").take(80)}"
        "memory" -> "Remember: ${a.o.optString("text").trim().take(120)}"
        else -> "Specialist idea: ${a.name} \u2014 ${a.o.optString("why").take(80)}"
    }

    /** Saves one action; returns the change with its undo, or null when nothing was saved. */
    fun apply(a: Action): Change? = try {
        when (a.type) {
            "skill" -> {
                val prev = Learning.skills.value.firstOrNull { it.name == a.name }?.toJson()
                Learning.upsertSkill(a.name, a.o.optString("description"), a.o.optString("when"), a.o.optString("steps"), null)
                Change("skill", title(a), JSONObject().put("type", "skill").put("name", a.name).put("prev", prev ?: JSONObject.NULL))
            }
            "workflow" -> {
                val prevMd = SkillPacks.all().firstOrNull { it.dir.name == a.name }?.let { java.io.File(it.dir, "SKILL.md").readText() }
                val r = SkillPacks.saveWorkflow(a.name, a.o.optString("trigger"), a.o.optString("steps"), a.o.optString("checks"), emptyList(), false)
                if (!r.startsWith("Workflow")) null
                else Change("workflow", title(a), JSONObject().put("type", "workflow").put("name", a.name).put("prev", prevMd ?: JSONObject.NULL))
            }
            "memory" -> {
                val before = Mind.all().associateBy { it.id }
                val (r, id) = Mind.save(Mind.normKind(a.o.optString("kind")), a.name, a.o.optString("text"), a.o.optString("related"))
                when (r) {
                    "saved" -> Change("memory", title(a), JSONObject().put("type", "memory").put("id", id))
                    "updated" -> Change("memory", title(a), JSONObject().put("type", "memory").put("id", id).put("prev", before[id]?.let { m ->
                        JSONObject().put("kind", m.kind).put("subject", m.subject).put("text", m.text).put("related", m.related) } ?: JSONObject.NULL))
                    else -> null
                }
            }
            "specialist" -> Change("specialist", title(a), null)
            else -> null
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }

    /** Undoes one change made by [apply]. */
    fun undo(u: JSONObject) {
        try {
            when (u.optString("type")) {
                "skill" -> { val p = u.optJSONObject("prev")
                    if (p == null) Learning.deleteSkill(u.optString("name"))
                    else Learning.Skill.from(p).let { Learning.upsertSkill(it.name, it.description, it.whenToUse, it.steps, it.replay) } }
                "workflow" -> { val name = u.optString("name"); val prev = u.optString("prev").takeIf { !u.isNull("prev") && it.isNotBlank() }
                    val pack = SkillPacks.all().firstOrNull { it.dir.name == name }
                    if (prev == null) SkillPacks.delete(name) else pack?.let { java.io.File(it.dir, "SKILL.md").writeText(prev); SkillMatch.invalidate() } }
                "memory" -> { val id = u.optLong("id"); if (id > 0) Mind.delete(id)
                    u.optJSONObject("prev")?.let { p -> Mind.save(p.optString("kind"), p.optString("subject"), p.optString("text"), p.optString("related")) } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
    }

    /**
     * Reviews one chat. [call] is the model (tests pass a fake). With "Ask before saving" on, the saves wait in
     * Learning's pending list and come back as changes with no undo; otherwise they are saved now.
     */
    suspend fun review(msgs: List<ChatMessage>, call: suspend (JSONArray) -> String? = { MindReview.cheap(it) }): List<Change> {
        if (!ChatDigest.worthReview(msgs)) return emptyList()
        val req = request(ChatDigest.build(msgs),
            Learning.skills.value.map { "${it.name}: ${it.description}" },
            try { SkillPacks.all().filter { it.description.startsWith("Workflow") }.map { "${it.dir.name}: ${it.description}" } } catch (_: Throwable) { emptyList() },
            Specialists.all().map { "${it.id}: ${it.name}" },
            try { Mind.core(Mind.all()) } catch (_: Throwable) { "" })
        val raw = call(req) ?: return emptyList()
        val actions = parse(raw)
        if (actions.isEmpty()) return emptyList()
        if (Settings.learnApproval) {
            Learning.queue(actions.filter { it.type != "specialist" }.map { Learning.Pending(0, "curator", title(it), JSONObject(it.o.toString()).put("_type", it.type).put("_name", it.name)) })
            return actions.map { Change(it.type, title(it), null) }
        }
        return actions.mapNotNull { apply(it) }
    }

    /** Saves a held curator change (Learning.approve). */
    fun applyHeld(o: JSONObject): Change? = apply(Action(o.optString("_type"), o.optString("_name"), o))
}
