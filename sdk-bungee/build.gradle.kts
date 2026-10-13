plugins {
    `maven-publish`
}

// BungeeCord and Waterfall: Java 8 is enough.
tasks.withType<JavaCompile>().configureEach { options.release = 8 }

dependencies {
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    // The engine's classes go inside this jar (below), so the published artifact has no dependencies.
    compileOnly(project(":sdk-core"))
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc { (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet") }

base.archivesName.set("pluginanalytics-bungee")

publishing {
    publications.create<MavenPublication>("sdk") {
        artifactId = "pluginanalytics-bungee"
        from(components["java"])
    }
    // A local Maven folder: build/maven, or the one in -PpaMavenDir / gradle.properties.
    repositories.maven { url = uri(rootProject.file(providers.gradleProperty("paMavenDir").getOrElse("build/maven"))) }
}

val core = project(":sdk-core").sourceSets.main.get()
tasks.jar { from(core.output) }
tasks.named<Jar>("sourcesJar") { from(core.allSource) }
tasks.javadoc { source(core.allJava); classpath += core.compileClasspath + core.output }
