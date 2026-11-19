package com.argus.assistant.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 2026-11-18 (owner: a NET103 PDF chat forgot its first topic). A PDF reaches the model as page pictures, and pictures leave
 * the prompt: saved history keeps only "[n image(s) were shared here earlier]", and older turns drop out of the window. The
 * text that was in those pictures was gone for good. Now an attached PDF is kept on the phone (`files/pdfs/<id>.pdf`) and the
 * message carries a short text reference ([ref]) that survives every rung. `pdf_page` shows any page again (1 to [MAX_SPAN]
 * at a time, one picture), including the pages past the first [Attachments.MAX_PDF_PAGES] that were never sent.
 *
 * The pure parts (ids, the reference, parsing, the schema) are unit-tested in PdfPagesTest.
 */
object PdfPages {
    const val TOOL = "pdf_page"
    const val DIR = "pdfs"
    /** Pages one call shows, stacked into one picture (only one picture reaches the next model call). */
    const val MAX_SPAN = 3
    /** PDFs kept on the phone; the oldest go first. */
    const val KEEP = 40
    const val MAX_BYTES = 64L * 1024 * 1024
    /** The note on the picture message, so it isn't mistaken for a screenshot ([VisionFast.pruneShots] drops it later). */
    const val NOTE = "[PDF page(s) the assistant asked to see]"
    /** Marks a shots entry as a PDF page picture (Agent swaps it for [NOTE]). */
    const val MARK = "pdfpage:"

    private val idRe = Regex("""\bpdf:([a-z0-9]{8,32})\b""")
    private val safeId = Regex("""^[a-z0-9]{8,32}$""")

    /** A stable id for a PDF's bytes (the same file attached twice shares one copy). */
    fun idFor(sha256Hex: String): String = sha256Hex.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(16)

    /** The text that travels with the pages in the message and stays after the pictures are gone. */
    fun ref(id: String, name: String, pages: Int, shown: Int): String {
        val clean = name.replace(Regex("[\\r\\n\"]+"), " ").trim().take(80)
        val sent = if (shown <= 0) "no pages sent as pictures" else if (shown >= pages) "all $pages page(s) sent as pictures here" else "pages 1 to $shown sent as pictures here"
        return "[PDF pdf:$id \"$clean\" · $pages page(s) · $sent. When those pictures are gone, or for a later page, " +
            "$TOOL(doc=\"pdf:$id\", page=N) shows a page again.]"
    }

    /** The PDF ids referenced in [text]. */
    fun ids(text: String): List<String> = idRe.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** "pdf:abc…" or "abc…" → the id, or null when it isn't one of ours (no paths, no tricks). */
    fun parseId(doc: String): String? = doc.trim().removePrefix("pdf:").lowercase().takeIf { safeId.matches(it) }

    /** The 0-based pages to show for [page] (1-based) .. [to], clamped to the document and to [MAX_SPAN]. */
    fun span(page: Int, to: Int?, count: Int): IntRange? {
        if (count <= 0 || page < 1 || page > count) return null
        val last = (to ?: page).coerceIn(page, count).coerceAtMost(page + MAX_SPAN - 1)
        return (page - 1) until last
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Look at pages of a PDF from this chat again (the [PDF pdf:… ] note names it): after its pictures have left the chat, " +
            "or a page that was never sent. Up to $MAX_SPAN pages at once; they arrive as one picture.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("doc", JSONObject().put("type", "string").put("description", "pdf:<id> from the note"))
            .put("page", JSONObject().put("type", "integer").put("description", "first page, from 1"))
            .put("to", JSONObject().put("type", "integer").put("description", "last page (optional, at most $MAX_SPAN pages)")))
            .put("required", org.json.JSONArray().put("doc").put("page"))))

    // ---------- on the phone ----------

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    fun file(ctx: Context, id: String): File? = parseId(id)?.let { File(dir(ctx), "$it.pdf") }?.takeIf { it.isFile }

    /** Copies the PDF at [uri] into the phone's PDF store; its id, or null when it can't be kept. */
    suspend fun keep(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val d = dir(ctx)
            val tmp = File(d, "in-" + System.nanoTime() + ".tmp")
            val md = java.security.MessageDigest.getInstance("SHA-256")
            var total = 0L
            ctx.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val k = inp.read(buf); if (k < 0) break; total += k
                    if (total > MAX_BYTES) { out.close(); tmp.delete(); return@withContext null }
                    md.update(buf, 0, k); out.write(buf, 0, k) }
            } } ?: return@withContext null
            val id = idFor(md.digest().joinToString("") { "%02x".format(it) })
            val f = File(d, "$id.pdf")
            if (f.isFile) tmp.delete() else if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
            f.setLastModified(System.currentTimeMillis())
            prune(d)
            id
        } catch (e: Exception) { com.argus.assistant.device.CrashLog.quiet("PdfPages: keep", e); null }
    }

    private fun prune(d: File) {
        val all = d.listFiles { f -> f.name.endsWith(".pdf") }?.sortedByDescending { it.lastModified() } ?: return
        all.drop(KEEP).forEach { it.delete() }
    }

    /**
     * Renders the pages [args] asks for into one picture. Returns the tool's reply and the picture (a [MARK]ed data URL),
     * or no picture with a reason.
     */
    suspend fun show(ctx: Context, args: JSONObject): Pair<String, String?> = withContext(Dispatchers.IO) {
        val id = parseId(args.optString("doc")) ?: return@withContext "That isn't a PDF from this chat. Use the pdf:<id> from its [PDF …] note." to null
        val f = file(ctx, id) ?: return@withContext "That PDF is no longer on the phone. Ask the user to attach it again." to null
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
                val n = pdf.pageCount
                val r = span(args.optInt("page", 1), if (args.has("to")) args.optInt("to") else null, n)
                    ?: return@withContext "That PDF has $n page(s); ask for a page from 1 to $n." to null
                val w = 1000
                val bmps = r.map { i -> pdf.openPage(i).use { p ->
                    val h = (w.toFloat() * p.height / p.width).toInt().coerceIn(1, 4000)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                } }
                val gap = 12
                val out = Bitmap.createBitmap(w, bmps.sumOf { it.height } + gap * (bmps.size - 1), Bitmap.Config.ARGB_8888)
                Canvas(out).apply { drawColor(Color.LTGRAY); var y = 0f; for (b in bmps) { drawBitmap(b, 0f, y, null); y += b.height + gap; b.recycle() } }
                val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
                out.recycle()
                val which = if (r.first == r.last) "Page ${r.first + 1}" else "Pages ${r.first + 1} to ${r.last + 1}"
                "$which of $n is in the next picture." to (MARK + "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            } }
        } catch (e: Exception) { "Couldn't open that PDF: " + (e.message ?: "unreadable").take(120) to null }
    }

    /** A shots entry → (note, url) for the picture message. */
    fun noteAndUrl(shot: String, screenshotNote: String): Pair<String, String> =
        if (shot.startsWith(MARK)) NOTE to shot.removePrefix(MARK) else screenshotNote to shot
}
