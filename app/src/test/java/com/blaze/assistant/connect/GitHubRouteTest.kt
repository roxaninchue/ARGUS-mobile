package com.blaze.assistant.connect

import com.blaze.assistant.connect.GitHubRoute.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRouteTest {
    @Test fun linksBecomeConnectionReads() {
        assertEquals(GitHubRoute.Target("roxaninchue/ARGUS-mobile", Kind.REPO), GitHubRoute.parse("https://github.com/roxaninchue/ARGUS-mobile"))
        assertEquals(GitHubRoute.Target("o/r", Kind.REPO), GitHubRoute.parse("github.com/o/r.git/"))
        assertEquals(GitHubRoute.Target("o/r", Kind.BLOB, "main", "app/src/Main.kt"), GitHubRoute.parse("https://github.com/o/r/blob/main/app/src/Main.kt#L10"))
        assertEquals(GitHubRoute.Target("o/r", Kind.TREE, "dev", "docs"), GitHubRoute.parse("https://github.com/o/r/tree/dev/docs"))
        assertEquals(GitHubRoute.Target("o/r", Kind.BLOB, "main", "README.md"), GitHubRoute.parse("https://raw.githubusercontent.com/o/r/main/README.md"))
        assertEquals(GitHubRoute.Target("o/r", Kind.PULL, number = 27), GitHubRoute.parse("https://github.com/o/r/pull/27/files"))
        assertEquals(GitHubRoute.Target("o/r", Kind.ISSUE, number = 3), GitHubRoute.parse("https://github.com/o/r/issues/3"))
        assertEquals(Kind.ACTIONS, GitHubRoute.parse("https://github.com/o/r/actions/runs/1")?.kind)
        assertEquals(GitHubRoute.Target("o/r", Kind.REPO), GitHubRoute.parse("https://api.github.com/repos/o/r"))
    }

    @Test fun notRepos() {
        assertNull(GitHubRoute.parse("https://github.com/settings/tokens"))
        assertNull(GitHubRoute.parse("https://github.com/search?q=x"))
        assertNull(GitHubRoute.parse("https://github.com/roxaninchue"))
        assertNull(GitHubRoute.parse("https://gitlab.com/o/r"))
        assertNull(GitHubRoute.parse("https://example.com/github.com/o/r"))
    }

    @Test fun toolsAndPrompt() {
        assertEquals("github_read", GitHubRoute.toolFor(GitHubRoute.parse("https://github.com/o/r/blob/main/a.kt")!!).first)
        assertEquals("github_pr", GitHubRoute.toolFor(GitHubRoute.parse("https://github.com/o/r/pull/2")!!).first)
        val line = GitHubRoute.promptLine("roxaninchue")
        assertTrue(line.contains("roxaninchue") && line.contains("Never use web_search, fetch_page or the browser"))
        assertTrue(GitHubRoute.slice("a\nb\nc", 2, 1).contains("2\tb"))
    }
}
