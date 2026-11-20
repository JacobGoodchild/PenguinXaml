# CLAUDE.md — Penguin XAML developer notes

Read this file and `git log` first. Keep "Status" current. Commit and push to `main` after each working step.

## What this is

A JetBrains Rider plugin (Kotlin) that adds a drag-and-drop XAML designer for Avalonia apps on Linux, modelled on
Visual Studio's WPF designer. Plugin ID `dev.penguinxaml`, Kotlin package `dev.penguinxaml`, .NET namespace and
assembly `PenguinXaml.Host`, user-facing name "Penguin XAML".

- **Plugin** (`src/main/kotlin/dev/penguinxaml`)
  - `xaml/XamlModel.kt`: small XAML reader that keeps offsets of every element and attribute.
  - `xaml/XamlEdits.kt`: minimal text edits (set/remove attribute, insert/move/delete element, Grid definitions).
  - `xaml/DropPlanner.kt`: which panel / Grid cell / StackPanel slot a drop lands in, and the attributes to set.
  - `xaml/CodeBehind.kt`: adds event handlers to `.axaml.cs`.
  - `editor/`: `DesignerEditorProvider` (split view via `TextEditorWithPreview`, design above XAML),
    `DesignerSession` (sync, render scheduling, one undoable command per gesture), `DesignSurface` (canvas).
  - `toolwindows/ToolWindows.kt`: Toolbox, Properties (with events), Document Outline. Hidden until a XAML file opens.
  - `host/`: `DotnetLocator` (from Penguin Forms: tries every candidate, needs a .NET 10 runtime, lists where it
    looked), `HostClient` (JSON lines; protocol in `docs/development/PROTOCOL.md`).
- **Design helper** (`host/PenguinXaml.Host`, .NET 10, Avalonia 12.1.3 headless + Skia): loads the XAML with
  Avalonia's runtime loader (x:Class and event attributes removed, each control tagged with a design id), applies
  the project's App.axaml styles, loads images from the project folder, renders with `RenderTargetBitmap` at the
  screen's scale (sharp at 200 %), and reports every control's bounds and Grid row/column sizes.

## Decisions

- **Base: Avalonia, own designer.** XamlVisualEditor (MIT, checked) is a full standalone IDE (ReactiveUI, Dock,
  Roslyn, Avalonia 12); its pieces aren't packaged to embed in a Swing plugin, so it's used as a reference only.
  AvaloniaRider (MIT, checked) is a live previewer through Rider's XAML previewer and JCEF; we don't depend on it.
  XamlPlayground not reviewed in this session.
- **Native Swing canvas, not a web page:** the helper returns a PNG + bounds; selection, handles and drop hints are
  drawn in Swing so they match Rider's theme. No JCEF needed.
- **XAML edits in Kotlin on the IDE document:** only changed characters are touched; each gesture is one
  `WriteCommandAction`, so Ctrl+Z is one step.
- **Coexisting with AvaloniaRider:** different editor type id (`penguinxaml-designer`) and tool window ids. If both
  are installed, both editors appear as tabs (not checked in real Rider).
- **Avalonia.BuildServices** (Avalonia's build-time usage reporting) is turned off with `AVALONIA_TELEMETRY_OPTOUT=1`
  in the build and CI. The plugin itself makes no network requests.
- Project build output (custom controls, view models) is only loaded into the helper for trusted projects.
- Licence files: `THIRD-PARTY-NOTICES.md` + `licenses/` + licence files from the NuGet packages; the build fails if
  one is missing or if a package in `packages.lock.json` isn't named in the notices.

## Building and testing

- `./gradlew test buildPlugin` → `build/distributions/penguin-xaml-<version>.zip` (Java 21, .NET 10 SDK). Built
  against IntelliJ IDEA 2026.1.5.1 (oldest supported platform); no until-build.
- `./gradlew verifyPlugin -PverifyRiders=2026.1` checks one Rider; `-PverifyLocalIde=/path` an IDE on disk.
- To test the real zip: unzip it into a separate plugins folder and start IntelliJ IDEA's `bin/idea.sh` with
  `IDEA_PROPERTIES` pointing at config/system/plugins/log folders of its own (see git history for the exact setup).
- `tools/run-idea.sh <project> <log> [file]` starts the test IDE on Xvfb :99 (`UISCALE=2` for 200 %),
  `tools/ui.sh` clicks/drags/types, `tools/shot.sh` takes screenshots (test machine only).
- `./gradlew runIdea -PopenPath=samples/HelloAvalonia -PtestMachine` for UI checks in the free IntelliJ IDEA (the
  sandbox Rider needs a login). `-PuiScale=2` simulates a 200 % screen.
- CI (`.github/workflows/ci.yml`): tests, zip, zip contents check, sample build; hand-started signed Marketplace
  build + plugin checker against Rider 2026.1 / 2026.2 / 2026.3.

## Status (20 Nov 2026, second session)

**Verified in IntelliJ IDEA 2026.1 with screenshots I looked at** (`docs/screenshots/ide-*`; driven with
`tools/ui.sh`, a `java.awt.Robot` helper, because the test machine has no xdotool):
- Split view (XAML beside the design view; Editor / Split / Design buttons); sample drawn with its own styles and image.
- Click to select: handles, and the XAML caret jumps to the element. Escape selects the parent.
- Drag to move: lands in the right Grid row, XAML edited in place. Ctrl+Z undoes each gesture in one step.
- Toolbox: groups, drag a control in; "StackPanel, at the end" / "Grid, row 1, column 1" label while dragging; the
  new element is inserted with matching indentation and selected.
- Double-click a button: creates `Button_Click` in the code-behind (with `using Avalonia.Interactivity;` if missing)
  and puts the caret inside it.
- Properties: values, events, edit a value (Width), drop-downs for fixed choices (alignment etc.), multi-select shows
  "2 controls selected" with shared values and blanks where they differ.
- Document Outline: tree with x:Names, follows selection both ways.
- Grid: strips above/left of a selected Grid; click to add a column/row, drag a border to resize (star sizes stay
  star sizes); undo restores the original text exactly.
- Delete key (registered as an IDE shortcut so the editor doesn't take it), arrow keys nudge (Shift = 10 px).
- Light and dark IDE themes; 200 % scale is sharp.
- No Penguin XAML panels in a project without XAML.
- File → New → Avalonia App (Penguin XAML)…: dialog, files written, project opens and lands in the designer; the
  generated app builds with `dotnet build` (0 warnings).
- Tests: 19 (XAML edits, drop planning, code-behind, template, Properties rows, and the real helper drawing the
  sample with ids matching the plugin's count).

- Selection box (drag on empty space or an unselected panel's background); snapping while resizing.
- ChessBoard sample (ItemsControl + UniformGrid, DataTemplate with bound classes, app resources and styles, design
  data): drawn exactly as it runs; clicking a square selects the ItemsControl (template content isn't editable, as
  in Visual Studio); double-click on a button with a handler jumps to the existing method.
- Broken XAML: the last picture stays, a plain message names the line ("<CheckBox> is missing its closing > or
  />."), and designer edits are refused until it's fixed (they're computed from the last readable text).
- UserControl files use d:DesignWidth / d:DesignHeight (test).
- No .NET 10 anywhere: a short, wrapped message says what to install and where it looked (checked by hiding this
  machine's .NET and starting the IDE from the installed zip). Rider's own bundled .NET is tried last; whether
  Rider 2026.x bundles .NET 10 is not known here (the verifier's Rider download has no runtime folder).

- Unbuilt project with design data: the design view says "Build the project to see your sample data in bindings."
  instead of showing silent blanks. Helper is precompiled (ReadyToRun): first drawing ~0.6 s, then ~0.15 s for the
  chess window at 200 %.

**Fixed during IDE testing:** project App.axaml styles were silently dropped (the Fluent theme assembly wasn't
loaded when the XAML loader looked for it, and resources were loaded apart from the styles that use them; the
first sample hid it because Fluent has its own "accent" button style) — now covered by a pixel test; code-behind read without the read lock (IDE error); drop hint stuck after a drop;
New Project used a blocking open that hung when closing the current project; split layout flipped on undo.

- Zoom (Ctrl+= twice → 156 %): redrawn sharp, clicks still hit the right control; handles keep their screen size.
- Copy / cut / paste / duplicate (Ctrl+C/X/V/D): duplicate checked in the IDE; names stay unique (NameBox → NameBox1).
- Snap lines while moving (edges and centres of siblings and the container): screenshot mid-drag.
- Design-time data: `<Design.DataContext>` with the project's own view model shows bound text (helper render,
  `docs/screenshots/helper-design-data-binding-shows-view-model-text.png`); without a build it draws without it.
- Plugin checker: **compatible with real Rider 2026.1 (RD-261.22158.336), 2026.2 (RD-262.8665.329) and 2026.3
  (RD-263.7307.259) with no warnings**, after replacing internal/deprecated calls (`OpenProjectTask`,
  `PluginManagerCore.getPlugin`, `PluginManager.getPluginByClass`, `FileEditorManager.openFile/openTextEditor`).
  Don't reintroduce them: the plugin finds its folder with `PathManager.getJarPathForClass`, files open with
  `OpenFileDescriptor.navigate`.
- The built zip itself, installed into a clean IntelliJ IDEA (not the dev sandbox): helper starts from the zip's
  `host/` folder and draws the sample (`docs/screenshots/ide-light-installed-from-zip.png`).

**Not done:** a `dotnet new` template for Rider's New Solution dialog (File → New → Avalonia App covers it; the
official Avalonia.Templates package also works in Rider). No website is planned: the plugin's page is its
Marketplace listing and the README. (A rebuilt project restarts the helper so the new build is shown: .NET can't
unload the old one.)

**Known rough edges:** the Outline isn't pinned to the side bar on first use (open it from View → Tool Windows);
the design view follows the IDE's light/dark theme (an app set to a fixed theme is shown in that theme).

**Still needs checking in real Rider:** Run/Debug of the sample (the plugin doesn't touch run configurations);
Solution Explorer; coexistence with AvaloniaRider; Rider's own New Solution dialog (our template is a File → New
action, not a `dotnet new` template).
