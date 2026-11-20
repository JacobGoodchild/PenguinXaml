package dev.penguinxaml

import dev.penguinxaml.project.ProjectTemplate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ProjectTemplateTest {
    @Test fun `names become valid namespaces`() {
        assertEquals("MyApp", ProjectTemplate.identifier("My App"))
        assertEquals("_2048", ProjectTemplate.identifier("2048"))
        assertNotNull(ProjectTemplate.validate("a/b"))
        assertNull(ProjectTemplate.validate("Chess"))
    }

    @Test fun `creates the app and refuses a non-empty folder`() {
        val dir = Files.createTempDirectory("px").toFile()
        val root = ProjectTemplate.create(dir, "Chess")
        assertTrue(File(root, "Chess.sln").isFile)
        assertTrue(File(root, "Chess/MainWindow.axaml").readText().contains("x:Class=\"Chess.MainWindow\""))
        assertThrows(IllegalStateException::class.java) { ProjectTemplate.create(dir, "Chess") }
        // Builds with the real .NET SDK when one is available (CI sets PENGUINXAML_BUILD_TESTS=1).
        if (System.getenv("PENGUINXAML_BUILD_TESTS") == "1") {
            val p = ProcessBuilder("dotnet", "build", File(root, "Chess.sln").path).inheritIO()
            p.environment()["AVALONIA_TELEMETRY_OPTOUT"] = "1"
            assertEquals(0, p.start().waitFor())
        }
    }
}
