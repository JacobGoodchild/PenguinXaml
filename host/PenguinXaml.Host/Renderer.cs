using System.Reflection;
using System.Runtime.Loader;
using System.Text.Json.Nodes;
using System.Xml.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Controls.Primitives;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using Avalonia.VisualTree;

namespace PenguinXaml.Host;

/// <summary>Loads a XAML document at design time and draws it, returning a PNG and where every control is.</summary>
public static class Renderer
{
    private static readonly HashSet<string> LoadedAssemblies = new();

    public static JsonObject Render(RenderRequest r)
    {
        // The project's own build output (custom controls, view models). The plugin only sends these for trusted projects.
        foreach (var path in r.Assemblies)
        {
            if (!File.Exists(path) || !LoadedAssemblies.Add(path)) continue;
            try { AssemblyLoadContext.Default.LoadFromAssemblyPath(path); } catch { /* shown without it */ }
        }

        var prepared = XamlPreprocessor.Prepare(r.Xaml);
        object loaded;
        try
        {
            loaded = Load(prepared.Document);
        }
        catch (Exception) when (prepared.Document.Descendants().Any(e => e.Attributes().Any(a => a.Name.LocalName == "DataType")))
        {
            XamlPreprocessor.RelaxBindings(prepared.Document);
            loaded = Load(prepared.Document);
        }

        var surface = new Border { ClipToBounds = true };
        var host = new Window { SizeToContent = SizeToContent.WidthAndHeight, Content = surface };
        host.RequestedThemeVariant = r.Theme == "Dark" ? ThemeVariant.Dark : ThemeVariant.Light;
        ApplyAppStyles(host, r.AppXaml);

        double width, height;
        if (loaded is Window w)
        {
            width = Pick(w.Width, prepared.DesignWidth, 800);
            height = Pick(w.Height, prepared.DesignHeight, 450);
            var content = w.Content;
            w.Content = null;
            surface.Child = new ContentControl { Content = content };
            CopyWindowLook(w, surface);
            DesignId.SetId(surface, DesignId.GetId(w));
            if (w.RequestedThemeVariant != null) host.RequestedThemeVariant = w.RequestedThemeVariant;
        }
        else if (loaded is Control c)
        {
            width = Pick(c.Width, prepared.DesignWidth, double.NaN);
            height = Pick(c.Height, prepared.DesignHeight, double.NaN);
            surface.Child = c;
        }
        else
        {
            throw new InvalidOperationException($"A {loaded.GetType().Name} can't be shown in the designer.");
        }

        if (!double.IsNaN(width)) surface.Width = width;
        if (!double.IsNaN(height)) surface.Height = height;
        if (surface.Background == null)
            surface.Bind(Border.BackgroundProperty, surface.GetResourceObservable("SystemControlBackgroundAltHighBrush"));

        host.Show();
        LoadImages(host, prepared, r.ProjectDir);
        host.UpdateLayout();
        Avalonia.Threading.Dispatcher.UIThread.RunJobs();
        host.UpdateLayout();

        var size = surface.Bounds.Size;
        if (size.Width < 1 || size.Height < 1) size = new Size(Math.Max(size.Width, 1), Math.Max(size.Height, 1));
        var scale = Math.Clamp(r.Scale, 0.5, 4.0);
        var pixels = new PixelSize((int)Math.Ceiling(size.Width * scale), (int)Math.Ceiling(size.Height * scale));
        using var bitmap = new RenderTargetBitmap(pixels, new Vector(96 * scale, 96 * scale));
        bitmap.Render(surface);
        using var png = new MemoryStream();
        bitmap.Save(png);

        var elements = new JsonArray();
        var seen = new HashSet<int>();
        foreach (var v in surface.GetSelfAndVisualDescendants().OfType<Control>())
        {
            var id = DesignId.GetId(v);
            if (id < 0 || !seen.Add(id) || !v.IsEffectivelyVisible) continue;
            var p = v.TranslatePoint(new Point(0, 0), surface) ?? new Point(0, 0);
            var e = new JsonObject
            {
                ["id"] = id, ["type"] = prepared.TypeNames.GetValueOrDefault(id, v.GetType().Name),
                ["x"] = Round(p.X), ["y"] = Round(p.Y), ["w"] = Round(v.Bounds.Width), ["h"] = Round(v.Bounds.Height),
            };
            var container = v is Panel ? "panel" : v is ContentControl or Decorator ? "content" : null;
            if (ReferenceEquals(v, surface)) container = "content";
            if (container != null) e["container"] = container;
            if (v is Grid g)
            {
                e["cols"] = new JsonArray(g.ColumnDefinitions.Select(d => (JsonNode)Round(d.ActualWidth)).ToArray());
                e["rows"] = new JsonArray(g.RowDefinitions.Select(d => (JsonNode)Round(d.ActualHeight)).ToArray());
            }
            elements.Add(e);
        }
        host.Close();

        return new JsonObject
        {
            ["ok"] = true, ["png"] = Convert.ToBase64String(png.ToArray()),
            ["width"] = Round(size.Width), ["height"] = Round(size.Height), ["scale"] = scale, ["elements"] = elements,
        };
    }

    private static object Load(XDocument doc)
    {
        var text = doc.ToString(SaveOptions.DisableFormatting);
        return AvaloniaRuntimeXamlLoader.Load(text, typeof(DesignId).Assembly, designMode: true);
    }

    private static double Pick(double own, double? design, double fallback) =>
        !double.IsNaN(own) ? own : design ?? fallback;

    private static double Round(double v) => Math.Round(v, 2);

    private static void CopyWindowLook(Window w, Border surface)
    {
        if (w.IsSet(TemplatedControl.BackgroundProperty)) surface.Background = w.Background;
        foreach (var s in w.Styles.ToList()) { w.Styles.Remove(s); surface.Styles.Add(s); }
        foreach (var kv in w.Resources.ToList()) surface.Resources[kv.Key] = kv.Value;
        foreach (var md in w.Resources.MergedDictionaries.ToList()) surface.Resources.MergedDictionaries.Add(md);
        surface.DataContext = w.DataContext;
        if (w.IsSet(TemplatedControl.FontFamilyProperty)) TextElement.SetFontFamily(surface, w.FontFamily);
        if (w.IsSet(TemplatedControl.FontSizeProperty)) TextElement.SetFontSize(surface, w.FontSize);
        if (w.IsSet(TemplatedControl.ForegroundProperty)) TextElement.SetForeground(surface, w.Foreground);
        surface.Padding = w.Padding;
    }

    /// <summary>The project's App.axaml styles and resources (its theme), or the Fluent theme when there is none.</summary>
    private static void ApplyAppStyles(Window host, string? appXaml)
    {
        if (string.IsNullOrWhiteSpace(appXaml))
        {
            host.Styles.Add(new FluentTheme());
            return;
        }
        var doc = XDocument.Parse(appXaml);
        var root = doc.Root!;
        var nsDecls = root.Attributes().Where(a => a.IsNamespaceDeclaration).ToList();
        XElement? Section(string name) => root.Elements().FirstOrDefault(e => e.Name.LocalName == "Application." + name);

        var styles = Section("Styles");
        var added = false;
        if (styles != null)
        {
            var s = new XElement(XName.Get("Styles", XamlPreprocessor.AvaloniaNs), nsDecls, styles.Elements());
            try
            {
                if (AvaloniaRuntimeXamlLoader.Load(s.ToString(), typeof(DesignId).Assembly, designMode: true) is Styles loaded)
                {
                    host.Styles.Add(loaded);
                    added = loaded.Count > 0;
                }
            }
            catch { /* fall back to the default theme below */ }
        }
        if (!added) host.Styles.Insert(0, new FluentTheme());

        var resources = Section("Resources");
        if (resources != null)
        {
            var inner = resources.Elements().ToList();
            var dict = inner.Count == 1 && inner[0].Name.LocalName == "ResourceDictionary"
                ? new XElement(inner[0].Name, nsDecls, inner[0].Attributes().Where(a => !a.IsNamespaceDeclaration), inner[0].Nodes())
                : new XElement(XName.Get("ResourceDictionary", XamlPreprocessor.AvaloniaNs), nsDecls, inner);
            try
            {
                if (AvaloniaRuntimeXamlLoader.Load(dict.ToString(), typeof(DesignId).Assembly, designMode: true) is IResourceDictionary rd)
                    host.Resources.MergedDictionaries.Add(rd);
            }
            catch { /* shown without them */ }
        }
        if (root.Attribute("RequestedThemeVariant")?.Value is { } variant && variant != "Default")
            host.RequestedThemeVariant = variant == "Dark" ? ThemeVariant.Dark : ThemeVariant.Light;
    }

    /// <summary>Images whose Source is a path in the project ("Assets/logo.png", "/Assets/logo.png", "avares://App/Assets/logo.png").</summary>
    private static void LoadImages(Window host, XamlPreprocessor.Result prepared, string? projectDir)
    {
        if (projectDir == null || prepared.ImageSources.Count == 0) return;
        var full = Path.GetFullPath(projectDir);
        foreach (var img in host.GetVisualDescendants().OfType<Image>())
        {
            if (!prepared.ImageSources.TryGetValue(DesignId.GetId(img), out var src)) continue;
            var rel = src;
            if (rel.StartsWith("avares://", StringComparison.Ordinal))
            {
                var slash = rel.IndexOf('/', 9);
                rel = slash < 0 ? "" : rel[(slash + 1)..];
            }
            rel = rel.TrimStart('/');
            var path = Path.GetFullPath(Path.Combine(full, rel));
            if (!path.StartsWith(full + Path.DirectorySeparatorChar, StringComparison.Ordinal) || !File.Exists(path)) continue;
            try { img.Source = new Bitmap(path); } catch { /* left empty */ }
        }
    }
}
