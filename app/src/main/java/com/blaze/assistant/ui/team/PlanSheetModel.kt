package com.blaze.assistant.ui.team

import com.blaze.assistant.team.Router

/**
 * P1-TM-06: the "Here's what I'll do" sheet (A-team planSheet), pure so PlanSheetModelTest checks it: the title, the
 * line under it ("“move the deck to Fri” · On this phone · 120 ms"), one row per change (a miss in the warning colour),
 * the Apply button ("Apply", "Apply 3"; off when nothing would change), the preview switch (A-team "Apply commands
 * without the preview step", hidden when the plan removes something, since removals always ask) and the receipt toast.
 */
object PlanSheetModel {
    const val TITLE = "Here's what I'll do"
    const val SWITCH = "Apply commands without this step"

    data class Line(val icon: String, val text: String, val miss: Boolean)

    fun sub(plan: Router.Plan): String = "\u201c" + plan.text.trim().let { if (it.length > 80) it.take(79) + "\u2026" else it } + "\u201d" +
        (if (plan.via.isNotEmpty()) " \u00b7 ${plan.via}" else "")

    fun lines(plan: Router.Plan): List<Line> = plan.actions.map { Line(it.icon, it.label, it is Router.Act.Miss) }

    fun button(plan: Router.Plan): String = plan.real.size.let { if (it > 1) "Apply $it" else "Apply" }

    fun enabled(plan: Router.Plan): Boolean = plan.real.isNotEmpty()

    fun showSwitch(plan: Router.Plan): Boolean = !plan.risky

    /** A-team toast: "Done · 2 changes". */
    fun done(applied: Router.Applied): String = if (applied.count == 0) applied.lines.firstOrNull() ?: "Nothing changed"
        else "Done \u00b7 ${applied.count} change${if (applied.count == 1) "" else "s"}"
}
