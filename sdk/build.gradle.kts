plugins {
    `maven-publish`
}

// Java 8 and the 1.8.8 API: the SDK must run inside any plugin on any server.
tasks.withType<JavaCompile>().configureEach { options.release = 8 }

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    // The engine's classes go inside this jar (below), so the published artifact has no dependencies.
    compileOnly(project(":sdk-core"))
}


java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc { (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet") }

base.archivesName.set("pluginanalytics-bukkit")

publishing {
    publications.create<MavenPublication>("sdk") {
        artifactId = "pluginanalytics-bukkit"
        from(components["java"])
    }
    // A local Maven folder: build/maven, or the one in -PpaMavenDir / gradle.properties.
    repositories.maven { url = uri(rootProject.file(providers.gradleProperty("paMavenDir").getOrElse("build/maven"))) }
}

val core = project(":sdk-core").sourceSets.main.get()
tasks.jar { from(core.output) }
tasks.named<Jar>("sourcesJar") { from(core.allSource) }
tasks.javadoc { source(core.allJava); classpath += core.compileClasspath + core.output }
