package dev.penguinxaml

import dev.penguinxaml.xaml.CodeBehind
import dev.penguinxaml.xaml.applyEdits
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodeBehindTest {
    private val cs = """
        using Avalonia.Controls;

        namespace Sample;

        public partial class MainWindow : Window
        {
            public MainWindow()
            {
                InitializeComponent(); // { not a brace that counts }
            }
        }
    """.trimIndent() + "\n"

    @Test fun `adds a handler at the end of the class and the using`() {
        val r = CodeBehind.addHandler(cs, "Sample.MainWindow", "OkButton_Click")!!
        val out = applyEdits(cs, r.edits)
        assertTrue(out.startsWith("using Avalonia.Controls;\nusing Avalonia.Interactivity;\n"), out)
        assertTrue(out.contains("    }\n\n    private void OkButton_Click(object? sender, RoutedEventArgs e)\n    {\n        \n    }\n}\n"), out)
        // Caret on the empty line inside.
        val caretLine = out.substring(0, r.caretAfter!!).substringAfterLast('\n')
        assertEquals("        ", caretLine)
    }

    @Test fun `existing handler is found, not added again`() {
        val first = applyEdits(cs, CodeBehind.addHandler(cs, "Sample.MainWindow", "A_Click")!!.edits)
        val again = CodeBehind.addHandler(first, "Sample.MainWindow", "A_Click")!!
        assertTrue(again.edits.isEmpty())
    }
}
