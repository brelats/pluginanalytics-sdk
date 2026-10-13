package com.gamepathics.pluginanalytics;

import com.gamepathics.pluginanalytics.core.ConfigFiles;
import com.gamepathics.pluginanalytics.core.Engine;
import com.gamepathics.pluginanalytics.core.Host;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Product analytics for your BungeeCord (or Waterfall) plugin: <a href="https://pluginanalytics.dev">PluginAnalytics</a>.
 *
 * <pre>{@code
 * public void onEnable() {
 *     analytics = PluginAnalytics.start(this, "pa_your_key");
 * }
 * public void onDisable() {
 *     analytics.stop(); // sends the last batch
 * }
 * }</pre>
 *
 * <p>Counts the proxy, its players, your events, errors and log lines; everything is aggregated in memory and
 * sent from its own thread. Players are a salted hash that never repeats across proxies. Owners turn it off
 * for every plugin in {@code plugins/PluginAnalytics/config.yml}.
 */
public final class PluginAnalytics {

    public static final String SDK_VERSION = Engine.SDK_VERSION;

    private final Plugin plugin;
    private final Engine engine;
    private volatile String noticePermission;
    private Listener listener;

    /** Starts collecting for {@code plugin}. Call it once, in {@code onEnable}; call {@link #stop} in {@code onDisable}. */
    public static PluginAnalytics start(Plugin plugin, String key) {
        return new PluginAnalytics(plugin, key);
    }

    private PluginAnalytics(Plugin plugin, String key) {
        this.plugin = plugin;
        this.noticePermission = plugin.getDescription().getName().toLowerCase(java.util.Locale.ROOT) + ".notify";
        this.engine = new Engine(new BungeeHost(), key);
        if (!engine.isEnabled()) return;
        listener = new Events();
        ProxyServer.getInstance().getPluginManager().registerListener(plugin, listener);
    }

    /** BungeeCord calls these; public so it can see them. */
    public final class Events implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onLogin(PostLoginEvent e) {
            ProxiedPlayer p = e.getPlayer();
            engine.joined(p.getUniqueId());
            if (engine.noticesAllowed() && p.hasPermission(noticePermission)) {
                for (String m : engine.noticesFor(p.getUniqueId().toString())) p.sendMessage(TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', m)));
            }
        }

        @EventHandler
        public void onDisconnect(PlayerDisconnectEvent e) {
            engine.left(e.getPlayer().getUniqueId());
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
    public void track(String event, ProxiedPlayer player) {
        engine.track(event, actor(player), null);
    }

    /** Counts something a player did, with properties as key/value pairs. */
    public void track(String event, ProxiedPlayer player, String... properties) {
        engine.track(event, actor(player), Engine.pairs(properties));
    }

    /** Counts something a player did, with properties. */
    public void track(String event, ProxiedPlayer player, Map<String, ?> properties) {
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

    /** The permission that receives notices on login (default {@code <plugin>.notify}). */
    public void noticePermission(String permission) {
        if (permission != null && !permission.isEmpty()) noticePermission = permission;
    }

    /** Sends feedback to you, the developer, from your own command: rating 1–5 (0 for none). */
    public void feedback(CommandSender sender, int rating, String message) {
        engine.feedback(rating, message, reply(sender));
    }

    /** Sends a bug report to you from your own command, with your plugin's recent errors and log lines attached. */
    public void report(CommandSender sender, String message) {
        sendReport(true, sender, message);
    }

    /** Sends an idea to you from your own command. */
    public void suggest(CommandSender sender, String message) {
        sendReport(false, sender, message);
    }

    private void sendReport(boolean bug, CommandSender sender, String message) {
        ProxiedPlayer p = sender instanceof ProxiedPlayer ? (ProxiedPlayer) sender : null;
        engine.report(bug, p != null ? p.getUniqueId().toString() : "console", p != null ? engine.actor(p.getUniqueId()) : null,
            p == null || p.hasPermission(noticePermission), message, reply(sender));
    }

    private static Engine.Reply reply(CommandSender sender) {
        if (sender == null) return null;
        return (ok, hint, message) -> sender.sendMessage(TextComponent.fromLegacyText((ok ? ChatColor.GREEN : hint ? ChatColor.YELLOW : ChatColor.RED) + message));
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

    /** Your plugin's config.yml (in its data folder) is read for Config usage (default on). */
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

    /** Stops collecting and sends the last batch: call it in {@code onDisable}. */
    public void stop() {
        engine.stop();
        if (listener != null) {
            try {
                ProxyServer.getInstance().getPluginManager().unregisterListener(listener);
            } catch (Throwable ignored) {
                // The proxy is shutting down.
            }
        }
    }

    private String actor(ProxiedPlayer player) {
        return player == null ? null : engine.actor(player.getUniqueId());
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private final class BungeeHost implements Host {
        @Override public String name() { return plugin.getDescription().getName(); }
        @Override public String version() { return plugin.getDescription().getVersion(); }
        @Override public Class<?> mainClass() { return plugin.getClass(); }
        @Override public File sharedConfig() { return new File(new File(plugin.getDataFolder().getParentFile(), "PluginAnalytics"), "config.yml"); }
        @Override public String sharedConfigPath() { return "plugins/PluginAnalytics/config.yml"; }
        @Override public String platform() { return hasClass("io.github.waterfallmc.waterfall.conf.WaterfallConfiguration") ? "waterfall" : "bungeecord"; }
        @Override public String software() { return ProxyServer.getInstance().getName() + " " + ProxyServer.getInstance().getVersion(); }
        @Override public String minecraftVersion() { return ProxyServer.getInstance().getGameVersion(); }
        @Override public Boolean onlineMode() { return ProxyServer.getInstance().getConfig().isOnlineMode(); }
        @Override public int maxPlayers() { return ProxyServer.getInstance().getConfig().getPlayerLimit(); }
        @Override public int onlinePlayers() { return ProxyServer.getInstance().getOnlineCount(); }

        @Override
        public Iterable<String> onlinePlayerNames() {
            List<String> names = new ArrayList<String>();
            for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) names.add(p.getName());
            return names;
        }

        @Override
        public Map<String, Object> config(Map<String, Object> defaultsOut) {
            for (String n : Arrays.asList("config.yml", "config.json", "config.properties")) {
                File f = new File(plugin.getDataFolder(), n);
                if (f.isFile()) {
                    defaultsOut.putAll(ConfigFiles.readResource(plugin.getClass(), n));
                    return ConfigFiles.read(Collections.singletonList(f));
                }
            }
            return Collections.emptyMap();
        }

        @Override
        public Map<String, Boolean> dependencies() {
            Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
            List<String> deps = new ArrayList<String>(plugin.getDescription().getDepends());
            deps.addAll(plugin.getDescription().getSoftDepends());
            for (String d : deps) out.put(d, ProxyServer.getInstance().getPluginManager().getPlugin(d) != null);
            return out;
        }

        @Override public void log(Level level, String message) { plugin.getLogger().log(level, message); }
        @Override public Logger julLogger() { return plugin.getLogger(); }
    }
}
