namespace HelloAvalonia;

/// <summary>The window's data. The design view shows these values too (see Design.DataContext in MainWindow.axaml).</summary>
public class MainWindowViewModel
{
    public string Greeting { get; set; } = "Welcome to Penguin XAML";
    public string[] Moves { get; } = { "e4", "e5", "Nf3" };
}
