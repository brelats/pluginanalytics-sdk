package com.gamepathics.pluginanalytics;

import com.gamepathics.pluginanalytics.core.ConfigFiles;
import com.gamepathics.pluginanalytics.core.Engine;
import com.gamepathics.pluginanalytics.core.Host;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Product analytics for your NeoForge mod: <a href="https://pluginanalytics.dev">PluginAnalytics</a>.
 *
 * <pre>{@code
 * @Mod("mymod")
 * public class MyMod {
 *     public MyMod() {
 *         analytics = PluginAnalytics.start("mymod", "pa_your_key");
 *     }
 * }
 * analytics.track("portal_built", player.getUUID());
 * }</pre>
 *
 * <p>Works on dedicated servers and on clients. On a server it counts the server, its players, your events,
 * errors and log lines; players are a salted hash that never repeats across servers. On a client each install is
 * one anonymous player. Everything is aggregated in memory and sent from its own thread. Owners turn it off for
 * every mod in {@code config/pluginanalytics.yml}. It stops by itself when the game shuts down.
 *
 * <p>It has no compile-time dependency on NeoForge or Minecraft, so one jar works on every NeoForge version.
 */
public final class PluginAnalytics {

    public static final String SDK_VERSION = Engine.SDK_VERSION;

    private final String modId;
    private final Class<?> mainClass;
    private final boolean client;
    private final Object modInfo;
    private volatile Object server;
    private final NeoForgeHost host;
    private final Engine engine;
    private Thread shutdownHook;

    /**
     * Starts collecting for your mod. Call it once, from your {@code @Mod} class's constructor, on a client or a
     * dedicated server. {@code modId} is your mod's id. Never throws and never blocks.
     */
    public static PluginAnalytics start(String modId, String key) {
        Class<?> caller = caller();
        if (modId == null || modId.isEmpty()) modId = modIdOf(caller);
        return new PluginAnalytics(modId, caller, key);
    }

    /** Starts collecting for your mod: {@code mod} is your {@code @Mod} instance (usually {@code this}). */
    public static PluginAnalytics start(Object mod, String key) {
        Class<?> main = mod == null ? caller() : mod.getClass();
        return new PluginAnalytics(modIdOf(main), main, key);
    }

    private PluginAnalytics(String modId, Class<?> mainClass, String key) {
        this.modId = modId == null || modId.isEmpty() ? mainClass.getSimpleName().toLowerCase(Locale.ROOT) : modId;
        this.mainClass = mainClass;
        // -Dpluginanalytics.client=true runs the client path on a dedicated server (for testing only).
        this.client = Boolean.getBoolean("pluginanalytics.client") || "CLIENT".equals(dist());
        this.modInfo = modInfo(this.modId);
        this.host = new NeoForgeHost();
        this.engine = new Engine(host, key);
        if (engine.isEnabled()) hook();
    }

    private void hook() {
        if (!client) {
            listen("net.neoforged.neoforge.event.server.ServerStartedEvent", e -> {
                server = call(e, "getServer");
                engine.mainThread(Thread.currentThread());
            });
            listen("net.neoforged.neoforge.event.server.ServerStoppingEvent", e -> stop());
            listen("net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent", this::onLogin);
            listen("net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedOutEvent", e -> {
                UUID id = uuid(call(e, "getEntity"));
                if (id != null) engine.left(id);
            });
        }
        listen("net.neoforged.neoforge.event.GameShuttingDownEvent", e -> stop());
        // Fallback for anything that ends the game without those events (and the client's way out).
        try {
            shutdownHook = new Thread(engine::stop, "PluginAnalytics-stop-" + modId);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (Throwable ignored) {
            shutdownHook = null;
        }
    }

    private void onLogin(Object event) {
        Object player = call(event, "getEntity");
        UUID id = uuid(player);
        if (id == null) return;
        engine.joined(id);
        try {
            if (engine.noticesAllowed() && admin(player)) {
                for (String m : engine.noticesFor(id.toString())) message(player, m);
            }
        } catch (Throwable ignored) {
            // Notices are best effort.
        }
    }

    public boolean isEnabled() {
        return engine.isEnabled();
    }

    /** Counts something the mod did, such as {@code "structure_generated"}. Names: lowercase, digits, {@code _ . : -}. */
    public void track(String event) {
        engine.track(event, null, null);
    }

    /** Counts something the mod did, with properties as key/value pairs. */
    public void track(String event, String... properties) {
        engine.track(event, null, Engine.pairs(properties));
    }

    /** Counts something a player did (their UUID), such as {@code "portal_built"}. Player events power funnels and reach. */
    public void track(String event, UUID player) {
        engine.track(event, actor(player), null);
    }

    /** Counts something a player did, with properties as key/value pairs. */
    public void track(String event, UUID player, String... properties) {
        engine.track(event, actor(player), Engine.pairs(properties));
    }

    /** Counts something a player did, with properties. */
    public void track(String event, UUID player, Map<String, ?> properties) {
        engine.track(event, actor(player), properties);
    }

    /** A per-server (or per-install) value read every few minutes, shown as the share of servers per value. */
    public void property(String name, Supplier<?> value) {
        engine.property(name, value);
    }

    /** The lowest level of your mod's log lines that are collected (default INFO; OFF for none). */
    public void logLevel(Level level) {
        engine.logLevel(level);
    }

    /** Shows (default) or hides the update notices you configure in the dashboard, sent to operators on join. */
    public void updateNotices(boolean enabled) {
        engine.updateNotices(enabled);
    }

    /** Sends feedback to you, the developer, from your own command or screen: rating 1–5 (0 for none). The result is logged. */
    public void feedback(int rating, String message) {
        engine.feedback(rating, message, reply());
    }

    /** Sends a bug report to you with your mod's recent errors and log lines attached. The result is logged. */
    public void report(String message) {
        engine.report(true, "console", null, true, message, reply());
    }

    /** Sends an idea to you. The result is logged. */
    public void suggest(String message) {
        engine.report(false, "console", null, true, message, reply());
    }

    private Engine.Reply reply() {
        return (ok, hint, message) -> log(ok || hint ? Level.INFO : Level.WARNING, message);
    }

    /** Measures a piece of your code: {@code try (PluginAnalytics.Span s = analytics.time("generate_dungeon")) { ... }}. */
    public Span time(String name) {
        return new Span(engine, engine.isEnabled() ? name : null);
    }

    /** Measures a piece of your code: {@code analytics.time("load_data", () -> loadData())}. */
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

    /** Your mod's config file ({@code config/<modid>-server.toml}, {@code -common.toml}, {@code .toml}, {@code .json}...) is read for Config usage (default on). */
    public void trackConfig(boolean enabled) {
        engine.trackConfig(enabled);
    }

    /** Leaves config paths out of Config usage, e.g. {@code excludeConfig("database")}. */
    public void excludeConfig(String... paths) {
        engine.excludeConfig(paths);
    }

    /** Reports an exception you caught yourself. Uncaught ones in your mod are reported automatically. */
    public void captureError(Throwable error) {
        engine.captureError(error);
    }

    /** Stops collecting. Called automatically when the server stops or the game shuts down. */
    public void stop() {
        engine.stop();
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (Throwable ignored) {
                // Already shutting down.
            }
        }
    }

    private String actor(UUID player) {
        return player == null ? null : engine.actor(player);
    }

    /* ---------------------------------------------------------------- the platform, through reflection */

    private final class NeoForgeHost implements Host {
        private final String name = modInfo == null ? modId : orElse(str(call(modInfo, "getDisplayName")), modId);
        private final String version = modInfo == null ? "unknown" : orElse(str(call(modInfo, "getVersion")), "unknown");
        private volatile String software;
        private volatile String minecraft;
        private final Object logger = slf4j(modId);

        @Override public String name() { return name; }
        @Override public String version() { return version; }
        @Override public Class<?> mainClass() { return mainClass; }
        @Override public File sharedConfig() { return new File(configDir(), "pluginanalytics.yml"); }
        @Override public String sharedConfigPath() { return "config/pluginanalytics.yml"; }
        @Override public String platform() { return client ? "neoforge-client" : "neoforge"; }
        @Override public boolean client() { return client; }

        @Override
        public String software() {
            if (software == null) software = "NeoForge " + orElse(modVersion("neoforge"), "unknown");
            return software;
        }

        @Override
        public String minecraftVersion() {
            if (minecraft == null) minecraft = mcVersion();
            return minecraft;
        }

        @Override
        public Boolean onlineMode() {
            Object s = server;
            Object v = s == null ? null : call(s, "usesAuthentication");
            return v instanceof Boolean ? (Boolean) v : null;
        }

        @Override
        public int maxPlayers() {
            Object s = server;
            if (s == null) return -1;
            Object v = call(s, "getMaxPlayers");
            if (!(v instanceof Integer)) v = call(call(s, "getPlayerList"), "getMaxPlayers");
            return v instanceof Integer ? (Integer) v : -1;
        }

        @Override
        public int onlinePlayers() {
            Object s = server;
            Object v = s == null ? null : call(s, "getPlayerCount");
            return v instanceof Integer ? (Integer) v : 0;
        }

        @Override
        public Iterable<String> onlinePlayerNames() {
            List<String> names = new ArrayList<String>();
            Object players = call(call(server, "getPlayerList"), "getPlayers");
            if (players instanceof Collection) {
                for (Object p : new ArrayList<Object>((Collection<?>) players)) {
                    String n = str(call(p, "getScoreboardName"));
                    if (n != null) names.add(n);
                }
            }
            return names;
        }

        @Override
        public Thread mainThread() {
            Object t = call(server, "getRunningThread");
            return t instanceof Thread ? (Thread) t : null;
        }

        @Override
        public Double averageTickTime() {
            Object s = server;
            if (s == null) return null;
            Object nanos = call(s, "getAverageTickTimeNanos"); // 1.20.3+
            if (nanos instanceof Long) return (Long) nanos / 1_000_000.0;
            Object ms = call(s, "getCurrentSmoothedTickTime");
            if (ms instanceof Float) return ((Float) ms).doubleValue();
            return null;
        }

        @Override
        public Map<String, Object> config(Map<String, Object> defaultsOut) {
            File dir = configDir();
            List<String> names = new ArrayList<String>();
            names.add(modId + "-server.toml");
            if (client) names.add(modId + "-client.toml");
            names.add(modId + "-common.toml");
            for (String ext : new String[] {".toml", ".json", ".json5", ".properties", ".yml", ".yaml"}) names.add(modId + ext);
            for (String n : names) {
                File f = new File(dir, n);
                if (f.isFile()) {
                    defaultsOut.putAll(ConfigFiles.readResource(mainClass, n));
                    return ConfigFiles.read(Collections.singletonList(f));
                }
            }
            return Collections.emptyMap();
        }

        @Override
        public Map<String, Boolean> dependencies() {
            Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
            Object deps = call(modInfo, "getDependencies");
            if (!(deps instanceof Collection)) return out;
            Object list = modList();
            for (Object d : (Collection<?>) deps) {
                String id = str(call(d, "getModId"));
                if (id == null || id.equals("minecraft") || id.equals("neoforge") || id.equals("forge") || id.equals("java") || id.equals(modId)) continue;
                String type = str(call(d, "getType")); // REQUIRED, OPTIONAL, INCOMPATIBLE, DISCOURAGED
                if ("INCOMPATIBLE".equals(type) || "DISCOURAGED".equals(type)) continue;
                Object loaded = call(list, "isLoaded", id);
                out.put(id, Boolean.TRUE.equals(loaded));
            }
            return out;
        }

        @Override
        public void log(Level level, String message) {
            boolean warn = level.intValue() >= Level.WARNING.intValue();
            if (logger == null || call(logger, warn ? "warn" : "info", message) == FAILED) {
                (warn ? System.err : System.out).println("[" + name + "] " + message);
            }
        }

        /** Mods log through SLF4J/log4j, usually under their id, their name, or their main class (LogUtils.getLogger()). */
        @Override
        public Set<String> loggerNames() {
            Set<String> names = new LinkedHashSet<String>();
            names.add(modId);
            names.add(name);
            names.add(mainClass.getName());
            return names;
        }
    }

    private void log(Level level, String message) {
        try {
            host.log(level, message);
        } catch (Throwable t) {
            System.out.println("[" + modId + "] " + message);
        }
    }

    /* ---------------------------------------------------------------- reflection helpers */

    /** Returned by {@link #call} when the method is missing or failed (null is a valid result). */
    private static final Object FAILED = new Object();

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, PluginAnalytics.class.getClassLoader());
        } catch (Throwable t) {
            try {
                return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    /** Calls a public method by name on {@code target} (a Class for static methods); {@link #FAILED} when it can't. */
    private static Object call(Object target, String name, Object... args) {
        if (target == null || target == FAILED) return FAILED;
        boolean statics = target instanceof Class;
        Class<?> type = statics ? (Class<?>) target : target.getClass();
        try {
            for (Method m : type.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != args.length || statics != Modifier.isStatic(m.getModifiers())) continue;
                if (!fits(m.getParameterTypes(), args)) continue;
                Method callable = publicVersion(m);
                return callable.invoke(statics ? null : target, args);
            }
        } catch (Throwable ignored) {
            // Missing on this version, or failed: FAILED below.
        }
        return FAILED;
    }

    private static boolean fits(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            Object a = args[i];
            if (a == null) {
                if (p.isPrimitive()) return false;
                continue;
            }
            if (p.isPrimitive()) {
                if (p == int.class && !(a instanceof Integer)) return false;
                if (p == boolean.class && !(a instanceof Boolean)) return false;
                if (p == long.class && !(a instanceof Long)) return false;
            } else if (!p.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    /** The same method on a public class or interface, so it can be invoked when the runtime class isn't public. */
    private static Method publicVersion(Method m) {
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) return m;
        for (Class<?> c = m.getDeclaringClass(); c != null; c = c.getSuperclass()) {
            Method found = onPublic(c, m);
            if (found != null) return found;
        }
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
            // Invoked as is; may fail, which call() reports.
        }
        return m;
    }

    private static Method onPublic(Class<?> c, Method m) {
        if (Modifier.isPublic(c.getModifiers())) {
            try {
                return c.getMethod(m.getName(), m.getParameterTypes());
            } catch (Throwable ignored) {
                // Not declared here.
            }
        }
        for (Class<?> i : c.getInterfaces()) {
            Method found = onPublic(i, m);
            if (found != null) return found;
        }
        return null;
    }

    private static Object field(Class<?> type, String name) {
        try {
            return type == null ? FAILED : type.getField(name).get(null);
        } catch (Throwable t) {
            return FAILED;
        }
    }

    private static String str(Object o) {
        if (o == null || o == FAILED) return null;
        if (o instanceof Optional) return ((Optional<?>) o).isPresent() ? str(((Optional<?>) o).get()) : null;
        String s = String.valueOf(o);
        return s.isEmpty() ? null : s;
    }

    private static String orElse(String s, String fallback) {
        return s == null ? fallback : s;
    }

    /** CLIENT or DEDICATED_SERVER: {@code FMLEnvironment.getDist()} (FML 10+) or the {@code dist} field (older). */
    private static String dist() {
        Class<?> env = cls("net.neoforged.fml.loading.FMLEnvironment");
        Object d = call(env, "getDist");
        if (d == FAILED) d = field(env, "dist");
        return str(d);
    }

    private static Object modList() {
        return call(cls("net.neoforged.fml.ModList"), "get");
    }

    private static Object modInfo(String id) {
        Object container = call(modList(), "getModContainerById", id);
        if (!(container instanceof Optional) || !((Optional<?>) container).isPresent()) return null;
        Object info = call(((Optional<?>) container).get(), "getModInfo");
        return info == FAILED ? null : info;
    }

    private static String modVersion(String id) {
        Object info = modInfo(id);
        return info == null ? null : str(call(info, "getVersion"));
    }

    private static String mcVersion() {
        String v = modVersion("minecraft");
        if (v != null) return v;
        Class<?> loader = cls("net.neoforged.fml.loading.FMLLoader");
        Object info = call(loader, "versionInfo"); // FML 1-4
        if (info == FAILED) info = call(call(loader, "getCurrent"), "getVersionInfo"); // FML 10+
        return orElse(str(call(info, "mcVersion")), "unknown");
    }

    private static File configDir() {
        Object paths = field(cls("net.neoforged.fml.loading.FMLPaths"), "CONFIGDIR");
        Object dir = call(paths, "get");
        if (dir instanceof java.nio.file.Path) return ((java.nio.file.Path) dir).toFile();
        return new File("config");
    }

    private static Object slf4j(String name) {
        Object logger = call(cls("org.slf4j.LoggerFactory"), "getLogger", name);
        return logger == FAILED ? null : logger;
    }

    /** Registers a listener on NeoForge's game bus, through whichever {@code addListener} this version has. */
    private static void listen(String eventClass, Consumer<Object> handler) {
        Class<?> type = cls(eventClass);
        Object bus = field(cls("net.neoforged.neoforge.common.NeoForge"), "EVENT_BUS");
        if (type == null || bus == FAILED) return;
        Consumer<Object> safe = e -> {
            try {
                handler.accept(e);
            } catch (Throwable ignored) {
                // Analytics never breaks the game.
            }
        };
        if (call(bus, "addListener", type, safe) != FAILED) return;
        Object normal = field(cls("net.neoforged.bus.api.EventPriority"), "NORMAL");
        if (normal != FAILED) call(bus, "addListener", normal, false, type, safe);
    }

    private static UUID uuid(Object player) {
        Object id = call(player, "getUUID");
        return id instanceof UUID ? (UUID) id : null;
    }

    /** An operator: permission level 2 ({@code hasPermissions(int)}), or the gamemaster permission on 1.21.11+. */
    private static boolean admin(Object player) {
        Object v = call(player, "hasPermissions", 2);
        if (v instanceof Boolean) return (Boolean) v;
        Object gamemaster = field(cls("net.minecraft.server.permissions.Permissions"), "COMMANDS_GAMEMASTER");
        v = call(call(player, "permissions"), "hasPermission", gamemaster);
        return Boolean.TRUE.equals(v);
    }

    private static void message(Object player, String legacy) {
        Object text = call(cls("net.minecraft.network.chat.Component"), "literal", legacy.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1"));
        if (text != FAILED) call(player, "sendSystemMessage", text);
    }

    /** The class that called {@code start}: the mod's own, for its jar and package. */
    private static Class<?> caller() {
        String self = PluginAnalytics.class.getName();
        for (StackTraceElement el : new Throwable().getStackTrace()) {
            if (el.getClassName().equals(self)) continue;
            Class<?> c = cls(el.getClassName());
            if (c != null) return c;
        }
        return PluginAnalytics.class;
    }

    /** The value of the class's {@code @Mod} annotation, or null. */
    private static String modIdOf(Class<?> type) {
        try {
            for (Annotation a : type.getAnnotations()) {
                if (a.annotationType().getName().equals("net.neoforged.fml.common.Mod")) return str(call(a, "value"));
            }
        } catch (Throwable ignored) {
            // No annotation.
        }
        return null;
    }
}
