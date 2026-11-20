package dev.penguinxaml.xaml

/**
 * A small, forgiving reader for XAML text that remembers where everything is, so the designer can change exactly the
 * characters it needs and leave the rest of the user's formatting alone.
 *
 * Object elements (`<Button>`) get a design id: their position among the document's object elements in document order,
 * skipping property elements (`<Grid.RowDefinitions>`). The design helper counts the same way.
 */
class XamlAttribute(
    val name: String,
    val value: String,
    /** Where the attribute starts (its name) and ends (after the closing quote). */
    val start: Int,
    val end: Int,
    /** The value's characters, between the quotes. */
    val valueStart: Int,
    val valueEnd: Int,
)

class XamlElement(
    val name: String,
    val start: Int,
    val parent: XamlElement?,
) {
    /** End of the start tag (after `>` or `/>`). */
    var startTagEnd: Int = -1
    /** Where the closing `/>` or `>` of the start tag begins. */
    var startTagCloseAt: Int = -1
    var selfClosing: Boolean = false
    /** End of the whole element (after `</Name>` or `/>`). */
    var end: Int = -1
    /** Where `</Name>` starts (equal to [end] for self-closing elements). */
    var endTagStart: Int = -1
    var designId: Int = -1
    val attributes = mutableListOf<XamlAttribute>()
    val children = mutableListOf<XamlElement>()

    val isPropertyElement: Boolean get() = name.contains('.')
    val localName: String get() = name.substringAfter(':')
    fun attr(name: String): XamlAttribute? = attributes.firstOrNull { it.name == name }
    fun attrValue(name: String): String? = attr(name)?.value
    /** Object children only (property elements such as Grid.RowDefinitions are skipped, their contents are not). */
    val objectChildren: List<XamlElement> get() = children.flatMap { if (it.isPropertyElement) emptyList() else listOf(it) }
    fun propertyElement(name: String): XamlElement? = children.firstOrNull { it.isPropertyElement && it.localName == "$localName.$name" }

    /** The label shown in the outline and the Properties header: `Button "OkButton"` or just `Button`. */
    val displayName: String get() = (attrValue("x:Name") ?: attrValue("Name"))?.let { "$localName $it" } ?: localName
    val xName: String? get() = attrValue("x:Name") ?: attrValue("Name")

    fun descendantsAndSelf(): Sequence<XamlElement> = sequence {
        yield(this@XamlElement)
        for (c in children) yieldAll(c.descendantsAndSelf())
    }
    fun isAncestorOf(other: XamlElement): Boolean {
        var p = other.parent
        while (p != null) { if (p === this) return true; p = p.parent }
        return false
    }
}

class XamlDocument(val text: String, val root: XamlElement?) {
    val objects: List<XamlElement> = root?.descendantsAndSelf()?.filter { !it.isPropertyElement }?.toList() ?: emptyList()
    fun byId(id: Int): XamlElement? = objects.getOrNull(id)
    /** The innermost object element whose text contains [offset] (for following the caret). */
    fun elementAt(offset: Int): XamlElement? = objects.lastOrNull { offset >= it.start && offset < it.end }

    companion object {
        fun parse(text: String): XamlDocument = XamlParser(text).parse()
    }
}

class XamlParseException(message: String, val offset: Int) : Exception(message)

private class XamlParser(private val s: String) {
    private var i = 0

    fun parse(): XamlDocument {
        var root: XamlElement? = null
        val stack = ArrayDeque<XamlElement>()
        var nextId = 0
        while (i < s.length) {
            val lt = s.indexOf('<', i)
            if (lt < 0) break
            i = lt
            when {
                s.startsWith("<!--", i) -> i = s.indexOf("-->", i).let { if (it < 0) s.length else it + 3 }
                s.startsWith("<![CDATA[", i) -> i = s.indexOf("]]>", i).let { if (it < 0) s.length else it + 3 }
                s.startsWith("<?", i) || s.startsWith("<!", i) -> i = s.indexOf('>', i).let { if (it < 0) s.length else it + 1 }
                s.startsWith("</", i) -> {
                    val close = s.indexOf('>', i)
                    if (close < 0) throw XamlParseException("A closing tag is not finished.", i)
                    val name = s.substring(i + 2, close).trim()
                    val top = stack.removeLastOrNull() ?: throw XamlParseException("</$name> has no matching start tag.", i)
                    if (top.name != name) throw XamlParseException("</$name> doesn't match <${top.name}>.", i)
                    top.endTagStart = i
                    top.end = close + 1
                    i = close + 1
                }
                else -> {
                    val start = i
                    i++
                    val name = readName()
                    if (name.isEmpty()) throw XamlParseException("A tag has no name.", start)
                    val el = XamlElement(name, start, stack.lastOrNull())
                    readAttributes(el)
                    if (!el.isPropertyElement) el.designId = nextId++
                    if (stack.isEmpty()) {
                        if (root != null) throw XamlParseException("There is more than one root element.", start)
                        root = el
                    } else stack.last().children += el
                    if (el.selfClosing) { el.end = el.startTagEnd; el.endTagStart = el.startTagEnd } else stack.addLast(el)
                }
            }
        }
        if (stack.isNotEmpty()) throw XamlParseException("<${stack.last().name}> is never closed.", stack.last().start)
        return XamlDocument(s, root)
    }

    private fun readName(): String {
        val b = i
        while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/' && s[i] != '=') i++
        return s.substring(b, i)
    }

    private fun readAttributes(el: XamlElement) {
        while (true) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) throw XamlParseException("<${el.name}> is not finished.", el.start)
            if (s[i] == '>') { el.startTagCloseAt = i; i++; el.startTagEnd = i; return }
            if (s.startsWith("/>", i)) { el.startTagCloseAt = i; i += 2; el.startTagEnd = i; el.selfClosing = true; return }
            val aStart = i
            val name = readName()
            if (name.isEmpty()) throw XamlParseException("Unexpected character in <${el.name}>.", i)
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || s[i] != '=') throw XamlParseException("$name has no value.", aStart)
            i++
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || (s[i] != '"' && s[i] != '\'')) throw XamlParseException("$name's value has no quotes.", aStart)
            val q = s[i]
            val vStart = i + 1
            val vEnd = s.indexOf(q, vStart)
            if (vEnd < 0) throw XamlParseException("$name's value is never closed.", aStart)
            i = vEnd + 1
            el.attributes += XamlAttribute(name, unescape(s.substring(vStart, vEnd)), aStart, i, vStart, vEnd)
        }
    }

    private fun unescape(v: String) = if ('&' !in v) v else
        v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
