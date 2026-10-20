plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    java
}

base {
    archivesName = "oresim"
    version = "1.0.0"
}

repositories {
    mavenCentral()
    mavenLocal()
    maven { url = uri("https://maven.ccbluex.net/releases") }
    maven { url = uri("https://maven.ccbluex.net/snapshots") }
    maven { url = uri("https://maven.fabricmc.net/") }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.160.5+26.3")
    implementation("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
    implementation("net.ccbluex:liquidbounce:0.41.0+26.3")
    // Baritone is only needed at compile time; the add-on talks to it only when the mod is installed.
    compileOnly(files(providers.gradleProperty("baritoneJar").orElse(
        System.getProperty("user.home") + "/.local/share/liquidlauncher/mod_cache/baritone/baritone-fabric/26.3-1.11.1-21-gd12a75c4/baritone-fabric-26.3-1.11.1-21-gd12a75c4.jar")))
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release = 25 }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
