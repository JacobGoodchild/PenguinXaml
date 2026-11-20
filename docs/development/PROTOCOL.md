# Plugin ↔ design helper protocol

The plugin starts `dotnet PenguinXaml.Host.dll` (argument list, no shell) and exchanges one JSON object per line.

On start the helper writes `{"kind":"hello","version":1}`.

Request `render`:

```json
{"id": 1, "cmd": "render", "xaml": "<Window …>", "appXaml": "<Application …>", "projectDir": "/path",
 "scale": 2.0, "theme": "Light", "assemblies": ["/path/bin/Debug/net10.0/App.dll"]}
```

Answer: `{"id":1,"ok":true,"png":"<base64>","width":480,"height":300,"scale":2,"elements":[…]}` where each element is
`{"id","type","x","y","w","h","container"?: "panel"|"content","rows"?: [..],"cols"?: [..]}` in design pixels. `id` is
the element's position among the XAML's object elements in document order (property elements such as
`Grid.RowDefinitions` are not counted); `XamlDocument` in the plugin counts the same way.

Errors: `{"id":1,"ok":false,"error":"plain message"}`. `assemblies` is only sent for trusted projects.
