pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "noctorium-cli"

/*
 * Noctorium in a terminal, and in a browser, on the same core as the desktop and the phone.
 *
 * `core` is everything that does not care what it runs on -- the library, the queue, playlists, lyrics,
 * scrobbling, Connect -- and `jvm` is what every computer build shares under its window: yt-dlp and mpv,
 * saving as MP3, Discord, the credential store. Both come from Noctorium-Base, found the way the desktop
 * finds it: a checkout named by NOCTORIUM_BASE, one beside this repository, or the base/ submodule.
 */
val baseCheckout: File = listOfNotNull(
    System.getenv("NOCTORIUM_BASE")?.takeIf { it.isNotBlank() }?.let(::file),
    file("../Noctorium-Base"),
    file("base"),
).firstOrNull { File(it, "core/build.gradle.kts").isFile && File(it, "jvm/build.gradle.kts").isFile }
    ?: error(
        "Noctorium-Base was not found. Either check it out beside this repository as ../Noctorium-Base, " +
            "run `git submodule update --init` to fetch the base/ submodule, or set NOCTORIUM_BASE to a checkout.",
    )

include(":core")
project(":core").projectDir = File(baseCheckout, "core")
include(":jvm")
project(":jvm").projectDir = File(baseCheckout, "jvm")
