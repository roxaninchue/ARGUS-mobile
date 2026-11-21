package com.blaze.assistant.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private typealias Run = DocFiles.Run

/**
 * Unreleased after 0.54.1 (owner, 21 Nov 2026: "Send PDF" looped, the model installed reportlab in the terminal, then stopped on
 * "Now let me write the full PDF-building script", twice; no PDF ever came. Then: "can you make a docs and PowerPoint maker
 * too?"). make_document makes a PDF, a Word file or a PowerPoint deck in ONE tool call on the phone: Markdown in (research is
 * already Markdown), the file out, saved to Downloads like save_file and shown as a card. The PDF is drawn with Android's own
 * [PdfDocument]; Word and PowerPoint are written by [DocFiles] (pure, tested). No terminal, no pip, no script for the model to
 * write, nothing to stop halfway. The terminal stays for layouts this can't draw.
 */
object MakeDoc {
    const val TOOL = "make_document"
    val FORMATS = setOf("pdf", "docx", "pptx")

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL)
        .put("description", "Make a PDF, a Word document (docx) or a PowerPoint deck (pptx) on the phone in ONE step, save it to Downloads and " +
            "get the card that opens it. Use this for every PDF, Word or slides request (\"make it a PDF\", \"send PDF\", \"as a Word doc\", " +
            "\"make a presentation\"); never write a reportlab, pandoc or python-pptx script for it. With no markdown it turns the newest document " +
            "or long answer in this chat into the file. For slides write the deck as Markdown: # deck title, a short line under it, then one " +
            "## heading per slide with 3-6 short bullets (tables become real tables).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("format", JSONObject().put("type", "string").put("enum", org.json.JSONArray(listOf("pdf", "docx", "pptx"))).put("description", "pdf, docx (Word) or pptx (PowerPoint)"))
            .put("markdown", JSONObject().put("type", "string").put("description", "The whole document in Markdown (optional; leave out to use the newest document in this chat)"))
            .put("title", JSONObject().put("type", "string").put("description", "Title (optional; the first # heading by default)"))
            .put("filename", JSONObject().put("type", "string").put("description", "File name (optional)")))
            .put("required", org.json.JSONArray(listOf("format")))))

    // ---- drawing (Android) ----

    private const val W = 595; private const val H = 842; private const val M = 54f
    private val ink = Color.rgb(28, 28, 32); private val soft = Color.rgb(96, 96, 104); private val line = Color.rgb(214, 214, 220)

    private fun styled(runs: List<Run>): CharSequence {
        val b = SpannableStringBuilder()
        for (r in runs) {
            val s = b.length; b.append(r.text)
            if (r.bold) b.setSpan(StyleSpan(Typeface.BOLD), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.italic) b.setSpan(StyleSpan(Typeface.ITALIC), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.code) b.setSpan(TypefaceSpan("monospace"), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(20)).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.18f).setIncludePad(false).build()

    private class Pager(val doc: PdfDocument, val title: String) {
        var page: PdfDocument.Page? = null; var y = M; var n = 0
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = soft }
        val bottom: Float get() = H - M - 14f
        fun canvas() = (page ?: newPage()).canvas
        fun newPage(): PdfDocument.Page {
            page?.let { end(it) }
            n++; val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); page = p; y = M; return p
        }
        private fun end(p: PdfDocument.Page) {
            p.canvas.drawText(title.take(80), M, H - M + 6f, foot)
            val num = "$n"; p.canvas.drawText(num, W - M - foot.measureText(num), H - M + 6f, foot)
            doc.finishPage(p)
        }
        fun finish() { page?.let { end(it) }; page = null }
        fun need(h: Float) { if (page == null || y + h > bottom) newPage() }

        /** Draws [l] at x, splitting it across pages line by line. */
        fun draw(l: StaticLayout, x: Float, bg: Int? = null) {
            var first = 0
            while (first < l.lineCount) {
                if (page == null || y + (l.getLineBottom(first) - l.getLineTop(first)) > bottom) newPage()
                var last = first
                while (last + 1 < l.lineCount && y + (l.getLineBottom(last + 1) - l.getLineTop(first)) <= bottom) last++
                val top = l.getLineTop(first).toFloat(); val h = l.getLineBottom(last) - top
                val c = canvas()
                if (bg != null) c.drawRect(x - 6f, y - 4f, W - M, y + h + 4f, Paint().apply { color = bg })
                c.save(); c.clipRect(x, y, W.toFloat(), y + h); c.translate(x, y - top); l.draw(c); c.restore()
                y += h; first = last + 1
            }
        }
    }

    /** [md] → PDF bytes. */
    fun render(md: String, title: String): ByteArray {
        val doc = PdfDocument()
        try {
            val pg = Pager(doc, title)
            val width = (W - 2 * M).toInt()
            val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = ink }
            val mono = TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 8.8f }
            val quote = TextPaint(body).apply { color = soft; typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
            val rule = Paint().apply { color = line; strokeWidth = 0.8f }
            var firstBlock = true
            for (b in DocFiles.blocks(md)) { when (b) {
                is DocFiles.Block.Heading -> {
                    val size = when (b.level) { 1 -> 20f; 2 -> 15f; 3 -> 12.5f; else -> 11f }
                    val p = TextPaint(body).apply { textSize = size; typeface = Typeface.DEFAULT_BOLD }
                    val l = layout(styled(DocFiles.inline(b.text)), p, width)
                    pg.y += if (firstBlock) 0f else size * 0.9f
                    pg.need(l.height + 30f)   // a heading never sits alone at the foot of a page
                    pg.draw(l, M)
                    if (b.level <= 2) { pg.y += 4f; pg.canvas().drawLine(M, pg.y, W - M, pg.y, rule) }
                    pg.y += 6f
                }
                is DocFiles.Block.Para -> { pg.draw(layout(styled(DocFiles.inline(b.text)), body, width), M); pg.y += 7f }
                is DocFiles.Block.Item -> {
                    val indent = 14f + b.depth * 14f
                    val l = layout(styled(DocFiles.inline(b.text)), body, (width - indent - 12f).toInt())
                    pg.need(l.getLineBottom(0).toFloat())
                    pg.canvas().drawText(b.mark, M + indent - 12f, pg.y + body.textSize, body)
                    pg.draw(l, M + indent + 2f); pg.y += 3f
                }
                is DocFiles.Block.Quote -> {
                    val l = layout(styled(DocFiles.inline(b.text)), quote, width - 14)
                    pg.need(l.getLineBottom(0).toFloat()); val y0 = pg.y
                    pg.draw(l, M + 14f)
                    if (pg.y > y0) pg.canvas().drawRect(M, y0, M + 2.5f, pg.y, Paint().apply { color = line })
                    pg.y += 7f
                }
                is DocFiles.Block.Code -> { pg.y += 3f; pg.draw(layout(b.text, mono, width - 6), M + 6f, Color.rgb(244, 244, 247)); pg.y += 10f }
                is DocFiles.Block.Rule -> { pg.need(12f); pg.y += 5f; pg.canvas().drawLine(M, pg.y, W - M, pg.y, rule); pg.y += 8f }
                is DocFiles.Block.Table -> {
                    val cols = b.rows.maxOf { it.size }.coerceAtLeast(1)
                    val cw = width.toFloat() / cols
                    val small = TextPaint(body).apply { textSize = if (cols > 4) 8.5f else 9.5f }
                    val bold = TextPaint(small).apply { typeface = Typeface.DEFAULT_BOLD }
                    pg.y += 2f
                    b.rows.forEachIndexed { r, row ->
                        val ls = (0 until cols).map { c -> layout(styled(DocFiles.inline(row.getOrElse(c) { "" })), if (r == 0) bold else small, (cw - 8f).toInt()) }
                        val h = ls.maxOf { it.height } + 8f
                        if (pg.y + h > pg.bottom) pg.newPage()
                        val c = pg.canvas()
                        if (r == 0) c.drawRect(M, pg.y, W - M, pg.y + h, Paint().apply { color = Color.rgb(240, 240, 245) })
                        ls.forEachIndexed { k, l -> c.save(); c.translate(M + k * cw + 4f, pg.y + 4f); l.draw(c); c.restore() }
                        pg.y += h; c.drawLine(M, pg.y, W - M, pg.y, rule)
                    }
                    pg.y += 10f
                }
            }; firstBlock = false }
            if (pg.page == null) pg.newPage()
            pg.finish()
            return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally { doc.close() }
    }

    private val mimes = mapOf("pdf" to "application/pdf", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation")

    /** Makes [md] as [format] and saves it: Downloads for the user, plus a private copy (files/media/docs) the chat card opens and shares. */
    fun save(ctx: Context, md: String, title0: String = "", filename: String = "", format: String = "pdf"): Pair<String, String?> {
        val f = format.lowercase().removePrefix(".").let { if (it in FORMATS) it else "pdf" }
        val title = title0.ifBlank { DocFiles.titleOf(md, "Document") }
        val bytes = try { when (f) { "docx" -> DocFiles.docx(md, title); "pptx" -> DocFiles.pptx(md, title); else -> render(md, title) } }
            catch (e: Exception) { return "Couldn't make the $f: ${e.message}. Tell the user plainly." to null }
        val name = DocFiles.fileName(filename, title, f)
        val local = runCatching { java.io.File(ctx.filesDir, "media/docs").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) } }.getOrNull()
        return SaveFileAndroid.save(ctx, SaveFile.Plan(name, mimes.getValue(f), "", bytes)) to local?.absolutePath
    }

    /** "Making the PDF" / "Making the Word document" / "Making the slides": the step row. Pure. */
    fun step(args: JSONObject): String = when (args.optString("format").lowercase()) { "docx" -> "Making the Word document"; "pptx" -> "Making the slides"; else -> "Making the PDF" }

    /** The chat's assistant texts, oldest first: the model's history, then what the chat shows (a blueprint's result lands there). */
    fun texts(history: org.json.JSONArray, shown: List<String>): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until history.length()) history.optJSONObject(i)?.takeIf { it.optString("role") == "assistant" }?.let { (it.opt("content") as? String)?.let { c -> out += c } }
        return out + shown
    }

    /** The tool: make the file from [args] (or the newest document in [texts]). The result ends with the card to show it. */
    fun run(ctx: Context, args: JSONObject, texts: List<String>): String {
        val md = args.optString("markdown").ifBlank { null } ?: DocFiles.pickSource(texts) { SaveFileAndroid.read(ctx, it) }
            ?: return "Nothing to turn into a file: no document or long answer in this chat yet. Pass markdown with the content."
        val (line, local) = save(ctx, md, args.optString("title"), args.optString("filename"), args.optString("format", "pdf"))
        if (local == null) return line
        val card = com.blaze.assistant.orchestra.DocCard.block(args.optString("title").ifBlank { DocFiles.titleOf(md, "Document") }, local, md)
        return "$line Put this card in your reply so they can open and share it:\n$card"
    }
}
