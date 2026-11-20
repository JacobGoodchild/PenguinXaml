# Third-party notices

Penguin XAML is MIT-licensed (see `LICENSE`). Its design helper bundles the libraries below. Their licence files are
shipped in the plugin's `licenses/` folder; the build stops if one is missing.

| Library | Licence | Licence file |
| --- | --- | --- |
| Avalonia, Avalonia.Headless, Avalonia.Skia, Avalonia.HarfBuzz, Avalonia.Themes.Fluent, Avalonia.Markup.Xaml.Loader, Avalonia.Remote.Protocol (AvaloniaUI OÜ) | MIT | `licenses/Avalonia-LICENSE.md` |
| Avalonia.Fonts.Inter (the Inter typeface, The Inter Project Authors) | SIL Open Font License 1.1 | `licenses/Inter-OFL.txt` |
| MicroCom.Runtime | MIT | `licenses/MicroCom-LICENSE` |
| SkiaSharp, SkiaSharp.NativeAssets.Linux, SkiaSharp.NativeAssets.macOS, SkiaSharp.NativeAssets.Win32, SkiaSharp.NativeAssets.WebAssembly (Microsoft; includes Skia, Google) | MIT, with Skia's BSD-3-Clause and others listed in the package | `licenses/packages/SkiaSharp*/` |
| HarfBuzzSharp, HarfBuzzSharp.NativeAssets.Linux, HarfBuzzSharp.NativeAssets.macOS, HarfBuzzSharp.NativeAssets.Win32, HarfBuzzSharp.NativeAssets.WebAssembly (Microsoft; includes HarfBuzz) | MIT, with HarfBuzz's Old MIT licence in the package | `licenses/packages/HarfBuzzSharp*/` |

Avalonia.BuildServices is used only while building the helper (it is not shipped); the build turns its usage
reporting off with `AVALONIA_TELEMETRY_OPTOUT=1`.
