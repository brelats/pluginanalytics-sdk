package com.gamepathics.pluginanalytics;

import com.gamepathics.pluginanalytics.core.ConfigFiles;
import com.gamepathics.pluginanalytics.core.Engine;
import com.gamepathics.pluginanalytics.core.Host;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Product analytics for your Velocity plugin: <a href="https://pluginanalytics.dev">PluginAnalytics</a>.
 *
 * <pre>{@code
 * @Subscribe
 * public void onInit(ProxyInitializeEvent e) {
 *     analytics = PluginAnalytics.start(this, server, dataDirectory, "pa_your_key");
 * }
 * analytics.track("party_created", player);
 * }</pre>
 *
 * <p>Counts the proxy, its players, your events, errors and log lines; everything is aggregated in memory and
 * sent from its own thread. Players are a salted hash that never repeats across proxies. Owners turn it off
 * for every plugin in {@code plugins/PluginAnalytics/config.yml}. It stops by itself when the proxy shuts down.
 */
public final class PluginAnalytics {

    public static final String SDK_VERSION = Engine.SDK_VERSION;

    private final Object plugin;
    private final ProxyServer server;
    private final Path dataDirectory;
    private final PluginDescription description;
    private final Engine engine;
    private volatile String noticePermission;

    /**
     * Starts collecting for your plugin. Call it once, from {@code ProxyInitializeEvent}. {@code plugin} is your
     * plugin's instance and {@code dataDirectory} the one Velocity injects. Never throws and never blocks.
     */
    public static PluginAnalytics start(Object plugin, ProxyServer server, Path dataDirectory, String key) {
        return new PluginAnalytics(plugin, server, dataDirectory, key);
    }

    private PluginAnalytics(Object plugin, ProxyServer server, Path dataDirectory, String key) {
        this.plugin = plugin;
        this.server = server;
        this.dataDirectory = dataDirectory;
        PluginContainer container = server.getPluginManager().fromInstance(plugin).orElse(null);
        this.description = container == null ? null : container.getDescription();
        this.noticePermission = id() + ".notify";
        this.engine = new Engine(new VelocityHost(), key);
        if (engine.isEnabled()) server.getEventManager().register(plugin, new Events());
    }

    /** Velocity calls these; public so it can see them. */
    public final class Events {
        @Subscribe
        public void onLogin(PostLoginEvent e) {
            engine.joined(e.getPlayer().getUniqueId());
            Player p = e.getPlayer();
            if (engine.noticesAllowed() && p.hasPermission(noticePermission)) {
                for (String m : engine.noticesFor(p.getUniqueId().toString())) p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(m));
            }
        }

        /** "/pa-consent <plugin> allow|deny", from a player with the notify permission or the console. */
        @Subscribe
        public void onCommand(CommandExecuteEvent e) {
            if (!e.getCommand().regionMatches(true, 0, "pa-consent", 0, 10)) return;
            CommandSource source = e.getCommandSource();
            if (source instanceof Player && !source.hasPermission(noticePermission)) return;
            String reply = engine.consentCommand(e.getCommand());
            if (reply == null) return;
            e.setResult(CommandExecuteEvent.CommandResult.denied());
            source.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(reply));
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent e) {
            engine.left(e.getPlayer().getUniqueId());
        }

        @Subscribe
        public void onShutdown(ProxyShutdownEvent e) {
            stop();
        }
    }

    public boolean isEnabled() {
        return engine.isEnabled();
    }

    /** Counts something the proxy did, such as {@code "server_registered"}. Names: lowercase, digits, {@code _ . : -}. */
    public void track(String event) {
        engine.track(event, null, null);
    }

    /** Counts something the proxy did, with properties as key/value pairs. */
    public void track(String event, String... properties) {
        engine.track(event, null, Engine.pairs(properties));
    }

    /** Counts something a player did, such as {@code "party_created"}. Player events power funnels and reach. */
    public void track(String event, Player player) {
        engine.track(event, actor(player), null);
    }

    /** Counts something a player did, with properties as key/value pairs. */
    public void track(String event, Player player, String... properties) {
        engine.track(event, actor(player), Engine.pairs(properties));
    }

    /** Counts something a player did, with properties. */
    public void track(String event, Player player, Map<String, ?> properties) {
        engine.track(event, actor(player), properties);
    }

    /** A per-proxy value read every few minutes, shown as the share of proxies per value. */
    public void property(String name, Supplier<?> value) {
        engine.property(name, value);
    }

    /** The lowest level of your plugin's log lines that are collected (default INFO; OFF for none). */
    public void logLevel(Level level) {
        engine.logLevel(level);
    }

    /** Shows (default) or hides the update notices you configure in the dashboard. */
    public void updateNotices(boolean enabled) {
        engine.updateNotices(enabled);
    }

    /**
     * Sends nothing until the network's owner allows it. Call it right after {@link #start}: players with the
     * notify permission are asked in chat when they join, and the console too; they answer with {@code /pa-consent <plugin> allow}
     * or {@code deny}. The answer is saved in the shared config ({@code consent-<plugin>}).
     */
    public PluginAnalytics requireConsent() {
        engine.requireConsent();
        return this;
    }

    /** The permission that receives notices on login (default {@code <pluginid>.notify}). */
    public void noticePermission(String permission) {
        if (permission != null && !permission.isEmpty()) noticePermission = permission;
    }

    /** Sends feedback to you, the developer, from your own command: rating 1–5 (0 for none). */
    public void feedback(CommandSource source, int rating, String message) {
        engine.feedback(rating, message, reply(source));
    }

    /** Sends a bug report to you from your own command, with your plugin's recent errors and log lines attached. */
    public void report(CommandSource source, String message) {
        sendReport(true, source, message);
    }

    /** Sends an idea to you from your own command. */
    public void suggest(CommandSource source, String message) {
        sendReport(false, source, message);
    }

    private void sendReport(boolean bug, CommandSource source, String message) {
        Player p = source instanceof Player ? (Player) source : null;
        engine.report(bug, p != null ? p.getUniqueId().toString() : "console", p != null ? engine.actor(p.getUniqueId()) : null,
            p == null || p.hasPermission(noticePermission), message, reply(source));
    }

    private static Engine.Reply reply(CommandSource source) {
        if (source == null) return null;
        return (ok, hint, message) -> source.sendMessage(Component.text(message, ok ? NamedTextColor.GREEN : hint ? NamedTextColor.YELLOW : NamedTextColor.RED));
    }

    /** Measures a piece of your code: {@code try (PluginAnalytics.Span s = analytics.time("route_player")) { ... }}. */
    public Span time(String name) {
        return new Span(engine, engine.isEnabled() ? name : null);
    }

    /** Measures a piece of your code: {@code analytics.time("load_parties", () -> loadParties())}. */
    public void time(String name, Runnable code) {
        try (Span ignored = time(name)) {
            code.run();
        }
    }

    /** A measured piece of code; close it when the work is done. */
    public static final class Span implements AutoCloseable {
        private final Engine engine;
        private final String name;
        private final long start = System.nanoTime();

        Span(Engine engine, String name) {
            this.engine = engine;
            this.name = name;
        }

        @Override
        public void close() {
            if (name != null && Engine.valid(name)) engine.recordTime(name, "custom", System.nanoTime() - start);
        }
    }

    /** Your plugin's config file (config.yml, .toml or .json in its data directory) is read for Config usage (default on). */
    public void trackConfig(boolean enabled) {
        engine.trackConfig(enabled);
    }

    /** Leaves config paths out of Config usage, e.g. {@code excludeConfig("database")}. */
    public void excludeConfig(String... paths) {
        engine.excludeConfig(paths);
    }

    /** Reports an exception you caught yourself. Uncaught ones in your plugin are reported automatically. */
    public void captureError(Throwable error) {
        engine.captureError(error);
    }

    /** Stops collecting. Called automatically when the proxy shuts down. */
    public void stop() {
        engine.stop();
    }

    private String actor(Player player) {
        return player == null ? null : engine.actor(player.getUniqueId());
    }

    private String id() {
        return description != null ? description.getId() : plugin.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    private final class VelocityHost implements Host {
        private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(id());

        @Override public String name() { return description != null ? description.getName().orElse(id()) : id(); }
        @Override public String version() { return description != null ? description.getVersion().orElse("unknown") : "unknown"; }
        @Override public Class<?> mainClass() { return plugin.getClass(); }
        @Override public File sharedConfig() { return dataDirectory.toAbsolutePath().getParent().resolve("PluginAnalytics").resolve("config.yml").toFile(); }
        @Override public String sharedConfigPath() { return "plugins/PluginAnalytics/config.yml"; }
        @Override public String platform() { return "velocity"; }
        @Override public String software() { return server.getVersion().getName() + " " + server.getVersion().getVersion(); }
        @Override public String minecraftVersion() { return ProtocolVersion.MAXIMUM_VERSION.getVersionIntroducedIn(); }
        @Override public Boolean onlineMode() { return server.getConfiguration().isOnlineMode(); }
        @Override public int maxPlayers() { return server.getConfiguration().getShowMaxPlayers(); }
        @Override public int onlinePlayers() { return server.getPlayerCount(); }

        @Override
        public Iterable<String> onlinePlayerNames() {
            List<String> names = new ArrayList<String>();
            for (Player p : server.getAllPlayers()) names.add(p.getUsername());
            return names;
        }

        @Override
        public Map<String, Object> config(Map<String, Object> defaultsOut) {
            List<File> files = new ArrayList<File>();
            for (String n : Arrays.asList("config.yml", "config.toml", "config.json", "config.properties")) files.add(dataDirectory.resolve(n).toFile());
            for (File f : files) {
                if (f.isFile()) {
                    defaultsOut.putAll(ConfigFiles.readResource(plugin.getClass(), f.getName()));
                    return ConfigFiles.read(Collections.singletonList(f));
                }
            }
            return Collections.emptyMap();
        }

        @Override
        public Map<String, Boolean> dependencies() {
            Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
            if (description != null) for (PluginDependency d : description.getDependencies()) out.put(d.getId(), server.getPluginManager().isLoaded(d.getId()));
            return out;
        }

        @Override
        public void log(Level level, String message) {
            if (level.intValue() >= Level.WARNING.intValue()) logger.warn(message);
            else logger.info(message);
        }

        /** Velocity's java.util.logging only bridges to log4j, which the engine already watches. */
        @Override public boolean watchJavaLogging() { return false; }

        /** Velocity names each plugin's injected logger after its id. */
        @Override public Set<String> loggerNames() { return Collections.singleton(id()); }
    }
}
