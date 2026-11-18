package com.blaze.assistant.browser

/**
 * 2026-11-18 (owner screenshot: Google Classroom opened in the wrong account). A phone signed in to several Google accounts
 * opens Google links in account 0 unless the link names one. Google names the account with `authuser=N` (any Google site)
 * or a `/u/N/` path segment (Gmail, Drive, Classroom, Calendar, Docs). A link tapped on a Google page in account N that
 * names no account gets `authuser=N`, so it stays in the account the user is in. The site sheet can switch account.
 *
 * Pure (no android.net.Uri), so GoogleAccountTest runs on the JVM.
 */
object GoogleAccount {
    /** Accounts offered in the switcher (Google allows up to 10 signed in; few phones have more than 5). */
    const val MAX_SWITCH = 5

    private val U_PATH = Regex("""/u/(\d{1,2})(/|$|\?|#)""")
    private val AUTHUSER = Regex("""[?&]authuser=(\d{1,2})(&|#|$)""")

    /** The host of [url], lowercase, without port (empty when there is none). */
    fun host(url: String): String {
        val s = url.substringAfter("://", "")
        if (s.isEmpty()) return ""
        return s.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()
    }

    /** Google's own account-aware sites (google.com and its subdomains), not the sign-in pages or google.<cc> search. */
    fun isGoogle(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val h = host(url)
        if (h == "accounts.google.com" || h == "myaccount.google.com") return false
        return h == "google.com" || h.endsWith(".google.com")
    }

    /** The account [url] names (authuser=N or /u/N/), or null when it names none. */
    fun accountOf(url: String): Int? {
        if (!isGoogle(url)) return null
        AUTHUSER.find(url)?.let { return it.groupValues[1].toIntOrNull() }
        val path = url.substringAfter("://").let { p -> p.indexOf('/').let { if (it < 0) "" else p.substring(it) } }.substringBefore('?').substringBefore('#')
        U_PATH.find(path)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** [url] with `authuser=[account]` added (or replaced), keeping its #fragment. Non-Google links come back unchanged. */
    fun withAccount(url: String, account: Int): String {
        if (!isGoogle(url) || account < 0) return url
        val frag = url.indexOf('#').let { if (it < 0) "" else url.substring(it) }
        var base = if (frag.isEmpty()) url else url.substring(0, url.length - frag.length)
        // A /u/N/ path already names the account: switch it there instead of adding a second, conflicting one.
        val pathStart = base.indexOf('/', base.indexOf("://") + 3).let { if (it < 0) base.length else it }
        val q = base.indexOf('?').let { if (it < 0) base.length else it }
        val path = base.substring(pathStart, q)
        val m = U_PATH.find(path)
        if (m != null) {
            val r = m.groups[1]!!.range
            base = base.substring(0, pathStart + r.first) + account + base.substring(pathStart + r.last + 1)
            return base + frag
        }
        base = if (Regex("""[?&]authuser=\d{1,2}""").containsMatchIn(base))
            base.replace(Regex("""([?&])authuser=\d{1,2}"""), "$1authuser=$account")
        else base + (if ('?' in base) (if (base.endsWith("?") || base.endsWith("&")) "" else "&") else "?") + "authuser=$account"
        return base + frag
    }

    /**
     * A link tapped from [from] to [to]: the URL to load instead, or null to let it load as it is. Only a Google page in a
     * non-default account (1+) passes its account on, and only to a Google link that names none.
     */
    fun carry(from: String?, to: String): String? {
        val acct = from?.let { accountOf(it) } ?: return null
        if (acct <= 0 || !isGoogle(to) || accountOf(to) != null) return null
        return withAccount(to, acct)
    }

    /** The switcher's labels: "Account 1" … for authuser 0 … */
    fun label(account: Int): String = "Account ${account + 1}"
}
