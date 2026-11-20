package dev.penguinxaml.toolwindows

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import dev.penguinxaml.editor.ActiveDesigner
import dev.penguinxaml.editor.DesignerSession
import dev.penguinxaml.xaml.XamlEdits
import dev.penguinxaml.xaml.XamlElement
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

object ToolWindowIds {
    const val TOOLBOX = "Penguin XAML Toolbox"
    const val PROPERTIES = "Penguin XAML Properties"
    const val OUTLINE = "Penguin XAML Outline"
    val ALL = listOf(TOOLBOX, PROPERTIES, OUTLINE)
}

/** Base: tool windows stay hidden until a XAML file is opened in the designer (so projects without XAML don't see them). */
abstract class DesignerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = false
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = build(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
    abstract fun build(project: Project, parent: Disposable): JComponent

    /** Calls [onChange] whenever the active designer or anything in it changes. */
    protected fun follow(project: Project, parent: Disposable, onChange: (DesignerSession?) -> Unit) {
        val active = project.getService(ActiveDesigner::class.java)
        var sub: Disposable? = null
        fun attach(s: DesignerSession?) {
            sub?.let { Disposer.dispose(it) }
            sub = null
            if (s != null) { val d = Disposer.newDisposable(parent, "follow"); sub = d; s.addListener(d) { onChange(s) } }
            onChange(s)
        }
        active.listen(parent) { attach(it) }
        attach(active.session)
    }
}

// ---- Toolbox ----------------------------------------------------------------------------------------------------

object Toolbox {
    const val PREFIX = "penguinxaml-control:"

    /** Groups and controls, as in Visual Studio's toolbox. */
    val groups = linkedMapOf(
        "Common Controls" to listOf("Button", "CheckBox", "ComboBox", "Image", "Label", "ListBox", "RadioButton", "TextBlock", "TextBox"),
        "Layout" to listOf("Border", "Canvas", "DockPanel", "Grid", "ScrollViewer", "StackPanel", "WrapPanel"),
        "All Controls" to listOf("Calendar", "DatePicker", "Expander", "NumericUpDown", "ProgressBar", "Separator", "Slider", "TabControl", "ToggleButton", "ToggleSwitch"),
    )

    /** The XAML a new control starts with (Visual Studio-like defaults, kept short). */
    fun snippet(type: String): String = when (type) {
        "Button" -> """<Button Content="Button" />"""
        "CheckBox" -> """<CheckBox Content="CheckBox" />"""
        "RadioButton" -> """<RadioButton Content="RadioButton" />"""
        "ToggleButton" -> """<ToggleButton Content="ToggleButton" />"""
        "ToggleSwitch" -> """<ToggleSwitch />"""
        "Label" -> """<Label Content="Label" />"""
        "TextBlock" -> """<TextBlock Text="TextBlock" />"""
        "TextBox" -> """<TextBox Width="120" Text="" />"""
        "ComboBox" -> """<ComboBox Width="120" />"""
        "ListBox" -> """<ListBox Width="120" Height="100" />"""
        "Image" -> """<Image Width="100" Height="100" />"""
        "Border" -> """<Border Width="100" Height="100" BorderBrush="Gray" BorderThickness="1" />"""
        "Canvas", "DockPanel", "Grid", "StackPanel", "WrapPanel" -> """<$type Width="200" Height="100" />"""
        "ScrollViewer" -> """<ScrollViewer Width="200" Height="100" />"""
        "Expander" -> """<Expander Header="Expander" />"""
        "ProgressBar" -> """<ProgressBar Width="100" Value="50" />"""
        "Slider" -> """<Slider Width="100" />"""
        "Separator" -> """<Separator Width="100" />"""
        "TabControl" -> """<TabControl Width="200" Height="100" />"""
        else -> "<$type />"
    }

    /** The event double-clicking a control creates, with its event-args type. */
    fun defaultEvent(type: String): Pair<String, String>? = when (type) {
        "Button", "ToggleButton", "RadioButton", "CheckBox", "RepeatButton", "SplitButton" -> "Click" to "RoutedEventArgs"
        "TextBox" -> "TextChanged" to "TextChangedEventArgs"
        "ComboBox", "ListBox", "TabControl" -> "SelectionChanged" to "SelectionChangedEventArgs"
        "Window", "UserControl" -> "Loaded" to "RoutedEventArgs"
        "Slider", "ProgressBar", "NumericUpDown" -> null
        else -> null
    }

    /** Events listed in Properties for a control type. */
    fun events(type: String): List<Pair<String, String>> = buildList {
        defaultEvent(type)?.let { add(it) }
        if (type == "CheckBox" || type == "ToggleButton" || type == "ToggleSwitch") { add("IsCheckedChanged" to "RoutedEventArgs") }
        add("PointerPressed" to "PointerPressedEventArgs")
        add("PointerEntered" to "PointerEventArgs")
        add("PointerExited" to "PointerEventArgs")
        add("GotFocus" to "GotFocusEventArgs")
        add("LostFocus" to "RoutedEventArgs")
        add("KeyDown" to "KeyEventArgs")
        if (type != "Window" && type != "UserControl") add("Loaded" to "RoutedEventArgs")
    }.distinctBy { it.first }
}

class ToolboxFactory : DesignerToolWindowFactory() {
    override fun build(project: Project, parent: Disposable): JComponent {
        val items = Toolbox.groups.flatMap { (g, list) -> listOf("# $g") + list }
        val list = JBList(items)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean): java.awt.Component {
                val s = value as String
                val c = super.getListCellRendererComponent(l, s.removePrefix("# "), index, sel && !s.startsWith("#"), focus) as JLabel
                c.border = JBUI.Borders.empty(3, if (s.startsWith("#")) 6 else 18, 3, 6)
                c.font = if (s.startsWith("#")) c.font.deriveFont(java.awt.Font.BOLD) else c.font
                return c
            }
        }
        list.dragEnabled = true
        list.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent) = COPY
            override fun createTransferable(c: JComponent): Transferable? =
                list.selectedValue?.takeIf { !it.startsWith("#") }?.let { StringSelection(Toolbox.PREFIX + it) }
        }
        list.emptyText.text = "Open a XAML file to use the toolbox"
        val panel = JPanel(BorderLayout())
        panel.add(JBScrollPane(list))
        val hint = JLabel("Drag a control onto the design view.")
        hint.border = JBUI.Borders.empty(6)
        hint.foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        panel.add(hint, BorderLayout.SOUTH)
        return panel
    }
}

// ---- Document Outline ---------------------------------------------------------------------------------------------

class OutlineFactory : DesignerToolWindowFactory() {
    override fun build(project: Project, parent: Disposable): JComponent {
        val root = DefaultMutableTreeNode()
        val model = DefaultTreeModel(root)
        val tree = Tree(model)
        tree.isRootVisible = false
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val el = (value as? DefaultMutableTreeNode)?.userObject as? XamlElement ?: return
                append(el.localName)
                el.xName?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
        var session: DesignerSession? = null
        var updating = false
        var shownText: String? = null
        tree.addTreeSelectionListener {
            if (updating) return@addTreeSelectionListener
            val ids = tree.selectionPaths?.mapNotNull { ((it.lastPathComponent as DefaultMutableTreeNode).userObject as? XamlElement)?.designId } ?: return@addTreeSelectionListener
            session?.select(ids)
        }
        follow(project, parent) { s ->
            session = s
            updating = true
            try {
                val doc = s?.model
                if (doc?.text != shownText) {
                    shownText = doc?.text
                    root.removeAllChildren()
                    fun add(node: DefaultMutableTreeNode, el: XamlElement) {
                        val n = DefaultMutableTreeNode(el)
                        node.add(n)
                        for (c in el.children) if (c.isPropertyElement) { if (!c.localName.startsWith("Design.")) c.children.filter { !it.isPropertyElement }.forEach { add(n, it) } } else add(n, c)
                    }
                    doc?.root?.let { add(root, it) }
                    model.reload()
                    for (i in 0 until 200) { if (i >= tree.rowCount) break; tree.expandRow(i) }
                }
                val ids = s?.selection ?: emptyList()
                val paths = mutableListOf<TreePath>()
                val e = root.depthFirstEnumeration()
                while (e.hasMoreElements()) {
                    val n = e.nextElement() as DefaultMutableTreeNode
                    if ((n.userObject as? XamlElement)?.designId in ids) paths += TreePath(n.path)
                }
                tree.selectionPaths = paths.toTypedArray()
                paths.firstOrNull()?.let { tree.scrollPathToVisible(it) }
            } finally { updating = false }
        }
        tree.emptyText.text = "Open a XAML file to see its outline"
        return JBScrollPane(tree)
    }
}

// ---- Properties ---------------------------------------------------------------------------------------------------

class PropertiesFactory : DesignerToolWindowFactory() {
    override fun build(project: Project, parent: Disposable): JComponent {
        val model = PropertiesModel()
        val table = object : JBTable(model) {
            // Properties with a fixed set of values get a drop-down (you can still type anything).
            override fun getCellEditor(row: Int, column: Int): javax.swing.table.TableCellEditor {
                val name = model.rows.getOrNull(row)?.name
                val choices = (if (name == "Source") model.session?.projectDir()?.let { PropertiesModel.projectImages(java.io.File(it)) }?.toTypedArray()
                               else name?.let { PropertiesModel.choices(it) })
                    ?: return super.getCellEditor(row, column)
                val box = com.intellij.openapi.ui.ComboBox(arrayOf("") + choices)
                box.isEditable = true
                return DefaultCellEditor(box).apply { clickCountToStart = 1 }
            }
        }
        table.setShowGrid(false)
        table.emptyText.text = "Select a control to see its properties"
        val header = JLabel(" ")
        header.border = JBUI.Borders.empty(6)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val row = model.rows.getOrNull(table.rowAtPoint(e.point)) ?: return
                if (row.event != null) {
                    val s = model.session ?: return
                    val el = s.selectedElements.singleOrNull() ?: return
                    s.goToHandler(el, row.name, row.event)
                }
            }
        })
        follow(project, parent) { s ->
            if (table.isEditing) return@follow
            model.show(s)
            header.text = when (val sel = s?.selectedElements ?: emptyList()) {
                emptyList<XamlElement>() -> " "
                else -> if (sel.size == 1) sel[0].displayName else "${sel.size} controls selected"
            }
        }
        val panel = JPanel(BorderLayout())
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(table))
        val hint = JLabel("Double-click an event to create its handler.")
        hint.border = JBUI.Borders.empty(6)
        hint.foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        panel.add(hint, BorderLayout.SOUTH)
        return panel
    }
}

/** One row: a property (editable text) or an event (its handler's name; double-click to go there). */
class PropertyRow(val name: String, val value: String, val event: String? = null)

class PropertiesModel : AbstractTableModel() {
    var session: DesignerSession? = null
    var rows: List<PropertyRow> = emptyList()

    fun show(s: DesignerSession?) {
        session = s
        val sel = s?.selectedElements ?: emptyList()
        rows = if (sel.isEmpty()) emptyList() else rowsFor(sel)
        fireTableDataChanged()
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 2
    override fun getColumnName(column: Int) = if (column == 0) "Property" else "Value"
    override fun getValueAt(r: Int, c: Int): Any = rows[r].let { if (c == 0) (if (it.event != null) "${it.name} (event)" else it.name) else it.value }
    override fun isCellEditable(r: Int, c: Int) = c == 1 && rows[r].event == null

    override fun setValueAt(v: Any?, r: Int, c: Int) {
        val s = session ?: return
        val row = rows[r]
        val text = (v as? String)?.trim()
        val doc = s.model
        val edits = s.selectedElements.flatMap { XamlEdits.setAttribute(doc, it, row.name, text?.ifEmpty { null }) }
        s.apply("Set ${row.name}", edits)
    }

    companion object {
        /** The project's images as XAML paths ("/Assets/logo.png"), for an Image's Source. Skips build folders. */
        fun projectImages(projectDir: java.io.File): List<String> = projectDir.walkTopDown()
            .onEnter { it.name !in setOf("bin", "obj", ".git", ".idea", "node_modules") }
            .filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp", "ico", "webp") }
            .map { "/" + it.relativeTo(projectDir).invariantSeparatorsPath }
            .sorted().take(200).toList()

        /** The values a property can take, when it is one of a few (null: free text). */
        fun choices(name: String): Array<String>? = when (name) {
            "HorizontalAlignment" -> arrayOf("Left", "Center", "Right", "Stretch")
            "VerticalAlignment" -> arrayOf("Top", "Center", "Bottom", "Stretch")
            "Orientation" -> arrayOf("Horizontal", "Vertical")
            "FontWeight" -> arrayOf("Thin", "Light", "Normal", "Medium", "SemiBold", "Bold", "Black")
            "IsEnabled", "IsVisible", "IsChecked", "IsReadOnly" -> arrayOf("True", "False")
            "Background", "Foreground", "BorderBrush" -> arrayOf("Transparent", "White", "Black", "Gray", "LightGray", "Red", "Green", "Blue", "Orange", "#3574F0")
            else -> null
        }

        private val layout = listOf("Width", "Height", "Margin", "HorizontalAlignment", "VerticalAlignment", "Grid.Row", "Grid.Column", "Grid.RowSpan", "Grid.ColumnSpan")
        private val look = listOf("Background", "Foreground", "FontSize", "FontWeight", "Opacity", "IsEnabled", "IsVisible")

        /** The rows for one or more controls: shared values, blank where they differ. */
        fun rowsFor(sel: List<XamlElement>): List<PropertyRow> {
            val types = sel.map { it.localName }.distinct()
            val first = sel[0]
            val names = buildList {
                add("x:Name")
                when (types.singleOrNull()) {
                    "TextBlock", "TextBox" -> add("Text")
                    "Window" -> add("Title")
                    "Image" -> add("Source")
                    "Grid" -> { add("RowDefinitions"); add("ColumnDefinitions") }
                    "StackPanel" -> { add("Orientation"); add("Spacing") }
                    null -> {}
                    else -> if (sel.all { it.localName in contentTypes }) add("Content")
                }
                addAll(layout); addAll(look)
                // Anything else the user wrote on all selected controls.
                for (a in first.attributes) if (!a.name.startsWith("xmlns") && a.name !in this && sel.all { it.attr(a.name) != null } &&
                    Toolbox.events(first.localName).none { it.first == a.name }) add(a.name)
            }
            val props = names.distinct().map { n ->
                val values = sel.map { it.attrValue(n) ?: "" }.distinct()
                PropertyRow(n, values.singleOrNull() ?: "")
            }
            val events = if (sel.size == 1) Toolbox.events(first.localName).map { (n, args) -> PropertyRow(n, first.attrValue(n) ?: "", args) } else emptyList()
            return props + events
        }

        private val contentTypes = setOf("Button", "CheckBox", "RadioButton", "ToggleButton", "Label", "Expander", "ContentControl", "UserControl", "Border")
    }
}
