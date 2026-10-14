# PluginAnalytics SDK

The open-source SDK of [PluginAnalytics](https://pluginanalytics.dev): product analytics for Minecraft plugin and mod developers. It goes inside your plugin or mod and sends anonymous usage data to your dashboard: servers and players, commands and events, funnels and retention, errors and logs, performance and config usage.

This repository is the code that runs on servers and in games, published so developers and server owners can check exactly what it does. The dashboard and the service it sends to are hosted at [pluginanalytics.dev](https://pluginanalytics.dev).

## Platforms

| Platform | Artifact | Start it |
|---|---|---|
| Spigot, Paper, Purpur, Folia | `pluginanalytics-bukkit` | `PluginAnalytics.start(this, key)` in `onEnable` |
| Velocity | `pluginanalytics-velocity` | `PluginAnalytics.start(this, server, dataDirectory, key)` on `ProxyInitializeEvent` |
| BungeeCord, Waterfall | `pluginanalytics-bungee` | `PluginAnalytics.start(this, key)` in `onEnable` |
| Fabric (servers and games) | `pluginanalytics-fabric` | `PluginAnalytics.start("yourmodid", key)` in `onInitialize` |
| NeoForge (servers and games) | `pluginanalytics-neoforge` | `PluginAnalytics.start("yourmodid", key)` in your `@Mod` constructor |

Every artifact is a single jar with no dependencies. The Bukkit, BungeeCord, Fabric and NeoForge SDKs target Java 8; the Velocity SDK needs Java 21, like Velocity itself.

## Install

```kotlin
plugins {
    id("com.gradleup.shadow") version "8.3.9"
}

repositories {
    maven("https://pluginanalytics.dev/maven")
}

dependencies {
    implementation("com.gamepathics:pluginanalytics-bukkit:1.7.0")
}

tasks.shadowJar {
    relocate("com.gamepathics.pluginanalytics", "me.you.yourplugin.libs.analytics")
}
```

```java
@Override
public void onEnable() {
    analytics = PluginAnalytics.start(this, "pa_your_project_key");
}
```

Relocating is required: an unrelocated copy turns itself off, so two plugins can never clash. Maven, the mod loaders, events and every method: [documentation](https://pluginanalytics.dev/docs/). Complete example plugins are in [`example`](example), [`example-velocity`](example-velocity) and [`example-bungee`](example-bungee).

## What is sent, and what never is

Each plugin or mod sends a short summary about once a minute (every 30 minutes from a player's game): a random server id, the server software and Minecraft, Java and plugin versions, player counts, counts of what the plugin tracks, its plain config choices, its own errors and log lines, and timings of its own code.

- **Players** are a hash of their UUID with a secret salt that never leaves the server, different on every server.
- **Never sent:** player names, UUIDs, IP addresses, chat, what players type, inventories, world data, the server's address or MOTD.
- **Messages** in errors and logs have names, IP addresses, UUIDs, emails and secrets replaced before sending.
- **Config values** are only plain choices: true/false, numbers and short single words such as `sqlite` or `en_US`. Lists, longer text, anything that looks like a password, IP or id, and keys that could hold something private (passwords, hosts, users, URLs, tokens, names, messages...) are skipped. See `choice()` and `PRIVATE_KEY` in `Engine.java`, or turn it off with `analytics.trackConfig(false)`.

The full list, for server owners: [pluginanalytics.dev/privacy](https://pluginanalytics.dev/privacy/). The code that builds each batch is in [`Engine.java`](sdk-core/src/main/java/com/gamepathics/pluginanalytics/core/Engine.java).

## Turning it off

Server owners turn it off for every plugin and mod that uses it with `enabled: false` in `plugins/PluginAnalytics/config.yml` (`config/pluginanalytics.yml` on Fabric and NeoForge, and in a player's game folder).

## Layout

| Folder | What it is |
|---|---|
| `sdk-core` | The platform-independent engine: collecting, batching, redaction and sending. Bundled inside every artifact. |
| `sdk`, `sdk-velocity`, `sdk-bungee`, `sdk-fabric`, `sdk-neoforge` | Each platform's `PluginAnalytics` class. |
| `example`, `example-velocity`, `example-bungee` | Example plugins that use it. |

## Building

```sh
./gradlew build
```

Gradle downloads the JDK the Velocity module needs if it isn't installed. `./gradlew publish` writes the artifacts to `build/maven`.

## Contributing

Issues and pull requests are welcome. For questions about your dashboard or account, use the support chat at [pluginanalytics.dev](https://pluginanalytics.dev).

## License

[MIT](LICENSE)
