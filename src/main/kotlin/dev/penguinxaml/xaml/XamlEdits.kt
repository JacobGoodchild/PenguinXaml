package dev.penguinxaml.xaml

/** One text change: replace [start] until [end] with [text]. */
data class TextEdit(val start: Int, val end: Int, val text: String)

/** Applies edits that don't overlap (in any order) to [text]. Used by tests; the IDE applies them to its document. */
fun applyEdits(text: String, edits: List<TextEdit>): String {
    val sb = StringBuilder(text)
    for (e in edits.sortedByDescending { it.start }) sb.replace(e.start, e.end, e.text)
    return sb.toString()
}

/**
 * Minimal text edits for designer gestures. Each function touches only the characters it must, so the user's line
 * breaks, attribute order and indentation stay as they were.
 */
object XamlEdits {
    fun escape(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /** Sets (or with null, removes) one attribute. */
    fun setAttribute(doc: XamlDocument, el: XamlElement, name: String, value: String?): List<TextEdit> {
        val existing = el.attr(name)
        if (existing != null) {
            if (value == null) {
                var from = existing.start
                while (from > 0 && doc.text[from - 1].let { it == ' ' || it == '\t' }) from--
                // Attribute alone on its line: remove the whole line.
                if (from > 0 && doc.text[from - 1] == '\n' && lineIsBlankAfter(doc.text, existing.end)) from--
                return listOf(TextEdit(from, existing.end, ""))
            }
            if (existing.value == value) return emptyList()
            return listOf(TextEdit(existing.valueStart, existing.valueEnd, escape(value)))
        }
        if (value == null) return emptyList()
        val after = el.attributes.lastOrNull()?.end ?: (el.start + 1 + el.name.length)
        return listOf(TextEdit(after, after, " $name=\"${escape(value)}\""))
    }

    fun setAttributes(doc: XamlDocument, el: XamlElement, values: Map<String, String?>): List<TextEdit> {
        // Several new attributes at the same spot: join them into one insert so their order is kept.
        val inserts = StringBuilder()
        val edits = mutableListOf<TextEdit>()
        for ((k, v) in values) {
            if (el.attr(k) == null) { if (v != null) inserts.append(" $k=\"${escape(v)}\"") }
            else edits += setAttribute(doc, el, k, v)
        }
        if (inserts.isNotEmpty()) {
            val after = el.attributes.lastOrNull()?.end ?: (el.start + 1 + el.name.length)
            edits += TextEdit(after, after, inserts.toString())
        }
        return edits
    }

    /** Removes an element; when it sits on its own lines, those lines go too. */
    fun deleteElement(doc: XamlDocument, el: XamlElement): List<TextEdit> {
        val (from, to) = wholeLines(doc.text, el.start, el.end)
        return listOf(TextEdit(from, to, ""))
    }

    /**
     * Inserts [xml] (one element, no indentation) as a child of [parent], before its object child number [index]
     * (or at the end). Indentation follows the parent's.
     */
    fun insertChild(doc: XamlDocument, parent: XamlElement, xml: String, index: Int? = null): List<TextEdit> {
        val t = doc.text
        val parentIndent = indentOf(t, parent.start)
        val unit = indentUnit(t, parent)
        val childIndent = parentIndent + unit
        val nl = if (t.contains("\r\n")) "\r\n" else "\n"
        val body = xml.lines().joinToString(nl) { if (it.isEmpty()) it else childIndent + it }
        if (parent.selfClosing) {
            val close = parent.startTagCloseAt
            var from = close
            while (from > 0 && t[from - 1] == ' ') from--
            return listOf(TextEdit(from, parent.end, ">$nl$body$nl$parentIndent</${parent.name}>"))
        }
        val kids = parent.objectChildren
        val before = index?.let { kids.getOrNull(it) }
        if (before != null) {
            val lineStart = lineStartIfOnlyIndent(t, before.start)
            return if (lineStart != null) listOf(TextEdit(lineStart, lineStart, "$body$nl"))
            else listOf(TextEdit(before.start, before.start, xml))
        }
        val lineStart = lineStartIfOnlyIndent(t, parent.endTagStart)
        if (lineStart != null) return listOf(TextEdit(lineStart, lineStart, "$body$nl"))
        return listOf(TextEdit(parent.endTagStart, parent.endTagStart, "$nl$body$nl$parentIndent"))
    }

    /** Moves [el] into [target] (before object child [index]): one delete plus one insert, attributes changed in [set]. */
    fun moveElement(doc: XamlDocument, el: XamlElement, target: XamlElement, index: Int?, set: Map<String, String?>): List<TextEdit> {
        require(el !== target && !el.isAncestorOf(target)) { "Can't move an element into itself." }
        val text = elementTextWith(doc, el, set)
        val (from, to) = wholeLines(doc.text, el.start, el.end)
        val insert = insertChild(doc, target, dedent(text), index)
        // The insert may fall inside the deleted lines' span only when it's right next to it; merge in that case.
        return listOf(TextEdit(from, to, "")) + insert
    }

    /** The element's own text with attribute changes applied (child elements untouched). */
    fun elementTextWith(doc: XamlDocument, el: XamlElement, set: Map<String, String?>): String {
        val edits = setAttributes(doc, el, set).map { TextEdit(it.start - el.start, it.end - el.start, it.text) }
        return applyEdits(doc.text.substring(el.start, el.end), edits)
    }

    // ---- Grid rows and columns ---------------------------------------------------------------------------------

    /** The Grid's row (or column) sizes as written, from the short attribute form or the long element form. */
    fun gridDefinitions(grid: XamlElement, rows: Boolean): List<String> {
        val attr = if (rows) "RowDefinitions" else "ColumnDefinitions"
        grid.attrValue(attr)?.let { v -> return v.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        val pe = grid.propertyElement(attr) ?: return emptyList()
        val sizeAttr = if (rows) "Height" else "Width"
        return pe.children.map { it.attrValue(sizeAttr) ?: "*" }
    }

    /** Replaces the Grid's row (or column) sizes, keeping the form the user wrote them in. */
    fun setGridDefinitions(doc: XamlDocument, grid: XamlElement, rows: Boolean, sizes: List<String>): List<TextEdit> {
        val attr = if (rows) "RowDefinitions" else "ColumnDefinitions"
        val pe = grid.propertyElement(attr)
        if (pe == null) return setAttribute(doc, grid, attr, sizes.joinToString(",").ifEmpty { null })
        val defName = if (rows) "RowDefinition" else "ColumnDefinition"
        val sizeAttr = if (rows) "Height" else "Width"
        val edits = mutableListOf<TextEdit>()
        val existing = pe.children
        for ((i, size) in sizes.withIndex()) {
            val def = existing.getOrNull(i)
            if (def != null) edits += setAttribute(doc, def, sizeAttr, size)
        }
        if (sizes.size > existing.size) {
            val xml = sizes.drop(existing.size).joinToString("\n") { "<$defName $sizeAttr=\"$it\" />" }
            edits += insertChild(doc, pe, xml)
        }
        for (def in existing.drop(sizes.size)) edits += deleteElement(doc, def)
        return edits
    }

    // ---- text helpers -------------------------------------------------------------------------------------------

    fun indentOf(t: String, offset: Int): String {
        val ls = t.lastIndexOf('\n', offset - 1) + 1
        var e = ls
        while (e < t.length && (t[e] == ' ' || t[e] == '\t')) e++
        return t.substring(ls, minOf(e, offset))
    }

    private fun indentUnit(t: String, parent: XamlElement): String {
        val p = indentOf(t, parent.start)
        for (c in parent.children) {
            if (lineStartIfOnlyIndent(t, c.start) == null) continue
            val ci = indentOf(t, c.start)
            if (ci.length > p.length && ci.startsWith(p)) return ci.substring(p.length)
        }
        var e = parent.parent
        while (e != null) {
            val ei = indentOf(t, e.start)
            if (p.length > ei.length && p.startsWith(ei)) return p.substring(ei.length)
            e = e.parent
        }
        return "    "
    }

    private fun lineStartIfOnlyIndent(t: String, offset: Int): Int? {
        val ls = t.lastIndexOf('\n', offset - 1) + 1
        return if (t.substring(ls, offset).isBlank()) ls else null
    }

    private fun lineIsBlankAfter(t: String, offset: Int): Boolean {
        val le = t.indexOf('\n', offset).let { if (it < 0) t.length else it }
        return t.substring(offset, le).isBlank() && !t.substring(offset, le).contains('>')
    }

    private fun wholeLines(t: String, start: Int, end: Int): Pair<Int, Int> {
        val ls = lineStartIfOnlyIndent(t, start)
        val le = t.indexOf('\n', end).let { if (it < 0) t.length else it }
        return if (ls != null && t.substring(end, le).isBlank()) ls to minOf(t.length, le + 1) else start to end
    }

    /** Removes the common indentation of an element's continuation lines (its first line has none). */
    private fun dedent(text: String): String {
        val lines = text.lines()
        if (lines.size <= 1) return text
        val common = lines.drop(1).filter { it.isNotBlank() }.minOfOrNull { l -> l.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        // Keep the closing tag's alignment with the opening tag.
        val closing = lines.last().takeWhile { it == ' ' || it == '\t' }.length
        val cut = minOf(common, closing)
        return (listOf(lines[0]) + lines.drop(1).map { if (it.length >= cut) it.substring(cut) else it.trimStart() }).joinToString("\n")
    }
}
