package dev.penguinxaml.editor

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import dev.penguinxaml.host.HostClient
import dev.penguinxaml.host.HostException
import dev.penguinxaml.host.RenderResult
import dev.penguinxaml.xaml.CodeBehind
import dev.penguinxaml.xaml.TextEdit
import dev.penguinxaml.xaml.XamlDocument
import dev.penguinxaml.xaml.XamlElement
import dev.penguinxaml.xaml.XamlParseException
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * One open XAML file in the designer: keeps the design view, the XAML text and the tool windows in step.
 * Every designer change is one IDE command on the file's document, so Ctrl+Z undoes it in one step.
 */
class DesignerSession(val project: Project, val file: VirtualFile, val document: Document) : Disposable {
    var model: XamlDocument = XamlDocument(document.text, null); private set
    var render: RenderResult? = null; private set
    var image: BufferedImage? = null; private set
    var error: String? = null; private set
    var selection: List<Int> = emptyList(); private set

    private val listeners = mutableListOf<() -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    @Volatile private var scale = 1.0
    @Volatile private var generation = 0

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { reparse(); scheduleRender(250) }
        }, this)
        reparse()
    }

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun reparse() {
        try {
            model = XamlDocument.parse(document.text)
            if (error?.startsWith(XAML_PROBLEM) == true) error = null
        } catch (e: XamlParseException) {
            error = "$XAML_PROBLEM line ${document.getLineNumber(e.offset.coerceIn(0, document.textLength)) + 1}: ${e.message}"
        }
        selection = selection.filter { model.byId(it) != null }
        changed()
    }

    fun setScale(s: Double) { if (s != scale) { scale = s; scheduleRender(0) } }

    fun scheduleRender(delay: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({ renderNow() }, delay)
    }

    private fun renderNow() {
        val gen = ++generation
        val text = ApplicationManager.getApplication().runReadAction<String> { document.text }
        val projectDir = projectDir()
        val app = projectDir?.let { File(it, "App.axaml") }?.takeIf { it.isFile }?.let { appFile ->
            LocalFileSystem.getInstance().findFileByIoFile(appFile)?.let { vf ->
                ApplicationManager.getApplication().runReadAction<String?> { FileDocumentManager.getInstance().getDocument(vf)?.text }
            }
        }
        // The project's own compiled code (custom controls, view models) only in trusted projects.
        val assemblies = if (TrustedProjects.isProjectTrusted(project) && projectDir != null) ProjectOutput.assemblies(File(projectDir)) else emptyList()
        try {
            val r = service<HostClient>().render(text, app, projectDir, scale, !JBColor.isBright(), assemblies)
            val img = ImageIO.read(r.png.inputStream())
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation || project.isDisposed) return@invokeLater
                render = r; image = img; error = null
                changed()
            }
        } catch (e: HostException) {
            ApplicationManager.getApplication().invokeLater { if (gen == generation) { error = e.message; changed() } }
        }
    }

    /** The folder of the nearest .csproj above the file. */
    fun projectDir(): String? {
        var dir = File(file.path).parentFile
        while (dir != null) {
            if (dir.listFiles()?.any { it.name.endsWith(".csproj") } == true) return dir.path
            dir = dir.parentFile
        }
        return null
    }

    // ---- selection ------------------------------------------------------------------------------------------------

    fun select(ids: List<Int>, fromText: Boolean = false) {
        if (ids == selection) return
        selection = ids
        changed()
        if (!fromText) ids.firstOrNull()?.let { model.byId(it) }?.let { revealInText(it) }
    }

    val selectedElements: List<XamlElement> get() = selection.mapNotNull { model.byId(it) }

    var textEditorCaretMover: ((Int) -> Unit)? = null
    private fun revealInText(el: XamlElement) { textEditorCaretMover?.invoke(el.start) }

    // ---- edits ----------------------------------------------------------------------------------------------------

    /** Applies [edits] as one undoable command named [name]. Returns false when the file couldn't be changed. */
    fun apply(name: String, edits: List<TextEdit>): Boolean {
        if (edits.isEmpty()) return true
        if (!FileDocumentManager.getInstance().requestWriting(document, project)) return false
        WriteCommandAction.runWriteCommandAction(project, name, GROUP, {
            for (e in edits.sortedByDescending { it.start }) document.replaceString(e.start, e.end, e.text)
        })
        return true
    }

    /** Selects the object element that starts at [offset] after the next parse (used after inserting a control). */
    fun selectAt(offset: Int) {
        model.objects.firstOrNull { it.start == offset }?.let { select(listOf(it.designId)) }
    }

    // ---- events ---------------------------------------------------------------------------------------------------

    /** The window's class from x:Class, and its code-behind file next to the XAML. */
    fun codeBehindFile(): VirtualFile? = file.parent?.findChild(file.name + ".cs")

    /**
     * Wires [event] of [el] to a handler (creating it in the code-behind when it doesn't exist) and opens the
     * code-behind at the handler. This is what double-clicking a control or an event in Properties does.
     */
    fun goToHandler(el: XamlElement, event: String, args: String = "RoutedEventArgs") {
        val csFile = codeBehindFile()
        val className = model.root?.attrValue("x:Class")
        if (csFile == null || className == null) {
            Notifications.info(project, "This file has no code-behind (.axaml.cs) with an x:Class, so event handlers can't be added.")
            return
        }
        val csDoc = ApplicationManager.getApplication().runReadAction<Document?> { FileDocumentManager.getInstance().getDocument(csFile) } ?: return
        val existing = el.attrValue(event)
        val method = existing ?: CodeBehind.handlerName(csDoc.text, el, event)
        val result = CodeBehind.addHandler(csDoc.text, className, method, args,
            if (args == "RoutedEventArgs") "Avalonia.Interactivity" else if (args.endsWith("EventArgs")) "Avalonia.Controls" else null)
        if (result == null) {
            Notifications.info(project, "Couldn't find the class ${className.substringAfterLast('.')} in ${csFile.name}.")
            return
        }
        WriteCommandAction.runWriteCommandAction(project, "Add $event Handler", GROUP, {
            if (existing == null) {
                for (e in XamlEditsBridge.setAttribute(model, el, event, method).sortedByDescending { it.start })
                    document.replaceString(e.start, e.end, e.text)
            }
            for (e in result.edits.sortedByDescending { it.start }) csDoc.replaceString(e.start, e.end, e.text)
        })
        val offset = result.caretAfter ?: CodeBehind.methodOffset(csDoc.text, method) ?: 0
        OpenFileDescriptor(project, csFile, offset).navigate(true)
    }

    override fun dispose() {}

    companion object {
        const val GROUP = "PenguinXaml"
        const val XAML_PROBLEM = "The XAML has a problem on"
    }
}

/** Indirection so the session file stays readable. */
internal object XamlEditsBridge {
    fun setAttribute(doc: XamlDocument, el: XamlElement, name: String, value: String?) =
        dev.penguinxaml.xaml.XamlEdits.setAttribute(doc, el, name, value)
}
