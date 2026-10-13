// The platform-independent part of the SDK (Engine): no dependencies, Java 8. Not published on its own: every
// platform artifact (pluginanalytics-bukkit, -velocity, -bungee, -fabric, -neoforge) carries these classes inside
// its jar, so developers still add and relocate a single dependency.
tasks.withType<JavaCompile>().configureEach { options.release = 8 }
