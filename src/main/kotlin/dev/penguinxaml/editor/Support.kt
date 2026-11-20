package dev.penguinxaml.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.penguinxaml.util.UiText
import java.io.File

/** Short messages for the user, always escaped (the IDE reads notification text as HTML). */
object Notifications {
    fun info(project: Project, text: String) = NotificationGroupManager.getInstance().getNotificationGroup("Penguin XAML")
        .createNotification(UiText.html(text), NotificationType.INFORMATION).notify(project)
}

/** The project's last build output, so the design view can show its own controls and view models. */
object ProjectOutput {
    fun assemblies(projectDir: File): List<String> {
        val csproj = projectDir.listFiles()?.firstOrNull { it.name.endsWith(".csproj") } ?: return emptyList()
        val name = csproj.nameWithoutExtension
        val dll = File(projectDir, "bin").walkTopDown().maxDepth(4)
            .filter { it.name == "$name.dll" && !it.path.contains("/ref/") }
            .maxByOrNull { it.lastModified() } ?: return emptyList()
        return listOf(dll.path)
    }
}
