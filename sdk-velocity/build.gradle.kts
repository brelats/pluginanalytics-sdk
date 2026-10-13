plugins {
    `maven-publish`
}

// Velocity 3.5+ runs on Java 21; the engine inside stays Java 8.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile>().configureEach { options.release = 21 }

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.1")
    // The engine's classes go inside this jar (below), so the published artifact has no dependencies.
    compileOnly(project(":sdk-core"))
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc { (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet") }

base.archivesName.set("pluginanalytics-velocity")

publishing {
    publications.create<MavenPublication>("sdk") {
        artifactId = "pluginanalytics-velocity"
        from(components["java"])
    }
    // A local Maven folder: build/maven, or the one in -PpaMavenDir / gradle.properties.
    repositories.maven { url = uri(rootProject.file(providers.gradleProperty("paMavenDir").getOrElse("build/maven"))) }
}

val core = project(":sdk-core").sourceSets.main.get()
tasks.jar { from(core.output) }
tasks.named<Jar>("sourcesJar") { from(core.allSource) }
tasks.javadoc { source(core.allJava); classpath += core.compileClasspath + core.output }
