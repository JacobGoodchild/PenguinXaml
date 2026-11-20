package dev.penguinxaml

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.penguinxaml.host.HostClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Runs the real design helper (built by the plugin build into build/host) on the sample window. */
class HostRenderTest {
    private val dll = File("build/host/PenguinXaml.Host.dll")
    private val dotnet = listOf(System.getenv("DOTNET_ROOT")?.let { "$it/dotnet" }, "/opt/dotnet/dotnet", "/usr/share/dotnet/dotnet", "/usr/lib/dotnet/dotnet", "/usr/bin/dotnet")
        .firstOrNull { it != null && File(it).canExecute() }

    private fun render(xaml: String, appXaml: String?): JsonObject {
        val p = ProcessBuilder(dotnet, dll.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val req = JsonObject().apply {
            addProperty("id", 1); addProperty("cmd", "render"); addProperty("xaml", xaml); addProperty("scale", 2.0)
            appXaml?.let { addProperty("appXaml", it) }; addProperty("projectDir", "samples/HelloAvalonia")
        }
        p.outputStream.bufferedWriter().use { it.write(req.toString()); it.newLine() }
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        return JsonParser.parseString(lines.last { it.contains("\"id\":1") }).asJsonObject
    }

    @Test fun `the sample window is drawn with a box for every control`() {
        assumeTrue(dotnet != null && dll.exists(), "needs the built helper and .NET 10")
        val xaml = File("samples/HelloAvalonia/MainWindow.axaml").readText()
        val answer = render(xaml, File("samples/HelloAvalonia/App.axaml").readText())
        assertTrue(answer.get("ok").asBoolean, answer.toString().take(300))
        val result = HostClient.parseRender(answer)
        assertEquals(480.0, result.width); assertEquals(300.0, result.height)
        val types = result.elements.map { it.type }
        for (t in listOf("Window", "Grid", "TextBlock", "StackPanel", "TextBox", "CheckBox", "Image", "Button")) assertTrue(t in types, "$t missing: $types")
        assertEquals(3, result.elements.first { it.type == "Grid" }.rows.size)
        // The ids match the plugin's own count of the XAML's elements.
        val doc = dev.penguinxaml.xaml.XamlDocument.parse(xaml)
        for (e in result.elements) assertEquals(doc.byId(e.id)?.localName, e.type)
        assertTrue(result.png.size > 1000)
    }

    @Test fun `broken xaml gives a plain message`() {
        assumeTrue(dotnet != null && dll.exists(), "needs the built helper and .NET 10")
        val answer = render("<Window xmlns=\"https://github.com/avaloniaui\"><NoSuchControl /></Window>", null)
        assertFalse(answer.get("ok").asBoolean)
        assertFalse(answer.get("error").asString.contains("   at "), "no stack traces for users")
    }
}
