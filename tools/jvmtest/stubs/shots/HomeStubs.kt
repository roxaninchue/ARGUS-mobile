package com.blaze.assistant.home
import kotlinx.coroutines.flow.MutableStateFlow
data class Widget(val id: String, val type: String)
data class WidgetData(val value: String, val change: String, val headline: String, val fetched: Long)
object WidgetTypes { const val WEATHER = "weather" }
object HomeBoard { val widgets = MutableStateFlow<List<Widget>>(emptyList()); val data = MutableStateFlow<Map<String, WidgetData>>(emptyMap()) }
