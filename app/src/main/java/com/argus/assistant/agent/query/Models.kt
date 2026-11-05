package com.argus.assistant.agent.query

/** 0.51.0 BlazeQuery: the shapes shared by the engine, the judge, the pack and research mode. */

/** What a sub-query is after; drives freshness rules, source preference and the registry lookups. */
enum class Intent(val id: String) {
    DOCS("docs"), VERSION("version"), ERROR("error"), NEWS("news"), HOWTO("how-to"), FACT("fact");
    companion object { fun of(s: String?): Intent = values().firstOrNull { it.id.equals(s?.trim(), true) || it.name.equals(s?.trim(), true) } ?: FACT }
}

data class SubQuery(val text: String, val intent: Intent)

/** Where a piece of evidence came from. */
enum class Origin { SNIPPET, PAGE, REGISTRY, CACHE }

/**
 * One fact or snippet with its provenance and scores (each 0..1). [id] is the short citation id ("q1") the model sees;
 * [score] the blended total the pack sorts by. [code] = keeps a command line or code block verbatim.
 */
data class Evidence(
    val text: String,
    val url: String,
    val title: String = "",
    val date: String = "",
    val origin: Origin = Origin.SNIPPET,
    val code: Boolean = false,
    val intent: Intent = Intent.FACT,
    val relevance: Double = 0.0,
    val authority: Double = 0.0,
    val freshness: Double = 0.0,
    val agreement: Double = 0.0,
    val score: Double = 0.0,
    val judged: Boolean = false,
    val conflict: String = "",
    val id: String = "",
) {
    val host: String get() = SourcePolicy.host(url)
}
