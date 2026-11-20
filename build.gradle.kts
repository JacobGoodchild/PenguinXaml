plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // Penguin XAML uses only the shared IntelliJ Platform, so it compiles the same against any 2026.x IDE.
        // It builds against the free IntelliJ IDEA 2026.1 (the oldest supported platform) by default;
        // -PbuildAgainst=rider builds against Rider instead, -PlocalIde=/path uses an IDE already on disk.
        val localIde = providers.gradleProperty("localIde").orNull
        when {
            localIde != null -> local(localIde)
            providers.gradleProperty("buildAgainst").orNull == "rider" -> rider(providers.gradleProperty("riderVersion")) { useInstaller = false }
            else -> intellijIdea(providers.gradleProperty("ideaVersion"))
        }
        jetbrainsRuntime()
        pluginVerifier("1.411")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "261" }
        changeNotes = providers.fileContents(layout.projectDirectory.file("docs/CHANGE-NOTES.html")).asText
    }
    buildSearchableOptions = false

    // Marketplace signing and upload read their secrets from environment variables only (on GitHub: repository
    // secrets). Without them these tasks stop. See docs/development/MARKETPLACE.md.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // ./gradlew verifyPlugin: JetBrains' plugin checker against real Rider releases (each a ~3 GB download).
    pluginVerification {
        ides {
            val localIde = providers.gradleProperty("verifyLocalIde").orNull
            if (localIde != null) local(localIde) else {
                for (v in listOf("2026.1", "2026.2", "2026.3"))
                    create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.Rider, v) { useInstaller = false }
            }
        }
    }
}

// The Marketplace build (-PriderOnly=true) also requires Rider, so it is offered to Rider users only. Day-to-day
// builds need only the shared platform, so the UI checks can run in the free IntelliJ IDEA.
tasks.patchPluginXml {
    val riderOnly = providers.gradleProperty("riderOnly").map { it == "true" }.orElse(false)
    inputs.property("riderOnly", riderOnly)
    doLast {
        if (!riderOnly.get()) return@doLast
        val out = outputFile.get().asFile
        out.writeText(out.readText().replaceFirst("<depends>com.intellij.modules.platform</depends>",
            "<depends>com.intellij.modules.platform</depends>\n  <depends>com.intellij.modules.rider</depends>"))
    }
}

tasks.test { useJUnitPlatform() }

// ---- The design helper (.NET), bundled into the plugin ------------------------------------------------

val dotnet: String = providers.gradleProperty("dotnetPath").orNull
    ?: listOf(System.getenv("DOTNET_ROOT")?.let { "$it/dotnet" }, "/opt/dotnet/dotnet", "/usr/share/dotnet/dotnet", "/usr/lib/dotnet/dotnet")
        .firstOrNull { it != null && file(it).canExecute() } ?: "dotnet"

val hostOut = layout.buildDirectory.dir("host")

val publishHost by tasks.registering(Exec::class) {
    description = "Builds PenguinXaml.Host (renders XAML with Avalonia) for any OS with a .NET 10 runtime."
    inputs.files(fileTree("host") { exclude("**/bin/**", "**/obj/**") })
    outputs.dir(hostOut)
    environment("DOTNET_CLI_TELEMETRY_OPTOUT", "1")
    environment("DOTNET_NOLOGO", "1")
    // Avalonia's build package (Avalonia.BuildServices) reports usage during builds unless this is set.
    environment("AVALONIA_TELEMETRY_OPTOUT", "1")
    commandLine(
        dotnet, "publish", file("host/PenguinXaml.Host/PenguinXaml.Host.csproj").absolutePath,
        "-c", "Release", "-r", "linux-x64", "--self-contained", "false", "-p:RestoreLockedMode=true",
        "-p:DebugType=none", "-o", hostOut.get().asFile.absolutePath,
    )
}

// Licence files of every NuGet package whose binaries ship in the plugin, taken from the restored packages so they
// match host/PenguinXaml.Host/packages.lock.json. A missing one stops the build.
val nugetPackages = File(System.getenv("NUGET_PACKAGES") ?: (System.getProperty("user.home") + "/.nuget/packages"))
// Packages that carry their own licence files:
val shippedPackages = listOf("SkiaSharp", "SkiaSharp.NativeAssets.Linux", "HarfBuzzSharp", "HarfBuzzSharp.NativeAssets.Linux")
// Packages whose licence text is kept in licenses/ (their packages only name the licence):
val repoLicences = mapOf(
    "Avalonia" to "Avalonia-LICENSE.md", "MicroCom.Runtime" to "MicroCom-LICENSE", "Avalonia.Fonts.Inter" to "Inter-OFL.txt",
)
val packageNotices by tasks.registering(Sync::class) {
    description = "Collects the licence files of the NuGet packages bundled with the design helper."
    dependsOn(publishHost)
    into(layout.buildDirectory.dir("package-notices"))
    val lock = groovy.json.JsonSlurper().parse(file("host/PenguinXaml.Host/packages.lock.json")) as Map<*, *>
    val resolved = (lock["dependencies"] as Map<*, *>).values.flatMap { (it as Map<*, *>).entries }
        .associate { it.key as String to (it.value as Map<*, *>)["resolved"] as String? }
    for (name in shippedPackages) {
        val version = resolved[name] ?: error("$name is not in packages.lock.json")
        from(nugetPackages.resolve("${name.lowercase()}/$version")) {
            include("LICENSE*", "license*", "THIRD-PARTY-NOTICES*", "ThirdPartyNotices*"); into(name)
        }
    }
    doLast {
        for (name in shippedPackages)
            check(destinationDir.resolve(name).listFiles()?.isNotEmpty() == true) { "No licence file found for $name in $nugetPackages" }
        for ((name, f) in repoLicences) check(file("licenses/$f").isFile) { "licenses/$f (for $name) is missing" }
        // Every package in the lock file must be named in THIRD-PARTY-NOTICES.md.
        val notices = file("THIRD-PARTY-NOTICES.md").readText()
        val missing = resolved.keys.filter { !notices.contains(it) && !it.startsWith("Microsoft.") && it != "Avalonia.BuildServices" }
        check(missing.isEmpty()) { "THIRD-PARTY-NOTICES.md doesn't mention: $missing" }
    }
}

tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    dependsOn(publishHost, packageNotices)
    from(hostOut) { into(pluginName.map { "$it/host" }) }
    from("LICENSE") { into(pluginName.map { "$it/licenses" }) }
    from("THIRD-PARTY-NOTICES.md") { into(pluginName.map { "$it/licenses" }) }
    from(packageNotices) { into(pluginName.map { "$it/licenses/packages" }) }
    from("licenses") { into(pluginName.map { "$it/licenses" }) }
}

// UI checks run in the free IntelliJ IDEA (the sandbox Rider needs a login): ./gradlew runIdea -PopenPath=...
intellijPlatformTesting {
    runIde {
        register("runIdea") {
            plugins { disablePlugin("com.intellij.platform.daemon") }
            task {
                args(listOfNotNull(providers.gradleProperty("openPath").orNull, providers.gradleProperty("openFile").orNull))
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf("-Dide.show.tips.on.startup.default.value=false", "-Djb.consents.confirmation.enabled=false", "-Djb.privacy.policy.text=<!--999.999-->") +
                    listOfNotNull(providers.gradleProperty("uiScale").orNull?.let { "-Dsun.java2d.uiScale=$it" }) +
                    // Only on a throwaway test machine (-PtestMachine): trust every project and hide error pop-ups.
                    if (providers.gradleProperty("testMachine").isPresent) listOf("-Didea.trust.all.projects=true",
                        "-Didea.fatal.error.notification=disabled") else emptyList()
                }
            }
        }
    }
}

tasks.named("verifyPluginSignature") { dependsOn(tasks.named("signPlugin")) }
