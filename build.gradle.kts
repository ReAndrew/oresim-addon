plugins {
    alias(libs.plugins.fabric.loom)
    java
}

base {
    archivesName = project.property("archives_base_name") as String
    // The Minecraft version the add-on is built for goes into its version, e.g. 1.0.0+26.3
    version = "${project.property("mod_version")}+${libs.versions.minecraft.get()}"
}

repositories {
    mavenCentral()
    mavenLocal()
    maven { name = "CCBlueX Releases"; url = uri("https://maven.ccbluex.net/releases") }
    maven { name = "CCBlueX Snapshots"; url = uri("https://maven.ccbluex.net/snapshots") }
    maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
}

// No mappings line and plain `implementation`: Loom uses Mojang's official names, like LiquidBounce itself.
dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    implementation(libs.fabric.kotlin)
    implementation(libs.liquidbounce)
    compileOnly(libs.baritone)
}

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

tasks.processResources {
    val modVersion = providers.gradleProperty("mod_version").zip(libs.versions.minecraft) { version, minecraft ->
        "$version+$minecraft"
    }
    val minecraftVersion = libs.versions.minecraft
    val loaderVersion = libs.versions.fabric.loader

    inputs.property("version", modVersion)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to modVersion.get(),
                "minecraft_version" to minecraftVersion.get(),
                "loader_version" to loaderVersion.get(),
            )
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = libs.versions.jdk.get().toInt()
}

java {
    withSourcesJar()
    toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt()) }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}
