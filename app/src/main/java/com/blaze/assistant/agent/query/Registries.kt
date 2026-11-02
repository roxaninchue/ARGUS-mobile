package com.blaze.assistant.agent.query

/**
 * 0.51.0 BlazeQuery: version facts straight from the package registries' free JSON APIs (no key, no scraping, no search
 * engine in between): PyPI, npm, crates.io, Maven Central, GitHub releases. These are the sources of record for "what is the
 * latest version of X" and for checking a dependency line before it is written into code.
 *
 * [detect] finds packages named in a message ("pip install requests", "npm i zod", "cargo add serde",
 * "androidx.core:core-ktx", "github.com/owner/repo" / "owner/repo on GitHub"). [url] is the API call, [parse] turns the body
 * into one fact line. Parsing is regex based on purpose: a body cut short by the fetch limit still yields its version.
 * Pure (RegistriesTest).
 */
object Registries {
    enum class Eco(val label: String) { PYPI("PyPI"), NPM("npm"), CRATES("crates.io"), MAVEN("Maven Central"), GITHUB("GitHub releases") }

    data class Pkg(val eco: Eco, val name: String) {
        val group: String get() = name.substringBefore(':')
        val artifact: String get() = name.substringAfter(':').substringBefore(':')
    }

    private val pip = Regex("""(?i)\b(?:pip3?|pipx|uv pip|poetry add|pypi)\s+(?:install\s+)?(?:-U\s+|--upgrade\s+)?([A-Za-z0-9][A-Za-z0-9._-]{1,60})""")
    private val pyPkg = Regex("""(?i)\b(?:python|pypi) (?:package|library|module)\s+([A-Za-z0-9][A-Za-z0-9._-]{1,60})""")
    private val npm = Regex("""(?i)\b(?:npm (?:i|install|add|view|info)|yarn add|pnpm add|npx|bun add)\s+(?:-[DgS]\s+)?((?:@[a-z0-9._-]+/)?[a-z0-9][a-z0-9._-]{0,60})""")
    private val npmPkg = Regex("""(?i)\bnpm (?:package|module)\s+((?:@[a-z0-9._-]+/)?[a-z0-9][a-z0-9._-]{0,60})""")
    private val cargo = Regex("""(?i)\b(?:cargo (?:add|install)|crate)\s+([a-z0-9][a-z0-9_-]{0,60})""")
    private val maven = Regex("""\b([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+):([a-zA-Z0-9][a-zA-Z0-9._-]{1,80})(?::[\w.+-]+)?\b""")
    private val ghUrl = Regex("""(?i)github\.com/([A-Za-z0-9_.-]{1,40})/([A-Za-z0-9_.-]{1,100})""")
    private val ghName = Regex("""(?i)\b([A-Za-z0-9_.-]{2,40})/([A-Za-z0-9_.-]{2,100})\b(?=[^.\n]{0,40}\b(?:on github|github|repo|release|releases|version)\b)""")

    private val notPkgs = setOf("install", "the", "a", "and", "or", "-u", "--upgrade", "latest", "version", "package", "packages", "it", "this", "that")

    fun detect(text: String): List<Pkg> {
        val out = LinkedHashSet<Pkg>()
        fun add(e: Eco, n: String) { val c = n.trim().trimEnd('.', ',', ')', '?', '!'); if (c.length >= 2 && c.lowercase() !in notPkgs) out += Pkg(e, c) }
        pip.findAll(text).forEach { add(Eco.PYPI, it.groupValues[1]) }
        pyPkg.findAll(text).forEach { add(Eco.PYPI, it.groupValues[1]) }
        npm.findAll(text).forEach { add(Eco.NPM, it.groupValues[1]) }
        npmPkg.findAll(text).forEach { add(Eco.NPM, it.groupValues[1]) }
        cargo.findAll(text).forEach { add(Eco.CRATES, it.groupValues[1]) }
        maven.findAll(text).forEach { m -> if (!m.value.contains("://") && !m.groupValues[2].all { it.isDigit() } && m.groupValues[1].count { it == '.' } >= 1 && !m.groupValues[1].startsWith("www.")) add(Eco.MAVEN, m.groupValues[1] + ":" + m.groupValues[2]) }
        ghUrl.findAll(text).forEach { add(Eco.GITHUB, it.groupValues[1] + "/" + it.groupValues[2].removeSuffix(".git")) }
        if (out.none { it.eco == Eco.GITHUB }) ghName.findAll(text).forEach { m ->
            val o = m.groupValues[1]; val r = m.groupValues[2]
            if (!o.contains('.') && o.lowercase() !in setOf("and", "or", "tcp", "udp", "km", "kg", "input", "output")) add(Eco.GITHUB, "$o/$r")
        }
        return out.take(3)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun url(p: Pkg): String = when (p.eco) {
        Eco.PYPI -> "https://pypi.org/pypi/${enc(p.name)}/json"
        Eco.NPM -> "https://registry.npmjs.org/${p.name.replace("/", "%2F")}/latest"
        Eco.CRATES -> "https://crates.io/api/v1/crates/${enc(p.name)}"
        Eco.MAVEN -> "https://search.maven.org/solrsearch/select?q=" + enc("g:\"${p.group}\" AND a:\"${p.artifact}\"") + "&rows=1&wt=json"
        Eco.GITHUB -> "https://api.github.com/repos/${p.name}/releases/latest"
    }

    /** The page a human would open for the same fact (the citation shown to the user). */
    fun page(p: Pkg): String = when (p.eco) {
        Eco.PYPI -> "https://pypi.org/project/${p.name}/"
        Eco.NPM -> "https://www.npmjs.com/package/${p.name}"
        Eco.CRATES -> "https://crates.io/crates/${p.name}"
        Eco.MAVEN -> "https://central.sonatype.com/artifact/${p.group}/${p.artifact}"
        Eco.GITHUB -> "https://github.com/${p.name}/releases/latest"
    }

    private fun str(body: String, key: String): String? = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
    private fun num(body: String, key: String): Long? = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()

    /** One fact line from the API's [body], with the release date as [Pair.second] (or null when the body has no version). */
    fun parse(p: Pkg, body: String): Pair<String, String>? {
        if (body.startsWith("Error")) return null
        return when (p.eco) {
            Eco.PYPI -> {
                val v = str(body, "version") ?: return null
                val req = str(body, "requires_python").orEmpty()
                val date = Regex("\"upload_time_iso_8601\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})").findAll(body).lastOrNull()?.groupValues?.get(1).orEmpty()
                "PyPI: ${p.name} latest release is $v" + (if (req.isNotBlank()) " (requires Python $req)" else "") + ". Install: `pip install ${p.name}==$v`" to date
            }
            Eco.NPM -> {
                val v = str(body, "version") ?: return null
                val node = Regex("\"engines\"\\s*:\\s*\\{[^}]*\"node\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                "npm: ${p.name} latest is $v" + (if (node.isNotBlank()) " (node $node)" else "") + ". Install: `npm install ${p.name}@$v`" to ""
            }
            Eco.CRATES -> {
                val v = str(body, "max_stable_version") ?: str(body, "newest_version") ?: str(body, "max_version") ?: return null
                val date = str(body, "updated_at")?.take(10).orEmpty()
                "crates.io: ${p.name} latest stable is $v. Cargo.toml: `${p.name} = \"$v\"`" to date
            }
            Eco.MAVEN -> {
                if ((num(body, "numFound") ?: 0L) == 0L) return null
                val v = str(body, "latestVersion") ?: str(body, "v") ?: return null
                val ts = num(body, "timestamp")
                val date = ts?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(it)) }.orEmpty()
                "Maven Central: ${p.group}:${p.artifact} latest is $v. Gradle: `implementation(\"${p.group}:${p.artifact}:$v\")`" to date
            }
            Eco.GITHUB -> {
                val tag = str(body, "tag_name") ?: return null
                val name = str(body, "name").orEmpty()
                val date = str(body, "published_at")?.take(10).orEmpty()
                "GitHub: ${p.name} latest release is $tag" + (if (name.isNotBlank() && name != tag) " (\"${name.take(60)}\")" else "") + (if (date.isNotBlank()) ", published $date" else "") + "." to date
            }
        }
    }
}
