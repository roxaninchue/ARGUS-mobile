package com.blaze.assistant.device

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.blaze.assistant.BrandName

/**
 * 0.55.15: shown instead of Argus when it crashed right after starting (CrashLog.launchBegan). Plain Android views and
 * nothing of Argus's own start-up, so it still opens when the chat screen is what crashes. The trace stays on the phone
 * until the user shares or copies it (saved secrets are scrubbed, as in "Send crash report").
 */
class CrashReportActivity : Activity() {
    companion object { const val EXTRA_SKIP = "skip_crash_screen" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val trace = CrashLog.lastCrash(CrashLog.read()).ifBlank { "No crash details were saved." }
        val pi = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(48), px(20), px(40)); setBackgroundColor(Color.WHITE) }
        root.addView(TextView(this).apply { text = "${BrandName.NAME} closed unexpectedly"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.BLACK) })
        root.addView(TextView(this).apply {
            text = "It crashed right after starting. Share this report so it can be fixed. It stays on this phone until you choose where to send it."
            textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, px(8), 0, px(12))
        })
        val box = ScrollView(this).apply { setBackgroundColor(Color.rgb(242, 242, 242)) }
        box.addView(TextView(this).apply {
            text = "${BrandName.NAME} ${pi?.versionName} (${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE})\n\n$trace"
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.BLACK); setPadding(px(10), px(10), px(10), px(10)); setTextIsSelectable(true)
        })
        root.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fun button(label: String, on: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { on() } }
            .also { root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }) }
        button("Share crash report") {
            try { CrashLog.shareIntent(this)?.let { startActivity(it) } ?: Toast.makeText(this, "No report saved", Toast.LENGTH_SHORT).show() }
            catch (e: Throwable) { Toast.makeText(this, "Couldn't share: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        button("Copy") {
            try { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("${BrandName.NAME} crash", CrashLog.reportText("${BrandName.NAME} ${pi?.versionName}\n\n$trace")))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
        }
        button("Open ${BrandName.NAME}") {
            CrashLog.dismissReport()
            (packageManager.getLaunchIntentForPackage(packageName) ?: Intent(Intent.ACTION_MAIN).setPackage(packageName))
                .let { startActivity(it.putExtra(EXTRA_SKIP, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
            finish()
        }
        root.gravity = Gravity.TOP
        setContentView(root)
    }
}
