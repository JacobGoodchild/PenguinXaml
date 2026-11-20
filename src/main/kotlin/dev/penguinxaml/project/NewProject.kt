package dev.penguinxaml.project

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.penguinxaml.util.UiText
import java.io.File
import java.nio.file.Path
import javax.swing.JComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.intellij.openapi.application.EDT

/** The files of a new Avalonia app, ready to open in the designer and to run. */
object ProjectTemplate {
    const val AVALONIA_VERSION = "12.1.3"

    /** A valid C# identifier from what the user typed ("My App" → "MyApp"). */
    fun identifier(name: String): String {
        val cleaned = name.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.trim('.')
        return if (cleaned.isEmpty()) "AvaloniaApp" else if (cleaned[0].isDigit()) "_$cleaned" else cleaned
    }

    fun validate(name: String): String? = when {
        name.isBlank() -> "Enter a project name."
        name.any { it == '/' || it == '\\' || it == ':' } -> "The name can't contain / \\ or :."
        else -> null
    }

    fun files(name: String): Map<String, String> {
        val ns = identifier(name)
        val projGuid = java.util.UUID.nameUUIDFromBytes(name.toByteArray()).toString().uppercase()
        return linkedMapOf(
            "$name.sln" to """
                |
                |Microsoft Visual Studio Solution File, Format Version 12.00
                |# Visual Studio Version 17
                |Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "$name", "$name\$name.csproj", "{$projGuid}"
                |EndProject
                |Global
                |	GlobalSection(SolutionConfigurationPlatforms) = preSolution
                |		Debug|Any CPU = Debug|Any CPU
                |		Release|Any CPU = Release|Any CPU
                |	EndGlobalSection
                |	GlobalSection(ProjectConfigurationPlatforms) = postSolution
                |		{$projGuid}.Debug|Any CPU.ActiveCfg = Debug|Any CPU
                |		{$projGuid}.Debug|Any CPU.Build.0 = Debug|Any CPU
                |		{$projGuid}.Release|Any CPU.ActiveCfg = Release|Any CPU
                |		{$projGuid}.Release|Any CPU.Build.0 = Release|Any CPU
                |	EndGlobalSection
                |EndGlobal
                |""".trimMargin(),
            "$name/$name.csproj" to """
                |<Project Sdk="Microsoft.NET.Sdk">
                |  <PropertyGroup>
                |    <OutputType>WinExe</OutputType>
                |    <TargetFramework>net10.0</TargetFramework>
                |    <Nullable>enable</Nullable>
                |    <RootNamespace>$ns</RootNamespace>
                |    <AvaloniaUseCompiledBindingsByDefault>true</AvaloniaUseCompiledBindingsByDefault>
                |  </PropertyGroup>
                |  <ItemGroup>
                |    <AvaloniaResource Include="Assets\**" />
                |  </ItemGroup>
                |  <ItemGroup>
                |    <PackageReference Include="Avalonia" Version="$AVALONIA_VERSION" />
                |    <PackageReference Include="Avalonia.Desktop" Version="$AVALONIA_VERSION" />
                |    <PackageReference Include="Avalonia.Themes.Fluent" Version="$AVALONIA_VERSION" />
                |  </ItemGroup>
                |</Project>
                |""".trimMargin(),
            "$name/App.axaml" to """
                |<Application xmlns="https://github.com/avaloniaui"
                |             xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
                |             x:Class="$ns.App"
                |             RequestedThemeVariant="Default">
                |  <Application.Styles>
                |    <FluentTheme />
                |  </Application.Styles>
                |</Application>
                |""".trimMargin(),
            "$name/App.axaml.cs" to """
                |using Avalonia;
                |using Avalonia.Controls.ApplicationLifetimes;
                |using Avalonia.Markup.Xaml;
                |
                |namespace $ns;
                |
                |public partial class App : Application
                |{
                |    public override void Initialize() => AvaloniaXamlLoader.Load(this);
                |
                |    public override void OnFrameworkInitializationCompleted()
                |    {
                |        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
                |            desktop.MainWindow = new MainWindow();
                |        base.OnFrameworkInitializationCompleted();
                |    }
                |}
                |""".trimMargin(),
            "$name/Program.cs" to """
                |using System;
                |using Avalonia;
                |
                |namespace $ns;
                |
                |internal static class Program
                |{
                |    [STAThread]
                |    public static void Main(string[] args) => BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
                |
                |    public static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<App>().UsePlatformDetect().LogToTrace();
                |}
                |""".trimMargin(),
            "$name/MainWindow.axaml" to """
                |<Window xmlns="https://github.com/avaloniaui"
                |        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
                |        x:Class="$ns.MainWindow"
                |        Title="$name" Width="800" Height="450">
                |  <Grid>
                |  </Grid>
                |</Window>
                |""".trimMargin(),
            "$name/MainWindow.axaml.cs" to """
                |using Avalonia.Controls;
                |
                |namespace $ns;
                |
                |public partial class MainWindow : Window
                |{
                |    public MainWindow()
                |    {
                |        InitializeComponent();
                |    }
                |}
                |""".trimMargin(),
            "$name/Assets/.gitkeep" to "",
        )
    }

    /** Writes the files under [parent]/[name]; refuses to write into a folder that already has files. */
    fun create(parent: File, name: String): File {
        val root = File(parent, name)
        if (root.exists() && root.list()?.isNotEmpty() == true) throw IllegalStateException("The folder ${root.path} already exists and isn't empty.")
        for ((rel, text) in files(name)) {
            val f = File(root, rel)
            f.parentFile.mkdirs()
            f.writeText(text)
        }
        return root
    }
}

/** Gives the New App action a coroutine scope that lives as long as the IDE. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.APP)
class OpenerScope(val scope: kotlinx.coroutines.CoroutineScope)

/** File | New | Avalonia App (Penguin XAML)…: a ready-to-run app whose MainWindow opens in the designer. */
class NewAvaloniaAppAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val dialog = NewAppDialog()
        if (!dialog.showAndGet()) return
        val root = try {
            ProjectTemplate.create(File(dialog.location.text), dialog.name.text.trim())
        } catch (ex: Exception) {
            Messages.showErrorDialog(UiText.dialog(ex.message ?: "Couldn't create the project."), "New Avalonia App")
            return
        }
        val main = File(root, "${dialog.name.text.trim()}/MainWindow.axaml")
        // Opening a project may first close the current one, so it runs in the background, not on the UI thread.
        com.intellij.openapi.components.service<OpenerScope>().scope.launch {
            val project = ProjectUtil.openOrImportAsync(Path.of(root.path), OpenProjectTask { forceOpenInNewFrame = true }) ?: return@launch
            // Start where a WPF user expects: the main window open in the designer.
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(main) ?: return@launch
            withContext(kotlinx.coroutines.Dispatchers.EDT) {
                if (!project.isDisposed) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
            }
        }
    }
}

private class NewAppDialog : DialogWrapper(true) {
    val name = JBTextField("AvaloniaApp", 24)
    val location = TextFieldWithBrowseButton().apply {
        text = System.getProperty("user.home") + File.separator + "RiderProjects"
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Choose a Folder"))
    }

    init { title = "New Avalonia App"; init() }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", name)
        .addLabeledComponent("Location:", location)
        .addComponentToRightColumn(javax.swing.JLabel("<html>A window app with C# code-behind, ready to design and run.</html>"))
        .panel

    override fun doValidate() = ProjectTemplate.validate(name.text)?.let { com.intellij.openapi.ui.ValidationInfo(it, name) }
    override fun getPreferredFocusedComponent() = name
}
