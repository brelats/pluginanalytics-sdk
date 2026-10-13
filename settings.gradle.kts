plugins {
    // Downloads the JDK a module asks for (Velocity's needs Java 21) when it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "pluginanalytics-sdk"
include("sdk-core", "sdk", "sdk-velocity", "sdk-bungee", "sdk-fabric", "sdk-neoforge", "example", "example-velocity", "example-bungee")
