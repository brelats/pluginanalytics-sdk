package com.gamepathics.pluginanalytics;

import com.gamepathics.pluginanalytics.core.Engine;
import com.gamepathics.pluginanalytics.core.Host;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Product analytics for your plugin: <a href="https://pluginanalytics.dev">PluginAnalytics</a>.
 *
 * <pre>{@code
 * PluginAnalytics analytics = PluginAnalytics.start(this, "pa_your_key");
 * analytics.track("arena_created");                         // a server did something
 * analytics.track("game_started", player);                  // a player did something
 * analytics.track("kit_selected", player, "kit", "archer"); // with properties
 * analytics.property("storage", () -> getConfig().getString("storage"));
 * }</pre>
 *
 * <p>Everything is aggregated in memory and sent from its own thread (every minute or so over a live connection,
 * hourly over HTTP when it can't connect), so tracking costs a map lookup.
 * Nothing that identifies a player leaves the server: players are a salted hash that never repeats across servers.
 * Server owners can turn it off for every plugin in {@code plugins/PluginAnalytics/config.yml}.
 */
public final class PluginAnalytics {

    public static final String SDK_VERSION = Engine.SDK_VERSION;
    private static final boolean FOLIA = hasClass("io.papermc.paper.threadedregions.RegionizedServer");
    private static final String PLATFORM = platform();
    private static volatile String mcVersion;

    private final Plugin plugin;
    private final Engine engine;
    private volatile boolean autoCommands = true;
    private volatile String commandBase;
    private volatile String noticePermission;
    private Listener listener;

    /**
     * Starts collecting for {@code plugin}. Call it once, in {@code onEnable}. It never throws and never blocks:
     * if the owner disabled analytics, every method becomes a no-op.
     */
    public static PluginAnalytics start(Plugin plugin, String key) {
        return new PluginAnalytics(plugin, key);
    }

    private PluginAnalytics(Plugin plugin, String key) {
        this.plugin = plugin;
        this.noticePermission = plugin.getName().toLowerCase(java.util.Locale.ROOT) + ".notify";
        this.engine = new Engine(new BukkitHost(), key);
        if (!engine.isEnabled()) return;
        listener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onJoin(PlayerJoinEvent e) {
                engine.joined(e.getPlayer().getUniqueId());
                notifyAdmin(e.getPlayer());
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(PlayerQuitEvent e) {
                engine.left(e.getPlayer().getUniqueId());
            }

            // "/pa-consent <plugin> allow|deny": answered by an admin, before the server looks the command up.
            @EventHandler(priority = EventPriority.LOWEST)
            public void onCommand(PlayerCommandPreprocessEvent e) {
                if (!e.getMessage().regionMatches(true, 0, "/pa-consent", 0, 11)) return;
                Player p = e.getPlayer();
                if (!(p.isOp() || p.hasPermission(noticePermission))) return;
                String reply = engine.consentCommand(e.getMessage());
                if (reply == null) return;
                e.setCancelled(true);
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', reply));
            }

            @EventHandler(priority = EventPriority.LOWEST)
            public void onConsole(ServerCommandEvent e) {
                if (!e.getCommand().regionMatches(true, 0, "pa-consent", 0, 10) && !e.getCommand().regionMatches(true, 0, "/pa-consent", 0, 11)) return;
                String reply = engine.consentCommand(e.getCommand());
                if (reply == null) return;
                e.setCancelled(true);
                e.getSender().sendMessage(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', reply)));
            }

            @EventHandler
            public void onDisable(PluginDisableEvent e) {
                if (e.getPlugin() == plugin) stop();
            }
        };
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        scheduleInstrument();
        // The main thread is sampled for Performance; Folia has none.
        if (!FOLIA) {
            if (Bukkit.isPrimaryThread()) engine.mainThread(Thread.currentThread());
            else Bukkit.getScheduler().runTask(plugin, () -> engine.mainThread(Thread.currentThread()));
        }
    }

    /** True unless the server owner turned analytics off. */
    public boolean isEnabled() {
        return engine.isEnabled();
    }

    /** Counts something the server did, such as {@code "arena_created"}. Names: lowercase, digits, {@code _ . : -}. */
    public void track(String event) {
        engine.track(event, null, null);
    }

    /** Counts something the server did, with properties given as key/value pairs: {@code track("reload", "trigger", "command")}. */
    public void track(String event, String... properties) {
        engine.track(event, null, Engine.pairs(properties));
    }

    /** Counts something a player did, such as {@code "game_started"}. Player events power funnels and reach. */
    public void track(String event, Player player) {
        engine.track(event, actor(player), null);
    }

    /** Counts something a player did, with properties given as key/value pairs. */
    public void track(String event, Player player, String... properties) {
        engine.track(event, actor(player), Engine.pairs(properties));
    }

    /** Counts something a player did, with properties. */
    public void track(String event, Player player, Map<String, ?> properties) {
        engine.track(event, actor(player), properties);
    }

    /**
     * A per-server value read every few minutes from the analytics thread, such as a config choice:
     * {@code property("storage", () -> config.getString("storage"))}. Shown as the share of servers per value.
     */
    public void property(String name, Supplier<?> value) {
        engine.property(name, value);
    }

    /**
     * The lowest level of your plugin's log lines that are collected: {@code Level.INFO} (default),
     * {@code Level.WARNING}, or {@code Level.OFF} to collect none.
     */
    public void logLevel(Level level) {
        engine.logLevel(level);
    }

    /** Shows (default) or hides the update notices you configure in the dashboard. */
    public void updateNotices(boolean enabled) {
        engine.updateNotices(enabled);
    }

    /**
     * Hides the feedback prompt even when it's on in the dashboard.
     *
     * @deprecated The prompt is off unless turned on in the dashboard, where it's also turned off; kept so
     *     plugins that call it still compile.
     */
    @Deprecated
    public void feedbackPrompts(boolean enabled) {
        engine.feedbackPrompts(enabled);
    }

    /**
     * Sends nothing until the server's owner allows it. Call it right after {@link #start}: operators (and
     * players with the notify permission) are asked in chat when they join, and the console too; they answer
     * with {@code /pa-consent <plugin> allow} or {@code deny}. The answer is saved in
     * {@code plugins/PluginAnalytics/config.yml} ({@code consent-<plugin>}). Without it, analytics is on unless
     * the owner turns it off.
     */
    public PluginAnalytics requireConsent() {
        engine.requireConsent();
        return this;
    }

    /** The permission that receives notices on join (default {@code <plugin>.notify}; operators always do). */
    public void noticePermission(String permission) {
        if (permission != null && !permission.isEmpty()) noticePermission = permission;
    }

    /**
     * Sends feedback to you, the developer: wire it to your own command, e.g. {@code /myplugin feedback 5 Great plugin}.
     * Rating 1–5, or 0 for none. Runs in the background; the sender gets a confirmation.
     */
    public void feedback(CommandSender sender, int rating, String message) {
        engine.feedback(rating, message, reply(sender));
    }

    /**
     * Sends a bug report to you, the developer: wire it to your own command, e.g.
     * {@code /myplugin report <what happened>}. Your plugin's errors and log lines from the last 15 minutes
     * on this server are attached, already stripped of player names, IPs and UUIDs.
     */
    public void report(CommandSender sender, String message) {
        sendReport(true, sender, message);
    }

    /** Sends an idea to you, the developer: e.g. {@code /myplugin suggest <idea>}. */
    public void suggest(CommandSender sender, String message) {
        sendReport(false, sender, message);
    }

    private void sendReport(boolean bug, CommandSender sender, String message) {
        Player p = sender instanceof Player ? (Player) sender : null;
        engine.report(bug, p != null ? p.getUniqueId().toString() : "console", p != null ? engine.actor(p.getUniqueId()) : null,
            sender == null || p == null || sender.isOp(), message, reply(sender));
    }

    private static Engine.Reply reply(CommandSender sender) {
        if (sender == null) return null;
        return (ok, hint, message) -> sender.sendMessage((ok ? ChatColor.GREEN : hint ? ChatColor.YELLOW : ChatColor.RED) + message);
    }

    /**
     * Measures a piece of your code: {@code try (PluginAnalytics.Span s = analytics.time("arena_tick")) { ... }}.
     * Shown in Performance with calls, average, p95 and max.
     */
    public Span time(String name) {
        return new Span(engine, engine.isEnabled() ? name : null);
    }

    /** Measures a piece of your code: {@code analytics.time("load_arenas", () -> loadArenas())}. */
    public void time(String name, Runnable code) {
        try (Span ignored = time(name)) {
            code.run();
        }
    }

    /**
     * Adds {@code /<pluginname> report <text>} and {@code /<pluginname> suggest <text>} (default on). If your
     * plugin has a command with that name they're added to it; otherwise the command is created. Pick another
     * base with {@link #commandBase}. Subcommands your plugin already has are never replaced. Turn it off to
     * use {@link #report} and {@link #suggest} from your own commands instead.
     */
    public void autoCommands(boolean enabled) {
        autoCommands = enabled;
    }

    /**
     * Puts the {@code report} and {@code suggest} subcommands on one of your own commands instead of
     * {@code /<pluginname>}: {@code analytics.commandBase("arena")} gives {@code /arena report <text>}.
     */
    public void commandBase(String command) {
        commandBase = command == null || command.trim().isEmpty() ? null : command.trim().replaceFirst("^/", "");
    }

    /** Measures your event handlers, commands and the rest of your code on the main thread automatically (default on). */
    public void autoPerformance(boolean enabled) {
        engine.autoPerformance(enabled);
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

    /**
     * Your plugin's config.yml is reported automatically (default on) for Config usage: only choices
     * (true/false, numbers, short words like "sqlite"), never anything that could be private. Keys
     * that look like passwords, tokens, hosts, URLs, users, IDs or databases are always skipped.
     */
    public void trackConfig(boolean enabled) {
        engine.trackConfig(enabled);
    }

    /** Leaves config paths out of Config usage, e.g. {@code excludeConfig("discord", "storage.mysql")}. */
    public void excludeConfig(String... paths) {
        engine.excludeConfig(paths);
    }

    /** Reports an exception you caught yourself. Uncaught ones in your plugin are reported automatically. */
    public void captureError(Throwable error) {
        engine.captureError(error);
    }

    /** Stops collecting. Called automatically when your plugin is disabled. */
    public void stop() {
        engine.stop();
        if (listener != null) HandlerList.unregisterAll(listener);
    }

    /* ---------------------------------------------------------------- Bukkit */

    private String actor(Player player) {
        return player == null ? null : engine.actor(player.getUniqueId());
    }

    private void notifyAdmin(Player player) {
        if (!engine.noticesAllowed() || !(player.isOp() || player.hasPermission(noticePermission))) return;
        for (String m : engine.noticesFor(player.getUniqueId().toString())) player.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
    }

    /** What the engine asks of the server, answered with the Bukkit API. */
    private final class BukkitHost implements Host {
        private java.lang.reflect.Method tickTime;
        private boolean tickTimeLooked;

        @Override public String name() { return plugin.getName(); }
        @Override public String version() { return plugin.getDescription().getVersion(); }
        @Override public Class<?> mainClass() { return plugin.getClass(); }
        @Override public File sharedConfig() { return new File(new File(plugin.getDataFolder().getParentFile(), "PluginAnalytics"), "config.yml"); }
        @Override public String sharedConfigPath() { return "plugins/PluginAnalytics/config.yml"; }
        @Override public String platform() { return PLATFORM; }
        @Override public String software() { return Bukkit.getName(); }
        @Override public String minecraftVersion() { return mcVersion(); }
        @Override public Boolean onlineMode() { return Bukkit.getOnlineMode(); }
        @Override public int maxPlayers() { return Bukkit.getMaxPlayers(); }

        @Override
        public int onlinePlayers() {
            try {
                return Bukkit.getOnlinePlayers().size();
            } catch (Throwable t) {
                return 0;
            }
        }

        @Override
        public Iterable<String> onlinePlayerNames() {
            List<String> names = new ArrayList<String>();
            try {
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            } catch (Throwable ignored) {
                // The player list may be unavailable during startup or shutdown.
            }
            return names;
        }

        /** Paper and its forks expose the average tick time; Spigot doesn't. */
        @Override
        public Double averageTickTime() {
            try {
                if (!tickTimeLooked) {
                    tickTimeLooked = true;
                    tickTime = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
                }
                return tickTime == null ? null : ((Number) tickTime.invoke(Bukkit.getServer())).doubleValue();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public Map<String, Object> config(Map<String, Object> defaultsOut) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
            org.bukkit.configuration.Configuration defaults = config.getDefaults();
            for (String path : config.getKeys(true)) {
                Object value = config.get(path);
                if (value instanceof org.bukkit.configuration.ConfigurationSection) continue;
                out.put(path, value);
                if (defaults != null) {
                    Object d = defaults.get(path);
                    if (d != null && !(d instanceof org.bukkit.configuration.ConfigurationSection)) defaultsOut.put(path, d);
                }
            }
            return out;
        }

        @Override
        public Map<String, Boolean> dependencies() {
            Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
            List<String> deps = new ArrayList<String>(plugin.getDescription().getDepend());
            deps.addAll(plugin.getDescription().getSoftDepend());
            for (String dep : deps) out.put(dep, Bukkit.getPluginManager().isPluginEnabled(dep));
            return out;
        }

        @Override public void log(Level level, String message) { plugin.getLogger().log(level, message); }
        @Override public Logger julLogger() { return plugin.getLogger(); }
        @Override public void refresh() { scheduleInstrument(); }
    }

    /* Automatic timing: event handlers and commands of this plugin, wrapped once on the main thread. */

    private void scheduleInstrument() {
        if ((!engine.autoPerformance() && !autoCommands) || FOLIA) return; // Folia has no main thread
        try {
            Bukkit.getScheduler().runTask(plugin, this::instrument);
        } catch (Throwable ignored) {
            // The plugin is disabling; nothing to measure.
        }
    }

    private void instrument() {
        if (!engine.isEnabled()) return;
        try {
            boolean perf = engine.autoPerformance();
            if (perf) {
                for (HandlerList list : HandlerList.getHandlerLists()) {
                    for (RegisteredListener rl : list.getRegisteredListeners()) {
                        if (rl.getPlugin() != plugin || rl instanceof TimedListener || rl.getListener() == listener) continue;
                        list.unregister(rl);
                        list.register(new TimedListener(this, rl));
                    }
                }
            }
            // Always /<pluginname> report|suggest: on the plugin's own command of that name if it has one,
            // else a command of that name registered here; only if another plugin took the name, the plugin's first command.
            String slug = commandBase != null ? commandBase.toLowerCase(java.util.Locale.ROOT) : commandSlug();
            String main = commandNamed(slug);
            if (autoCommands && main == null && !slug.isEmpty() && !registerOwnCommand(slug)) main = firstCommand();
            for (String name : plugin.getDescription().getCommands().keySet()) {
                PluginCommand command = Bukkit.getPluginCommand(name);
                if (command == null || command.getPlugin() != plugin || command.getExecutor() instanceof TimedCommand) continue;
                boolean extras = autoCommands && name.equals(main);
                if (!perf && !extras) continue;
                CommandExecutor original = command.getExecutor();
                // Bukkit uses the executor for tab completion when it's also a TabCompleter: keep that working.
                org.bukkit.command.TabCompleter completer = command.getTabCompleter() != null ? command.getTabCompleter()
                    : original instanceof org.bukkit.command.TabCompleter ? (org.bukkit.command.TabCompleter) original : null;
                // The plugin's own "report"/"suggest" subcommands always win over ours.
                Set<String> taken = extras ? firstArguments(completer, command) : Collections.<String>emptySet();
                boolean report = extras && !taken.contains("report");
                boolean suggest = extras && !taken.contains("suggest");
                if (report || suggest) command.setTabCompleter(new ExtraCompleter(completer, report, suggest));
                else if (command.getTabCompleter() == null && completer != null) command.setTabCompleter(completer);
                command.setExecutor(new TimedCommand(this, "/" + command.getName(), original, report, suggest));
            }
        } catch (Throwable ignored) {
            // Measuring and extra commands are optional: never break the plugin over them.
        }
    }

    /** The plugin's name as a command: lower case, letters and digits only ("Arena-Plus" → "arenaplus"). */
    private String commandSlug() {
        return plugin.getName().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The plugin's own command called {@code slug} (by name or alias), or null. */
    private String commandNamed(String slug) {
        if (slug.isEmpty()) return null;
        for (Map.Entry<String, Map<String, Object>> c : plugin.getDescription().getCommands().entrySet()) {
            if (c.getKey().equalsIgnoreCase(slug)) return c.getKey();
            Object aliases = c.getValue() == null ? null : c.getValue().get("aliases");
            if (aliases instanceof List) for (Object a : (List<?>) aliases) if (slug.equalsIgnoreCase(String.valueOf(a))) return c.getKey();
            if (aliases instanceof String && slug.equalsIgnoreCase((String) aliases)) return c.getKey();
        }
        return null;
    }

    private String firstCommand() {
        Set<String> names = plugin.getDescription().getCommands().keySet();
        return names.isEmpty() ? null : names.iterator().next();
    }

    private static Set<String> firstArguments(org.bukkit.command.TabCompleter completer, Command command) {
        Set<String> out = new java.util.HashSet<String>();
        if (completer == null) return out;
        try {
            List<String> list = completer.onTabComplete(Bukkit.getConsoleSender(), command, command.getName(), new String[] {""});
            if (list != null) for (String s : list) out.add(s.toLowerCase(java.util.Locale.ROOT));
        } catch (Throwable ignored) {
            // A completer that fails for the console simply doesn't claim anything.
        }
        return out;
    }

    /** "/arena report <text>" or "/arena suggest <text>". */
    void handleSubcommand(String sub, CommandSender sender, String label, String[] args) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < args.length; i++) text.append(i > 1 ? " " : "").append(args[i]);
        if (text.length() == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " " + sub + (sub.equals("report") ? " <what happened>" : " <your idea>"));
            sender.sendMessage(ChatColor.GRAY + (sub.equals("report") ? "Tell the developer of " + plugin.getName() + " about a problem."
                : "Suggest an idea to the developer of " + plugin.getName() + "."));
            return;
        }
        sendReport(sub.equals("report"), sender, text.toString());
    }

    /** Registers /<pluginname> report|suggest at runtime; false if the name is taken or commands can't be added. */
    private boolean registerOwnCommand(String name) {
        try {
            Object map = Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
            org.bukkit.command.CommandMap commandMap = (org.bukkit.command.CommandMap) map;
            Command existing = commandMap.getCommand(name);
            if (existing instanceof OwnCommand) return true; // already ours (instrument runs again every few minutes)
            if (existing != null) return false; // never take a name another plugin uses
            commandMap.register(name, new OwnCommand(this, name));
            try {
                Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer()); // show it to online players
            } catch (Throwable ignored) {
                // Older servers send the command list on join anyway.
            }
            return true;
        } catch (Throwable ignored) {
            return false; // no command map access: report() and suggest() still work from the plugin's code
        }
    }

    private static final class OwnCommand extends Command {
        private final PluginAnalytics owner;

        OwnCommand(PluginAnalytics owner, String name) {
            super(name, "Report a problem or suggest an idea to the developer", "/" + name + " <report|suggest> <message>", new ArrayList<String>());
            this.owner = owner;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
            if (sub.equals("report") || sub.equals("suggest")) owner.handleSubcommand(sub, sender, label, args);
            else {
                sender.sendMessage(ChatColor.YELLOW + "/" + label + " report <what happened>" + ChatColor.GRAY + " - tell the developer about a problem");
                sender.sendMessage(ChatColor.YELLOW + "/" + label + " suggest <your idea>" + ChatColor.GRAY + " - suggest an idea");
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            List<String> out = new ArrayList<String>();
            if (args.length == 1) for (String s : new String[] {"report", "suggest"}) if (s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) out.add(s);
            return out;
        }
    }

    /** The plugin's own tab completion, plus "report" and "suggest" as first arguments. */
    private static final class ExtraCompleter implements org.bukkit.command.TabCompleter {
        private final org.bukkit.command.TabCompleter inner;
        private final boolean report;
        private final boolean suggest;

        ExtraCompleter(org.bukkit.command.TabCompleter inner, boolean report, boolean suggest) {
            this.inner = inner;
            this.report = report;
            this.suggest = suggest;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length >= 2 && ((report && args[0].equalsIgnoreCase("report")) || (suggest && args[0].equalsIgnoreCase("suggest")))) {
                return new ArrayList<String>(); // free text: no suggestions (and no player names)
            }
            List<String> base = inner == null ? null : inner.onTabComplete(sender, command, alias, args);
            List<String> out = base == null ? new ArrayList<String>() : new ArrayList<String>(base);
            if (args.length == 1) {
                String typed = args[0].toLowerCase(java.util.Locale.ROOT);
                if (report && "report".startsWith(typed) && !out.contains("report")) out.add("report");
                if (suggest && "suggest".startsWith(typed) && !out.contains("suggest")) out.add("suggest");
            }
            return out;
        }
    }

    private static final class TimedListener extends RegisteredListener {
        private final PluginAnalytics owner;
        private final RegisteredListener inner;
        private final Map<Class<?>, String> names = new ConcurrentHashMap<Class<?>, String>();

        TimedListener(PluginAnalytics owner, RegisteredListener inner) {
            super(inner.getListener(), null, inner.getPriority(), inner.getPlugin(), inner.isIgnoringCancelled());
            this.owner = owner;
            this.inner = inner;
        }

        @Override
        public void callEvent(Event event) throws org.bukkit.event.EventException {
            if (!owner.engine.collectsPerformance()) {
                inner.callEvent(event);
                return;
            }
            long start = System.nanoTime();
            try {
                inner.callEvent(event);
            } finally {
                long took = System.nanoTime() - start;
                String name = names.get(event.getClass());
                if (name == null) {
                    name = owner.engine.relativeClass(inner.getListener().getClass().getName()) + " · " + event.getEventName() + (event.isAsynchronous() ? " (async)" : "");
                    names.put(event.getClass(), name);
                }
                owner.engine.recordTime(name, "event", took);
            }
        }
    }

    /**
     * Times a command and counts it as an event: "command:arena" or, with a subcommand, "command:arena.join".
     * Subcommands only count when the command's own tab completion offers them, so free text and player
     * names never become event names.
     */
    private static final class TimedCommand implements CommandExecutor {
        private static final Pattern SUB = Pattern.compile("[a-z][a-z0-9_\\-]{0,19}");
        private final PluginAnalytics owner;
        private final String name;
        private final CommandExecutor inner;
        private final boolean report;
        private final boolean suggest;
        private Set<String> subs;
        private long subsAt;

        TimedCommand(PluginAnalytics owner, String name, CommandExecutor inner, boolean report, boolean suggest) {
            this.owner = owner;
            this.name = name;
            this.inner = inner;
            this.report = report;
            this.suggest = suggest;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            String first = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
            if ((report && first.equals("report")) || (suggest && first.equals("suggest"))) {
                owner.handleSubcommand(first, sender, label, args);
                return true;
            }
            long start = System.nanoTime();
            try {
                return inner.onCommand(sender, command, label, args);
            } finally {
                Engine engine = owner.engine;
                if (engine.autoPerformance() && engine.collectsPerformance()) engine.recordTime(name, "command", System.nanoTime() - start);
                try {
                    String event = "command:" + command.getName().toLowerCase(java.util.Locale.ROOT);
                    if (args.length > 0 && subcommands(command, label).contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
                        event += "." + args[0].toLowerCase(java.util.Locale.ROOT);
                    }
                    if (event.length() <= 48) engine.track(event, sender instanceof Player ? owner.actor((Player) sender) : null, null);
                } catch (Throwable ignored) {
                    // Counting is optional.
                }
            }
        }

        /** The command's first-argument completions, refreshed hourly, minus anything that is a player's name. */
        private Set<String> subcommands(Command command, String label) {
            long now = System.currentTimeMillis();
            if (subs != null && now - subsAt < 3_600_000L) return subs;
            Set<String> found = new java.util.HashSet<String>();
            try {
                Set<String> players = new java.util.HashSet<String>();
                for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName().toLowerCase(java.util.Locale.ROOT));
                for (String c : command.tabComplete(Bukkit.getConsoleSender(), label, new String[] {""})) {
                    String lower = c.toLowerCase(java.util.Locale.ROOT);
                    if (SUB.matcher(lower).matches() && !players.contains(lower) && found.size() < 50) found.add(lower);
                }
            } catch (Throwable ignored) {
                // No completions: commands are still counted, without subcommands.
            }
            subs = found;
            subsAt = now;
            return found;
        }
    }

    /* ---------------------------------------------------------------- helpers */

    private static String platform() {
        if (FOLIA) return "folia";
        if (hasClass("org.purpurmc.purpur.PurpurConfig")) return "purpur";
        if (hasClass("io.papermc.paper.configuration.Configuration") || hasClass("com.destroystokyo.paper.PaperConfig")) return "paper";
        if (hasClass("org.spigotmc.SpigotConfig")) return "spigot";
        return "bukkit";
    }

    /** "1.21.4": the Minecraft version without the build suffix. */
    private static String mcVersion() {
        String v = mcVersion;
        if (v == null) mcVersion = v = Bukkit.getBukkitVersion().split("-")[0];
        return v;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
