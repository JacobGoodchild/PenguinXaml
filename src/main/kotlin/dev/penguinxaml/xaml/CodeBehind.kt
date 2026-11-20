package dev.penguinxaml.xaml

/** Adds event handlers to a window's code-behind (`MainWindow.axaml.cs`) without disturbing the rest of the file. */
object CodeBehind {
    /** Where the handler is (offset of its body's first line), and the edits that add it (empty if it already exists). */
    data class Result(val edits: List<TextEdit>, val caretAfter: Int?)

    fun hasMethod(cs: String, method: String) = Regex("""\bvoid\s+${Regex.escape(method)}\s*\(""").containsMatchIn(cs)

    fun methodOffset(cs: String, method: String): Int? = Regex("""\bvoid\s+${Regex.escape(method)}\s*\(""").find(cs)?.range?.first

    /**
     * Adds `private void [method](object? sender, [argsType] e) { }` at the end of class [className].
     * Returns null when the class can't be found.
     */
    fun addHandler(cs: String, className: String, method: String, argsType: String = "RoutedEventArgs", argsNamespace: String? = "Avalonia.Interactivity"): Result? {
        methodOffset(cs, method)?.let { return Result(emptyList(), it) }
        val shortName = className.substringAfterLast('.')
        val decl = Regex("""\bclass\s+${Regex.escape(shortName)}\b""").find(cs) ?: return null
        val open = cs.indexOf('{', decl.range.last)
        if (open < 0) return null
        val close = matchingBrace(cs, open) ?: return null
        val nl = if (cs.contains("\r\n")) "\r\n" else "\n"
        val classIndent = XamlEdits.indentOf(cs, decl.range.first).let { lineIndent(cs, decl.range.first) }
        val unit = guessUnit(cs)
        val mi = classIndent + unit
        // Insert on the line of the closing brace, before it.
        val lineStart = cs.lastIndexOf('\n', close - 1) + 1
        val onlyIndentBefore = cs.substring(lineStart, close).isBlank()
        val beforeBody = cs.substring(open + 1, if (onlyIndentBefore) lineStart else close)
        val sep = if (beforeBody.isBlank()) "" else nl
        val method = "$sep$mi" + "private void $method(object? sender, $argsType e)$nl$mi{$nl$mi$unit$nl$mi}$nl"
        val at = if (onlyIndentBefore) lineStart else close
        val text = if (onlyIndentBefore) method else nl + method + classIndent
        val edits = mutableListOf(TextEdit(at, at, text))
        var shift = 0
        if (argsNamespace != null && !Regex("""^\s*using\s+${Regex.escape(argsNamespace)}\s*;""", RegexOption.MULTILINE).containsMatchIn(cs)) {
            val lastUsing = Regex("""^\s*using\s+[\w.]+\s*;[^\n]*\n""", RegexOption.MULTILINE).findAll(cs).lastOrNull()
            val pos = lastUsing?.range?.last?.plus(1) ?: 0
            val line = "using $argsNamespace;$nl" + if (lastUsing == null) nl else ""
            edits += TextEdit(pos, pos, line)
            if (pos <= at) shift = line.length
        }
        // Caret: the empty line inside the new method.
        val bodyLine = at + shift + text.indexOf("{$nl") + 1 + nl.length + mi.length + unit.length
        return Result(edits, bodyLine)
    }

    private fun lineIndent(t: String, offset: Int): String {
        val ls = t.lastIndexOf('\n', offset - 1) + 1
        return t.substring(ls).takeWhile { it == ' ' || it == '\t' }
    }

    private fun guessUnit(cs: String): String =
        if (Regex("""^\t""", RegexOption.MULTILINE).containsMatchIn(cs)) "\t" else "    "

    /** The `}` that closes the `{` at [open], skipping strings, characters and comments. */
    fun matchingBrace(s: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("//", i) -> { i = s.indexOf('\n', i).let { if (it < 0) s.length else it }; continue }
                s.startsWith("/*", i) -> { i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }; continue }
                c == '"' || c == '\'' -> {
                    val verbatim = i > 0 && s[i - 1] == '@'
                    i++
                    while (i < s.length && s[i] != c) { if (s[i] == '\\' && !verbatim) i++; i++ }
                }
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** A handler name that isn't taken yet: `OkButton_Click`, or `Button_Click`, `Button_Click_1`, ... */
    fun handlerName(cs: String, element: XamlElement, event: String): String {
        val base = "${element.xName ?: element.localName}_$event"
        if (!hasMethod(cs, base)) return base
        var n = 1
        while (hasMethod(cs, "${base}_$n")) n++
        return "${base}_$n"
    }
}
