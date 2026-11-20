using Avalonia.Controls;
using Avalonia.Interactivity;

namespace HelloAvalonia;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        DataContext = new MainWindowViewModel();
    }

    private void OkButton_Click(object? sender, RoutedEventArgs e)
    {
        Title = $"Hello, {NameBox.Text}";
    }
}
