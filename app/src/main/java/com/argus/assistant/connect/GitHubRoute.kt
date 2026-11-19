package com.blaze.assistant.connect

/**
 * 0.53.4 GitHub goes through the connection, not the web. With GitHub connected, a github.com / raw.githubusercontent.com
 * / api.github.com link that fetch_page or browser_open is given is read with the user's token instead (private repos
 * work, nothing is scraped), and the prompt tells the model the github_* tools come first. Pure parsing here
 * (GitHubRouteTest); the API calls live in Connectors.github.
 */
object GitHubRoute {
    enum class Kind { REPO, TREE, BLOB, COMMITS, COMMIT, ISSUES, ISSUE, PULLS, PULL, ACTIONS, RELEASES }

    data class Target(val repo: String, val kind: Kind, val ref: String = "", val path: String = "", val number: Int = 0)

    /** Owner names that are GitHub pages, not accounts. */
    private val notOwners = setOf("settings", "login", "logout", "search", "explore", "topics", "marketplace", "orgs", "features",
        "notifications", "pulls", "issues", "sponsors", "about", "pricing", "collections", "trending", "apps", "new", "codespaces", "users")

    private val seg = Regex("[A-Za-z0-9_.-]{1,100}")

    fun parse(url0: String): Target? {
        val url = url0.trim().substringBefore('#').substringBefore('?').removeSuffix("/")
        val m = Regex("(?i)^(?:https?://)?(?:www\\.)?(github\\.com|raw\\.githubusercontent\\.com|api\\.github\\.com)/(.+)$").find(url) ?: return null
        val host = m.groupValues[1].lowercase()
        var parts = m.groupValues[2].split('/').filter { it.isNotEmpty() }
        if (host == "api.github.com") { if (parts.firstOrNull() != "repos") return null; parts = parts.drop(1) }
        if (parts.size < 2) return null
        val owner = parts[0]; val name = parts[1].removeSuffix(".git")
        if (!seg.matches(owner) || !seg.matches(name) || owner.lowercase() in notOwners) return null
        val repo = "$owner/$name"
        val rest = parts.drop(2)
        if (host == "raw.githubusercontent.com") {
            if (rest.isEmpty()) return null
            return Target(repo, Kind.BLOB, rest[0], rest.drop(1).joinToString("/"))
        }
        if (host == "api.github.com" && rest.firstOrNull() == "contents")
            return Target(repo, Kind.BLOB, "", rest.drop(1).joinToString("/"))
        if (rest.isEmpty()) return Target(repo, Kind.REPO)
        fun num(i: Int) = rest.getOrNull(i)?.toIntOrNull() ?: 0
        return when (rest[0]) {
            "tree" -> Target(repo, Kind.TREE, rest.getOrElse(1) { "" }, rest.drop(2).joinToString("/"))
            "blob" -> Target(repo, Kind.BLOB, rest.getOrElse(1) { "" }, rest.drop(2).joinToString("/"))
            "commits" -> Target(repo, Kind.COMMITS, rest.getOrElse(1) { "" })
            "commit" -> Target(repo, Kind.COMMIT, rest.getOrElse(1) { "" })
            "issues" -> if (num(1) > 0) Target(repo, Kind.ISSUE, number = num(1)) else Target(repo, Kind.ISSUES)
            "pull" -> if (num(1) > 0) Target(repo, Kind.PULL, number = num(1)) else Target(repo, Kind.PULLS)
            "pulls" -> Target(repo, Kind.PULLS)
            "actions" -> Target(repo, Kind.ACTIONS)
            "releases" -> Target(repo, Kind.RELEASES)
            else -> Target(repo, Kind.REPO)
        }
    }

    /** The github_* call that answers [t]: tool name and its arguments. */
    fun toolFor(t: Target): Pair<String, Map<String, Any>> = when (t.kind) {
        Kind.REPO -> "github_repo" to mapOf("repo" to t.repo)
        Kind.TREE, Kind.BLOB -> "github_read" to mapOf("repo" to t.repo, "path" to t.path, "ref" to t.ref)
        Kind.COMMITS -> "github_commits" to mapOf("repo" to t.repo, "branch" to t.ref)
        Kind.COMMIT -> "github_commits" to mapOf("repo" to t.repo, "sha" to t.ref)
        Kind.ISSUES -> "github_issues" to mapOf("repo" to t.repo)
        Kind.ISSUE, Kind.PULL -> "github_pr" to mapOf("repo" to t.repo, "number" to t.number)
        Kind.PULLS -> "github_my_prs" to emptyMap()
        Kind.ACTIONS -> "github_ci" to mapOf("repo" to t.repo, "wait_seconds" to 0)
        Kind.RELEASES -> "github_repo" to mapOf("repo" to t.repo)
    }

    /** Tools a GitHub link may be redirected from. */
    val redirectFrom = setOf("fetch_page", "browser_open", "open_url")

    /** The system-prompt line when GitHub is connected. */
    fun promptLine(login: String): String =
        "GitHub is connected${if (login.isNotBlank()) " as $login" else ""} with full repo access (private repos included). For anything on GitHub " +
            "(the user's repos, files, code, commits, branches, issues, pull requests, CI runs, releases, or a github.com link) use the github_* tools " +
            "(github_my_repos, github_repo, github_read, github_commits, github_search_code, github_pr, github_issues, github_ci) or the git tool to clone/push. " +
            "Never use web_search, fetch_page or the browser for GitHub while it is connected.\n"

    /** Paths the model gives with or without a leading slash. */
    fun cleanPath(p: String) = p.trim().trim('/')

    /** Caps a decoded file for the model, with a line range when asked. */
    fun slice(text: String, start: Int, lines: Int, cap: Int = 24_000): String {
        val all = text.lines()
        val from = start.coerceAtLeast(1)
        val part = all.drop(from - 1).take(lines.coerceIn(1, 3000))
        var body = part.mapIndexed { i, l -> "${from + i}\t$l" }.joinToString("\n")
        val cut = body.length > cap
        if (cut) body = body.take(cap)
        val shown = if (cut) body.count { it == '\n' } + 1 else part.size
        val more = if (from - 1 + shown < all.size) "\n[lines $from-${from + shown - 1} of ${all.size}; pass start_line to read on]" else ""
        return "(${all.size} lines)\n$body$more"
    }
}
