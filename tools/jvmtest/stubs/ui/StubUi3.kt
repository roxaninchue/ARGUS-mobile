package com.blaze.assistant.team
object TeamGraph {
    class Graph(val sql: com.blaze.assistant.store.Sql, val store: TeamStore, val threads: BotThreads)
    fun get(context: android.content.Context): Graph = throw IllegalStateException("stub")
}
