package com.blaze.assistant.movies

import java.net.URI
import java.net.URLDecoder

/**
 * 0.55.14 what the user typed in Add source -> one provider source (Finn: "the Vega-style provider source adder").
 *
 * The forms other provider apps accept, so a source someone shares works here as typed:
 * - an author name: `author` (GitHub), `author@cb` (Codeberg), `author@bb` (Bitbucket), `author@gl` (GitLab). A bare author
 *   means that author's `vega-providers` repo, the conventional repo name for a provider source, on its default branch.
 *   The `@` suffix names the HOST, not a branch.
 * - `owner/repo`, and `owner/repo@branch` for a branch (GitHub).
 * - a repo link on GitHub, Codeberg, Bitbucket or GitLab (subgroups too), with or without a branch and a folder
 *   (`/tree/<branch>/<folder>`, `/src/branch/<branch>`, `/-/tree/<branch>`), or a raw file link on raw.githubusercontent.com.
 * - any other http(s) link: a manifest file (path ends in `.json`) or the folder that holds `manifest.json`.
 * A link to `manifest.json` itself always works. Written for Argus from those public conventions (no other app's code).
 *
 * Pure (java.net only); SourceInputTest.
 */
object SourceInput {
    enum class Host(val suffix: String, val label: String) {
        GITHUB("gh", "GitHub"), CODEBERG("cb", "Codeberg"), BITBUCKET("bb", "Bitbucket"), GITLAB("gl", "GitLab"), WEB("", "Web");
        companion object { fun ofSuffix(s: String): Host? = entries.firstOrNull { it.suffix.isNotEmpty() && it.suffix.equals(s, true) } }
    }

    /** The repo name a bare author name points at. */
    const val DEFAULT_REPO = "vega-providers"
    /** Branches tried, in order, when the input names none. */
    val DEFAULT_BRANCHES = listOf("main", "master")
    const val MAX_TOKEN = 255

    /**
     * One resolved source. [key] identifies it (two inputs for the same repo give the same key, so adding it again updates it);
     * [label] is what the picker shows. [bases] are the folders to look for the manifest in, in order (each also with dist/).
     * [manifest] is set only when the input named a manifest file other than `<base>/manifest.json`.
     * [authPrefix] is the only URL prefix a private token may be sent to ("" = this host can't take a token).
     */
    data class Spec(val key: String, val label: String, val host: Host, val bases: List<String>, val manifest: String = "", val authPrefix: String = "") {
        val privateOk: Boolean get() = authPrefix.isNotEmpty()
    }

    /** [spec] or a sentence to show under the field. */
    data class Parsed(val spec: Spec?, val error: String = "") { val ok get() = spec != null }

    const val EMPTY = "Type an author name, a repo link or a manifest link."
    const val NOT_A_SOURCE = "That isn't an author name or a link. Try author, author@cb, owner/repo or a repo URL."
    const val PRIVATE_HOSTS = "Private sources work with GitHub repos or your own https manifest link."
    const val BAD_TOKEN = "Paste the access token: one word, no spaces."

    private val AUTHOR = Regex("^@?([A-Za-z0-9][A-Za-z0-9_-]{0,38})(?:@([A-Za-z]{1,8}))?$")
    private val OWNER_REPO = Regex("^([A-Za-z0-9][A-Za-z0-9_-]{0,38})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?(?:@([A-Za-z0-9._/-]{1,100}))?$")
    private val KNOWN = mapOf("raw.githubusercontent.com" to Host.GITHUB, "github.com" to Host.GITHUB, "codeberg.org" to Host.CODEBERG,
        "bitbucket.org" to Host.BITBUCKET, "gitlab.com" to Host.GITLAB)

    fun parse(input: String): Parsed {
        val s = input.trim().trim('"', '\'', '<', '>', '`').substringBefore('#').trim()
        if (s.isEmpty()) return Parsed(null, EMPTY)
        if (s.any { it.isWhitespace() }) return Parsed(null, NOT_A_SOURCE)
        val hasScheme = Regex("(?i)^[a-z][a-z0-9+.-]*://").containsMatchIn(s)
        if (!hasScheme) {
            AUTHOR.matchEntire(s)?.let { m ->
                val suffix = m.groupValues[2]
                val host = if (suffix.isEmpty()) Host.GITHUB else Host.ofSuffix(suffix)
                    ?: return Parsed(null, "\u201c@$suffix\u201d isn't a host. Use @cb (Codeberg), @bb (Bitbucket) or @gl (GitLab), or nothing for GitHub.")
                return Parsed(repo(host, m.groupValues[1], DEFAULT_REPO, "", ""))
            }
            OWNER_REPO.matchEntire(s)?.let { m -> return Parsed(repo(Host.GITHUB, m.groupValues[1], m.groupValues[2], m.groupValues[3].trim('/'), "")) }
            // "github.com/o/r", "example.org/folder": a link without its https://
            if (!s.substringBefore('/').contains('.')) return Parsed(null, NOT_A_SOURCE)
        }
        val url = if (hasScheme) s else "https://$s"
        UrlGuard.check(url)?.let { return Parsed(null, it) }
        val u = try { URI(url) } catch (_: Exception) { return Parsed(null, "That isn't a valid link.") }
        val host = u.host.lowercase().removePrefix("www.")
        val segs = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { dec(it) }
            .let { if (it.lastOrNull().equals("manifest.json", true)) it.dropLast(1) else it }
        KNOWN[host]?.let { h -> return knownHost(h, host, segs) }
        return Parsed(web(u))
    }

    private fun dec(s: String) = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }

    private fun knownHost(h: Host, host: String, segs: List<String>): Parsed {
        val bad = Parsed(null, "That ${h.label} link doesn't point to a repo. Use ${h.label.lowercase().let { if (h == Host.GITHUB) "github.com" else host }}/owner/repo.")
        return when (host) {
            "raw.githubusercontent.com" -> {
                if (segs.size < 3) return bad
                // raw.githubusercontent.com/o/r/<branch>/<folder> or /o/r/refs/heads/<branch>/<folder>
                val rest = segs.drop(2)
                val (branch, path) = if (rest.size >= 3 && rest[0] == "refs" && rest[1] == "heads") rest[2] to rest.drop(3) else rest[0] to rest.drop(1)
                Parsed(repo(h, segs[0], segs[1], branch, path.joinToString("/")))
            }
            "github.com" -> {
                if (segs.size < 2) return bad
                val (branch, path) = if (segs.size >= 4 && (segs[2] == "tree" || segs[2] == "blob")) segs[3] to segs.drop(4) else "" to emptyList()
                Parsed(repo(h, segs[0], segs[1].removeSuffix(".git"), branch, path.joinToString("/")))
            }
            "codeberg.org" -> {
                if (segs.size < 2) return bad
                // codeberg.org/o/r/src|raw/branch/<branch>/<folder>
                val (branch, path) = if (segs.size >= 5 && (segs[2] == "src" || segs[2] == "raw") && segs[3] == "branch") segs[4] to segs.drop(5) else "" to emptyList()
                Parsed(repo(h, segs[0], segs[1].removeSuffix(".git"), branch, path.joinToString("/")))
            }
            "bitbucket.org" -> {
                if (segs.size < 2) return bad
                val (branch, path) = if (segs.size >= 4 && (segs[2] == "src" || segs[2] == "raw")) segs[3] to segs.drop(4) else "" to emptyList()
                Parsed(repo(h, segs[0], segs[1].removeSuffix(".git"), branch, path.joinToString("/")))
            }
            else -> {   // gitlab.com/<group>[/<subgroup>…]/<repo>[/-/tree|raw|blob/<branch>/<folder>]
                val dash = segs.indexOf("-")
                val project = if (dash >= 0) segs.take(dash) else segs
                if (project.size < 2) return bad
                val view = segs.getOrNull(dash + 1)
                val (branch, path) = if (dash >= 0 && view in setOf("tree", "raw", "blob") && segs.size > dash + 2) segs[dash + 2] to segs.drop(dash + 3) else "" to emptyList()
                Parsed(repo(h, project.dropLast(1).joinToString("/"), project.last().removeSuffix(".git"), branch, path.joinToString("/")))
            }
        }
    }

    /** A repo on a git host. No branch = try main, then master. */
    private fun repo(h: Host, owner: String, name: String, branch: String, path: String): Spec {
        val branches = if (branch.isNotBlank()) listOf(branch) else DEFAULT_BRANCHES
        val folder = path.trim('/').let { if (it.isEmpty()) "" else "/$it" }
        val bases = branches.map { b -> raw(h, owner, name, b) + folder }.flatMap { listOf(it, "$it/dist") }.distinct()
        val suffix = if (h == Host.GITHUB) "" else "@" + h.suffix
        val who = if (name.equals(DEFAULT_REPO, true) && branch.isBlank() && folder.isEmpty()) owner else "$owner/$name"
        val label = who + (if (branch.isNotBlank()) " ($branch${folder})" else if (folder.isNotEmpty()) " (${folder.trimStart('/')})" else "") + suffix
        val key = (h.suffix + ":" + owner + "/" + name + "@" + branch + folder).lowercase()
        val auth = if (h == Host.GITHUB) "https://raw.githubusercontent.com/$owner/$name/" else ""
        return Spec(key, label, h, bases, "", auth)
    }

    fun raw(h: Host, owner: String, name: String, branch: String): String = when (h) {
        Host.CODEBERG -> "https://codeberg.org/$owner/$name/raw/branch/$branch"
        Host.BITBUCKET -> "https://bitbucket.org/$owner/$name/raw/$branch"
        Host.GITLAB -> "https://gitlab.com/$owner/$name/-/raw/$branch"
        else -> "https://raw.githubusercontent.com/$owner/$name/$branch"
    }

    /** Any other site: a manifest file (….json) or the folder holding manifest.json. A token only ever goes to that https folder. */
    private fun web(u: URI): Spec {
        val segs = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.toMutableList()
        val file = if (segs.isNotEmpty() && segs.last().endsWith(".json", true)) segs.removeAt(segs.size - 1) else ""
        val origin = u.scheme.lowercase() + "://" + u.rawAuthority.lowercase()
        val base = origin + (if (segs.isEmpty()) "" else "/" + segs.joinToString("/"))
        val manifest = if (file.isNotEmpty() && !file.equals("manifest.json", true)) "$base/$file" + (u.rawQuery?.let { "?$it" } ?: "") else ""
        val bases = if (file.isNotEmpty()) listOf(base) else listOf(base, "$base/dist")
        val label = (u.host.lowercase().removePrefix("www.") + (if (segs.isEmpty()) "" else "/" + segs.joinToString("/") { dec(it) })).take(60)
        val auth = if (u.scheme.equals("https", true)) "$base/" else ""
        return Spec("web:" + base.lowercase(), label, Host.WEB, bases, manifest, auth)
    }

    /** A token the user pasted, cleaned; null when it can't be one (empty, too long, has spaces). */
    fun token(t: String): String? = t.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN && it.none { c -> c.isWhitespace() } }

    /** May a source's token go to [url]? Only under its own [Spec.authPrefix] (never another repo, host or http). */
    fun authAllowed(authPrefix: String, url: String): Boolean =
        authPrefix.startsWith("https://") && (url.startsWith(authPrefix) || url == authPrefix.trimEnd('/'))

    /** The Authorization header a private source sends (GitHub's raw files accept a fine-grained or classic token this way). */
    fun authHeader(token: String): Map<String, String> = mapOf("Authorization" to "token $token")
}
