using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;

namespace ChessBoard;

public partial class MainWindow : Window
{
    private readonly GameViewModel _game = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = _game;
    }

    private void Square_PointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (sender is Control { DataContext: Square square }) _game.Click(square);
    }

    private void NewGameButton_Click(object? sender, RoutedEventArgs e) => _game.NewGame();

    private void UndoButton_Click(object? sender, RoutedEventArgs e) => _game.UndoAll();
}
