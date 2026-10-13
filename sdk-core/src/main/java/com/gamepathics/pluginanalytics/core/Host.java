package com.gamepathics.pluginanalytics.core;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the engine needs from the platform it runs on (Bukkit, Velocity, BungeeCord, Fabric, NeoForge). Each
 * SDK artifact implements it in a few lines; everything else (collecting, sending, privacy) lives in {@link Engine}.
 * Every method may be called from the analytics thread and must never throw.
 */
public interface Host {

    /** The plugin or mod's display name. */
    String name();

    /** The plugin or mod's version, as servers report it ("1.4.0"). */
    String version();

    /** A class of the plugin or mod: its jar and package tell the engine which code is "own". */
    Class<?> mainClass();

    /** The config file shared by every plugin or mod on this server (created on first start). */
    File sharedConfig();

    /** Where to tell the owner to turn analytics off, as shown in the config header ("plugins/PluginAnalytics/config.yml"). */
    String sharedConfigPath();

    /** spigot, paper, purpur, folia, bukkit, velocity, bungeecord, fabric, neoforge, or fabric-client / neoforge-client. */
    String platform();

    /** A player's own game rather than a server: anonymous per install, sent every 30 minutes over HTTP. */
    default boolean client() {
        return false;
    }

    /** The server software as it names itself ("Paper", "Velocity"). */
    String software();

    /** The Minecraft version ("1.21.4"), or a proxy's supported range. */
    String minecraftVersion();

    /** Online mode, or null when unknown (clients). */
    default Boolean onlineMode() {
        return null;
    }

    /** Max players, or -1 when unknown. */
    default int maxPlayers() {
        return -1;
    }

    /** Players online now. */
    int onlinePlayers();

    /** Names of the players online now, so they can be removed from error messages. */
    default Iterable<String> onlinePlayerNames() {
        return Collections.emptyList();
    }

    /** The thread whose time is sampled for Performance, or null (proxies, Folia, clients). */
    default Thread mainThread() {
        return null;
    }

    /** The server's average tick time in ms, or null where it isn't exposed. */
    default Double averageTickTime() {
        return null;
    }

    /**
     * The plugin's config as flat paths ("storage.type" → "sqlite") for Config usage, and its packaged defaults
     * in {@code defaultsOut}; an empty map when there's no config.
     */
    default Map<String, Object> config(Map<String, Object> defaultsOut) {
        return Collections.emptyMap();
    }

    /** The plugins or mods this one depends on (or softdepends on), and whether each is installed. */
    default Map<String, Boolean> dependencies() {
        return Collections.emptyMap();
    }

    /** Logs a line of the SDK's own, through the plugin's logger. */
    void log(Level level, String message);

    /** The plugin's java.util.logging logger, whose lines are collected for Logs; null when it logs elsewhere. */
    default Logger julLogger() {
        return null;
    }

    /**
     * Whether errors logged through java.util.logging's root logger should be watched too (default). Off where
     * java.util.logging is only a bridge to log4j that warns about added handlers (Velocity).
     */
    default boolean watchJavaLogging() {
        return true;
    }

    /** Names of the log4j/SLF4J loggers whose lines are the plugin's own, for Logs. */
    default Set<String> loggerNames() {
        return Collections.emptySet();
    }

    /** After a batch was delivered, at most every 10 minutes: Bukkit wraps listeners registered since. */
    default void refresh() {
    }
}
