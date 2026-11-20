package dev.penguinxaml.host

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What the design helper drew: the picture and where every control is (in device-independent pixels). */
class RenderResult(
    val png: ByteArray,
    val width: Double,
    val height: Double,
    val scale: Double,
    val elements: List<RenderedElement>,
)

class RenderedElement(
    val id: Int,
    val type: String,
    val x: Double, val y: Double, val w: Double, val h: Double,
    /** "panel" (holds many children), "content" (holds one) or null. */
    val container: String?,
    val rows: List<Double>, val cols: List<Double>,
) {
    fun contains(px: Double, py: Double) = px >= x && py >= y && px < x + w && py < y + h
}

class HostException(message: String) : Exception(message)

/**
 * The design helper process (PenguinXaml.Host): one per IDE, started on first use, talked to with one JSON line per
 * request. Started from an argument list (never a shell); stopped with the IDE.
 */
@Service(Service.Level.APP)
class HostClient : Disposable {
    private val log = logger<HostClient>()
    private val gson = Gson()
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    private var nextId = 1L

    @Synchronized
    fun render(xaml: String, appXaml: String?, projectDir: String?, scale: Double, dark: Boolean, assemblies: List<String>): RenderResult {
        val req = JsonObject().apply {
            addProperty("cmd", "render")
            addProperty("xaml", xaml)
            appXaml?.let { addProperty("appXaml", it) }
            projectDir?.let { addProperty("projectDir", it) }
            addProperty("scale", scale)
            addProperty("theme", if (dark) "Dark" else "Light")
            add("assemblies", gson.toJsonTree(assemblies))
        }
        val answer = call(req)
        return parseRender(answer)
    }

    @Synchronized
    private fun call(request: JsonObject): JsonObject {
        ensureStarted()
        val id = nextId++
        request.addProperty("id", id)
        try {
            input!!.write(gson.toJson(request)); input!!.newLine(); input!!.flush()
            while (true) {
                val line = output!!.readLine() ?: throw HostException("The design helper stopped unexpectedly.")
                val obj = JsonParser.parseString(line).asJsonObject
                if (obj.get("id")?.asLong == id) {
                    if (obj.get("ok")?.asBoolean != true) throw HostException(obj.get("error")?.asString ?: "The design helper couldn't do that.")
                    return obj
                }
            }
        } catch (e: java.io.IOException) {
            stop()
            throw HostException("The design helper stopped unexpectedly.")
        }
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val search = DotnetLocator.search()
        val dotnet = search.found ?: throw HostException(
            "Penguin XAML needs the .NET 10 runtime to draw the design view, and couldn't find it.\n" +
            "Install .NET 10 (for example: sudo apt install dotnet-runtime-10.0), then reopen the file.\n" +
            "Places checked:\n" + search.describe())
        val dll = hostDll() ?: throw HostException("The design helper is missing from the plugin. Try reinstalling Penguin XAML.")
        val cmd = GeneralCommandLine(dotnet.path, dll.toString())
            .withEnvironment("DOTNET_CLI_TELEMETRY_OPTOUT", "1")
            .withEnvironment("DOTNET_NOLOGO", "1")
            .withRedirectErrorStream(false)
        val p = cmd.createProcess()
        process = p
        input = p.outputStream.bufferedWriter(Charsets.UTF_8)
        output = p.inputStream.bufferedReader(Charsets.UTF_8)
        Thread({ p.errorStream.bufferedReader().forEachLine { log.info("design helper: $it") } }, "Penguin XAML helper log").apply { isDaemon = true }.start()
        val hello = output!!.readLine() ?: throw HostException("The design helper didn't start.")
        if (!hello.contains("hello")) throw HostException("The design helper didn't start properly.")
    }

    @Synchronized
    fun stop() {
        process?.let { p ->
            try { input?.close() } catch (_: Exception) {}
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null; input = null; output = null
    }

    override fun dispose() = stop()

    companion object {
        const val PLUGIN_ID = "dev.penguinxaml"

        /** Where the bundled helper is; `penguinxaml.hostDll` overrides it for development. */
        fun hostDll(): Path? {
            System.getProperty("penguinxaml.hostDll")?.let { return Path.of(it) }
            val dir = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath ?: return null
            return dir.resolve("host/PenguinXaml.Host.dll").takeIf { File(it.toString()).exists() }
        }

        internal fun parseRender(obj: JsonObject): RenderResult {
            val els = obj.getAsJsonArray("elements").map { it.asJsonObject }.map { e ->
                RenderedElement(
                    e.get("id").asInt, e.get("type").asString,
                    e.get("x").asDouble, e.get("y").asDouble, e.get("w").asDouble, e.get("h").asDouble,
                    e.get("container")?.asString,
                    e.getAsJsonArray("rows")?.map { it.asDouble } ?: emptyList(),
                    e.getAsJsonArray("cols")?.map { it.asDouble } ?: emptyList(),
                )
            }
            return RenderResult(
                java.util.Base64.getDecoder().decode(obj.get("png").asString),
                obj.get("width").asDouble, obj.get("height").asDouble, obj.get("scale").asDouble, els,
            )
        }
    }
}
