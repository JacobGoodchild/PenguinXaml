package dev.penguinxaml.xaml

import dev.penguinxaml.host.RenderedElement
import kotlin.math.roundToInt

/** Where a dragged control will land, worked out before the mouse is released (so it can be shown). */
class DropPlan(
    val target: XamlElement,
    val targetBox: RenderedElement,
    /** Insert before this object child (null: at the end). */
    val index: Int?,
    /** Attributes to set on the dropped control (null removes one). */
    val set: Map<String, String?>,
    /** Short plain-English description, shown while dragging: "Grid, row 1, column 0". */
    val label: String,
    /** The Grid cell or insertion line to highlight, in design pixels: x, y, w, h. */
    val highlight: DoubleArray,
)

object DropPlanner {
    /**
     * Plans a drop at point ([px], [py]) where the control's top-left corner will be at ([left], [top]).
     * [dragged] is the control being moved (it and its children can't be targets), or null for a new control.
     */
    fun plan(doc: XamlDocument, boxes: List<RenderedElement>, px: Double, py: Double, left: Double, top: Double, dragged: XamlElement?): DropPlan? {
        val byId = boxes.associateBy { it.id }
        val candidates = boxes.filter { it.container != null && it.contains(px, py) }
            .mapNotNull { b -> doc.byId(b.id)?.let { it to b } }
            .filter { (el, _) -> dragged == null || (el !== dragged && !dragged.isAncestorOf(el)) }
            .filter { (el, b) -> b.container == "panel" || canTakeContent(doc, el, dragged) }
        // The innermost: the one that appears last in the document among those containing the point.
        val (target, box) = candidates.maxByOrNull { it.first.designId } ?: return null
        val name = target.localName
        val kids = target.objectChildren.filter { it !== dragged }

        if (box.container == "content") {
            return DropPlan(target, box, null, mapOf("Margin" to null, "Grid.Row" to null, "Grid.Column" to null, "Canvas.Left" to null, "Canvas.Top" to null),
                name, doubleArrayOf(box.x, box.y, box.w, box.h))
        }
        when (name) {
            "Grid" -> {
                val (row, rowStart, rowSize) = cell(box.rows, py - box.y, box.h)
                val (col, colStart, colSize) = cell(box.cols, px - box.x, box.w)
                val cx = box.x + colStart
                val cy = box.y + rowStart
                val set = linkedMapOf<String, String?>(
                    "Grid.Row" to (if (row > 0) row.toString() else null),
                    "Grid.Column" to (if (col > 0) col.toString() else null),
                    "HorizontalAlignment" to "Left", "VerticalAlignment" to "Top",
                    "Margin" to "${fmt(maxOf(0.0, left - cx))},${fmt(maxOf(0.0, top - cy))},0,0",
                    "Canvas.Left" to null, "Canvas.Top" to null,
                )
                val where = buildList {
                    if (box.rows.size > 1) add("row $row")
                    if (box.cols.size > 1) add("column $col")
                }
                return DropPlan(target, box, null, set, (listOf(label(target)) + where).joinToString(", "),
                    doubleArrayOf(cx, cy, colSize, rowSize))
            }
            "Canvas" -> {
                return DropPlan(target, box, null, linkedMapOf(
                    "Canvas.Left" to fmt(left - box.x), "Canvas.Top" to fmt(top - box.y),
                    "Margin" to null, "Grid.Row" to null, "Grid.Column" to null,
                ), label(target), doubleArrayOf(box.x, box.y, box.w, box.h))
            }
            else -> {
                // StackPanel, WrapPanel, DockPanel, ...: an insertion point between children.
                val horizontal = name == "StackPanel" && target.attrValue("Orientation") == "Horizontal" || name == "WrapPanel"
                val kidBoxes = kids.mapNotNull { k -> byId[k.designId]?.let { k to it } }
                val i = kidBoxes.indexOfFirst { (_, b) -> if (horizontal) px < b.x + b.w / 2 else py < b.y + b.h / 2 }
                val index = if (i < 0) null else target.objectChildren.indexOf(kidBoxes[i].first)
                val line = when {
                    kidBoxes.isEmpty() -> doubleArrayOf(box.x, box.y, box.w, 2.0)
                    i < 0 -> kidBoxes.last().second.let { if (horizontal) doubleArrayOf(it.x + it.w, it.y, 2.0, it.h) else doubleArrayOf(it.x, it.y + it.h, it.w, 2.0) }
                    else -> kidBoxes[i].second.let { if (horizontal) doubleArrayOf(it.x, it.y, 2.0, it.h) else doubleArrayOf(it.x, it.y, it.w, 2.0) }
                }
                val pos = if (index == null) "at the end" else "position ${kidBoxes.indexOfFirst { it.first === target.objectChildren[index] } + 1}"
                return DropPlan(target, box, index, linkedMapOf(
                    "Margin" to null, "Grid.Row" to null, "Grid.Column" to null, "Canvas.Left" to null, "Canvas.Top" to null,
                    "HorizontalAlignment" to null, "VerticalAlignment" to null,
                ), "${label(target)}, $pos", line)
            }
        }
    }

    /** A content control (Border, Button, the window) can take a control only while it holds nothing else. */
    private fun canTakeContent(doc: XamlDocument, el: XamlElement, dragged: XamlElement?): Boolean {
        if (el.attr("Content") != null || el.attr("Child") != null) return false
        val kids = el.objectChildren.filter { it !== dragged }
        if (kids.isNotEmpty()) return false
        // Text written directly inside (<Button>OK</Button>) is content too.
        if (el.selfClosing) return true
        var text = doc.text.substring(el.startTagEnd, el.endTagStart)
        for (c in el.children.sortedByDescending { it.start }) text = text.removeRange(c.start - el.startTagEnd, c.end - el.startTagEnd)
        return text.isBlank()
    }

    /** Which row (or column) an offset falls in, with that cell's start and size. */
    fun cell(sizes: List<Double>, offset: Double, total: Double): Triple<Int, Double, Double> {
        if (sizes.isEmpty()) return Triple(0, 0.0, total)
        var start = 0.0
        for ((i, s) in sizes.withIndex()) {
            if (offset < start + s || i == sizes.lastIndex) return Triple(i, start, s)
            start += s
        }
        return Triple(0, 0.0, 0.0)
    }

    private fun label(el: XamlElement) = el.displayName

    fun fmt(v: Double): String = v.roundToInt().toString()
}
