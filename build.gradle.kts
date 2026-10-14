plugins {
    java
    id("com.gradleup.shadow") version "8.3.9" apply false
}

allprojects {
    group = "com.gamepathics"
    version = "1.8.0"
}

subprojects {
    apply(plugin = "java")
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation,-options"))
    }
}
