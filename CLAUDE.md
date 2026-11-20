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
- `./gradlew runIdea -PopenPath=samples/HelloAvalonia -PtestMachine` for UI checks in the free IntelliJ IDEA (the
  sandbox Rider needs a login). `-PuiScale=2` simulates a 200 % screen.
- CI (`.github/workflows/ci.yml`): tests, zip, zip contents check, sample build; hand-started signed Marketplace
  build + plugin checker against Rider 2026.1 / 2026.2 / 2026.3.

## Status (20 Nov 2026, first session, ~90 minutes)

**Working (tested):**
- XAML model, minimal edits, Grid definitions (short and long form), move between panels, code-behind handler
  insertion, drop planning with Grid row/column labels: 14 unit tests.
- Design helper renders the sample (project styles, Fluent theme, project image) sharp at 200 %, light and dark:
  `docs/screenshots/helper-sample-*.png` (looked at). Tested from the built zip too.
- Plugin compiles against 2026.1 with no deprecation warnings; zip contains the helper and all licence files.

**Written but not yet checked in an IDE (no screenshots yet):** split view, canvas selection/handles, move/resize,
Toolbox drag and drop with drop labels, Delete, Grid rail (click to add row/column, drag borders), Properties
(multi-select shared values), events and double-click → handler, Document Outline, caret ↔ selection sync,
tool windows hidden until a XAML file opens.

**Not done:** New Project template; keyboard focus checks (Tab, arrows to nudge); copy/paste; snap lines; zoom;
property editors beyond plain text (colour/enum pickers); design-time DataContext / bindings with sample data;
UserControl sizing from d:DesignWidth beyond basics; GitHub Pages site; plugin verifier run (CI job written, not run).

**Best next steps:** 1) run in IntelliJ IDEA with the sample, take light/dark/200 % screenshots, fix what breaks;
2) New Project template (an Avalonia app like `samples/HelloAvalonia`); 3) keyboard handling and focus;
4) run the `verify-plugin` CI job; 5) colour/enum editors in Properties.

**Still needs checking in real Rider:** everything in the UI; Run/Debug of the sample (should be untouched, as the
plugin doesn't change run configurations); Solution Explorer nesting of `.axaml.cs`; coexistence with AvaloniaRider.
