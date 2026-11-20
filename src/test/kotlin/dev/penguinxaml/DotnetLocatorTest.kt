package dev.penguinxaml

import dev.penguinxaml.host.DotnetLocator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class DotnetLocatorTest {
    @Test fun `looks in the usual places, then Rider's own runtime last, never relative folders`() {
        val c = DotnetLocator.candidates(null, mapOf("PATH" to "relative:/opt/tools", "DOTNET_ROOT" to "/custom"), "/home/u", "/opt/rider")
            .map { it.path }
        assertEquals("/custom/dotnet", c.first())
        assertTrue("/opt/tools/dotnet" in c)
        assertFalse(c.any { it.startsWith("relative") })
        assertEquals("/opt/rider/lib/ReSharperHost/linux-arm64/dotnet/dotnet", c.last())
    }

    @Test fun `picks the first that really has dotnet 10 and says where it looked`() {
        val dir = Files.createTempDirectory("dn").toFile()
        val old = File(dir, "old").apply { writeText(""); setExecutable(true) }
        val new = File(dir, "new").apply { writeText(""); setExecutable(true) }
        val s = DotnetLocator.choose(listOf(File(dir, "missing"), old, new)) { if (it.name == "new") listOf("10.0.1") else listOf("8.0.11") }
        assertEquals(new.canonicalPath, s.found?.path)
        assertTrue(s.describe().contains("has .NET 8.0.11")); assertFalse(s.describe().contains("missing"))
        assertEquals(3, s.describe().lines().size)
    }
}
