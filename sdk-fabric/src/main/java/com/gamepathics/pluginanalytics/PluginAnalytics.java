package com.gamepathics.pluginanalytics;

import com.gamepathics.pluginanalytics.core.ConfigFiles;
import com.gamepathics.pluginanalytics.core.Engine;
import com.gamepathics.pluginanalytics.core.Host;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Product analytics for your Fabric mod: <a href="https://pluginanalytics.dev">PluginAnalytics</a>.
 *
 * <pre>{@code
 * public void onInitialize() {
 *     analytics = PluginAnalytics.start("yourmod", "pa_your_key");
 * }
 * analytics.track("waystone_placed", player.getUuid());
 * }</pre>
 *
 * <p>Works on dedicated servers and on clients, on every Minecraft version Fabric runs: it only needs Fabric
 * Loader, and uses Fabric API (when installed) for server start/stop and player joins. Everything is aggregated
 * in memory and sent from its own thread. On a server, players are a salted hash that never repeats across
 * servers; on a client the install is one anonymous player. Owners turn it off for every mod in
 * {@code config/pluginanalytics.yml}. It stops by itself when the game or server shuts down.
 */
public final class PluginAnalytics {

    public static final String SDK_VERSION = Engine.SDK_VERSION;

    private final String modId;
    private final Class<?> mainClass;
    private final ModMetadata metadata;
    private final boolean client;
    private final Mc mc = new Mc();
    private final Engine engine;
    /** The MinecraftServer, once started (dedicated servers only). */
    private volatile Object server;

    /**
     * Starts collecting for the mod {@code modId} (as in its fabric.mod.json). Call it once, from your mod
     * initializer, on a client or a dedicated server. Never throws and never blocks.
     */
    public static PluginAnalytics start(String modId, String key) {
        return new PluginAnalytics(modId, callerClass(), key);
    }

    /** Starts collecting for the mod whose initializer is {@code modInitializer} (usually {@code this}). */
    public static PluginAnalytics start(Object modInitializer, String key) {
        Class<?> type = modInitializer instanceof Class ? (Class<?>) modInitializer : modInitializer.getClass();
        return new PluginAnalytics(modIdOf(type), type, key);
    }

    private PluginAnalytics(String modId, Class<?> mainClass, String key) {
        this.modId = modId;
        this.mainClass = mainClass;
        ModContainer container = FabricLoader.getInstance().getModContainer(modId).orElse(null);
        this.metadata = container == null ? null : container.getMetadata();
        // -Dpluginanalytics.client=true: behave as a client on a dedicated server, so the client path can be tested headless.
        this.client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT || Boolean.getBoolean("pluginanalytics.client");
        this.engine = new Engine(new FabricHost(), key);
        if (!engine.isEnabled()) return;
        if (client) hookClient();
        else hookServer();
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "PluginAnalytics-" + modId + "-shutdown"));
        } catch (Throwable ignored) {
            // Already shutting down.
        }
    }

    public boolean isEnabled() {
        return engine.isEnabled();
    }

    /** Counts something the mod did, such as {@code "world_generated"}. Names: lowercase, digits, {@code _ . : -}. */
    public void track(String event) {
        engine.track(event, null, null);
    }

    /** Counts something the mod did, with properties as key/value pairs. */
    public void track(String event, String... properties) {
        engine.track(event, null, Engine.pairs(properties));
    }

    /**
     * Counts something a player did, such as {@code "waystone_placed"}, by their UUID. Player events power
     * funnels and reach. On a client every player event is the install's, whatever the UUID.
     */
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

    /** Shows (default) or hides the update notices you configure in the dashboard. */
    public void updateNotices(boolean enabled) {
        engine.updateNotices(enabled);
    }

    /** Sends feedback to you, the developer, e.g. from your own command or screen: rating 1–5 (0 for none). The result is logged. */
    public void feedback(int rating, String message) {
        engine.feedback(rating, message, reply());
    }

    /** Sends a bug report to you, with your mod's recent errors and log lines attached. The result is logged. */
    public void report(String message) {
        sendReport(true, message);
    }

    /** Sends an idea to you. The result is logged. */
    public void suggest(String message) {
        sendReport(false, message);
    }

    private void sendReport(boolean bug, String message) {
        engine.report(bug, "console", client ? engine.actor(null) : null, true, message, reply());
    }

    private Engine.Reply reply() {
        // "PluginAnalytics: " keeps the answer out of the mod's own collected log lines.
        return (ok, hint, message) -> log(ok || hint ? Level.INFO : Level.WARNING, "PluginAnalytics: " + message);
    }

    /** Measures a piece of your code: {@code try (PluginAnalytics.Span s = analytics.time("generate_chunk")) { ... }}. */
    public Span time(String name) {
        return new Span(engine, engine.isEnabled() ? name : null);
    }

    /** Measures a piece of your code: {@code analytics.time("load_recipes", () -> loadRecipes())}. */
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

    /** Your mod's config file ({@code config/<modid>.json}, .json5, .toml, .properties or .yml) is read for Config usage (default on). */
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

    /** Stops collecting and sends the last batch. Called automatically when the server or game stops. */
    public void stop() {
        engine.stop();
    }

    private String actor(UUID player) {
        return player == null ? null : engine.actor(player);
    }

    /* ---------------------------------------------------------------- Fabric API hooks, by reflection */

    private void hookServer() {
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STARTED", args -> {
            server = args[0];
            engine.mainThread(Thread.currentThread());
        });
        register("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STOPPING", args -> stop());
        register("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents", "JOIN", args -> {
            if (server == null && args.length > 2) server = args[2];
            UUID id = mc.uuid(mc.handlerPlayer(args[0]));
            if (id != null) engine.joined(id);
        });
        register("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents", "DISCONNECT", args -> {
            UUID id = mc.uuid(mc.handlerPlayer(args[0]));
            if (id != null) engine.left(id);
        });
    }

    private void hookClient() {
        register("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents", "CLIENT_STOPPING", args -> stop());
    }

    private interface Callback {
        void run(Object[] args) throws Throwable;
    }

    /** Registers {@code callback} on a Fabric API event field (such as ServerLifecycleEvents.SERVER_STARTED); nothing when Fabric API is absent. */
    private void register(String holder, String field, Callback callback) {
        try {
            ClassLoader loader = PluginAnalytics.class.getClassLoader();
            Field f = Class.forName(holder, true, loader).getField(field);
            Object event = f.get(null);
            // The listener type is the event's type argument: Event<ServerLifecycleEvents.ServerStarted>.
            Class<?> listener = (Class<?>) ((java.lang.reflect.ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, (self, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(self);
                        case "equals": return self == args[0];
                        default: return "PluginAnalytics-" + modId + "-" + field;
                    }
                }
                try {
                    callback.run(args == null ? new Object[0] : args);
                } catch (Throwable ignored) {
                    // Analytics must never break the game.
                }
                return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
            });
            Class.forName("net.fabricmc.fabric.api.event.Event", true, loader).getMethod("register", Object.class).invoke(event, proxy);
        } catch (Throwable ignored) {
            // No Fabric API (or this module of it): the shutdown hook still stops the engine.
        }
    }

    /* ---------------------------------------------------------------- helpers */

    /** The first class on the stack outside the SDK: the mod's code that called start. */
    private static Class<?> callerClass() {
        String sdk = PluginAnalytics.class.getName();
        sdk = sdk.substring(0, sdk.lastIndexOf('.') + 1);
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith(sdk) || c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")) continue;
            try {
                return Class.forName(c, false, PluginAnalytics.class.getClassLoader());
            } catch (Throwable ignored) {
                break;
            }
        }
        return PluginAnalytics.class;
    }

    /** The id of the mod whose jar (or folder) holds {@code type}. */
    private static String modIdOf(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try {
            for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
                String id = m.getMetadata().getId();
                if (id.equals("minecraft") || id.equals("java") || id.equals("fabricloader")) continue;
                for (Path root : m.getRootPaths()) {
                    if (Files.exists(root.resolve(resource))) return id;
                }
            }
        } catch (Throwable ignored) {
            // Fall through.
        }
        return type.getSimpleName().toLowerCase(Locale.ROOT);
    }

    private volatile Object logger;
    private volatile Method info;
    private volatile Method warn;

    private void log(Level level, String message) {
        try {
            if (logger == null) {
                ClassLoader loader = PluginAnalytics.class.getClassLoader();
                try {
                    Class<?> type = Class.forName("org.slf4j.Logger", true, loader);
                    Object l = Class.forName("org.slf4j.LoggerFactory", true, loader).getMethod("getLogger", String.class).invoke(null, modId);
                    info = type.getMethod("info", String.class);
                    warn = type.getMethod("warn", String.class);
                    logger = l;
                } catch (Throwable noSlf4j) {
                    Class<?> type = Class.forName("org.apache.logging.log4j.Logger", true, loader);
                    Object l = Class.forName("org.apache.logging.log4j.LogManager", true, loader).getMethod("getLogger", String.class).invoke(null, modId);
                    info = type.getMethod("info", String.class);
                    warn = type.getMethod("warn", String.class);
                    logger = l;
                }
            }
            (level.intValue() >= Level.WARNING.intValue() ? warn : info).invoke(logger, message);
        } catch (Throwable t) {
            System.out.println("[" + modId + "] " + message);
        }
    }

    private final class FabricHost implements Host {
        @Override public String name() { return metadata != null ? metadata.getName() : modId; }
        @Override public String version() { return metadata != null ? metadata.getVersion().getFriendlyString() : "unknown"; }
        @Override public Class<?> mainClass() { return mainClass; }
        @Override public File sharedConfig() { return FabricLoader.getInstance().getConfigDir().resolve("pluginanalytics.yml").toFile(); }
        @Override public String sharedConfigPath() { return "config/pluginanalytics.yml"; }
        @Override public String platform() { return client ? "fabric-client" : "fabric"; }
        @Override public boolean client() { return client; }
        @Override public String software() { return "Fabric Loader " + modVersion("fabricloader"); }
        @Override public String minecraftVersion() { return modVersion("minecraft"); }
        @Override public Boolean onlineMode() { return client ? null : mc.onlineMode(server); }
        @Override public int maxPlayers() { return client ? -1 : mc.maxPlayers(server); }
        @Override public int onlinePlayers() { return client ? 1 : mc.onlinePlayers(server); }
        @Override public Iterable<String> onlinePlayerNames() { return client ? Collections.<String>emptyList() : mc.playerNames(server); }
        @Override public Double averageTickTime() { return client ? null : mc.averageTickTime(server); }

        @Override
        public Map<String, Object> config(Map<String, Object> defaultsOut) {
            Path dir = FabricLoader.getInstance().getConfigDir();
            for (String ext : Arrays.asList(".json", ".json5", ".toml", ".properties", ".yml", ".yaml")) {
                File f = dir.resolve(modId + ext).toFile();
                if (f.isFile()) {
                    defaultsOut.putAll(ConfigFiles.readResource(mainClass, f.getName()));
                    return ConfigFiles.read(Collections.singletonList(f));
                }
            }
            return Collections.emptyMap();
        }

        @Override
        public Map<String, Boolean> dependencies() {
            Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
            if (metadata == null) return out;
            try {
                Collection<ModDependency> deps = metadata.getDependencies();
                for (ModDependency d : deps) {
                    ModDependency.Kind k = d.getKind();
                    if (k != ModDependency.Kind.DEPENDS && k != ModDependency.Kind.RECOMMENDS && k != ModDependency.Kind.SUGGESTS) continue;
                    String id = d.getModId();
                    if (id.equals("minecraft") || id.equals("java") || id.startsWith("fabric")) continue; // fabricloader, fabric-api, fabric-*
                    out.put(id, FabricLoader.getInstance().isModLoaded(id));
                }
            } catch (Throwable ignored) {
                // Older loaders: no dependencies.
            }
            return out;
        }

        @Override
        public void log(Level level, String message) {
            PluginAnalytics.this.log(level, message);
        }

        /** Fabric mods log through SLF4J/log4j loggers, usually named after the mod's id or name. */
        @Override
        public Set<String> loggerNames() {
            Set<String> names = new LinkedHashSet<String>();
            names.add(modId);
            if (metadata != null && metadata.getName() != null) names.add(metadata.getName());
            return names;
        }
    }

    private static String modVersion(String id) {
        try {
            return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Minecraft, by reflection: names are looked up as intermediary (what production uses up to 1.21.11), mapped
     * by Fabric's MappingResolver (development), then Yarn and Mojang names (unobfuscated 26.1+ and Mojang-mapped
     * development). Anything missing gives the default.
     */
    private static final class Mc {
        private final Map<String, Object> cache = new ConcurrentHashMap<String, Object>();
        private static final Object NONE = new Object();

        Object handlerPlayer(Object handler) {
            // ServerPlayNetworkHandler#getPlayer (PlayerAssociatedNetworkHandler), or its public player field.
            Object p = call(handler, "net.minecraft.class_5629", "method_32311", "()Lnet/minecraft/class_3222;", "getPlayer", "getPlayer");
            return p != null ? p : field(handler, "net.minecraft.class_3244", "field_14140", "Lnet/minecraft/class_3222;", "player", "player");
        }

        UUID uuid(Object entity) {
            Object id = call(entity, "net.minecraft.class_1297", "method_5667", "()Ljava/util/UUID;", "getUuid", "getUUID");
            return id instanceof UUID ? (UUID) id : null;
        }

        private Object playerManager(Object server) {
            return call(server, "net.minecraft.server.MinecraftServer", "method_3760", "()Lnet/minecraft/class_3324;", "getPlayerManager", "getPlayerList");
        }

        Boolean onlineMode(Object server) {
            Object v = call(server, "net.minecraft.server.MinecraftServer", "method_3828", "()Z", "isOnlineMode", "usesAuthentication");
            return v instanceof Boolean ? (Boolean) v : null;
        }

        int maxPlayers(Object server) {
            Object v = call(playerManager(server), "net.minecraft.class_3324", "method_14592", "()I", "getMaxPlayerCount", "getMaxPlayers");
            return v instanceof Integer ? (Integer) v : -1;
        }

        int onlinePlayers(Object server) {
            Object v = call(playerManager(server), "net.minecraft.class_3324", "method_14574", "()I", "getCurrentPlayerCount", "getPlayerCount");
            return v instanceof Integer ? (Integer) v : 0;
        }

        List<String> playerNames(Object server) {
            List<String> names = new ArrayList<String>();
            Object list = call(playerManager(server), "net.minecraft.class_3324", "method_14571", "()Ljava/util/List;", "getPlayerList", "getPlayers");
            if (!(list instanceof List)) return names;
            Object[] players;
            try {
                players = ((List<?>) list).toArray(); // the main thread may change it meanwhile
            } catch (Throwable t) {
                return names;
            }
            for (Object p : players) {
                Object profile = call(p, "net.minecraft.class_1657", "method_7334", "()Lcom/mojang/authlib/GameProfile;", "getGameProfile", "getGameProfile");
                Object name = plain(profile, "getName");
                if (name == null) name = plain(profile, "name"); // authlib 7+: a record
                if (name != null) names.add(String.valueOf(name));
            }
            return names;
        }

        Double averageTickTime(Object server) {
            Object v = call(server, "net.minecraft.server.MinecraftServer", "method_54834", "()J", "getAverageNanosPerTick", "getAverageTickTimeNanos");
            if (v instanceof Long) return ((Long) v) / 1_000_000.0;
            v = call(server, "net.minecraft.server.MinecraftServer", "method_54832", "()F", "getAverageTickTime", "getCurrentSmoothedTickTime");
            return v instanceof Float ? Double.valueOf((Float) v) : null;
        }

        /** Calls a public no-argument method, found by its intermediary name (mapped), Yarn name or Mojang name. */
        private Object call(Object target, String owner, String intermediary, String desc, String yarn, String mojang) {
            if (target == null) return null;
            try {
                String key = "m|" + target.getClass().getName() + "|" + intermediary;
                Object m = cache.get(key);
                if (m == null) {
                    m = NONE;
                    for (String n : names(owner, intermediary, desc, yarn, mojang, false)) {
                        try {
                            Method found = target.getClass().getMethod(n);
                            found.setAccessible(true);
                            m = found;
                            break;
                        } catch (Throwable ignored) {
                            // Next name.
                        }
                    }
                    cache.put(key, m);
                }
                return m == NONE ? null : ((Method) m).invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }

        private Object field(Object target, String owner, String intermediary, String desc, String yarn, String mojang) {
            if (target == null) return null;
            try {
                String key = "f|" + target.getClass().getName() + "|" + intermediary;
                Object f = cache.get(key);
                if (f == null) {
                    f = NONE;
                    for (String n : names(owner, intermediary, desc, yarn, mojang, true)) {
                        try {
                            f = target.getClass().getField(n);
                            break;
                        } catch (Throwable ignored) {
                            // Next name.
                        }
                    }
                    cache.put(key, f);
                }
                return f == NONE ? null : ((Field) f).get(target);
            } catch (Throwable t) {
                return null;
            }
        }

        private static Object plain(Object target, String method) {
            if (target == null) return null;
            try {
                return target.getClass().getMethod(method).invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }

        private static List<String> names(String owner, String intermediary, String desc, String yarn, String mojang, boolean isField) {
            Set<String> out = new LinkedHashSet<String>();
            try {
                MappingResolver r = FabricLoader.getInstance().getMappingResolver();
                if (r.getNamespaces().contains("intermediary")) {
                    out.add(isField ? r.mapFieldName("intermediary", owner, intermediary, desc) : r.mapMethodName("intermediary", owner, intermediary, desc));
                }
            } catch (Throwable ignored) {
                // No intermediary (unobfuscated Minecraft): the plain names below.
            }
            out.add(intermediary);
            out.add(yarn);
            out.add(mojang);
            return new ArrayList<String>(out);
        }
    }
}
