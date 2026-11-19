package com.argus.assistant.team
object TeamGraph {
    class Graph(val sql: com.argus.assistant.store.Sql, val store: TeamStore, val threads: BotThreads)
    fun get(context: android.content.Context): Graph = throw IllegalStateException("stub")
}
