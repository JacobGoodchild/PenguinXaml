using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Runtime.CompilerServices;

namespace ChessBoard;

/// <summary>One square of the board.</summary>
public class Square : INotifyPropertyChanged
{
    private string _piece = "";
    private bool _isSelected;

    public Square(int index) => Index = index;

    public int Index { get; }
    public bool IsLight => (Index / 8 + Index % 8) % 2 == 0;
    public string Name => $"{"abcdefgh"[Index % 8]}{8 - Index / 8}";

    public string Piece { get => _piece; set => Set(ref _piece, value); }
    public bool IsSelected { get => _isSelected; set => Set(ref _isSelected, value); }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

/// <summary>
/// The game. It moves pieces where you click without checking the rules: it is a sample of data binding,
/// styles and code-behind, not a chess engine.
/// </summary>
public class GameViewModel : INotifyPropertyChanged
{
    private const string BackRank = "♜♞♝♛♚♝♞♜";
    private string _status = "";

    public GameViewModel() => NewGame();

    public ObservableCollection<Square> Squares { get; } = new(Enumerable.Range(0, 64).Select(i => new Square(i)));
    public ObservableCollection<string> Moves { get; } = new();
    public string Status { get => _status; private set { _status = value; PropertyChanged?.Invoke(this, new(nameof(Status))); } }
    public Square? Selected => Squares.FirstOrDefault(s => s.IsSelected);

    public void NewGame()
    {
        const string white = "♖♘♗♕♔♗♘♖";
        for (var i = 0; i < 64; i++)
        {
            var (row, col) = (i / 8, i % 8);
            Squares[i].IsSelected = false;
            Squares[i].Piece = row switch { 0 => BackRank[col].ToString(), 1 => "♟", 6 => "♙", 7 => white[col].ToString(), _ => "" };
        }
        Moves.Clear();
        Status = "White to move";
    }

    /// <summary>First click picks a piece, second click moves it there.</summary>
    public void Click(Square square)
    {
        var from = Selected;
        if (from == null)
        {
            if (square.Piece != "") square.IsSelected = true;
            return;
        }
        from.IsSelected = false;
        if (from == square) return;
        Moves.Add($"{Moves.Count / 2 + 1}. {from.Piece} {from.Name}–{square.Name}");
        square.Piece = from.Piece;
        from.Piece = "";
        Status = Moves.Count % 2 == 0 ? "White to move" : "Black to move";
    }

    public void UndoAll() => NewGame();

    public event PropertyChangedEventHandler? PropertyChanged;
}
