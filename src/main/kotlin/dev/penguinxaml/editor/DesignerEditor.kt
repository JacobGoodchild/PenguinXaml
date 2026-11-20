package dev.penguinxaml.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import dev.penguinxaml.toolwindows.ToolWindowIds
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Opens .axaml files (except App.axaml, which has no window to draw) as a split view: the design view on top and the
 * XAML below, like Visual Studio's. The IDE's Editor / Split / Design buttons switch between them.
 */
class DesignerEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = isDesignable(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val design = DesignerFileEditor(project, file, text)
        return TextEditorWithPreview(text, design, "Penguin XAML", TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW, false)
    }

    override fun getEditorTypeId() = "penguinxaml-designer"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        fun isDesignable(file: VirtualFile) = !file.isDirectory && file.extension.equals("axaml", true) &&
            !file.name.equals("App.axaml", true) && file.isInLocalFileSystem
    }
}

class DesignerFileEditor(private val project: Project, private val file: VirtualFile, text: TextEditor) : UserDataHolderBase(), FileEditor {
    val session = DesignerSession(project, file, text.editor.document)
    private val surface = DesignSurface(session, this)
    private val scroll = JBScrollPane(surface)

    init {
        Disposer.register(this, session)
        // XAML caret → design selection; design selection → XAML caret.
        text.editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (movingCaret) return
                val el = session.model.elementAt(text.editor.caretModel.offset) ?: return
                session.select(listOf(el.designId), fromText = true)
            }
        }, this)
        session.textEditorCaretMover = { offset ->
            movingCaret = true
            try {
                text.editor.caretModel.moveToOffset(offset)
                text.editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            } finally { movingCaret = false }
        }
        project.getService(ActiveDesigner::class.java).activate(session)
        ToolWindowManager.getInstance(project).invokeLater {
            val twm = ToolWindowManager.getInstance(project)
            for (id in ToolWindowIds.ALL) twm.getToolWindow(id)?.let { it.isAvailable = true; it.isShowStripeButton = true }
        }
        session.scheduleRender(0)
    }

    private var movingCaret = false

    override fun getComponent(): JComponent = scroll
    override fun getPreferredFocusedComponent(): JComponent = surface
    override fun getName() = "Design"
    override fun getFile() = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun selectNotify() { project.getService(ActiveDesigner::class.java).activate(session) }
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() { project.getService(ActiveDesigner::class.java).closed(session) }
}

/** The designer the tool windows (Toolbox, Properties, Document Outline) currently follow. */
@Service(Service.Level.PROJECT)
class ActiveDesigner(private val project: Project) : Disposable {
    var session: DesignerSession? = null; private set
    private val listeners = mutableListOf<(DesignerSession?) -> Unit>()

    fun activate(s: DesignerSession) { if (session !== s) { session = s; listeners.toList().forEach { it(s) } } }
    fun closed(s: DesignerSession) { if (session === s) { session = null; listeners.toList().forEach { it(null) } } }
    fun listen(parent: Disposable, l: (DesignerSession?) -> Unit) { listeners += l; Disposer.register(parent) { listeners -= l } }
    override fun dispose() { listeners.clear() }
}
