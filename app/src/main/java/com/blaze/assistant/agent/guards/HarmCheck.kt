package com.blaze.assistant.agent.guards

/**
 * P2-SF-07 (AT-34 guard layer, policy part): the A team's harm precheck / postcheck as pure Kotlin. Ported from A team
 * `app/www/guard.js` section 4 (owner grant, MIT); its cases in A team `evals/test/guard.test.mjs` are JUnit cases in
 * HarmCheckTest. Category-level patterns only: no operational detail lives here. Each needs an intent + a harmful object.
 *
 *  - [precheck] (the user's message, before the model): clearly illegal or seriously harmful asks (mass-harm weapons,
 *    malware for others' systems, breaking into accounts that aren't the user's, fraud, making hard drugs, violence,
 *    stalking, sexual content involving minors, evading police, self-harm methods) get a brief refusal plus a safe
 *    alternative ([refusal]); look-alikes (own systems, CTF, education, fiction, dosage, lock-picking hobby) go through.
 *  - [postcheck] (the model's reply to a refused topic): compliance is replaced with the refusal; a real refusal stays.
 * This is a content rule, not an approval rule: it does not touch [com.blaze.assistant.agent.CommandPolicy] or the
 * P2-SF-01 policy engine (which decide whether an ACTION needs a card). Not wired into Agent.kt; see the P2-SF-07 report.
 */
object HarmCheck {
    private val ic = RegexOption.IGNORE_CASE

    data class Harm(val cat: String, val re: Regex)
    /** action = "refuse" | "allow" (A team wire words); [reply] set when refusing; [note] says why a look-alike passed. */
    data class Verdict(val action: String, val category: String? = null, val reply: String? = null, val note: String? = null)

    val HARM: List<Harm> = listOf(
        Harm("weapons", Regex("""\b(make|making|build|building|synthesi[sz]e|synthesi[sz]ing|produce|producing|weaponi[sz]e|culture|culturing|enhance|enhancing|create|creating|assemble|assembling|instructions (for|to)|recipe|step[- ]by[- ]step)\b[^.?!]{0,60}\b(nerve agents?|sarin|vx|chemical weapons?|bio-?weapons?|biological weapons?|anthrax|smallpox|dirty bomb|nuclear (bomb|weapon)s?|pipe bombs?|explosive devices?|ied|bombs?)(?![-\w])""", ic)),
        Harm("weapons", Regex("""\b(enhanc\w*|increas\w*|boost\w*|make)\b[^.?!]{0,40}\b(pathogens?|virus(es)?|bacteri\w*)\b[^.?!]{0,60}\b(spread\w*|transmissib\w*|lethal\w*|deadl\w*|virulen\w*)""", ic)),
        Harm("weapons", Regex("""\b(untraceable|ghost)\s+(gun|firearm|rifle|pistol)s?\b|\b(convert|make)\b[^.]{0,30}\b(full[- ]?auto|machine gun)\b""", ic)),
        Harm("malware", Regex("""\b(make|making|build|building|synthesi[sz]e|synthesi[sz]ing|produce|producing|weaponi[sz]e|culture|culturing|enhance|enhancing|create|creating|write|code|deploy|spread|need|want|get|instructions|recipe|guide|step[- ]by[- ]step|walk me through|tell me how|explain how|show me how|how (do|can|would|should) i|how to|help me)\b[^.?!]{0,50}\b(ransomware|botnet|worm that spreads|self-spreading|credential[- ]?stealer|info[- ]?stealer|password[- ]stealer|rootkit)\b""", ic)),
        Harm("malware", Regex("""\b(keylogger|spyware|stalkerware|rat\b|remote access trojan)\b[^.?!]{0,60}\b(spy|without|secretly|girlfriend|boyfriend|wife|husband|partner|someone|their|her|his)\b""", ic)),
        Harm("intrusion", Regex("""\b(hack\w*|break\w* into|get\w* into|access\w*|take over|brute[- ]?forc\w*|crack\w*)\b[^.?!]{0,40}\b(my (ex|girlfriend|boyfriend|wife|husband|partner|boss|neighbou?r|coworker|school|university|employer|teacher)'?s?|someone(?: else)?'?s|another person'?s|(a|the|their) (bank|hospital|government|school|company|competitor)'?s?|that isn'?t mine|not mine)\b[^.?!]{0,60}\b(accounts?|email|phones?|instagram|facebook|whatsapp|network|servers?|systems?|website|database|wifi|wi-fi|password|grading)\b""", ic)),
        Harm("intrusion", Regex("""\b(hospital|bank|school)'?s? (patient |customer |student )?(database|system|records?)\b[^.?!]{0,30}\b(isn'?t mine|not mine|without (permission|authori[sz]ation))""", ic)),
        Harm("intrusion", Regex("""\bddos\b\s+(a|an|the|their|this|that|my (competitor|rival|ex|school)|someone|competitor)""", ic)),
        Harm("fraud", Regex("""\b(phishing (page|email|kit|site)s? (to|that) (steal|harvest)\w*|clone (a )?credit cards?|carding|launder\w* (money|cash|crypto|funds)|steal\w* (someone'?s )?identit(y|ies)|scam\w* (elderly|old|vulnerable) people|counterfeit (money|bills|currency|notes|cash|kwacha|dollars|euros))\b""", ic)),
        Harm("fraud", Regex("""\b(make|create|forge|print|produce|get)\b[^.?!]{0,20}\bfake (ids?|passports?|driver'?s? licen[cs]es?|identity documents?|invoices? to)\b""", ic)),
        Harm("fraud", Regex("""\b(print|make|produce)\w*\b[^.?!]{0,15}\bcounterfeit\b""", ic)),
        Harm("drugs", Regex("""\b(synthesi[sz]e|cook|make|manufactur\w*|produc\w*)\b[^.?!]{0,40}\b(meth(amphetamine)?|fentanyl|heroin|crack cocaine|cocaine|mdma|lsd)\b""", ic)),
        Harm("violence", Regex("""\b(how (do i|to|can i|would i)|help me|plan\w*)\b[^.?!]{0,40}\b(kill|murder|poison|hurt|attack|kidnap)\b[^.?!]{0,40}\b(someone|a person|people|my \w+( \w+)?|him|her|them|a crowd)\b""", ic)),
        Harm("violence", Regex("""\bpoison\w*\b[^.?!]{0,40}\b(give|slip|put)\b[^.?!]{0,30}\b(someone|him|her|them|my \w+)\b|\b(poison|kill)\w*\b[^.?!]{0,60}\b(nobody (notices|knows|finds out)|without (anyone|them) (noticing|knowing)|not get caught|undetect\w*)""", ic)),
        Harm("stalking", Regex("""\b(track|spy on|monitor|follow|find the (home )?address of)\b[^.?!]{0,40}\b(my (ex|wife|husband|girlfriend|boyfriend|partner)|someone|a person|her|him)\b[^.?!]{0,40}\b(without (them|her|him) knowing|secretly|without consent|hidden)\b""", ic)),
        Harm("csam", Regex("""\b(sexual|nude|explicit|porn)\w*\b[^.?!]{0,40}\b(child|children|minor|kid|underage|teen(?:ager)?s? under)\b|\b(child|minor|underage)\b[^.?!]{0,40}\b(sexual|nude|explicit|porn)""", ic)),
        Harm("evasion", Regex("""\b(evade|avoid|escape)\b[^.?!]{0,30}\b(police|law enforcement|a warrant|arrest)\b|\b(dispose of|hide|get rid of)\b[^.?!]{0,20}\b(a body|the body|evidence of (a|the) (crime|murder))\b""", ic)),
        Harm("selfharm", Regex("""\b(best|easiest|painless|quickest)\s+(way|method)s?\s+to\s+(kill myself|end my life|commit suicide)\b""", ic))
    )

    /** Look-alikes that must get help. */
    private val LEGIT = Regex("""\b(my own|i own|our own|our (company|org)'?s?|authori[sz]ed|with permission|pentest(ing)? (my|our)|ctf|capture the flag|home ?lab|my (lab|router|server|homelab|vm)|for (a|my) (class|course|exam|thesis)|history of|how does .* work|why (is|are) .* (dangerous|toxic|illegal)|safety|protect|defend|detect|prevent|recogni[sz]e|signs of|symptoms|overdose (signs|symptoms)|dosage|side effects|first aid|lock ?pick(ing)? (as a )?hobby|locksport|my (own )?lock|fiction|novel|story)\b""", ic)
    private val OPERATIONAL = Regex("""\b(step \d|\d\.\s+\w+|ingredients|you will need|materials:|precursor|reagent|yield|grams? of|ml of|payload|exploit code|shellcode|import (socket|pynput|ctypes)|encrypt(s|ing)? (all )?(the )?files)\b""", ic)
    private val REFUSE_RE = Regex("""\b(i can'?t|i cannot|i won'?t|i'?m not able to|i am not able to|i will not|unable to help|can'?t help with|not something i can|i must decline|i'?m not going to)\b""", ic)
    private val STRONG_INTENT = Regex("""\b(steal|without (them|her|him) knowing|secretly|untraceable)\b""", ic)

    const val CRISIS = "I can't help with that, but I don't want you to be alone with it. If you're in danger right now, call your local emergency number. In Zambia you can reach Lifeline/Childline on 933 or 116; elsewhere, findahelpline.com lists free, confidential lines. If you'd like, tell me what's going on."
    private val ALT = mapOf(
        "weapons" to "I can explain the history, the treaties that ban them, or how detection and protection work.",
        "malware" to "I can help you harden your own systems, set up detection, or explain how this class of attack is defended against.",
        "intrusion" to "If it is your own account or system, I can help you recover it or test it with permission; if someone else's, the owner or the platform's support is the route.",
        "fraud" to "I can help you spot and report scams or protect your own accounts.",
        "drugs" to "I can share harm-reduction and addiction-support resources, or explain the pharmacology at a textbook level.",
        "violence" to "If someone is in danger, contact local emergency services. If you're dealing with conflict, I can help think it through.",
        "stalking" to "If you are worried about someone's safety, I can point you to the right services; I can also help with consent-based location sharing.",
        "csam" to "If you know of a child at risk, report it to local police or NCMEC/IWF.",
        "evasion" to "I can explain your legal rights or how to find a lawyer.",
    )

    fun refusal(cat: String): String = if (cat == "selfharm") CRISIS else "I can't help with that. " + (ALT[cat] ?: "I can help with a safe, legal version of what you need.")

    /** Before sending to the model. Only clear cases refuse. */
    fun precheck(userText: String?): Verdict {
        val t = userText ?: ""
        val hit = HARM.firstOrNull { it.re.containsMatchIn(t) } ?: return Verdict("allow")
        if (hit.cat != "csam" && hit.cat != "weapons" && LEGIT.containsMatchIn(t) && !STRONG_INTENT.containsMatchIn(t)) return Verdict("allow", hit.cat, note = "look-alike: answer helpfully at a safe level")
        return Verdict("refuse", hit.cat, refusal(hit.cat))
    }

    /** After the model replied to [userText] (and before any tool runs). Returns action "replace" with the refusal for compliance. */
    fun postcheck(userText: String?, reply: String?): Verdict {
        val pre = precheck(userText); val r = reply ?: ""
        if (pre.action != "refuse") return Verdict("allow")
        if (looksLikeRefusal(r) && !OPERATIONAL.containsMatchIn(r) && !r.contains("```run")) return Verdict("allow", pre.category, note = "model refused")
        return Verdict("replace", pre.category, pre.reply)
    }

    /** Models often reply with typographic apostrophes (I’m, can’t); they are folded to ' before matching. */
    fun looksLikeRefusal(text: String?): Boolean = REFUSE_RE.containsMatchIn((text ?: "").take(400).replace(Regex("[\u2018\u2019\u02BC\uFF07]"), "'"))
}
