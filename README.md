# Penguin XAML

A drag-and-drop XAML designer for [Avalonia](https://avaloniaui.net) apps in JetBrains Rider on Linux, modelled on
the classic WPF designer: XAML for the look, C# code-behind, and a design view kept in sync with the XAML.

![The sample window drawn by Penguin XAML's design helper](docs/screenshots/helper-sample-light-200pc.png)

## Requirements

- JetBrains Rider 2026.1 or newer, on Linux
- The .NET 10 runtime (`sudo apt install dotnet-runtime-10.0`, or Microsoft's installer)

## Install

Download the plugin zip from the latest CI run (Actions → CI → `penguin-xaml-plugin`), then in Rider:
Settings → Plugins → gear icon → Install Plugin from Disk.

## Quick start

1. Open an Avalonia project (or `samples/HelloAvalonia` from this repository).
2. Open `MainWindow.axaml`. The window is drawn above its XAML.
3. Open the **Penguin XAML Toolbox** and drag a Button onto the window. The label shows where it will land.
4. Select, move, resize or delete controls; Ctrl+Z undoes each change.
5. Double-click a button to write its Click handler in `MainWindow.axaml.cs`.

## Credits

Built on [Avalonia](https://github.com/AvaloniaUI/Avalonia) (MIT), drawn with SkiaSharp and HarfBuzzSharp (MIT).
See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Penguin XAML is MIT-licensed.
