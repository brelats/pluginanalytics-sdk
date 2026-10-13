import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile>().configureEach { options.release = 21 }

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.1")
    implementation(project(":sdk-velocity"))
}

tasks.processResources { filesMatching("velocity-plugin.json") { expand("version" to project.version) } }

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("AnalyticsExampleVelocity")
    archiveClassifier.set("")
    relocate("com.gamepathics.pluginanalytics", "com.gamepathics.paexample.velocity.analytics")
}

tasks.build { dependsOn("shadowJar") }
