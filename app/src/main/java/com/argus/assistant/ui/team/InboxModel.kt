package com.argus.assistant.ui.team

import com.argus.assistant.policy.NeedsYou

/**
 * P2-SF-03: the Inbox list's UI state, pure (InboxTest). The bell's InboxSheet and Desk → Inbox draw the same
 * [com.argus.assistant.ui.team.InboxList] over this model, fed by the one [NeedsYou] list (never a count of their own).
 *
 *  - a filter chip per [NeedsYou.Filter], each with its count from the same list;
 *  - selection (long-press) for bulk Allow / Deny, enabled only by [NeedsYou.bulkAllowed] (one bot, one kind, no money/delete);
 *  - a selection keeps only ids still in the list and still visible under the filter (an answered card drops out).
 */
object InboxModel {
    data class State(val filter: NeedsYou.Filter = NeedsYou.Filter.ALL, val selected: Set<String> = emptySet())

    data class Chip(val filter: NeedsYou.Filter, val label: String, val count: Int, val on: Boolean)

    fun chips(items: List<NeedsYou.Item>, s: State): List<Chip> {
        val c = NeedsYou.counts(items)
        return NeedsYou.Filter.entries.map { Chip(it, it.label, c[it] ?: 0, it == s.filter) }
    }

    fun visible(items: List<NeedsYou.Item>, s: State): List<NeedsYou.Item> = NeedsYou.filter(items, s.filter)

    /** Switching filter clears the selection (bulk acts on what you can see). */
    fun setFilter(s: State, f: NeedsYou.Filter): State = if (f == s.filter) s else State(f, emptySet())

    fun toggle(s: State, id: String): State = s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)

    fun clear(s: State): State = s.copy(selected = emptySet())

    /** Drop selected ids that are no longer visible (answered, expired, filtered away). */
    fun prune(items: List<NeedsYou.Item>, s: State): State {
        val ids = visible(items, s).map { it.id }.toSet()
        val keep = s.selected.filterTo(LinkedHashSet()) { it in ids }
        return if (keep == s.selected) s else s.copy(selected = keep)
    }

    fun selection(items: List<NeedsYou.Item>, s: State): List<NeedsYou.Item> = visible(items, s).filter { it.id in s.selected }

    fun selecting(s: State): Boolean = s.selected.isNotEmpty()

    /** Bulk Allow / Deny: approvals only (questions are answered one by one), and only per [NeedsYou.bulkAllowed]. */
    fun bulkEnabled(items: List<NeedsYou.Item>, s: State): Boolean {
        val sel = selection(items, s)
        return sel.isNotEmpty() && sel.all { it.kind == NeedsYou.Kind.APPROVAL } && NeedsYou.bulkAllowed(sel)
    }

    /** The row's time: minutes left for an approval, otherwise how long ago. */
    fun time(i: NeedsYou.Item, now: Long): String = if (i.kind == NeedsYou.Kind.APPROVAL) {
        val m = NeedsYou.minutesLeft(i, now); "$m min left"
    } else {
        val ago = ((now - i.createdAt).coerceAtLeast(0)) / 60_000
        when { ago < 1 -> "now"; ago < 60 -> "${ago}m ago"; ago < 1440 -> "${ago / 60}h ago"; else -> "${ago / 1440}d ago" }
    }

    /** The empty line under a filter. */
    fun empty(f: NeedsYou.Filter): String = if (f == NeedsYou.Filter.ALL) "Nothing needs you." else "No ${f.label.lowercase()} waiting."
}
