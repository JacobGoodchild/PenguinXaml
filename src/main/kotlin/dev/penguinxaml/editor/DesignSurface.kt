package dev.penguinxaml.editor

import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.penguinxaml.host.RenderedElement
import dev.penguinxaml.toolwindows.Toolbox
import dev.penguinxaml.xaml.DropPlan
import dev.penguinxaml.xaml.DropPlanner
import dev.penguinxaml.xaml.XamlEdits
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.awt.event.*
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The design view: the window as the helper drew it, with selection handles, dragging, resizing and drops on top.
 * Coordinates: "design pixels" are the window's own device-independent pixels; the picture is drawn at [ORIGIN].
 */
class DesignSurface(private val session: DesignerSession, parent: Disposable) : JPanel(), Disposable {
    private val origin get() = JBUI.scale(24)
    private var drag: Drag? = null
    private var plan: DropPlan? = null
    private var ghost: DoubleArray? = null

    private sealed class Drag(val startX: Double, val startY: Double) {
        class Move(sx: Double, sy: Double, val box: RenderedElement) : Drag(sx, sy)
        class Resize(sx: Double, sy: Double, val box: RenderedElement, val handle: Int) : Drag(sx, sy)
        class Grid(sx: Double, sy: Double, val box: RenderedElement, val rows: Boolean, val index: Int) : Drag(sx, sy)
    }

    init {
        isFocusable = true
        background = JBColor.namedColor("Editor.background", JBColor(0xF2F2F2, 0x1E1F22))
        session.addListener(parent) { revalidate(); repaint() }
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pressed(e)
            override fun mouseDragged(e: MouseEvent) = dragged(e)
            override fun mouseReleased(e: MouseEvent) = released(e)
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) doubleClicked(e) }
            override fun mouseMoved(e: MouseEvent) { cursor = cursorAt(e) }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        registerShortcuts()
        DropTarget(this, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun dragOver(e: DropTargetDragEvent) {
                val type = toolType(e.transferable) ?: run { e.rejectDrag(); return }
                val (x, y) = toDesign(e.location.x, e.location.y)
                plan = planFor(x, y, x, y, null)
                ghost = doubleArrayOf(x, y, 0.0, 0.0)
                if (plan == null) e.rejectDrag() else e.acceptDrag(DnDConstants.ACTION_COPY)
                toolTipText = plan?.let { "$type → ${it.label}" }
                repaint()
            }
            override fun dragExit(dte: DropTargetEvent) { plan = null; ghost = null; toolTipText = null; repaint() }
            override fun drop(e: DropTargetDropEvent) {
                e.acceptDrop(DnDConstants.ACTION_COPY)
                val type = toolType(e.transferable)
                val (x, y) = toDesign(e.location.x, e.location.y)
                val p = planFor(x, y, x, y, null)
                plan = null; ghost = null; toolTipText = null
                if (type != null && p != null) insertControl(type, p)
                e.dropComplete(type != null && p != null)
                requestFocusInWindow()
                repaint()
            }
        })
        parent.let { com.intellij.openapi.util.Disposer.register(it, this) }
    }

    override fun dispose() {}

    /**
     * Keys go through the IDE's own actions first, so a plain key listener would lose Delete and the arrows to the
     * editor. Registering them as actions on this component gives the design view first say while it has focus.
     */
    private fun registerShortcuts() {
        fun on(keys: String, run: () -> Unit) {
            val set = com.intellij.openapi.actionSystem.CustomShortcutSet(*keys.split('|').map {
                com.intellij.openapi.actionSystem.KeyboardShortcut(javax.swing.KeyStroke.getKeyStroke(it), null) }.toTypedArray())
            object : com.intellij.openapi.project.DumbAwareAction() {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = run()
            }.registerCustomShortcutSet(set, this)
        }
        on("DELETE|BACK_SPACE") { deleteSelection() }
        on("ESCAPE") { session.selectedElements.firstOrNull()?.parent?.let { p -> (if (p.isPropertyElement) p.parent else p)?.let { session.select(listOf(it.designId)) } } }
        on("ctrl A") { session.select(session.model.objects.filter { it.designId != 0 && it.parent?.let { p -> if (p.isPropertyElement) p.parent else p }?.designId == session.model.objects.getOrNull(1)?.designId }.map { it.designId }) }
        for ((key, dx, dy) in listOf(Triple("LEFT", -1, 0), Triple("RIGHT", 1, 0), Triple("UP", 0, -1), Triple("DOWN", 0, 1))) {
            on(key) { nudge(dx.toDouble(), dy.toDouble()) }
            on("shift $key") { nudge(dx * 10.0, dy * 10.0) }
        }
        on("ENTER") { session.selectedElements.singleOrNull()?.let { el -> Toolbox.defaultEvent(el.localName)?.let { session.goToHandler(el, it.first, it.second) } } }
    }

    /** Arrow keys move the selected controls by changing Canvas.Left/Top or the Margin (one undo step). */
    private fun nudge(dx: Double, dy: Double) {
        val doc = session.model
        val els = session.selectedElements.filter { it.designId != 0 }
        if (els.isEmpty()) return
        val edits = els.flatMap { el ->
            val parentName = el.parent?.let { if (it.isPropertyElement) it.parent else it }?.localName
            if (parentName == "Canvas") {
                val l = (el.attrValue("Canvas.Left")?.toDoubleOrNull() ?: 0.0) + dx
                val t = (el.attrValue("Canvas.Top")?.toDoubleOrNull() ?: 0.0) + dy
                XamlEdits.setAttributes(doc, el, linkedMapOf("Canvas.Left" to DropPlanner.fmt(l), "Canvas.Top" to DropPlanner.fmt(t)))
            } else {
                val m = margin(el.attrValue("Margin"))
                m[0] += dx; m[1] += dy
                XamlEdits.setAttribute(doc, el, "Margin", m.joinToString(",") { DropPlanner.fmt(it) })
            }
        }
        session.apply(if (els.size == 1) "Move ${els[0].localName}" else "Move ${els.size} Controls", edits)
    }

    override fun getPreferredSize(): Dimension {
        val r = session.render ?: return Dimension(400, 300)
        return Dimension((r.width + 2 * origin / 1.0).roundToInt() + origin, (r.height + 2 * origin / 1.0).roundToInt() + origin)
    }

    private fun toDesign(x: Int, y: Int): Pair<Double, Double> = (x - origin).toDouble() to (y - origin).toDouble()

    private fun toolType(t: java.awt.datatransfer.Transferable): String? = try {
        (t.getTransferData(DataFlavor.stringFlavor) as? String)?.takeIf { it.startsWith(Toolbox.PREFIX) }?.removePrefix(Toolbox.PREFIX)
    } catch (_: Exception) { null }

    private fun boxes() = session.render?.elements ?: emptyList()

    private fun hit(x: Double, y: Double): RenderedElement? =
        boxes().filter { it.contains(x, y) }.maxByOrNull { it.id }

    private fun planFor(px: Double, py: Double, left: Double, top: Double, dragged: dev.penguinxaml.xaml.XamlElement?) =
        DropPlanner.plan(session.model, boxes(), px, py, left, top, dragged)

    // ---- painting -------------------------------------------------------------------------------------------------

    override fun paintComponent(g0: Graphics) {
        super.paintComponent(g0)
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val r = session.render
        val img = session.image
        session.setScale(JBUIScale.sysScale(this).toDouble())
        if (r != null && img != null) {
            g.translate(origin, origin)
            g.color = JBColor.border()
            g.drawRect(-1, -1, r.width.roundToInt() + 1, r.height.roundToInt() + 1)
            // Drawn at its design size: on a scaled screen the picture's extra pixels give a sharp result.
            g.drawImage(img, 0, 0, r.width.roundToInt(), r.height.roundToInt(), null)
            paintGridRails(g)
            paintSelection(g)
            plan?.let { p ->
                g.color = DROP
                g.stroke = BasicStroke(2f)
                val h = p.highlight
                g.drawRect(h[0].roundToInt(), h[1].roundToInt(), max(2, h[2].roundToInt()), max(2, h[3].roundToInt()))
                g.color = DROP_FILL
                g.fillRect(h[0].roundToInt(), h[1].roundToInt(), max(2, h[2].roundToInt()), max(2, h[3].roundToInt()))
                paintLabel(g, p.label, h[0], h[1])
            }
            ghost?.let { gh -> if (gh[2] > 0) { g.color = SELECT; g.stroke = DASH; g.drawRect(gh[0].roundToInt(), gh[1].roundToInt(), gh[2].roundToInt(), gh[3].roundToInt()) } }
            g.translate(-origin, -origin)
        }
        session.error?.let { msg ->
            g.font = UIUtil.getLabelFont()
            val fm = g.fontMetrics
            val lines = msg.lines()
            val y0 = height - JBUI.scale(12) - fm.height * lines.size
            g.color = JBColor.namedColor("Notification.errorBackground", JBColor(0xFFF0F0, 0x402020))
            g.fillRect(0, y0 - JBUI.scale(6), width, height - y0 + JBUI.scale(6))
            g.color = JBColor.namedColor("Notification.errorForeground", UIUtil.getLabelForeground())
            lines.forEachIndexed { i, l -> g.drawString(l, JBUI.scale(12), y0 + fm.ascent + i * fm.height) }
        }
        if (r == null && session.error == null) {
            g.color = UIUtil.getContextHelpForeground()
            g.font = UIUtil.getLabelFont()
            g.drawString("Drawing the design view…", origin, origin + g.fontMetrics.ascent)
        }
        g.dispose()
    }

    private fun paintLabel(g: Graphics2D, text: String, x: Double, y: Double) {
        g.font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
        val fm = g.fontMetrics
        val w = fm.stringWidth(text) + JBUI.scale(8)
        val h = fm.height + JBUI.scale(2)
        val ly = (y - h - 2).roundToInt().coerceAtLeast(-origin + 2)
        g.color = DROP
        g.fillRoundRect(x.roundToInt(), ly, w, h, 4, 4)
        g.color = Color.WHITE
        g.drawString(text, x.roundToInt() + JBUI.scale(4), ly + fm.ascent + 1)
    }

    /** Above and left of a selected Grid: its rows and columns, with a + to add one and borders to drag. */
    private fun paintGridRails(g: Graphics2D) {
        val grid = selectedGrid() ?: return
        val rail = JBUI.scale(12)
        g.color = RAIL
        g.fillRect(grid.x.roundToInt(), grid.y.roundToInt() - rail - 2, grid.w.roundToInt(), rail)
        g.fillRect(grid.x.roundToInt() - rail - 2, grid.y.roundToInt(), rail, grid.h.roundToInt())
        g.color = SELECT
        g.stroke = DASH
        var acc = grid.x
        for (c in grid.cols.dropLast(1)) { acc += c; g.drawLine(acc.roundToInt(), (grid.y - rail - 2).roundToInt(), acc.roundToInt(), (grid.y + grid.h).roundToInt()) }
        acc = grid.y
        for (rh in grid.rows.dropLast(1)) { acc += rh; g.drawLine((grid.x - rail - 2).roundToInt(), acc.roundToInt(), (grid.x + grid.w).roundToInt(), acc.roundToInt()) }
        g.toolTipText()
    }

    private fun Graphics2D.toolTipText() {}

    private fun selectedGrid(): RenderedElement? {
        val id = session.selection.singleOrNull() ?: return null
        val el = session.model.byId(id) ?: return null
        if (el.localName != "Grid") return null
        return boxes().firstOrNull { it.id == id }
    }

    private fun paintSelection(g: Graphics2D) {
        val sel = session.selection
        for (id in sel) {
            val b = boxes().firstOrNull { it.id == id } ?: continue
            g.color = SELECT
            g.stroke = BasicStroke(1f)
            g.drawRect(b.x.roundToInt(), b.y.roundToInt(), b.w.roundToInt(), b.h.roundToInt())
            if (sel.size == 1 && id != 0) for (hr in handles(b)) {
                g.color = UIUtil.getPanelBackground(); g.fill(hr)
                g.color = SELECT; g.draw(hr)
            }
        }
    }

    /** The eight resize handles, clockwise from top-left. */
    private fun handles(b: RenderedElement): List<Rectangle> {
        val s = JBUI.scale(6)
        val xs = listOf(b.x, b.x + b.w / 2, b.x + b.w)
        val ys = listOf(b.y, b.y + b.h / 2, b.y + b.h)
        val pts = listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 1 to 2, 0 to 2, 0 to 1)
        return pts.map { (i, j) -> Rectangle((xs[i] - s / 2).roundToInt(), (ys[j] - s / 2).roundToInt(), s, s) }
    }

    private fun handleAt(x: Double, y: Double): Pair<RenderedElement, Int>? {
        val id = session.selection.singleOrNull()?.takeIf { it != 0 } ?: return null
        val b = boxes().firstOrNull { it.id == id } ?: return null
        val i = handles(b).indexOfFirst { it.apply { grow(2, 2) }.contains(x, y) }
        return if (i >= 0) b to i else null
    }

    private fun gridBorderAt(x: Double, y: Double): Drag.Grid? {
        val grid = selectedGrid() ?: return null
        val rail = JBUI.scale(12) + 2
        if (y in grid.y - rail..grid.y && x in grid.x..grid.x + grid.w) {
            var acc = grid.x
            grid.cols.dropLast(1).forEachIndexed { i, c -> acc += c; if (abs(acc - x) < 4) return Drag.Grid(x, y, grid, false, i) }
        }
        if (x in grid.x - rail..grid.x && y in grid.y..grid.y + grid.h) {
            var acc = grid.y
            grid.rows.dropLast(1).forEachIndexed { i, rh -> acc += rh; if (abs(acc - y) < 4) return Drag.Grid(x, y, grid, true, i) }
        }
        return null
    }

    private fun cursorAt(e: MouseEvent): Cursor {
        val (x, y) = toDesign(e.x, e.y)
        handleAt(x, y)?.let { (_, i) ->
            return Cursor.getPredefinedCursor(when (i) { 0, 4 -> Cursor.NW_RESIZE_CURSOR; 2, 6 -> Cursor.NE_RESIZE_CURSOR; 1, 5 -> Cursor.N_RESIZE_CURSOR; else -> Cursor.E_RESIZE_CURSOR })
        }
        gridBorderAt(x, y)?.let { return Cursor.getPredefinedCursor(if (it.rows) Cursor.N_RESIZE_CURSOR else Cursor.E_RESIZE_CURSOR) }
        return Cursor.getDefaultCursor()
    }

    // ---- mouse ----------------------------------------------------------------------------------------------------

    private fun pressed(e: MouseEvent) {
        requestFocusInWindow()
        val (x, y) = toDesign(e.x, e.y)
        handleAt(x, y)?.let { (b, i) -> drag = Drag.Resize(x, y, b, i); return }
        gridBorderAt(x, y)?.let { drag = it; return }
        if (gridAddClicked(x, y)) return
        val b = hit(x, y)
        if (b == null) { session.select(emptyList()); return }
        if (e.isControlDown || e.isShiftDown) {
            session.select(if (b.id in session.selection) session.selection - b.id else session.selection + b.id)
            return
        }
        if (b.id !in session.selection) session.select(listOf(b.id))
        if (b.id != 0) drag = Drag.Move(x, y, b)
    }

    /** A click on an empty part of a selected Grid's rail adds a row or column there (as in Visual Studio). */
    private fun gridAddClicked(x: Double, y: Double): Boolean {
        val grid = selectedGrid() ?: return false
        val el = session.model.byId(grid.id) ?: return false
        val rail = JBUI.scale(12) + 2
        val rows = when {
            y in grid.y - rail..grid.y - 1 && x in grid.x..grid.x + grid.w -> false
            x in grid.x - rail..grid.x - 1 && y in grid.y..grid.y + grid.h -> true
            else -> return false
        }
        val sizes = XamlEdits.gridDefinitions(el, rows)
        val actual = if (rows) grid.rows else grid.cols
        val offset = if (rows) y - grid.y else x - grid.x
        val total = if (rows) grid.h else grid.w
        val (i, start, size) = DropPlanner.cell(actual, offset, total)
        val split = splitSizes(sizes.ifEmpty { listOf("*") }, actual.ifEmpty { listOf(total) }, i, offset - start, size)
        session.apply(if (rows) "Add Grid Row" else "Add Grid Column", XamlEdits.setGridDefinitions(session.model, el, rows, split))
        return true
    }

    private fun dragged(e: MouseEvent) {
        val d = drag ?: return
        val (x, y) = toDesign(e.x, e.y)
        when (d) {
            is Drag.Move -> {
                val left = d.box.x + x - d.startX
                val top = d.box.y + y - d.startY
                ghost = doubleArrayOf(left, top, d.box.w, d.box.h)
                plan = planFor(x, y, left, top, session.model.byId(d.box.id))
            }
            is Drag.Resize -> ghost = resized(d, x, y)
            is Drag.Grid -> ghost = if (d.rows) doubleArrayOf(d.box.x, y, d.box.w, 0.5) else doubleArrayOf(x, d.box.y, 0.5, d.box.h)
        }
        repaint()
    }

    private fun released(e: MouseEvent) {
        val d = drag ?: return
        drag = null
        val (x, y) = toDesign(e.x, e.y)
        val g = ghost; val p = plan
        ghost = null; plan = null
        repaint()
        if (abs(x - d.startX) < 3 && abs(y - d.startY) < 3) return
        val doc = session.model
        when (d) {
            is Drag.Move -> {
                val el = doc.byId(d.box.id) ?: return
                if (p == null) return
                val edits = if (p.target === el.parent || (el.parent?.isPropertyElement == true && p.target === el.parent.parent)) {
                    if (p.index != null && p.target.objectChildren.indexOf(el) != p.index)
                        XamlEdits.moveElement(doc, el, p.target, p.index, p.set)
                    else XamlEdits.setAttributes(doc, el, p.set)
                } else XamlEdits.moveElement(doc, el, p.target, p.index, p.set)
                session.apply("Move ${el.localName}", edits)
            }
            is Drag.Resize -> {
                val el = doc.byId(d.box.id) ?: return
                val r = g ?: return
                val set = linkedMapOf<String, String?>("Width" to DropPlanner.fmt(r[2]), "Height" to DropPlanner.fmt(r[3]))
                if (d.handle in listOf(0, 1, 2, 6, 7)) {
                    // Moved its left or top edge: keep the position too.
                    val parentBox = el.parent?.let { pe -> boxes().firstOrNull { it.id == (if (pe.isPropertyElement) pe.parent?.designId else pe.designId) } }
                    if (el.parent?.localName == "Canvas" && parentBox != null) {
                        set["Canvas.Left"] = DropPlanner.fmt(r[0] - parentBox.x); set["Canvas.Top"] = DropPlanner.fmt(r[1] - parentBox.y)
                    } else {
                        val m = margin(el.attrValue("Margin"))
                        m[0] += r[0] - d.box.x; m[1] += r[1] - d.box.y
                        set["Margin"] = m.joinToString(",") { DropPlanner.fmt(it) }
                    }
                }
                if (d.handle == 1 || d.handle == 5) set.remove("Width")
                if (d.handle == 3 || d.handle == 7) set.remove("Height")
                session.apply("Resize ${el.localName}", XamlEdits.setAttributes(doc, el, set))
            }
            is Drag.Grid -> {
                val el = doc.byId(d.box.id) ?: return
                val actual = if (d.rows) d.box.rows else d.box.cols
                val delta = if (d.rows) y - d.startY else x - d.startX
                val sizes = XamlEdits.gridDefinitions(el, d.rows).toMutableList()
                if (sizes.size != actual.size) return
                val a = (actual[d.index] + delta).coerceAtLeast(4.0)
                val b = (actual[d.index + 1] - delta).coerceAtLeast(4.0)
                sizes[d.index] = resize(sizes[d.index], actual[d.index], a, actual, sizes)
                sizes[d.index + 1] = resize(sizes[d.index + 1], actual[d.index + 1], b, actual, sizes)
                session.apply(if (d.rows) "Resize Grid Row" else "Resize Grid Column", XamlEdits.setGridDefinitions(doc, el, d.rows, sizes))
            }
        }
    }

    private fun doubleClicked(e: MouseEvent) {
        val (x, y) = toDesign(e.x, e.y)
        val b = hit(x, y) ?: return
        val el = session.model.byId(b.id) ?: return
        val event = Toolbox.defaultEvent(el.localName) ?: return
        session.goToHandler(el, event.first, event.second)
    }

    fun deleteSelection() {
        val doc = session.model
        val els = session.selectedElements.filter { it.designId != 0 }
        // Skip ones inside another selected element (deleted with it).
        val top = els.filter { el -> els.none { it !== el && it.isAncestorOf(el) } }
        if (top.isEmpty()) return
        session.select(emptyList())
        session.apply(if (top.size == 1) "Delete ${top[0].localName}" else "Delete ${top.size} Controls", top.flatMap { XamlEdits.deleteElement(doc, it) })
    }

    private fun insertControl(type: String, p: DropPlan) {
        val xml = Toolbox.snippet(type)
        val doc = session.model
        val tmp = dev.penguinxaml.xaml.XamlDocument.parse(xml)
        val root = tmp.root ?: return
        val withPos = XamlEdits.elementTextWith(tmp, root, p.set)
        val edits = XamlEdits.insertChild(doc, p.target, withPos, p.index)
        if (session.apply("Add $type", edits)) {
            val at = edits.first().start + edits.first().text.indexOf('<')
            session.selectAt(at)
        }
    }

    private fun resized(d: Drag.Resize, x: Double, y: Double): DoubleArray {
        val b = d.box
        var l = b.x; var t = b.y; var r = b.x + b.w; var btm = b.y + b.h
        val dx = x - d.startX; val dy = y - d.startY
        when (d.handle) { 0, 6, 7 -> l += dx; 2, 3, 4 -> r += dx }
        when (d.handle) { 0, 1, 2 -> t += dy; 4, 5, 6 -> btm += dy }
        return doubleArrayOf(minOf(l, r - 4), minOf(t, btm - 4), max(4.0, r - l), max(4.0, btm - t))
    }

    companion object {
        val SELECT = JBColor(0x3574F0, 0x548AF7)
        val DROP = JBColor(0x3574F0, 0x548AF7)
        val DROP_FILL = JBColor(Color(0x35, 0x74, 0xF0, 0x22), Color(0x54, 0x8A, 0xF7, 0x33))
        val RAIL = JBColor(Color(0, 0, 0, 0x14), Color(255, 255, 255, 0x1A))
        val DASH = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 3f), 0f)

        fun margin(v: String?): DoubleArray {
            val parts = v?.split(',', ' ')?.filter { it.isNotBlank() }?.mapNotNull { it.trim().toDoubleOrNull() } ?: emptyList()
            return when (parts.size) {
                1 -> doubleArrayOf(parts[0], parts[0], parts[0], parts[0])
                2 -> doubleArrayOf(parts[0], parts[1], parts[0], parts[1])
                4 -> parts.toDoubleArray()
                else -> doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            }
        }

        /** A new size for one row/column, in the same kind of unit the user wrote (pixels, star or Auto→pixels). */
        fun resize(written: String, oldActual: Double, newActual: Double, actual: List<Double>, all: List<String>): String {
            val w = written.trim()
            if (w.endsWith("*")) {
                val weight = w.removeSuffix("*").toDoubleOrNull() ?: 1.0
                val v = weight * newActual / max(oldActual, 1.0)
                return (if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')) + "*"
            }
            return DropPlanner.fmt(newActual)
        }

        /** Splits row/column [i] at [at] pixels into two, keeping star sizes as stars and pixel sizes as pixels. */
        fun splitSizes(sizes: List<String>, actual: List<Double>, i: Int, at: Double, size: Double): List<String> {
            if (i >= sizes.size) return sizes
            val w = sizes[i].trim()
            val first: String; val second: String
            if (w.endsWith("*") || w == "Auto") {
                val weight = if (w == "Auto") 1.0 else (w.removeSuffix("*").toDoubleOrNull() ?: 1.0)
                val f = at / max(size, 1.0)
                first = starOf(weight * f); second = starOf(weight * (1 - f))
            } else {
                first = DropPlanner.fmt(at); second = DropPlanner.fmt(size - at)
            }
            return sizes.take(i) + first + second + sizes.drop(i + 1)
        }

        private fun starOf(v: Double): String {
            val r = (v * 100).roundToInt() / 100.0
            return (if (r == r.roundToInt().toDouble()) r.roundToInt().toString() else r.toString()) + "*"
        }
    }
}
