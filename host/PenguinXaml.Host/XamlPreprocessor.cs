using System.Reflection;
using System.Xml.Linq;
using Avalonia;
using Avalonia.Controls;

namespace PenguinXaml.Host;

/// <summary>
/// Turns the user's XAML into something the runtime loader can show at design time, without the compiled app:
/// x:Class and event handlers are removed, image sources are remembered (loaded from the project folder later), and
/// every control gets a design id: its position among the document's object elements (not property elements such as
/// Grid.RowDefinitions), counted in document order. The plugin counts the same way, so ids map clicks to XAML.
/// </summary>
public static class XamlPreprocessor
{
    public const string AvaloniaNs = "https://github.com/avaloniaui";
    public const string XamlNs = "http://schemas.microsoft.com/winfx/2006/xaml";
    public const string DesignNs = "clr-namespace:PenguinXaml.Host;assembly=PenguinXaml.Host";

    private static readonly string[] TemplateLike = { "Template", "Style", "Styles", "Resources", "ResourceDictionary", "DataTemplates", "ControlTheme" };

    public sealed class Result
    {
        public required XDocument Document { get; init; }
        public Dictionary<int, string> ImageSources { get; } = new();
        public Dictionary<int, string> TypeNames { get; } = new();
        public double? DesignWidth { get; set; }
        public double? DesignHeight { get; set; }
    }

    public static Result Prepare(string xaml)
    {
        var doc = XDocument.Parse(xaml, LoadOptions.PreserveWhitespace);
        var result = new Result { Document = doc };
        var root = doc.Root ?? throw new InvalidOperationException("The file has no root element.");
        root.Attribute(XName.Get("Class", XamlNs))?.Remove();
        root.SetAttributeValue(XNamespace.Xmlns + "pxd", DesignNs);

        foreach (var a in root.Attributes())
        {
            if (a.Name.LocalName == "DesignWidth" && double.TryParse(a.Value, System.Globalization.CultureInfo.InvariantCulture, out var w)) result.DesignWidth = w;
            if (a.Name.LocalName == "DesignHeight" && double.TryParse(a.Value, System.Globalization.CultureInfo.InvariantCulture, out var h)) result.DesignHeight = h;
        }

        var id = 0;
        foreach (var el in doc.Descendants().ToList())
        {
            if (IsPropertyElement(el)) continue;
            var myId = id++;
            var type = ResolveAvaloniaType(el);
            result.TypeNames[myId] = el.Name.LocalName;
            if (type == null || !typeof(Control).IsAssignableFrom(type) || InsideTemplate(el)) continue;

            foreach (var attr in el.Attributes().ToList())
            {
                if (attr.IsNamespaceDeclaration || attr.Name.Namespace != XNamespace.None) continue;
                if (type.GetEvent(attr.Name.LocalName) != null) attr.Remove();
            }
            if (typeof(Avalonia.Controls.Image).IsAssignableFrom(type) && el.Attribute("Source") is { } src && !src.Value.StartsWith('{'))
            {
                result.ImageSources[myId] = src.Value;
                src.Remove();
            }
            if (typeof(Window).IsAssignableFrom(type)) el.Attribute("Icon")?.Remove();
            el.SetAttributeValue(XName.Get("DesignId.Id", DesignNs), myId);
        }
        return result;
    }

    /// <summary>Removes compiled-binding hints, for a second try when the view model's type can't be found.</summary>
    public static void RelaxBindings(XDocument doc)
    {
        foreach (var el in doc.Descendants())
            foreach (var a in el.Attributes().Where(a => a.Name.Namespace == XamlNs && a.Name.LocalName is "DataType" or "CompileBindings").ToList())
                a.Remove();
        doc.Root!.SetAttributeValue(XName.Get("CompileBindings", XamlNs), "False");
    }

    public static bool IsPropertyElement(XElement el) => el.Name.LocalName.Contains('.');

    private static bool InsideTemplate(XElement el)
    {
        for (var p = el.Parent; p != null; p = p.Parent)
        {
            var name = p.Name.LocalName;
            var last = name.Contains('.') ? name[(name.LastIndexOf('.') + 1)..] : name;
            if (TemplateLike.Any(t => last.EndsWith(t, StringComparison.Ordinal))) return true;
        }
        return false;
    }

    private static readonly Dictionary<string, Type?> TypeCache = new();
    private static readonly Assembly[] AvaloniaAssemblies =
    {
        typeof(Control).Assembly, typeof(Avalonia.Controls.Primitives.TemplatedControl).Assembly,
        typeof(Avalonia.Controls.Shapes.Rectangle).Assembly,
    };

    public static Type? ResolveAvaloniaType(XElement el)
    {
        var ns = el.Name.NamespaceName;
        var name = el.Name.LocalName;
        var key = ns + "|" + name;
        if (TypeCache.TryGetValue(key, out var cached)) return cached;
        Type? found = null;
        if (ns == AvaloniaNs)
        {
            found = AvaloniaAssemblies.Distinct()
                .SelectMany(a => a.GetExportedTypes())
                .FirstOrDefault(t => t.Name == name && (t.Namespace?.StartsWith("Avalonia", StringComparison.Ordinal) ?? false) && typeof(AvaloniaObject).IsAssignableFrom(t));
        }
        else if (ns.StartsWith("clr-namespace:", StringComparison.Ordinal) || ns.StartsWith("using:", StringComparison.Ordinal))
        {
            var clrNs = ns.StartsWith("using:") ? ns[6..] : ns[14..].Split(';')[0];
            found = AppDomain.CurrentDomain.GetAssemblies()
                .Select(a => { try { return a.GetType(clrNs + "." + name); } catch { return null; } })
                .FirstOrDefault(t => t != null);
        }
        TypeCache[key] = found;
        return found;
    }
}

/// <summary>The attached property that carries each control's design id through loading.</summary>
public sealed class DesignId : AvaloniaObject
{
    public static readonly AttachedProperty<int> IdProperty =
        AvaloniaProperty.RegisterAttached<DesignId, Control, int>("Id", -1);

    public static int GetId(Control c) => c.GetValue(IdProperty);
    public static void SetId(Control c, int value) => c.SetValue(IdProperty, value);
}
