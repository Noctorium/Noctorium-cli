import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Noctorium in a terminal: a music player you drive from the keyboard, and `noctorium web`, the same
 * library and the same queue in a browser on any device in the house.
 *
 * The versions are the desktop's, stated here because this is the root project and `core` and `jvm`,
 * included from Noctorium-Base, apply the Kotlin plugins without one.
 */
plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

/** The newest release tag this checkout has, such as `v0.7.0`, for a build nobody handed a version to. */
val latestReleaseTag: String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.startsWith("v") }
}.getOrNull()

/** From -PappVersion when the release workflow passes one, and otherwise from the newest tag. */
val appVersion: String = (findProperty("appVersion") as String?)?.trim()?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: latestReleaseTag?.removePrefix("v")
    ?: "0.7.0"

/** What jpackage will accept as a version: three numbers, no suffix. */
val packagedVersion: String = appVersion.substringBefore('-').split('.')
    .mapNotNull(String::toIntOrNull)
    .let { (it + listOf(0, 0, 0)).take(3) }
    .let { parts -> if (parts[0] == 0 && parts[1] == 0 && parts[2] == 0) "1.0.0" else parts.joinToString(".") }

version = appVersion

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

val ktorVersion = "3.1.3"

dependencies {
    implementation(project(":core"))
    implementation(project(":jvm"))

    // The terminal: raw keys, its size, and writing to it, on Windows consoles as well as Unix ones. The
    // JNI provider carries its own natives for both, so nothing has to be installed.
    implementation("org.jline:jline:3.29.0")

    // `noctorium web`: an HTTP server and a WebSocket for the browser player.
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")

    // QR codes, drawn in the terminal for a phone to scan: signing in, and opening the web player.
    implementation("com.google.zxing:core:3.5.3")

    implementation("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

application {
    mainClass.set("app.noctorium.cli.MainKt")
    applicationName = "noctorium"
    applicationDefaultJvmArgs = cliJvmArguments()
}

/** What the program always runs with, from Gradle and from the packaged launcher alike. */
fun cliJvmArguments() = listOf(
    // Cover art is decoded with ImageIO, which lives in java.desktop; nothing here ever opens a window.
    "-Djava.awt.headless=true",
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    // jline's terminal natives, without the warning JDK 21 prints for every library that loads some.
    "--enable-native-access=ALL-UNNAMED",
    "-Xss2m",
    "-XX:+UseSerialGC",
    "-Xshare:auto",
)

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    systemProperty("noctorium.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // Where TuiRenderCheck draws the pages, when it is asked to.
    System.getProperty("noctorium.renderTui")?.let { systemProperty("noctorium.renderTui", it) }
}

// --- The version, where the running program can read it ---

tasks.processResources {
    val version = appVersion
    inputs.property("version", version)
    from(resources.text.fromString("version=$version\n")) {
        rename { "noctorium-cli-version.properties" }
    }
}

// --- The browser player, built from noctorium-web-player and carried inside the jar ---

/**
 * Where the web player's source is: a checkout named by NOCTORIUM_WEB, one beside this repository, or the
 * web/ submodule. Null when there is none, and then the program says so at `noctorium web` rather than
 * serving an empty page.
 */
val webCheckout: File? = listOfNotNull(
    System.getenv("NOCTORIUM_WEB")?.takeIf { it.isNotBlank() }?.let(::file),
    file("../noctorium-web-player"),
    file("web"),
).firstOrNull { File(it, "package.json").isFile }

val npm = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"

val buildWebPlayer by tasks.registering(Exec::class) {
    description = "Builds the browser player with npm, if its source is here."
    val source = webCheckout
    val skip = findProperty("skipWeb") != null
    onlyIf { source != null && !skip }
    if (source != null) {
        workingDir = source
        inputs.dir(File(source, "src"))
        inputs.files(File(source, "package.json"), File(source, "index.html"))
        inputs.files(fileTree(source) { include("vite.config.*", "tsconfig*.json", "package-lock.json") })
        inputs.files(fileTree(source) { include("public/**") })
        outputs.dir(File(source, "dist"))
        // ci when there is a lockfile and node_modules is missing, so a clean checkout builds; then build.
        val install = if (File(source, "node_modules").isDirectory) "" else "$npm ci && "
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            commandLine("cmd", "/c", "${install}$npm run build")
        } else {
            commandLine("sh", "-c", "${install}$npm run build")
        }
    }
}

val webResources = layout.buildDirectory.dir("generated/web")

val copyWebPlayer by tasks.registering(Sync::class) {
    dependsOn(buildWebPlayer)
    val source = webCheckout
    if (source != null) from(File(source, "dist"))
    into(webResources.map { it.dir("web") })
}

sourceSets.main {
    resources.srcDir(webResources)
}

tasks.processResources { dependsOn(copyWebPlayer) }

// --- A folder that runs on a machine with no Java: jlink's runtime, and jpackage's launcher ---

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val isArm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it.startsWith("arm") }

val jlinkRuntime by tasks.registering(Exec::class) {
    description = "A trimmed Java runtime for the packaged CLI."
    val output = layout.buildDirectory.dir("jlink/runtime").get().asFile
    outputs.dir(output)
    doFirst { output.deleteRecursively() }
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    val jlink = launcher.map { it.metadata.installationPath.file(if (isWindows) "bin/jlink.exe" else "bin/jlink").asFile.absolutePath }
    commandLine(
        jlink.get(),
        "--add-modules",
        listOf(
            "java.base", "java.desktop", "java.logging", "java.management", "java.naming", "java.net.http",
            "java.sql", "java.xml", "jdk.crypto.ec", "jdk.unsupported", "jdk.zipfs", "jdk.charsets",
            "jdk.localedata", "java.security.jgss",
        ).joinToString(","),
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress", "zip-6",
        "--output", output.absolutePath,
    )
}

/**
 * On a Mac the folder is put together here rather than by jpackage, whose Mac launcher only comes inside an
 * application bundle -- and a terminal program that opens as a Dock icon when double-clicked, and has to be
 * reached at noctorium-cli.app/Contents/MacOS from a shell, is the wrong shape for a command. So it is the
 * shape the Linux build already has: bin/noctorium, beside the jars and the runtime, the launcher a short
 * script that finds its own folder (through the link the installer puts on PATH) and starts the runtime.
 */
fun macCliFolder(folder: File, jars: File, runtime: File) {
    fun run(vararg command: String) {
        val process = ProcessBuilder(*command).inheritIO().start()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed" }
    }
    folder.mkdirs()
    // cp rather than a Gradle copy, which would not keep the runtime's programs executable.
    run("cp", "-R", runtime.absolutePath, File(folder, "runtime").absolutePath)
    run("cp", "-R", jars.absolutePath, File(folder, "lib").absolutePath)
    val options = cliJvmArguments().joinToString(" ") { "\"$it\"" }
    val launcher = File(folder, "bin/noctorium").apply { parentFile.mkdirs() }
    launcher.writeText(
        """
        |#!/bin/sh
        |# Noctorium CLI, with the Java runtime it was built with beside it. The installer links this into
        |# ~/.local/bin, so the link is followed back to the real folder first.
        |self=${'$'}0
        |while [ -L "${'$'}self" ]; do
        |    link=${'$'}(readlink "${'$'}self")
        |    case ${'$'}link in
        |        /*) self=${'$'}link ;;
        |        *) self=${'$'}(dirname "${'$'}self")/${'$'}link ;;
        |    esac
        |done
        |home=${'$'}(cd "${'$'}(dirname "${'$'}self")/.." && pwd -P)
        |exec "${'$'}home/runtime/bin/java" $options -Dapple.awt.UIElement=true -cp "${'$'}home/lib/*" app.noctorium.cli.MainKt "${'$'}@"
        |""".trimMargin(),
    )
    launcher.setExecutable(true, false)
}

val packageCli by tasks.registering(Exec::class) {
    description = "The CLI as a folder with its own launcher and runtime: build/package/noctorium-cli."
    dependsOn(tasks.installDist, jlinkRuntime)
    val input = layout.buildDirectory.dir("install/noctorium/lib").get().asFile
    val runtime = layout.buildDirectory.dir("jlink/runtime").get().asFile
    val destination = layout.buildDirectory.dir("package").get().asFile
    outputs.dir(destination)
    doFirst { destination.deleteRecursively(); destination.mkdirs() }
    if (isMac) {
        // Nothing for Exec itself to run; the folder is made in doLast below.
        commandLine("true")
        doLast { macCliFolder(File(destination, "noctorium-cli"), input, runtime) }
        return@registering
    }
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    val jpackage = launcher.map { it.metadata.installationPath.file(if (isWindows) "bin/jpackage.exe" else "bin/jpackage").asFile.absolutePath }
    val mainJar = "noctorium-cli-$appVersion.jar"
    val arguments = mutableListOf(
        jpackage.get(),
        "--type", "app-image",
        "--name", "noctorium-cli",
        "--app-version", packagedVersion,
        "--vendor", "Noctorium",
        "--input", input.absolutePath,
        "--main-jar", mainJar,
        "--main-class", "app.noctorium.cli.MainKt",
        "--runtime-image", runtime.absolutePath,
        "--dest", destination.absolutePath,
    )
    cliJvmArguments().forEach { arguments += listOf("--java-options", it) }
    if (isWindows) arguments += listOf("--win-console", "--icon", file("packaging/noctorium.ico").absolutePath)
    else arguments += listOf("--icon", file("packaging/noctorium.png").absolutePath)
    commandLine(arguments)
    doLast {
        // jpackage names the Windows launcher after the image; the command people type is `noctorium`.
        if (isWindows) {
            val folder = File(destination, "noctorium-cli")
            File(folder, "noctorium-cli.exe").renameTo(File(folder, "noctorium.exe"))
            File(folder, "app/noctorium-cli.cfg").renameTo(File(folder, "app/noctorium.cfg"))
        } else {
            val folder = File(destination, "noctorium-cli")
            File(folder, "bin/noctorium-cli").renameTo(File(folder, "bin/noctorium"))
            File(folder, "lib/app/noctorium-cli.cfg").renameTo(File(folder, "lib/app/noctorium.cfg"))
        }
    }
}

val platformName = when {
    isWindows -> "windows-x64"
    isMac -> if (isArm) "macos-arm64" else "macos-x64"
    else -> "linux-x64"
}

val cliArchive by tasks.registering {
    description = "The packaged CLI as the release ships it: a zip on Windows, a tar.gz on Linux and the Mac."
    dependsOn(packageCli)
    val folder = layout.buildDirectory.dir("package").get().asFile
    val out = layout.buildDirectory.dir("dist").get().asFile
    val name = "noctorium-cli-$appVersion-$platformName" + if (isWindows) ".zip" else ".tar.gz"
    outputs.file(File(out, name))
    doLast {
        out.mkdirs()
        val archive = File(out, name)
        archive.delete()
        val process = if (isWindows) {
            ProcessBuilder("tar", "-a", "-c", "-f", archive.absolutePath, "noctorium-cli")
        } else {
            ProcessBuilder("tar", "-czf", archive.absolutePath, "noctorium-cli")
        }.directory(folder).inheritIO().start()
        check(process.waitFor() == 0) { "tar could not make $name" }
        println("Wrote ${archive.absolutePath}")
    }
}
