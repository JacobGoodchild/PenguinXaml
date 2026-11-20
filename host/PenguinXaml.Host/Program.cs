using System.Text.Json;
using System.Text.Json.Nodes;
using Avalonia;
using Avalonia.Headless;
using Avalonia.Threading;

namespace PenguinXaml.Host;

/// <summary>
/// The design helper. Reads one JSON request per line on stdin and writes one JSON answer per line on stdout
/// (docs/development/PROTOCOL.md). It never opens a window, never touches the network and never writes files.
/// </summary>
public static class Program
{
    public static int Main(string[] args)
    {
        if (args.Contains("--version"))
        {
            Console.WriteLine(typeof(Program).Assembly.GetName().Version);
            return 0;
        }

        // The XAML loader only finds types in assemblies already loaded: make sure the themes projects use are.
        GC.KeepAlive(typeof(Avalonia.Themes.Fluent.FluentTheme).Assembly);

        AppBuilder.Configure<DesignApp>()
            .UseSkia()
            .UseHarfBuzz()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
            .SetupWithoutStarting();

        var stdout = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
        Console.SetOut(TextWriter.Null); // nothing else may write to the protocol stream
        stdout.WriteLine(JsonSerializer.Serialize(new { kind = "hello", version = 1 }));

        string? line;
        while ((line = Console.In.ReadLine()) != null)
        {
            if (line.Length == 0) continue;
            JsonObject answer;
            long id = 0;
            try
            {
                var request = JsonNode.Parse(line)!.AsObject();
                id = request["id"]?.GetValue<long>() ?? 0;
                answer = Handle(request);
            }
            catch (Exception e)
            {
                answer = new JsonObject { ["ok"] = false, ["error"] = Messages.Plain(e) };
            }
            answer["id"] = id;
            stdout.WriteLine(answer.ToJsonString());
        }
        return 0;
    }

    private static JsonObject Handle(JsonObject request)
    {
        var cmd = request["cmd"]?.GetValue<string>();
        switch (cmd)
        {
            case "ping":
                return new JsonObject { ["ok"] = true };
            case "render":
                var r = new RenderRequest(
                    request["xaml"]?.GetValue<string>() ?? "",
                    request["appXaml"]?.GetValue<string>(),
                    request["projectDir"]?.GetValue<string>(),
                    request["scale"]?.GetValue<double>() ?? 1.0,
                    request["theme"]?.GetValue<string>() ?? "Light",
                    request["assemblies"]?.AsArray().Select(a => a!.GetValue<string>()).ToList() ?? new List<string>());
                JsonObject result = null!;
                Dispatcher.UIThread.Invoke(() => result = Renderer.Render(r));
                return result;
            default:
                return new JsonObject { ["ok"] = false, ["error"] = $"Unknown command: {cmd}" };
        }
    }
}

public sealed class DesignApp : Application
{
    // The theme comes from the project's own App.axaml when there is one (Renderer adds it per render), so the
    // application itself starts without styles.
}

public sealed record RenderRequest(string Xaml, string? AppXaml, string? ProjectDir, double Scale, string Theme, List<string> Assemblies);

internal static class Messages
{
    /// <summary>A short, plain message for the user, without stack traces.</summary>
    public static string Plain(Exception e)
    {
        var inner = e;
        while (inner is System.Reflection.TargetInvocationException { InnerException: not null } t) inner = t.InnerException;
        var msg = inner.Message;
        var nl = msg.IndexOf('\n');
        return nl > 0 ? msg[..nl].Trim() : msg.Trim();
    }
}
