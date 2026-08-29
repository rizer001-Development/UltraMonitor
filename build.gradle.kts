plugins {
    id("java")
    id("application")
}

group = "com.ultramonitor"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

val javafxVersion = "25.0.4"

// OpenJFX publishes the actual classes under an OS classifier (the plain
// artifact is empty). The classifier is chosen from the build machine so the
// portable jar carries the right native libraries.
val javafxPlatform = providers.gradleProperty("javafx.platform")
    .orElse(when {
        System.getProperty("os.name").lowercase().contains("win") -> "win"
        System.getProperty("os.name").lowercase().contains("mac") -> "mac"
        System.getProperty("os.name").lowercase().contains("linux") -> "linux"
        else -> "win"
    })
    .get()

// LWJGL bundles per-platform natives under a classifier; map it from the same
// platform property used for JavaFX (win / linux / mac).
val lwjglNatives = when (javafxPlatform) {
    "linux" -> "natives-linux"
    "mac" -> if (System.getProperty("os.arch").lowercase().contains("aarch64")
            || System.getProperty("os.arch").lowercase().contains("arm")) {
        "natives-macos-arm64"
    } else {
        "natives-macos"
    }
    else -> "natives-windows"
}
val lwjglVersion = "3.3.4"

dependencies {
    // JavaFX UI toolkit
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")

    // GPU stress: OpenGL via LWJGL (real hardware-accelerated rendering)
    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion:$lwjglNatives")
    implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglNatives")

    // Hardware monitoring (CPU, RAM, disks, network, sensors)
    implementation("com.github.oshi:oshi-core:7.4.3")

    // Silent SLF4J backend (OSHI logs through SLF4J; we don't need log output)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.ultramonitor.Main"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Self-contained portable jar: app classes + all runtime dependencies merged.
tasks.register<Jar>("fatJar") {
    archiveFileName = "UltraMonitor.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.ultramonitor.Main"
        attributes["Implementation-Title"] = "UltraMonitor"
        attributes["Implementation-Version"] = project.version
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/versions/**/module-info.class")
        exclude("module-info.class")
    }
}
