package com.blaze.assistant.agent.query

/**
 * 0.51.0 BlazeQuery: which sources Blaze trusts for facts it injects into a prompt.
 *
 * Every url gets a trust score in 0..1 ([trust]); 0 = blocked, never fetched, never injected. The policy is a fixed list
 * (no network, no model), so it is fast enough for the hot path and identical on every phone. Pure (SourcePolicyTest).
 *
 *  - BLOCKED (0): Wikipedia and its mirrors / other open wikis, content farms, SEO answer sites, homework dumps, AI-written
 *    aggregators, scraped forum mirrors, social feeds. Exception: the official wikis of a project ([officialWikis], e.g.
 *    wiki.archlinux.org, wiki.alpinelinux.org), which are that project's own documentation.
 *  - OFFICIAL (1.0): vendor docs, standards bodies, man pages, package registries, Android developer docs.
 *  - HIGH (0.85): official code hosts (GitHub / GitLab repos and releases), readthedocs, government / university sites,
 *    reputable news wires.
 *  - OK (0.6): Q&A (Stack Overflow, Stack Exchange), established tech press.
 *  - LOW (0.3): tutorial mills and blogging platforms: usable only when nothing better agrees.
 *  - everything else: 0.45.
 */
object SourcePolicy {
    const val OFFICIAL = 1.0
    const val HIGH = 0.85
    const val OK = 0.6
    const val LOW = 0.3
    const val UNKNOWN = 0.45

    /** The url's host, lower case, without "www." / "m." (empty for a malformed url). */
    fun host(url: String): String {
        val u = url.trim().lowercase()
        val noScheme = u.substringAfter("://", u)
        return noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            .removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
    }

    private fun under(h: String, domain: String) = h == domain || h.endsWith(".$domain")

    private val blockedDomains = listOf(
        // wikis and mirrors
        "wikipedia.org", "wikimedia.org", "wikiwand.com", "wikidata.org", "wiktionary.org", "fandom.com", "wikia.com", "wikihow.com",
        "wiki.gg", "everybodywiki.com", "dbpedia.org", "infogalactic.com", "justapedia.org", "wikizero.com", "wiki2.org", "kiwix.org",
        // SEO answer sites / content farms / homework dumps
        "answers.com", "ehow.com", "reference.com", "quora.com", "chegg.com", "coursehero.com", "studocu.com", "brainly.com", "brainly.in",
        "scribd.com", "slideshare.net", "pinterest.com", "ask.com", "answers.yahoo.com", "thoughtco.com", "techwalla.com", "itstillworks.com",
        // scraped forum / code mirrors
        "codegrepper.com", "newbedev.com", "exchangetuts.com", "itecnote.com", "appsloveworld.com", "programmerall.com", "bleepcoder.com",
        "githubhelp.com", "gitmemory.com", "issuehint.com", "stackovernet.com", "stackprinter.com", "codeproject.cn", "devasking.com",
        "anycodings.com", "solveforum.com", "pythonclear.com", "copyprogramming.com", "tutorialmeta.com", "errorsolutions.tech",
        // AI-written aggregators / directories
        "toolify.ai", "aitoolnet.com", "theresanaiforthat.com", "futurepedia.io", "phind.com", "you.com", "perplexity.ai",
        // social feeds (not sources of record)
        "facebook.com", "instagram.com", "tiktok.com", "x.com", "twitter.com", "threads.net", "linkedin.com",
    )

    /** A project's own wiki is its documentation, not an open encyclopedia. */
    private val officialWikis = listOf("wiki.archlinux.org", "wiki.alpinelinux.org", "wiki.debian.org", "wiki.ubuntu.com", "wiki.gentoo.org",
        "wiki.termux.com", "wiki.lineageos.org", "wiki.postgresql.org", "wiki.python.org", "wiki.openjdk.org")

    private val official = listOf(
        // Android / Google / JVM
        "developer.android.com", "source.android.com", "android.googlesource.com", "android-developers.googleblog.com", "developers.google.com",
        "firebase.google.com", "cloud.google.com", "kotlinlang.org", "docs.oracle.com", "openjdk.org", "gradle.org", "docs.gradle.org",
        "plugins.gradle.org", "maven.apache.org", "central.sonatype.com", "search.maven.org", "repo1.maven.org", "mvnrepository.com",
        "jetbrains.com", "developer.apple.com", "learn.microsoft.com", "docs.microsoft.com", "devblogs.microsoft.com", "docs.aws.amazon.com",
        // languages, registries, runtimes
        "python.org", "docs.python.org", "peps.python.org", "pypi.org", "npmjs.com", "registry.npmjs.org", "docs.npmjs.com", "nodejs.org",
        "crates.io", "docs.rs", "rust-lang.org", "doc.rust-lang.org", "go.dev", "pkg.go.dev", "golang.org", "ruby-lang.org", "rubygems.org",
        "php.net", "packagist.org", "dart.dev", "pub.dev", "flutter.dev", "swift.org", "typescriptlang.org", "deno.com", "deno.land", "bun.sh",
        "developer.mozilla.org", "react.dev", "vuejs.org", "angular.dev", "nextjs.org", "vitejs.dev", "svelte.dev", "tailwindcss.com",
        // Linux, man pages, tools
        "man7.org", "manpages.debian.org", "manpages.ubuntu.com", "gnu.org", "kernel.org", "docs.kernel.org", "alpinelinux.org",
        "pkgs.alpinelinux.org", "debian.org", "packages.debian.org", "ubuntu.com", "packages.ubuntu.com", "archlinux.org", "fedoraproject.org",
        "freedesktop.org", "git-scm.com", "curl.se", "openssl.org", "termux.dev", "f-droid.org", "ffmpeg.org", "sqlite.org", "postgresql.org",
        "dev.mysql.com", "redis.io", "docs.docker.com", "docker.com", "kubernetes.io", "nginx.org", "apache.org", "busybox.net", "proot-me.github.io",
        "docs.github.com", "docs.gitlab.com", "cli.github.com", "about.gitlab.com",
        // standards bodies
        "ietf.org", "rfc-editor.org", "datatracker.ietf.org", "w3.org", "whatwg.org", "ecma-international.org", "iso.org", "unicode.org", "ieee.org",
        "owasp.org", "nist.gov", "cve.org", "nvd.nist.gov",
    )

    private val high = listOf(
        "github.com", "gitlab.com", "codeberg.org", "bitbucket.org", "sourceforge.net", "readthedocs.io", "readthedocs.org", "github.io",
        "reuters.com", "apnews.com", "bbc.com", "bbc.co.uk", "theguardian.com", "nytimes.com", "ft.com", "bloomberg.com", "economist.com",
        "aljazeera.com", "npr.org", "washingtonpost.com", "wsj.com", "cnbc.com", "afp.com", "dw.com", "france24.com", "nature.com", "science.org",
        "arxiv.org", "who.int", "un.org", "worldbank.org", "imf.org", "boz.zm", "znbc.co.zm",
    )

    private val ok = listOf(
        "stackoverflow.com", "stackexchange.com", "serverfault.com", "superuser.com", "askubuntu.com", "mathoverflow.net",
        "arstechnica.com", "theverge.com", "techcrunch.com", "wired.com", "zdnet.com", "theregister.com", "lwn.net", "phoronix.com",
        "androidauthority.com", "androidpolice.com", "9to5google.com", "xda-developers.com", "infoq.com", "baeldung.com", "realpython.com",
        "digitalocean.com", "cloudflare.com", "blog.cloudflare.com", "hacks.mozilla.org", "web.dev", "css-tricks.com", "smashingmagazine.com",
        "lusakatimes.com", "zambiamonitor.com", "news.ycombinator.com",
    )

    private val low = listOf(
        "medium.com", "dev.to", "hashnode.dev", "substack.com", "blogspot.com", "wordpress.com", "w3schools.com", "geeksforgeeks.org",
        "javatpoint.com", "tutorialspoint.com", "programiz.com", "simplilearn.com", "guru99.com", "educba.com", "reddit.com", "youtube.com",
    )

    /** The policy's class name for a score (for the evidence line and telemetry). */
    fun label(score: Double): String = when {
        score <= 0.0 -> "blocked"
        score >= OFFICIAL -> "official"
        score >= HIGH -> "high"
        score >= OK -> "ok"
        score >= UNKNOWN -> "unknown"
        else -> "low"
    }

    /** Trust in 0..1 for [url]; 0 = blocked (never fetched or injected). */
    fun trust(url: String): Double {
        val h = host(url)
        if (h.isEmpty() || !url.trim().lowercase().let { it.startsWith("http://") || it.startsWith("https://") || !it.contains("://") }) return 0.0
        if (officialWikis.any { under(h, it) }) return OFFICIAL
        if (blockedDomains.any { under(h, it) }) return 0.0
        // any other open wiki ("somewiki.org", "wiki.example.com", "xyz.fandom…"): blocked; a GitHub repo wiki stays (project-owned)
        if (Regex("(^|\\.)wiki[^.]*\\.|wiki\\.").containsMatchIn(h) || h.split('.').any { it.endsWith("pedia") }) return 0.0
        if (official.any { under(h, it) }) return OFFICIAL
        // government and university sites are primary sources
        if (h.endsWith(".gov") || h.contains(".gov.") || h.endsWith(".mil") || h.endsWith(".edu") || h.contains(".edu.") || h.contains(".ac.")) return HIGH
        if (high.any { under(h, it) }) return if (under(h, "github.com") && isRelease(url)) OFFICIAL else HIGH
        if (ok.any { under(h, it) }) return OK
        if (low.any { under(h, it) }) return LOW
        return UNKNOWN
    }

    /** Release notes / tags on a code host: the project's own word on versions. */
    fun isRelease(url: String): Boolean = Regex("(?i)/(releases|tags|changelog|CHANGELOG\\.md|NEWS|HISTORY)(/|$|\\?|#|\\.)").containsMatchIn(url)

    fun allowed(url: String): Boolean = trust(url) > 0.0
}
