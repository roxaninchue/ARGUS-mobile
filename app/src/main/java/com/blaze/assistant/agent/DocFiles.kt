package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * Unreleased after 0.54.1 (owner, 21 Nov 2026: "Send PDF" looped and stopped twice on "Now let me write the PDF script"; then
 * "can you make a docs and PowerPoint maker too?"). The pure half of make_document: Markdown → blocks → a Word file or a
 * PowerPoint deck, written as Office Open XML in a zip with no library and no terminal, plus the checks that hold a turn to
 * the file the user asked for ([wantsFile], [madeIn]). The PDF is drawn on the phone in [MakeDoc]. Tested in DocFilesTest.
 */
object DocFiles {
    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Para(val text: String) : Block()
        data class Item(val text: String, val mark: String, val depth: Int) : Block()
        data class Quote(val text: String) : Block()
        data class Code(val text: String) : Block()
        data class Table(val rows: List<List<String>>) : Block()
        object Rule : Block()
    }

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)([-*+•]|\d{1,3}[.)])\s+(.*)$""")
    private val ruleRx = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    /** Markdown → blocks. Charts and other ```json cards become a one-line note (the picture lives in the app). Pure. */
    fun blocks(md: String): List<Block> {
        val out = ArrayList<Block>()
        val lines = md.replace("\r\n", "\n").split('\n')
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.setLength(0) }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val t = l.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val lang = t.removePrefix("```").trim().lowercase()
                    val body = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { body.append(lines[i]).append('\n'); i++ }
                    when {
                        lang.startsWith("chart") -> out += Block.Quote("Chart: " + (runCatching { JSONObject(body.toString()).optString("title") }.getOrNull()?.ifBlank { null } ?: "see it in Argus"))
                        lang in setOf("doc", "card", "gallery", "followups", "chips") -> Unit
                        body.isNotBlank() -> out += Block.Code(body.toString().trimEnd())
                    }
                }
                t.isEmpty() -> flush()
                heading.matches(t) -> { flush(); val m = heading.find(t)!!; out += Block.Heading(m.groupValues[1].length, m.groupValues[2]) }
                ruleRx.matches(t) -> { flush(); out += Block.Rule }
                t.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flush()
                    val rows = ArrayList<List<String>>()
                    rows += cells(t); i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                    out += Block.Table(rows); continue
                }
                t.startsWith(">") -> { flush(); out += Block.Quote(t.trimStart('>', ' ')) }
                bullet.matches(l) -> {
                    flush(); val m = bullet.find(l)!!
                    val mk = m.groupValues[2].let { if (it[0].isDigit()) it.trimEnd(')').trimEnd('.') + "." else "•" }
                    out += Block.Item(m.groupValues[3].trim(), mk, (m.groupValues[1].replace("\t", "  ").length / 2).coerceAtMost(3))
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** One inline span: its text and whether it is bold / italic / code. */
    data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    private val inlineRx = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?!\*)|<(https?://[^>\s]+)>""")

    /** Inline Markdown → runs: **bold**, *italic*, `code`; a link keeps its words with the address after them. Pure. */
    fun inline(s: String): List<Run> {
        val out = ArrayList<Run>(); var at = 0
        for (m in inlineRx.findAll(s)) {
            if (m.range.first > at) out += Run(s.substring(at, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> Run(g[1], bold = true)
                g[2].isNotEmpty() -> Run(g[2], bold = true)
                g[3].isNotEmpty() -> Run(g[3], code = true)
                g[4].isNotEmpty() -> Run(if (g[4] == g[5]) g[5] else "${g[4]} (${g[5]})")
                g[6].isNotEmpty() -> Run(g[6], italic = true)
                else -> Run(g[7])
            }
            at = m.range.last + 1
        }
        if (at < s.length) out += Run(s.substring(at))
        return out.filter { it.text.isNotEmpty() }
    }

    /** The document's title: its first # heading, else [fallback]. Pure. */
    fun titleOf(md: String, fallback: String): String =
        Regex("(?m)^#\\s+(.+)$").find(md)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: fallback.trim().ifBlank { "Document" }

    /** "how-anthropic-was-created.pdf" from a title or a given name, with [ext] (pdf, docx, pptx). Pure. */
    fun fileName(given: String, title: String, ext: String = "pdf"): String {
        val base = given.trim().substringAfterLast('/').replace(Regex("""(?i)\.(pdf|docx?|pptx?|md|txt)$"""), "").ifBlank { title }
        return base.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifBlank { "document" } + "." + ext
    }

    // The ask is for a FILE (make / send / export / save one, "as a PDF", "Send PDF", "make slides"), not a question about one they attached.
    private const val KIND = """(?:pdf|word(?:\s+(?:doc(?:ument)?|file))?|docx|doc|power\s?point|pptx|ppt|slides?|slide\s?deck|deck|presentation)"""
    // "a PDF maker", "the PowerPoint app": about a tool, not a file to make
    private const val END = """\b(?!\s+(?:makers?|tools?|apps?|features?|editors?|viewers?|readers?|templates?|support|generators?|converters?|software|programs?))"""
    private val howRx = Regex("""(?i)^\s*(?:how|why|what|which|where|when|is|are|does|do|did)\b""")
    private val askRx = listOf(
        Regex("""(?i)\b(?:as|into|in|to)\s+(?:a\s+|an\s+|the\s+)?(?:nice\s+|proper\s+|cited\s+|full\s+|short\s+)?$KIND$END"""),
        Regex("""(?i)\b(?:make|send|create|export|save|give|generate|build|write|get|prepare|produce|download|share|draft|put together)\s+(?:me\s+|us\s+)?(?:it\s+|this\s+|that\s+)?(?:as\s+)?(?:a\s+|an\s+|the\s+|some\s+)?(?:[\w-]+\s+){0,3}$KIND$END"""),
        Regex("""(?i)^\s*(?:send\s+|make\s+|a\s+|the\s+)?$KIND(?:\s+(?:please|version|file|copy|now|it))*\s*[.!]*\s*$"""),
        Regex("""(?i)\b$KIND\s+(?:please|version\s+(?:please|of))\b"""))
    private val notAsk = Regex("""(?i)\b(?:what(?:'s| is)? in|read|summari[sz]e|explain|translate|open|look at|check|review)\s+(?:this|the|that|my)\s+(?:[\w-]+\s+)?$KIND\b""")

    /** The file [ask] wants made: "pdf", "docx" or "pptx", else null. Pure. */
    fun wantsFile(ask: String): String? {
        if (notAsk.containsMatchIn(ask) || howRx.containsMatchIn(ask)) return null
        val m = askRx.firstNotNullOfOrNull { it.find(ask) } ?: return null
        val k = m.value.lowercase()
        return when {
            Regex("pdf").containsMatchIn(k) -> "pdf"
            Regex("""power\s?point|pptx|ppt|slide|deck|presentation""").containsMatchIn(k) -> "pptx"
            else -> "docx"
        }
    }

    /** True when a turn's words or saved path show a file of [ext] was made (make_document, or the terminal's /root/phone/….pdf). Pure. */
    fun madeIn(ext: String, vararg texts: String?): Boolean = texts.any { it != null && Regex("""(?i)[\w./-]+\.$ext\b""").containsMatchIn(it) }

    /** The newest document in [texts] (newest last): a saved doc card's file, else the newest long answer. */
    fun pickSource(texts: List<String>, readDoc: (String) -> String?): String? {
        for (t in texts.asReversed()) {
            Regex("```doc\\s*\\n([\\s\\S]*?)```").find(t)?.groupValues?.get(1)?.let { j ->
                runCatching { JSONObject(j).optString("path") }.getOrNull()?.takeIf { it.endsWith(".md") }?.let { p -> readDoc(p)?.takeIf { it.isNotBlank() }?.let { return it } }
            }
            if (t.length >= 600) return t
        }
        return null
    }


    // ---- Word (.docx) and PowerPoint (.pptx): plain Office Open XML in a zip, written here, no library ----

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    private fun zip(parts: List<Pair<String, String>>): ByteArray {
        val bo = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bo).use { z -> for ((n, x) in parts) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(x.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return bo.toByteArray()
    }

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun wRuns(text: String, bold: Boolean = false): String = inline(text).joinToString("") { r ->
        // CT_RPr order: rFonts, b, i
        val pr = buildString { if (r.code) append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>"); if (r.bold || bold) append("<w:b/>"); if (r.italic) append("<w:i/>") }
        "<w:r>" + (if (pr.isEmpty()) "" else "<w:rPr>$pr</w:rPr>") + "<w:t xml:space=\"preserve\">${esc(r.text)}</w:t></w:r>"
    }

    private fun wPara(style: String?, runs: String, ind: Int = 0): String =
        "<w:p>" + (if (style != null || ind > 0) "<w:pPr>" + (style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: "") + (if (ind > 0) "<w:ind w:left=\"$ind\" w:hanging=\"300\"/>" else "") + "</w:pPr>" else "") + runs + "</w:p>"

    /** [md] → a .docx: Title, Heading 1-3, paragraphs, bullets and numbers, quotes, code, tables. Pure. */
    fun docx(md: String, title: String): ByteArray {
        val body = StringBuilder()
        for (b in blocks(md)) body.append(when (b) {
            is Block.Heading -> wPara(if (b.level == 1) "Title" else "Heading${(b.level - 1).coerceIn(1, 3)}", wRuns(b.text))
            is Block.Para -> wPara(null, wRuns(b.text))
            is Block.Item -> wPara("ListParagraph", "<w:r><w:t xml:space=\"preserve\">${esc(b.mark)}\t</w:t></w:r>" + wRuns(b.text), 560 + b.depth * 360)
            is Block.Quote -> wPara("Quote", wRuns(b.text))
            is Block.Code -> b.text.split('\n').joinToString("") { wPara("Code", "<w:r><w:t xml:space=\"preserve\">${esc(it)}</w:t></w:r>") }
            is Block.Rule -> "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"BBBBBB\"/></w:pBdr></w:pPr></w:p>"
            is Block.Table -> {
                val cols = b.rows.maxOf { it.size }.coerceAtLeast(1); val cw = 9000 / cols
                "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid>" +
                    (1..cols).joinToString("") { "<w:gridCol w:w=\"$cw\"/>" } + "</w:tblGrid>" +
                    b.rows.mapIndexed { r, row -> "<w:tr>" + (0 until cols).joinToString("") { c ->
                        "<w:tc><w:tcPr><w:tcW w:w=\"$cw\" w:type=\"dxa\"/>" + (if (r == 0) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EDEDF2\"/>" else "") + "</w:tcPr>" +
                            wPara(null, wRuns(row.getOrElse(c) { "" }, r == 0)) + "</w:tc>" } + "</w:tr>" }.joinToString("") +
                    "</w:tbl>" + wPara(null, "")
            }
        })
        val doc = XML + "<w:document xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\"><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1200\" w:right=\"1200\" w:bottom=\"1200\" w:left=\"1200\" w:header=\"600\" w:footer=\"600\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        fun st(id: String, name: String, pPr: String, rPr: String, type: String = "paragraph") =
            "<w:style w:type=\"$type\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr>$pPr</w:pPr><w:rPr>$rPr</w:rPr></w:style>"
        val styles = XML + "<w:styles xmlns:w=\"$W_NS\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:lang w:val=\"en-GB\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"140\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            st("Title", "Title", "<w:spacing w:after=\"240\"/>", "<w:b/><w:color w:val=\"1C1C20\"/><w:sz w:val=\"44\"/>") +
            st("Heading1", "heading 1", "<w:keepNext/><w:spacing w:before=\"320\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/>", "<w:b/><w:color w:val=\"2B3A67\"/><w:sz w:val=\"32\"/>") +
            st("Heading2", "heading 2", "<w:keepNext/><w:spacing w:before=\"240\" w:after=\"100\"/><w:outlineLvl w:val=\"1\"/>", "<w:b/><w:color w:val=\"2B3A67\"/><w:sz w:val=\"26\"/>") +
            st("Heading3", "heading 3", "<w:keepNext/><w:spacing w:before=\"200\" w:after=\"80\"/><w:outlineLvl w:val=\"2\"/>", "<w:b/><w:sz w:val=\"23\"/>") +
            st("ListParagraph", "List Paragraph", "<w:spacing w:after=\"60\"/>", "") +
            st("Quote", "Quote", "<w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"8\" w:color=\"BBBBC4\"/></w:pBdr><w:ind w:left=\"420\"/>", "<w:i/><w:color w:val=\"55555E\"/>") +
            st("Code", "Code", "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F4F4F7\"/><w:spacing w:after=\"0\"/>", "<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/><w:sz w:val=\"18\"/>") +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblBorders>" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"C8C8D0\"/>" } +
            "</w:tblBorders><w:tblCellMar><w:left w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style></w:styles>"
        val core = XML + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>${esc(title)}</dc:title><dc:creator>Argus</dc:creator></cp:coreProperties>"
        return zip(listOf(
            "[Content_Types].xml" to XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>",
            "_rels/.rels" to XML + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"word/document.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to XML + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$REL/styles\" Target=\"styles.xml\"/></Relationships>",
            "word/document.xml" to doc, "word/styles.xml" to styles, "docProps/core.xml" to core))
    }

    /** One slide: a title and its lines (a line is its text and its depth; depth -1 = a sub-heading), or a table. */
    data class Slide(val title: String, val lines: List<Pair<String, Int>> = emptyList(), val table: List<List<String>>? = null, val subtitle: String = "")

    /** Most lines a slide shows before the rest carries on to the next one. */
    const val SLIDE_LINES = 7

    /** [md] → slides: the # title (and the first paragraph under it) is the cover; every ## starts a slide; a long slide carries on. Pure. */
    fun slides(md: String, title: String): List<Slide> {
        val bs = blocks(md)
        val out = ArrayList<Slide>()
        var cur: String? = null; val lines = ArrayList<Pair<String, Int>>()
        var subtitle = ""; var seenBody = false; var emitted = false
        fun plain(s: String) = inline(s).joinToString("") { it.text }
        fun push() {
            val t = cur ?: return
            if (lines.isEmpty()) { if (!emitted) out += Slide(t); emitted = true; return }
            lines.chunked(SLIDE_LINES).forEachIndexed { i, ch -> out += Slide(if (i == 0 && !emitted) t else "$t (cont.)", ch.toList()) }
            lines.clear(); emitted = true
        }
        for (b in bs) {
            if (b is Block.Heading && b.level == 1 && out.isEmpty() && cur == null) continue
            if (cur == null && !seenBody && b is Block.Para) { subtitle = plain(b.text).take(160); seenBody = true; continue }
            when (b) {
                is Block.Heading -> if (b.level <= 2) { push(); cur = plain(b.text); emitted = false } else lines += plain(b.text) to -1
                is Block.Table -> {
                    if (lines.isNotEmpty()) push()
                    val t = cur ?: title
                    b.rows.drop(1).chunked(8).forEachIndexed { i, rs -> out += Slide(if (i == 0 && !emitted) t else "$t (cont.)", table = listOf(b.rows.first()) + rs) }
                    cur = t; emitted = true
                }
                is Block.Para -> { if (cur == null) cur = title; plain(b.text).let { lines += (if (it.length > 220) it.take(217).trimEnd() + "…" else it) to 0 } }
                is Block.Item -> { if (cur == null) cur = title; lines += plain(b.text) to b.depth.coerceAtMost(2) }
                is Block.Quote -> { if (cur == null) cur = title; lines += ("\u201c" + plain(b.text) + "\u201d") to 0 }
                is Block.Code -> { if (cur == null) cur = title; b.text.lines().take(SLIDE_LINES).forEach { lines += it to 1 } }
                is Block.Rule -> Unit
            }
            seenBody = true
        }
        push()
        return listOf(Slide(title, subtitle = subtitle)) + out
    }

    private const val EMU = 12700L   // per point
    private fun aRuns(s: String, size: Int, bold: Boolean = false, color: String = "1C1C20"): String = inline(s).joinToString("") { r ->
        "<a:r><a:rPr lang=\"en-GB\" sz=\"${size * 100}\" b=\"${if (bold || r.bold) 1 else 0}\" i=\"${if (r.italic) 1 else 0}\" dirty=\"0\">" +
            "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>" + (if (r.code) "<a:latin typeface=\"Consolas\"/>" else "") + "</a:rPr><a:t>${esc(r.text)}</a:t></a:r>"
    }

    private fun box(id: Int, name: String, x: Int, y: Int, w: Int, h: Int, paras: String, anchor: String = "t") =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"${x * EMU}\" y=\"${y * EMU}\"/>" +
            "<a:ext cx=\"${w * EMU}\" cy=\"${h * EMU}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>"

    private fun slideXml(s: Slide, n: Int, total: Int, cover: Boolean): String {
        val shapes = StringBuilder()
        // 960 x 540 pt (16:9)
        shapes.append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Band\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
            "<a:ext cx=\"${(if (cover) 960 else 14) * EMU}\" cy=\"${540 * EMU}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:solidFill><a:srgbClr val=\"2B3A67\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>")
        if (cover) {
            shapes.append(box(3, "Title", 72, 170, 816, 140, "<a:p>${aRuns(s.title, 40, true, "FFFFFF")}</a:p>", "b"))
            if (s.subtitle.isNotBlank()) shapes.append(box(4, "Subtitle", 72, 320, 816, 110, "<a:p>${aRuns(s.subtitle, 18, color = "DDE3F5")}</a:p>"))
        } else {
            shapes.append(box(3, "Title", 48, 28, 864, 70, "<a:p>${aRuns(s.title, 28, true, "2B3A67")}</a:p>", "b"))
            val t = s.table
            if (t != null) {
                val cols = t.maxOf { it.size }.coerceAtLeast(1); val cw = 864 / cols; val rh = 34
                val size = if (cols > 4 || t.size > 6) 12 else 14
                shapes.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"4\" name=\"Table\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>" +
                    "<p:xfrm><a:off x=\"${48 * EMU}\" y=\"${112 * EMU}\"/><a:ext cx=\"${cw * cols * EMU}\" cy=\"${rh * t.size * EMU}\"/></p:xfrm><a:graphic>" +
                    "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr firstRow=\"1\" bandRow=\"1\"/><a:tblGrid>" +
                    (1..cols).joinToString("") { "<a:gridCol w=\"${cw * EMU}\"/>" } + "</a:tblGrid>" +
                    t.mapIndexed { r, row -> "<a:tr h=\"${rh * EMU}\">" + (0 until cols).joinToString("") { c ->
                        "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p>${aRuns(row.getOrElse(c) { "" }, size, r == 0, if (r == 0) "FFFFFF" else "1C1C20")}</a:p></a:txBody>" +
                            "<a:tcPr><a:solidFill><a:srgbClr val=\"${if (r == 0) "2B3A67" else if (r % 2 == 0) "EEF0F6" else "FFFFFF"}\"/></a:solidFill></a:tcPr></a:tc>" } + "</a:tr>" }.joinToString("") +
                    "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
            } else if (s.lines.isNotEmpty()) {
                val size = when { s.lines.size > 6 || s.lines.sumOf { it.first.length } > 600 -> 16; s.lines.size > 4 -> 18; else -> 20 }
                val paras = s.lines.joinToString("") { (text, depth) ->
                    if (depth < 0) "<a:p><a:pPr><a:spcBef><a:spcPts val=\"600\"/></a:spcBef></a:pPr>${aRuns(text, size, true, "2B3A67")}</a:p>"
                    else "<a:p><a:pPr marL=\"${(22 + depth * 22) * EMU}\" indent=\"-${16 * EMU}\"><a:spcBef><a:spcPts val=\"500\"/></a:spcBef><a:buFont typeface=\"Arial\"/><a:buChar char=\"${if (depth == 0) "•" else "–"}\"/></a:pPr>${aRuns(text, size - depth * 2)}</a:p>"
                }
                shapes.append(box(4, "Body", 48, 108, 864, 392, paras))
            }
        }
        if (!cover) shapes.append(box(9, "Number", 860, 500, 70, 28, "<a:p><a:pPr algn=\"r\"/>${aRuns("$n / $total", 10, color = "8A8A96")}</a:p>"))
        return XML + "<p:sld xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>$shapes</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private val THEME = XML + "<a:theme xmlns:a=\"$A_NS\" name=\"Argus\"><a:themeElements><a:clrScheme name=\"Argus\">" +
        "<a:dk1><a:srgbClr val=\"1C1C20\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"2B3A67\"/></a:dk2><a:lt2><a:srgbClr val=\"EEF0F6\"/></a:lt2>" +
        listOf("2B3A67", "4F6DC8", "E0803C", "3BA47A", "9B59B6", "C0392B").mapIndexed { i, c -> "<a:accent${i + 1}><a:srgbClr val=\"$c\"/></a:accent${i + 1}>" }.joinToString("") +
        "<a:hlink><a:srgbClr val=\"4F6DC8\"/></a:hlink><a:folHlink><a:srgbClr val=\"9B59B6\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"Argus\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Argus\">" +
        "<a:fillStyleLst>" + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3) + "</a:fillStyleLst>" +
        "<a:lnStyleLst>" + "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>".repeat(3) + "</a:lnStyleLst>" +
        "<a:effectStyleLst>" + "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3) + "</a:effectStyleLst>" +
        "<a:bgFillStyleLst>" + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3) + "</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"

    private fun tree() = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>"

    /** [md] → a 16:9 .pptx: a cover, one slide per ## section (long ones carry on), tables as real tables. Pure. */
    fun pptx(md: String, title: String): ByteArray {
        val ss = slides(md, title)
        val parts = ArrayList<Pair<String, String>>()
        val ct = StringBuilder("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        val presRels = StringBuilder("<Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/><Relationship Id=\"rId2\" Type=\"$REL/theme\" Target=\"theme/theme1.xml\"/>")
        val ids = StringBuilder()
        ss.forEachIndexed { i, s ->
            val n = i + 1
            parts += "ppt/slides/slide$n.xml" to slideXml(s, n, ss.size, i == 0)
            parts += "ppt/slides/_rels/slide$n.xml.rels" to XML + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$REL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"
            ct.append("<Override PartName=\"/ppt/slides/slide$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            presRels.append("<Relationship Id=\"rId${n + 10}\" Type=\"$REL/slide\" Target=\"slides/slide$n.xml\"/>")
            ids.append("<p:sldId id=\"${255 + n}\" r:id=\"rId${n + 10}\"/>")
        }
        ct.append("</Types>")
        parts += "[Content_Types].xml" to XML + ct
        parts += "_rels/.rels" to XML + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>"
        parts += "docProps/core.xml" to XML + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>${esc(title)}</dc:title><dc:creator>Argus</dc:creator></cp:coreProperties>"
        parts += "ppt/presentation.xml" to XML + "<p:presentation xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx=\"${960 * EMU}\" cy=\"${540 * EMU}\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"
        parts += "ppt/_rels/presentation.xml.rels" to XML + "<Relationships xmlns=\"$PKG\">$presRels</Relationships>"
        parts += "ppt/slideMasters/slideMaster1.xml" to XML + "<p:sldMaster xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\">${tree()}" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>"
        parts += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to XML + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$REL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$REL/theme\" Target=\"../theme/theme1.xml\"/></Relationships>"
        parts += "ppt/slideLayouts/slideLayout1.xml" to XML + "<p:sldLayout xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\" type=\"blank\" preserve=\"1\">${tree()}" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
        parts += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to XML + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"
        parts += "ppt/theme/theme1.xml" to THEME
        return zip(parts)
    }
}
