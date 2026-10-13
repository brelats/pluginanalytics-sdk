import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow")
}

tasks.withType<JavaCompile>().configureEach { options.release = 8 }

dependencies {
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    implementation(project(":sdk-bungee"))
}

tasks.processResources { filesMatching("bungee.yml") { expand("version" to project.version) } }

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("AnalyticsExampleBungee")
    archiveClassifier.set("")
    relocate("com.gamepathics.pluginanalytics", "com.gamepathics.paexample.bungee.analytics")
}

tasks.build { dependsOn("shadowJar") }
