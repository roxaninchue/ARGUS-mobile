package com.blaze.assistant.connect

/**
 * P4-CN-01/04: reads the argus-connect OAuth return caught on the loopback (`GET /connected?app=<id>&status=ok|error`)
 * and words it. Pure, so ConnectResumeTest checks every case without a socket.
 */
object ConnectReturn {
    enum class Result { Ok, Failed, WrongApp, TimedOut, Garbled }

    /** [requestLine] is the first HTTP line (`GET /connected?app=github&status=ok HTTP/1.1`). */
    fun parse(requestLine: String, expectApp: String): Result {
        val target = requestLine.trim().split(" ").getOrNull(1) ?: return Result.Garbled
        if (!target.startsWith("/connected")) return Result.Garbled
        val q = query(target.substringAfter('?', ""))
        val app = q["app"].orEmpty()
        if (app.isNotBlank() && app != expectApp) return Result.WrongApp
        return when (q["status"]) { "ok" -> Result.Ok; "error" -> Result.Failed; null -> Result.Garbled; else -> Result.Failed }
    }

    fun query(qs: String): Map<String, String> = qs.split('&').filter { it.contains('=') }.associate {
        val (k, v) = it.split('=', limit = 2)
        java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
    }

    fun message(r: Result): String = when (r) {
        Result.Ok -> "Connected"
        Result.Failed -> "The sign-in didn't finish. Try Connect again."
        Result.WrongApp -> "That sign-in was for another app. Try Connect again."
        Result.TimedOut -> "The sign-in timed out after 5 minutes."
        Result.Garbled -> "The sign-in came back in a form I couldn't read. Try Connect again."
    }

    fun page(r: Result, brand: String): String {
        val title = if (r == Result.Ok) "Connected" else "Sign-in didn't finish"
        return "<html><head><meta name='viewport' content='width=device-width'></head><body style='font-family:sans-serif;padding:40px'>" +
            "<h2>$title</h2><p>You can go back to ${esc(brand)}.</p></body></html>"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
