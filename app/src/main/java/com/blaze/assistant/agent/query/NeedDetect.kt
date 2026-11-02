package com.blaze.assistant.agent.query

import com.blaze.assistant.agent.SearchRoute

/**
 * 0.51.0 BlazeQuery need detection: for every message the user sends, decide (in microseconds, no model, no network)
 * whether outside facts would make the reply more correct.
 *
 *  - [Need.NONE]: small talk, slash commands, weather (get_weather has it), phone actions ("set an alarm", "open WhatsApp"),
 *    pure writing jobs ("write me a poem"). Nothing is fetched and nothing is injected.
 *  - [Need.LOCAL]: about Blaze, its / commands, this conversation or the user's own data (0.50 [SearchRoute.isLocal]).
 *    Never sent to the web: only the chat's own cache (cached documents, BlazeQuery facts from earlier turns) is consulted.
 *  - [Need.CODE]: code / command / API / version / error-message work. The priority trigger: a wrong flag or a stale API is
 *    exactly what fresh docs fix. Official docs, registries and release notes are preferred.
 *  - [Need.EXTERNAL]: a question about the world (facts, how-to, news, prices, releases).
 *
 * This is not a keyword trigger for ANSWERING from the web (0.50 removed that): it only decides whether a compact evidence
 * pack is prepared next to the normal prompt. The model still answers every message itself. Pure (NeedDetectTest).
 */
object NeedDetect {
    enum class Need { NONE, LOCAL, EXTERNAL, CODE }

    data class Decision(val need: Need, val reason: String, val intents: Set<Intent> = emptySet()) {
        val external: Boolean get() = need == Need.EXTERNAL || need == Need.CODE
    }

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** An error message or stack trace in the text. */
    val errorLine = r("""(?:\b[A-Z][A-Za-z]+(?:Exception|Error)\b|Traceback \(most recent call last\)|\berror(?:\[E\d+\])?:|\bE/AndroidRuntime\b|npm ERR!|\bFATAL\b|\bpanic:|undefined reference|cannot find symbol|unresolved reference|command not found|No such file or directory|Permission denied|segmentation fault|exit (?:code|status) [1-9]|\bfailed with\b|BUILD FAILED|Could not resolve|ModuleNotFoundError|SyntaxError)""")
    private val cliTool = r("""\b(?:pip3?|pipx|npm|npx|yarn|pnpm|apk add|apt(?:-get)?|dpkg|brew|cargo|rustup|go (?:get|install|mod)|gradle|gradlew|mvn|maven|adb|fastboot|git|gh|glab|kubectl|docker|podman|curl|wget|ffmpeg|ssh|scp|rsync|systemctl|journalctl|chmod|chown|sed|awk|grep|find|tar|unzip|openssl|keytool|apksigner|zipalign|sdkmanager|avdmanager|termux|proot|python3?|node|deno|bun|java|kotlinc|javac|rustc|gcc|clang|make|cmake)\b""")
    private val flag = Regex("""(?:^|\s)--?[a-zA-Z][\w-]*""")
    private val codeWords = r("""\b(?:api|apis|sdk|library|libraries|package|dependency|dependencies|module|function|method|class|kotlin|java|python|javascript|typescript|rust|golang|c\+\+|swift|dart|flutter|react|compose|jetpack|androidx|gradle|manifest|permission|intent|coroutine|regex|json|yaml|sql|sqlite|http|endpoint|rest|graphql|oauth|webhook|cli|command|flag|option|syntax|compile|build|deprecated|migration|breaking change|changelog|release notes|bash|shell|script|terminal|linux|android \d+|api level|targetsdk|minsdk)\b""")
    private val versionWords = r("""\b(?:latest|newest|current|stable|lts)\s+(?:version|release|build)|\bversion of\b|\bwhat version\b|\breleased?\b|\brelease notes\b|\bchangelog\b|\bv?\d+\.\d+(?:\.\d+)?\b""")
    private val newsWords = r("""\b(?:news|today|tonight|yesterday|this (?:week|month|year)|latest|recent(?:ly)?|right now|currently|just (?:happened|announced)|breaking|election|score|scores|match|fixture|price|prices|cost|exchange rate|rate|stock|shares|bitcoin|crypto|announced|launch(?:ed)?|update[sd]?|20[2-3]\d)\b""")
    private val howTo = r("""^\s*(?:how (?:do|can|to|should|would)|what(?:'s| is) the (?:best|right|correct) way|steps to|guide to|tutorial)\b""")
    private val question = r("""^\s*(?:what|which|who|whom|whose|when|where|why|how|is|are|was|were|does|did|do|can|could|will|would|should|has|have|tell me|explain|compare|list|define)\b|\?\s*$""")
    /** A phone action Blaze performs with its own tools: no outside facts needed. */
    private val phoneAction = r("""^\s*(?:please\s+)?(?:set|start|stop|cancel|turn|switch|open|close|launch|call|ring|dial|text|send|message|reply|play|pause|resume|skip|mute|unmute|take|snap|record|remind|wake|show me my|read my|check my|lock|unlock|share|install|uninstall|delete|remove|save|add|create a (?:reminder|alarm|note|routine|event))\b""")
    /** Writing / creative jobs from the model's own skill ("write me a poem", "draft an email to my boss"). */
    private val writingJob = r("""^\s*(?:please\s+)?(?:write|draft|compose|rewrite|rephrase|translate|summari[sz]e|fix (?:the )?(?:grammar|spelling)|make (?:it|this) (?:shorter|longer|better)|brainstorm|tell me a (?:joke|story)|give me (?:a )?(?:poem|joke|story|name|names|ideas?))\b""")
    private val codeFence = Regex("```")

    fun intents(text: String): Set<Intent> {
        val out = LinkedHashSet<Intent>()
        if (errorLine.containsMatchIn(text)) out += Intent.ERROR
        if (versionWords.containsMatchIn(text)) out += Intent.VERSION
        if (howTo.containsMatchIn(text)) out += Intent.HOWTO
        if (codeWords.containsMatchIn(text) || cliTool.containsMatchIn(text)) out += Intent.DOCS
        if (newsWords.containsMatchIn(text) && Intent.VERSION !in out && Intent.ERROR !in out) out += Intent.NEWS
        if (out.isEmpty()) out += Intent.FACT
        return out
    }

    /** Code / command work: an error, a code fence, a CLI tool with a flag, or a code word in a question / task about it. */
    fun isCode(text: String): Boolean {
        if (errorLine.containsMatchIn(text) || codeFence.containsMatchIn(text)) return true
        val cli = cliTool.containsMatchIn(text)
        if (cli && (flag.containsMatchIn(text) || question.containsMatchIn(text) || text.contains('$'))) return true
        val words = codeWords.findAll(text).count()
        return (words >= 1 && (question.containsMatchIn(text) || howTo.containsMatchIn(text) || versionWords.containsMatchIn(text))) || words >= 2 || (cli && words >= 1)
    }

    /**
     * The decision for [text] (the user's newest message). [recent] = earlier turns (newest last), used only to tell a
     * short follow-up ("and the latest version?") from small talk.
     */
    fun decide(text: String, recent: List<String> = emptyList()): Decision {
        val t = text.trim()
        if (t.isEmpty()) return Decision(Need.NONE, "empty")
        if (t.startsWith("/")) return Decision(Need.NONE, "slash command")
        if (t.startsWith("[Scheduled routine")) return Decision(Need.NONE, "routine")
        when (SearchRoute.route(t)) {
            SearchRoute.Route.CHAT -> return Decision(Need.NONE, "small talk")
            SearchRoute.Route.COMMANDS -> return Decision(Need.LOCAL, "Blaze's own commands")
            SearchRoute.Route.LOCAL -> return Decision(Need.LOCAL, "about Blaze, this chat or the user's data")
            SearchRoute.Route.WEATHER -> return Decision(Need.NONE, "weather tool")
            SearchRoute.Route.SEARCH -> return Decision(if (isCode(t)) Need.CODE else Need.EXTERNAL, "explicit search", intents(t))
            SearchRoute.Route.MODEL -> { }
        }
        val code = isCode(t)
        if (code) return Decision(Need.CODE, "code / command work", intents(t))
        if (phoneAction.containsMatchIn(t) && !question.containsMatchIn(t)) return Decision(Need.NONE, "phone action")
        if (writingJob.containsMatchIn(t) && !newsWords.containsMatchIn(t)) return Decision(Need.NONE, "writing job")
        val words = t.split(Regex("\\s+")).count { it.length >= 3 }
        if (question.containsMatchIn(t) || howTo.containsMatchIn(t) || newsWords.containsMatchIn(t)) {
            // "and the latest one?" leans on the previous turn: it counts when that turn was a world / code question
            if (words < 2 && recent.lastOrNull()?.let { decide(it).external } != true) return Decision(Need.NONE, "too short")
            return Decision(Need.EXTERNAL, "world question", intents(t))
        }
        return Decision(Need.NONE, "no outside facts needed")
    }
}
