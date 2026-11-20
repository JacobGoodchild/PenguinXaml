package dev.penguinxaml

import dev.penguinxaml.host.RenderedElement
import dev.penguinxaml.xaml.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XamlEditsTest {
    private val window = """
        <Window xmlns="https://github.com/avaloniaui"
                xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
                x:Class="Sample.MainWindow" Title="Sample">
          <Grid RowDefinitions="Auto,*" ColumnDefinitions="*,*">
            <TextBlock Text="Hello"/>
            <Button Grid.Row="1"   Content="OK" />
          </Grid>
        </Window>
    """.trimIndent()

    private fun doc(t: String = window) = XamlDocument.parse(t)

    @Test fun `ids count object elements only, in document order`() {
        val d = doc()
        assertEquals(listOf("Window", "Grid", "TextBlock", "Button"), d.objects.map { it.localName })
        assertEquals(3, d.objects.last().designId)
    }

    @Test fun `changing a value touches only the value`() {
        val d = doc()
        val out = applyEdits(d.text, XamlEdits.setAttribute(d, d.byId(3)!!, "Content", "Cancel"))
        assertEquals(window.replace("Content=\"OK\"", "Content=\"Cancel\""), out)
    }

    @Test fun `new attribute goes after the last one and keeps odd spacing elsewhere`() {
        val d = doc()
        val out = applyEdits(d.text, XamlEdits.setAttribute(d, d.byId(3)!!, "Width", "80"))
        assertTrue(out.contains("""<Button Grid.Row="1"   Content="OK" Width="80" />"""), out)
    }

    @Test fun `removing an attribute`() {
        val d = doc()
        val out = applyEdits(d.text, XamlEdits.setAttribute(d, d.byId(3)!!, "Grid.Row", null))
        assertTrue(out.contains("""<Button   Content="OK" />"""), out)
    }

    @Test fun `values are escaped`() {
        val d = doc()
        val out = applyEdits(d.text, XamlEdits.setAttribute(d, d.byId(2)!!, "Text", "a < b & \"c\""))
        assertTrue(out.contains("""Text="a &lt; b &amp; &quot;c&quot;""""), out)
        assertEquals("a < b & \"c\"", XamlDocument.parse(out).byId(2)!!.attrValue("Text"))
    }

    @Test fun `delete removes the whole line`() {
        val d = doc()
        val out = applyEdits(d.text, XamlEdits.deleteElement(d, d.byId(2)!!))
        assertFalse(out.contains("TextBlock"))
        assertTrue(out.contains("ColumnDefinitions=\"*,*\">\n    <Button"), out)
    }

    @Test fun `insert child follows the indentation`() {
        val d = doc()
        val out = applyEdits(d.text, XamlEdits.insertChild(d, d.byId(1)!!, """<CheckBox Content="CheckBox" />"""))
        assertTrue(out.contains("""Content="OK" />""" + "\n    <CheckBox Content=\"CheckBox\" />\n  </Grid>"), out)
    }

    @Test fun `insert into a self-closing panel opens it`() {
        val t = "<StackPanel xmlns=\"https://github.com/avaloniaui\">\n  <Border />\n</StackPanel>"
        val d = doc(t)
        val out = applyEdits(t, XamlEdits.insertChild(d, d.byId(1)!!, "<Button />"))
        assertEquals("<StackPanel xmlns=\"https://github.com/avaloniaui\">\n  <Border>\n    <Button />\n  </Border>\n</StackPanel>", out)
    }

    @Test fun `move into another panel keeps the element text`() {
        val t = "<StackPanel>\n  <StackPanel x:Name=\"A\">\n    <Button Content=\"1\" />\n  </StackPanel>\n  <StackPanel x:Name=\"B\">\n  </StackPanel>\n</StackPanel>"
        val d = doc(t)
        val out = applyEdits(t, XamlEdits.moveElement(d, d.byId(2)!!, d.byId(3)!!, null, emptyMap()))
        assertEquals("<StackPanel>\n  <StackPanel x:Name=\"A\">\n  </StackPanel>\n  <StackPanel x:Name=\"B\">\n    <Button Content=\"1\" />\n  </StackPanel>\n</StackPanel>", out)
    }

    @Test fun `grid definitions in short and long form`() {
        val d = doc()
        assertEquals(listOf("Auto", "*"), XamlEdits.gridDefinitions(d.byId(1)!!, true))
        val out = applyEdits(d.text, XamlEdits.setGridDefinitions(d, d.byId(1)!!, true, listOf("Auto", "*", "2*")))
        assertTrue(out.contains("RowDefinitions=\"Auto,*,2*\""))
        val long = "<Grid>\n  <Grid.RowDefinitions>\n    <RowDefinition Height=\"Auto\" />\n  </Grid.RowDefinitions>\n</Grid>"
        val ld = doc(long)
        assertEquals(listOf("Auto"), XamlEdits.gridDefinitions(ld.root!!, true))
        val lout = applyEdits(long, XamlEdits.setGridDefinitions(ld, ld.root!!, true, listOf("Auto", "*")))
        assertEquals("<Grid>\n  <Grid.RowDefinitions>\n    <RowDefinition Height=\"Auto\" />\n    <RowDefinition Height=\"*\" />\n  </Grid.RowDefinitions>\n</Grid>", lout)
    }

    @Test fun `bad xaml reports a line`() {
        val e = assertThrows(XamlParseException::class.java) { XamlDocument.parse("<Grid>\n  <Button>\n</Grid>") }
        assertTrue(e.message!!.contains("Grid"))
    }

    @Test fun `drop into a grid names the cell`() {
        val d = doc()
        val boxes = listOf(
            RenderedElement(0, "Window", 0.0, 0.0, 400.0, 300.0, "content", emptyList(), emptyList()),
            RenderedElement(1, "Grid", 0.0, 0.0, 400.0, 300.0, "panel", listOf(30.0, 270.0), listOf(200.0, 200.0)),
            RenderedElement(2, "TextBlock", 0.0, 0.0, 200.0, 30.0, null, emptyList(), emptyList()),
            RenderedElement(3, "Button", 0.0, 30.0, 50.0, 30.0, "content", emptyList(), emptyList()),
        )
        val p = DropPlanner.plan(d, boxes, 250.0, 100.0, 250.0, 100.0, null)!!
        assertEquals("Grid", p.target.localName)
        assertEquals("Grid, row 1, column 1", p.label)
        assertEquals("1", p.set["Grid.Row"]); assertEquals("1", p.set["Grid.Column"])
        assertEquals("50,70,0,0", p.set["Margin"])
    }
}

class PropertiesTest {
    @Test fun `shared values, blank where they differ, and choices`() {
        val d = XamlDocument.parse("<StackPanel><Button Width=\"80\" Content=\"A\" /><Button Width=\"80\" Content=\"B\" /></StackPanel>")
        val rows = dev.penguinxaml.toolwindows.PropertiesModel.rowsFor(d.objects.drop(1))
        assertEquals("80", rows.first { it.name == "Width" }.value)
        assertEquals("", rows.first { it.name == "Content" }.value)
        assertTrue(rows.none { it.event != null }, "events are for one control at a time")
        assertArrayEquals(arrayOf("Left", "Center", "Right", "Stretch"), dev.penguinxaml.toolwindows.PropertiesModel.choices("HorizontalAlignment"))
    }
}

class ParseMessageTest {
    @Test fun `an unfinished tag is described plainly`() {
        val e = assertThrows(XamlParseException::class.java) {
            XamlDocument.parse("<StackPanel>\n  <CheckBox Content=\"A\"\n</StackPanel>")
        }
        assertEquals("<CheckBox> is missing its closing > or />.", e.message)
        assertEquals(15, e.offset)
    }
}

class ProjectImagesTest {
    @Test fun `lists project images as XAML paths, not build output`() {
        val dir = java.nio.file.Files.createTempDirectory("imgs").toFile()
        java.io.File(dir, "Assets").mkdirs(); java.io.File(dir, "Assets/logo.png").writeText("x")
        java.io.File(dir, "bin/Debug").mkdirs(); java.io.File(dir, "bin/Debug/copy.png").writeText("x")
        java.io.File(dir, "readme.txt").writeText("x")
        assertEquals(listOf("/Assets/logo.png"), dev.penguinxaml.toolwindows.PropertiesModel.projectImages(dir))
    }
}
