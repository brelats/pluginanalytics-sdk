plugins {
    `maven-publish`
}

// Fabric Loader runs on Java 8 and up (Minecraft 1.14+); the engine inside is Java 8 too.
tasks.withType<JavaCompile>().configureEach { options.release = 8 }

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    // Only the loader's API: no Minecraft classes and no Loom, so one jar works on every Minecraft version.
    // Fabric API and Minecraft are reached by reflection.
    compileOnly("net.fabricmc:fabric-loader:0.16.14") { isTransitive = false }
    // The engine's classes go inside this jar (below), so the published artifact has no dependencies.
    compileOnly(project(":sdk-core"))
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc { (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet") }

base.archivesName.set("pluginanalytics-fabric")

publishing {
    publications.create<MavenPublication>("sdk") {
        artifactId = "pluginanalytics-fabric"
        from(components["java"])
    }
    // A local Maven folder: build/maven, or the one in -PpaMavenDir / gradle.properties.
    repositories.maven { url = uri(rootProject.file(providers.gradleProperty("paMavenDir").getOrElse("build/maven"))) }
}

val core = project(":sdk-core").sourceSets.main.get()
tasks.jar { from(core.output) }
tasks.named<Jar>("sourcesJar") { from(core.allSource) }
tasks.javadoc { source(core.allJava); classpath += core.compileClasspath + core.output }
