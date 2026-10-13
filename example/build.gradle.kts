import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow")
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT")
    implementation(project(":sdk"))
}

tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("AnalyticsExample")
    archiveClassifier.set("")
    relocate("com.gamepathics.pluginanalytics", "com.gamepathics.paexample.analytics")
}

tasks.build { dependsOn("shadowJar") }
