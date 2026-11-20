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

    @Test fun `the project's App styles and resources are applied`() {
        assumeTrue(dotnet != null && dll.exists(), "needs the built helper and .NET 10")
        val app = """
            <Application xmlns="https://github.com/avaloniaui" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
              <Application.Resources><SolidColorBrush x:Key="Brand">#FF0000</SolidColorBrush></Application.Resources>
              <Application.Styles>
                <FluentTheme />
                <Style Selector="Border.brand"><Setter Property="Background" Value="{StaticResource Brand}" /></Style>
              </Application.Styles>
            </Application>""".trimIndent()
        val window = """<Window xmlns="https://github.com/avaloniaui" Width="100" Height="100"><Border Classes="brand" /></Window>"""
        val answer = render(window, app)
        assertTrue(answer.get("ok").asBoolean, answer.toString().take(300))
        val img = javax.imageio.ImageIO.read(HostClient.parseRender(answer).png.inputStream())
        assertEquals(0xFF0000, img.getRGB(img.width / 2, img.height / 2) and 0xFFFFFF, "the App.axaml style should paint the border red")
    }

    @Test fun `a UserControl uses its design size`() {
        assumeTrue(dotnet != null && dll.exists(), "needs the built helper and .NET 10")
        val uc = """
            <UserControl xmlns="https://github.com/avaloniaui" xmlns:d="http://schemas.microsoft.com/expression/blend/2008"
                         xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" mc:Ignorable="d"
                         d:DesignWidth="320" d:DesignHeight="120">
              <StackPanel><TextBlock Text="Card" /><Button Content="Open" /></StackPanel>
            </UserControl>""".trimIndent()
        val answer = render(uc, null)
        assertTrue(answer.get("ok").asBoolean, answer.toString().take(300))
        val r = HostClient.parseRender(answer)
        assertEquals(320.0, r.width); assertEquals(120.0, r.height)
        assertEquals(listOf("UserControl", "StackPanel", "TextBlock", "Button"), r.elements.map { it.type })
    }
}
