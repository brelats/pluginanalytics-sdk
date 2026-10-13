package com.gamepathics.pluginanalytics.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * The part of PluginAnalytics that is the same on every platform: collecting events, players, errors, logs,
 * performance and config usage, and sending them (a batch a minute over a live connection, HTTP as the
 * fallback). Each SDK artifact wraps it in a {@code PluginAnalytics} class with the platform's own types and
 * a {@link Host} that answers the platform questions.
 *
 * <p>Nothing that identifies a player leaves the server: players are a salted hash that never repeats across
 * servers, and names, IPs, UUIDs and secrets are removed from messages. Owners turn it off for every plugin
 * in one shared config file.
 */
public final class Engine {

    public static final String SDK_VERSION = "1.7.0";
    private static final String DEFAULT_ENDPOINT = "https://pluginanalytics.dev/api/v1/ingest";
    private static final long INTERVAL_MINUTES = 60;
    /** Clients send over HTTP only, this often: a live connection per player's game would be wasteful. */
    private static final long CLIENT_INTERVAL_MINUTES = 30;
    private static final int MAX_EVENT_KEYS = 500;
    private static final int MAX_ACTORS_PER_EVENT = 5000;
    private static final int MAX_FIRSTS = 2000;
    /** Firsts already queued; the service ignores repeats, so forgetting them when full only bounds memory. */
    private static final int MAX_FIRSTS_SEEN = 50_000;
    private static final int MAX_ERRORS = 10;
    private static final int MAX_LOG_LINES = 200;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.:\\-]{1,48}");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b");
    /** Full or "::"-compressed IPv6 (times like 12:30:45 have no "::" and too few groups). */
    private static final Pattern IPV6 = Pattern.compile(
        "(?<![\\w:\\]])(?:(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|(?:[0-9a-fA-F]{1,4}:){0,6}:(?::?[0-9a-fA-F]{1,4}){1,7}|(?:[0-9a-fA-F]{1,4}:){1,7}:)(?![\\w:])");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    /** user:password@ in URLs such as jdbc:mysql://user:pass@host (common in database errors). */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(://)[^/\\s:@]+(:[^/\\s@]*)?@");
    private static final Pattern SECRET_PAIR = Pattern.compile("(?i)\\b(password|passwd|pwd|pass|secret|token|api[_-]?key)(\\s*[=:]\\s*)[^\\s,;&)]+");
    private static final Pattern HOME_PATH = Pattern.compile("(?i)([/\\\\](?:home|users)[/\\\\])[^/\\\\\\s]+");
    private static final Pattern UUIDS = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final SecureRandom RANDOM = new SecureRandom();

    final Host host;
    private final String key;
    private final String endpoint;
    private final boolean enabled;
    private final boolean logSent;
    private final boolean ownerAllowsNotices;
    private final boolean client;
    private final String serverId;
    private final String salt;
    /** The SDK's own package (wherever it was relocated): its classes are never the plugin's. */
    private final String sdkPackage;
    private final String ownPackage;
    /** Every package with classes in the plugin's jar ("com.x.arena."), so code outside the main class's package counts too. */
    private final Set<String> ownPackages;
    /** On a client every player event is this install's (one player per game). */
    private final String installActor;
    private volatile boolean deliveredOnce;
    private volatile boolean finalBatch;

    private final Map<String, EventAgg> events = new ConcurrentHashMap<String, EventAgg>();
    private final Map<String, Supplier<?>> properties = new ConcurrentHashMap<String, Supplier<?>>();
    private final Map<String, String> sentProperties = new HashMap<String, String>();
    private final Map<String, String> sentDefaults = new HashMap<String, String>();
    private volatile boolean trackConfig = true;
    private final Set<String> configExcluded = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    // Properties, dependencies and config are read at most every few minutes; batches in between send only changes.
    private Map<String, String> readValues = Collections.emptyMap();
    private Map<String, String> readConfigValues = Collections.emptyMap();
    private Map<String, String> readDefaults = Collections.emptyMap();
    private long readAt;
    private final Set<String> firstsSeen = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final List<Object[]> firsts = Collections.synchronizedList(new ArrayList<Object[]>());
    private final Map<String, ErrorAgg> errors = new ConcurrentHashMap<String, ErrorAgg>();
    private final Map<String, LogAgg> logLines = new ConcurrentHashMap<String, LogAgg>();
    private volatile int logLevel = Level.INFO.intValue();
    private final Map<String, PerfAgg> perf = new ConcurrentHashMap<String, PerfAgg>();
    // Entries idle for a whole report leave the maps but are drained once more by the next one, so a count
    // that raced with the removal still goes out. Only touched while holding the sending lock.
    private final List<Map.Entry<String, ErrorAgg>> retiredErrors = new ArrayList<Map.Entry<String, ErrorAgg>>();
    private final List<LogAgg> retiredLogs = new ArrayList<LogAgg>();
    private final List<PerfAgg> retiredPerf = new ArrayList<PerfAgg>();
    private final Map<UUID, String> actorIds = new ConcurrentHashMap<UUID, String>();
    private volatile boolean autoPerformance = true;
    // The last errors and log lines of this plugin, attached to a bug report so the developer sees what led to it.
    private final java.util.ArrayDeque<Object[]> recentFailures = new java.util.ArrayDeque<Object[]>();
    private final java.util.ArrayDeque<Object[]> recentLogs = new java.util.ArrayDeque<Object[]>();
    private final Map<String, Long> lastReportBy = new ConcurrentHashMap<String, Long>();
    private volatile Thread mainThread;
    private Thread sampler;
    private final AtomicLong msptSum = new AtomicLong();
    private final AtomicLong msptSamples = new AtomicLong();
    private volatile long lastBatch = System.currentTimeMillis();
    private volatile long lastHttpSent;

    // Live connection (1.6+): batches go over one WebSocket every minute; the service decides the pace and what
    // is collected. HTTP stays as the fallback, and is all clients use.
    private volatile Live live;
    private volatile long nextLiveAttempt;
    private volatile int liveFailures;
    private volatile int everySeconds = 60;
    private volatile boolean urgent;
    private volatile boolean sentOnce;
    private volatile boolean collectPerformance = true;
    private volatile boolean collectLogs = true;
    private volatile boolean collectConfig = true;
    private volatile boolean allFirsts = true;
    /** Unique players and servers are counted per window (set by the service, an hour): only the ones new in it are sent. */
    private volatile long windowSeconds = 3600;
    private long windowStart;
    private final Map<String, Set<String>> windowActors = new HashMap<String, Set<String>>();
    private final Set<String> windowEvents = new java.util.HashSet<String>();
    private final AtomicLong playerSamples = new AtomicLong();
    private final AtomicLong playerSum = new AtomicLong();

    private final Set<Throwable> recentErrors = Collections.newSetFromMap(new WeakHashMap<Throwable, Boolean>());
    private volatile boolean updateNotices = true;
    private volatile boolean feedbackPrompts = true;
    private volatile Notice update;
    private volatile Notice feedbackPrompt;
    private final Set<String> told = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile ScheduledExecutorService executor;
    private Handler errorHandler;
    private Handler logHandler;
    private Object log4jAppender;
    private volatile long propertiesSentAt;
    private long refreshedAt;

    /** Answers to a person who sent feedback, a bug report or an idea; the platform adds colors. */
    public interface Reply {
        /** {@code ok}: sent (shown in green); otherwise a problem (red) or a hint ({@code hint}, yellow). */
        void send(boolean ok, boolean hint, String message);
    }

    /**
     * Starts collecting for the plugin {@code host} describes. Never throws and never blocks: if the owner
     * disabled analytics, the key is wrong or the SDK wasn't relocated, every method becomes a no-op.
     */
    public Engine(Host host, String key) {
        this.host = host;
        this.key = key;
        this.client = host.client();
        String pkg = Engine.class.getName();
        // The facade's package (one level up from core): both are the SDK's own.
        String core = pkg.substring(0, pkg.lastIndexOf('.'));
        this.sdkPackage = core.substring(0, core.lastIndexOf('.') + 1);
        String own = host.mainClass().getName();
        this.ownPackage = own.contains(".") ? own.substring(0, own.lastIndexOf('.') + 1) : own;
        this.ownPackages = jarPackages(host.mainClass(), sdkPackage);

        SharedConfig config = SharedConfig.load(host.sharedConfig(), host.sharedConfigPath(), client);
        this.serverId = config.serverId;
        this.salt = config.salt;
        this.logSent = config.logSent;
        this.ownerAllowsNotices = config.notifyAdmins;
        this.installActor = "p:" + sha256(salt + serverId).substring(0, 16);
        this.endpoint = System.getProperty("pluginanalytics.endpoint", DEFAULT_ENDPOINT);

        // Built at runtime: shading tools rewrite string constants that look like the package.
        String original = String.join(".", "com", "gamepathics", "pluginanalytics", "");
        boolean relocated = !sdkPackage.startsWith(original) || System.getProperty("pluginanalytics.dev") != null;
        boolean valid = key != null && key.startsWith("pa_");
        if (!relocated) say(Level.WARNING, "PluginAnalytics is disabled: relocate com.gamepathics.pluginanalytics into your plugin's package.");
        if (!valid) say(Level.WARNING, "PluginAnalytics is disabled: invalid project key.");
        this.enabled = config.enabled && relocated && valid;
        if (enabled) begin();
    }

    private void begin() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PluginAnalytics-" + host.name());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        // The first batch goes out 15-45 s after startup (the jitter spreads servers that restart together),
        // so a developer setting up sees the server right away; -Dpluginanalytics.firstDelay=<seconds> changes it.
        // Clients wait a little longer: a game being launched is busy loading.
        long first = Long.getLong("pluginanalytics.firstDelay", (client ? 60 : 15) + ThreadLocalRandom.current().nextInt(31));
        executor.scheduleAtFixedRate(this::samplePlayers, 0, 1, TimeUnit.MINUTES);
        executor.scheduleWithFixedDelay(this::tick, first, 2, TimeUnit.SECONDS);
        if (client) first("$join", installActor); // the player who launched the game

        errorHandler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getThrown() != null) captureIfOurs(record.getThrown());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        if (host.watchJavaLogging()) Logger.getLogger("").addHandler(errorHandler);
        Logger jul = host.julLogger();
        if (jul != null && !jul.getUseParentHandlers()) jul.addHandler(errorHandler);
        log4jAppender = attachLog4j();

        // The plugin's own log lines (getLogger()), merged by message within each batch.
        if (jul != null) {
            logHandler = new Handler() {
                @Override public void publish(LogRecord record) {
                    if (record.getLevel().intValue() >= logLevel) captureLog(record);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            jul.addHandler(logHandler);
        }
        startSampler();
    }

    /** Set while the SDK logs its own lines, so they aren't collected as the plugin's. */
    private static final ThreadLocal<Boolean> SAYING = new ThreadLocal<Boolean>();

    /** The SDK's own recent lines: platforms that log on another thread (Velocity) still hand them back to us. */
    private final Map<String, Long> said = new ConcurrentHashMap<String, Long>();

    private void say(Level level, String message) {
        long now = System.currentTimeMillis();
        if (said.size() > 50) said.values().removeIf(t -> now - t > 60_000);
        said.put(message.trim(), now);
        SAYING.set(Boolean.TRUE);
        try {
            host.log(level, message);
        } catch (Throwable ignored) {
            // Logging is best effort.
        } finally {
            SAYING.remove();
        }
    }

    /* ---------------------------------------------------------------- what the facades call */

    /** True unless the owner turned analytics off (or the key or relocation is wrong). */
    public boolean isEnabled() {
        return enabled;
    }

    /** Counts an event; {@code actor} is a player's id from {@link #actor(UUID)}, or null for the server. */
    public void track(String event, String actor, Map<String, ?> rawProps) {
        if (!enabled || !valid(event)) return;
        Map<String, String> props = null;
        if (rawProps != null && !rawProps.isEmpty()) {
            props = new TreeMap<String, String>();
            for (Map.Entry<String, ?> e : rawProps.entrySet()) {
                if (props.size() == 5) break;
                if (valid(e.getKey()) && e.getValue() != null) props.put(e.getKey(), clip(String.valueOf(e.getValue()), 48));
            }
        }
        String id = eventId(actor != null, event, props);
        while (true) {
            EventAgg agg = events.get(id);
            if (agg == null) {
                if (events.size() >= MAX_EVENT_KEYS) return;
                Map<String, String> fixed = props;
                agg = events.computeIfAbsent(id, k -> new EventAgg(k, event, actor != null, fixed));
            }
            if (agg.add(1, actor)) break; // else a batch just retired it: the next lookup makes a new one
        }
        first(event, actor);
    }

    /** A player's anonymous id: a salted hash of their UUID, different on every server. On a client, the install's. */
    public String actor(UUID uuid) {
        if (client || uuid == null) return installActor;
        String id = actorIds.get(uuid);
        if (id == null) {
            if (actorIds.size() > 10_000) actorIds.clear(); // quits normally keep it small
            id = "p:" + sha256(salt + uuid).substring(0, 16);
            actorIds.put(uuid, id);
        }
        return id;
    }

    /** A player joined: the first join of each player is recorded for funnels and player counts. */
    public void joined(UUID uuid) {
        if (enabled && uuid != null) first("$join", actor(uuid));
    }

    /** A player left: their cached id can go. */
    public void left(UUID uuid) {
        if (uuid != null) actorIds.remove(uuid);
    }

    public void property(String name, Supplier<?> value) {
        if (enabled && valid(name)) properties.put(name, value);
    }

    public void logLevel(Level level) {
        if (level != null) logLevel = level.intValue();
    }

    public void updateNotices(boolean enabled) {
        updateNotices = enabled;
    }

    public void feedbackPrompts(boolean enabled) {
        feedbackPrompts = enabled;
    }

    public void autoPerformance(boolean enabled) {
        autoPerformance = enabled;
    }

    public boolean autoPerformance() {
        return autoPerformance;
    }

    /** Whether the plugin's plan collects performance right now (the service decides). */
    public boolean collectsPerformance() {
        return collectPerformance;
    }

    public void trackConfig(boolean enabled) {
        trackConfig = enabled;
    }

    public void excludeConfig(String... paths) {
        if (paths == null) return;
        for (String p : paths) if (p != null && !p.isEmpty()) configExcluded.add(p.toLowerCase(java.util.Locale.ROOT));
    }

    public void captureError(Throwable error) {
        if (enabled && error != null) capture(error);
    }

    /** The thread to sample for Performance, when the platform only knows it later (the server thread). */
    public void mainThread(Thread thread) {
        mainThread = thread;
        if (enabled && thread != null && sampler == null) startSampler();
    }

    /**
     * Notices for someone who just joined and may see them ({@code who}: a stable id such as their UUID): the
     * update notice and the feedback prompt, each once. Messages keep their {@code &} color codes.
     */
    public List<String> noticesFor(String who) {
        List<String> out = new ArrayList<String>();
        if (!enabled || !ownerAllowsNotices) return out;
        Notice n = update;
        if (n != null && n.admins && updateNotices && tell(who + "|" + n.id)) out.add(n.message);
        Notice fb = feedbackPrompt;
        if (fb != null && feedbackPrompts && tell(who + "|" + fb.id)) out.add(fb.message);
        return out;
    }

    /** Whether the owner lets notices reach admins in game (notify-admins in the shared config). */
    public boolean noticesAllowed() {
        return ownerAllowsNotices;
    }

    /** Sends feedback to the developer: rating 1–5 (0 for none). Runs in the background; {@code reply} may be null. */
    public void feedback(int rating, String message, Reply reply) {
        if (!enabled || executor == null) {
            if (reply != null) reply.send(false, false, "Feedback is turned off on this server.");
            return;
        }
        String body = new Json().open().field("key", key).field("server", serverId).field("version", host.version())
            .field("platform", host.platform()).field("rating", rating).field("message", message == null ? "" : redact(clip(message, 2000))).close().toString();
        executor.execute(() -> {
            int status;
            try {
                status = post(api("feedback"), body, false);
            } catch (Throwable t) {
                status = -1;
            }
            if (reply != null) {
                if (status == 200) reply.send(true, false, "Thanks! Your feedback was sent to the developer of " + host.name() + ".");
                else reply.send(false, false, "Couldn't send your feedback right now. Please try again later.");
            }
        });
    }

    private static final long REPORT_COOLDOWN_MS = 60_000;

    /**
     * Sends a bug report ({@code bug}) or an idea to the developer. {@code who} limits it to one a minute per
     * person; {@code actor} is the player's id or "console"; {@code admin} whether an operator sent it. A bug
     * report carries the plugin's errors and log lines from the last 15 minutes, already redacted.
     */
    public void report(boolean bug, String who, String actor, boolean admin, String message, Reply reply) {
        if (!enabled || executor == null) {
            if (reply != null) reply.send(false, false, bug ? "Bug reports are turned off on this server." : "Ideas are turned off on this server.");
            return;
        }
        String kind = bug ? "bug" : "idea";
        String text = message == null ? "" : message.trim();
        if (text.length() < 3) {
            if (reply != null) reply.send(false, false, bug ? "Describe what happened in a few words." : "Describe your idea in a few words.");
            return;
        }
        // One per minute per person, so a command can't be used to flood the developer.
        String limitKey = kind + ":" + who;
        long now = System.currentTimeMillis();
        Long last = lastReportBy.get(limitKey);
        if (last != null && now - last < REPORT_COOLDOWN_MS) {
            if (reply != null) reply.send(false, false, "Please wait a minute before sending another one.");
            return;
        }
        lastReportBy.put(limitKey, now);
        if (lastReportBy.size() > 1000) lastReportBy.values().removeIf(t -> now - t >= REPORT_COOLDOWN_MS);

        Json j = new Json().open().field("key", key).field("server", serverId).field("kind", kind)
            .field("version", host.version()).field("platform", host.platform())
            .field("mc", host.minecraftVersion())
            .field("actor", actor == null ? "console" : actor)
            .field("admin", admin)
            .field("message", redact(clip(text, 1000)));
        // Errors still waiting for the next batch travel with this one instead, so they show up in Errors
        // right away (and aren't counted twice).
        final Map<String, ErrorAgg> moved = new HashMap<String, ErrorAgg>();
        if (bug) {
            long since = now / 1000 - 15 * 60;
            j.name("errors").array();
            synchronized (recentFailures) {
                for (Object[] f : recentFailures) {
                    if ((Long) f[0] >= since) j.open().field("t", (Long) f[0]).field("f", (String) f[1]).field("type", (String) f[2]).close();
                    String fp = (String) f[1];
                    ErrorAgg pending = moved.containsKey(fp) ? null : errors.get(fp);
                    long count = pending == null ? 0 : pending.count.getAndSet(0);
                    if (count > 0) moved.put(fp, pending.copy(count));
                }
            }
            j.end();
            j.name("issues").array();
            for (Map.Entry<String, ErrorAgg> e : moved.entrySet()) {
                ErrorAgg a = e.getValue();
                j.open().field("f", e.getKey()).field("type", a.type).field("msg", a.message).field("stack", a.stack).field("c", a.count.get()).close();
            }
            j.end();
            j.name("logs").array();
            synchronized (recentLogs) {
                for (Object[] l : recentLogs) {
                    if ((Long) l[0] >= since) j.open().field("t", (Long) l[0]).field("lv", (String) l[1]).field("m", (String) l[2]).close();
                }
            }
            j.end();
        }
        String body = j.close().toString();
        executor.execute(() -> {
            int status;
            try {
                status = post(api("report"), body, false);
            } catch (Throwable t) {
                status = -1;
            }
            if (status != 200) {
                // Not delivered: the moved errors go back to the next batch.
                for (Map.Entry<String, ErrorAgg> e : moved.entrySet()) mergeError(e.getKey(), e.getValue());
            }
            if (reply == null) return;
            if (status == 200) {
                reply.send(true, false, (bug ? "Thanks! Your report was sent to the developer of " : "Thanks! Your idea was sent to the developer of ") + host.name() + ".");
            } else {
                lastReportBy.remove(limitKey);
                reply.send(false, false, "Couldn't send it right now. Please try again later.");
            }
        });
    }

    private static void remember(java.util.ArrayDeque<Object[]> list, Object[] item, int max) {
        synchronized (list) {
            list.addLast(item);
            while (list.size() > max) list.removeFirst();
        }
    }

    /** Stops collecting: sends a last batch and lets go of the logging hooks. */
    public void stop() {
        ScheduledExecutorService ex = executor;
        if (ex == null) return;
        executor = null;
        ex.shutdownNow();
        try {
            ex.awaitTermination(2, TimeUnit.SECONDS); // a batch going out right now finishes first
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A last batch on the way out, so a server stopped minutes after starting isn't missed. Sent right here,
        // while the plugin's classes can still load (they can't once it's disabled), with short timeouts.
        finalBatch = true;
        finalSend();
        if (sampler != null) sampler.interrupt();
        if (host.watchJavaLogging()) Logger.getLogger("").removeHandler(errorHandler);
        Logger jul = host.julLogger();
        if (jul != null) {
            jul.removeHandler(errorHandler);
            if (logHandler != null) jul.removeHandler(logHandler);
        }
        if (log4jAppender != null) {
            try {
                Object root = Class.forName("org.apache.logging.log4j.LogManager").getMethod("getRootLogger").invoke(null);
                root.getClass().getMethod("removeAppender", Class.forName("org.apache.logging.log4j.core.Appender")).invoke(root, log4jAppender);
            } catch (Throwable ignored) {
                // The logging system is already gone during shutdown.
            }
        }
    }

    /* ---------------------------------------------------------------- collecting */

    /**
     * An event's count and players since the last batch. Guarded by its own monitor so a batch can take
     * them out while players keep adding: nothing lands in between and gets lost.
     */
    private static final class EventAgg {
        final String id;
        final String name;
        final boolean player;
        final Map<String, String> props;
        long count;
        Set<String> actors = new java.util.HashSet<String>();
        /** Removed from the map while idle: whoever still holds it adds to a fresh one instead. */
        boolean dead;

        EventAgg(String id, String name, boolean player, Map<String, String> props) {
            this.id = id;
            this.name = name;
            this.player = player;
            this.props = props;
        }

        synchronized boolean add(long n, String actor) {
            if (dead) return false;
            count += n;
            if (actor != null && actors.size() < MAX_ACTORS_PER_EVENT) actors.add(actor);
            return true;
        }

        synchronized boolean addAll(long n, Set<String> more) {
            if (dead) return false;
            count += n;
            for (String a : more) if (actors.size() < MAX_ACTORS_PER_EVENT) actors.add(a);
            return true;
        }

        synchronized boolean pending() {
            return count > 0 || !actors.isEmpty();
        }

        /** What happened since the last batch, leaving this one empty; null (and removed) if nothing did. */
        synchronized EventAgg drain(Map<String, EventAgg> map) {
            if (count == 0 && actors.isEmpty()) {
                dead = true;
                map.remove(id, this);
                return null;
            }
            EventAgg out = new EventAgg(id, name, player, props);
            out.count = count;
            out.actors = actors;
            count = 0;
            actors = new java.util.HashSet<String>();
            return out;
        }
    }

    private static final class Notice {
        final String id;
        final String message;
        final boolean admins;
        final boolean warning;

        Notice(String id, String message, boolean admins, boolean warning) {
            this.id = id;
            // One line: a notice can't break into lines that pass for server messages.
            this.message = message.replaceAll("[\\r\\n]+", " ");
            this.admins = admins;
            this.warning = warning;
        }
    }

    private static final class PerfAgg {
        final String name;
        final String kind;
        final AtomicLong calls = new AtomicLong();
        final AtomicLong totalNanos = new AtomicLong();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicLongArray buckets = new AtomicLongArray(7);

        PerfAgg(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }

        void raiseMax(long nanos) {
            long max;
            while (nanos > (max = maxNanos.get()) && !maxNanos.compareAndSet(max, nanos)) {
                // retry: another thread raised the max
            }
        }

        /** Its totals so far, leaving this one at zero (a call landing meanwhile simply counts next time). */
        PerfAgg drain() {
            PerfAgg out = new PerfAgg(name, kind);
            out.calls.set(calls.getAndSet(0));
            out.totalNanos.set(totalNanos.getAndSet(0));
            out.maxNanos.set(maxNanos.getAndSet(0));
            for (int i = 0; i < 7; i++) out.buckets.set(i, buckets.getAndSet(i, 0));
            return out;
        }
    }

    /** Upper bounds of the histogram buckets, in nanoseconds: 0.1, 0.5, 1, 5, 10, 50 ms and above. */
    private static final long[] BUCKETS = {100_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L, 50_000_000L};
    /** Adds a measured call: {@code kind} is "custom", "event" or "command". */
    public void recordTime(String name, String kind, long nanos) {
        if (!collectPerformance) return;
        PerfAgg agg = perfAgg(name, kind);
        if (agg == null) return;
        agg.calls.incrementAndGet();
        agg.totalNanos.addAndGet(nanos);
        agg.raiseMax(nanos);
        int b = 0;
        while (b < BUCKETS.length && nanos >= BUCKETS[b]) b++;
        agg.buckets.incrementAndGet(b);
    }

    private PerfAgg perfAgg(String name, String kind) {
        Map<String, PerfAgg> map = perf;
        PerfAgg agg = map.get(name);
        if (agg == null) {
            if (map.size() >= 160) return null;
            PerfAgg created = new PerfAgg(name, kind);
            PerfAgg raced = map.putIfAbsent(name, created);
            agg = raced != null ? raced : created;
        }
        return agg;
    }

    /** The packages of the classes in the plugin's jar, minus the SDK's own; empty if the jar can't be read. */
    private static Set<String> jarPackages(Class<?> main, String sdkPackage) {
        Set<String> out = new java.util.HashSet<String>();
        try {
            File jar = new File(main.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar)) {
                for (java.util.Enumeration<java.util.jar.JarEntry> e = file.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                    int slash = name.lastIndexOf('/');
                    if (slash < 0) continue; // the default package can't be told apart from the server
                    String pkg = name.substring(0, slash + 1).replace('/', '.');
                    if (!pkg.startsWith(sdkPackage)) out.add(pkg);
                }
            }
        } catch (Throwable ignored) {
            // Unreadable jar: fall back to the main class's package and below.
        }
        return out;
    }

    /** True for classes of this plugin (any package in its jar), never for the SDK itself. */
    boolean isOwn(String className) {
        if (className.startsWith(sdkPackage)) return false;
        if (ownPackages.isEmpty()) return ownPackage.contains(".") && className.startsWith(ownPackage);
        int dot = className.lastIndexOf('.');
        return dot > 0 && ownPackages.contains(className.substring(0, dot + 1));
    }

    private synchronized void startSampler() {
        if (sampler != null || client || !autoPerformance) return;
        if (ownPackages.isEmpty() && !ownPackage.contains(".")) return; // a plugin in the default package can't be told apart from the server
        if (mainThread == null) mainThread = host.mainThread();
        if (mainThread == null) return; // started later by mainThread(Thread), or never (proxies, Folia)
        sampler = new Thread(this::sampleLoop, "PluginAnalytics-sampler-" + host.name());
        sampler.setDaemon(true);
        sampler.setPriority(Thread.MIN_PRIORITY);
        sampler.start();
    }

    private void sampleLoop() {
        long last = System.nanoTime();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Jittered so samples don't line up with the 50 ms tick.
                Thread.sleep(80 + ThreadLocalRandom.current().nextInt(41));
                long now = System.nanoTime();
                long weight = Math.min(now - last, 1_000_000_000L);
                last = now;
                Thread main = mainThread;
                if (!autoPerformance || !collectPerformance || main == null) continue;
                // Innermost frame of the plugin (where the time goes) and outermost (the task, listener or
                // command that led there): "ScoreboardTask#run > Boards#render".
                StackTraceElement inner = null;
                StackTraceElement entry = null;
                for (StackTraceElement f : main.getStackTrace()) {
                    String c = f.getClassName();
                    if (isOwn(c)) {
                        if (inner == null) inner = f;
                        entry = f;
                    }
                }
                if (inner == null) continue;
                String at = methodName(inner.getClassName(), inner.getMethodName(), inner.getLineNumber());
                String from = methodName(entry.getClassName(), entry.getMethodName(), entry.getLineNumber());
                recordSample(from.equals(at) ? at : from + " > " + at, "method", weight);
                recordSample("$main", "total", weight);
            }
        } catch (InterruptedException ignored) {
            // Stopped with the plugin.
        } catch (Throwable ignored) {
            // Sampling is optional: never let it bother the server.
        }
    }

    private void recordSample(String name, String kind, long nanos) {
        PerfAgg agg = perfAgg(name, kind);
        if (agg == null) return;
        agg.calls.incrementAndGet();
        agg.totalNanos.addAndGet(nanos);
    }

    /**
     * "com.x.arena.mob.Boss" + "tick" → "mob.Boss#tick": the class relative to the plugin's main package, and the
     * raw method name. Kept whole (nested classes, lambdas) so obfuscated names can be mapped back; the dashboard
     * shortens them for display.
     */
    public String methodName(String className, String method, int line) {
        String rel = relativeClass(className);
        String name = rel + "#" + method;
        // Obfuscators give many methods the same short name ("a"): the line tells them apart once the
        // dashboard has the mapping of this version.
        if (line > 0 && OBFUSCATED.matcher(rel.substring(rel.lastIndexOf('.') + 1)).matches() && method.length() <= 2) name += ":" + line;
        return name.length() > 46 ? name.substring(0, 46) : name;
    }

    /** Class names like "a", "b$c" or "ab": what ProGuard and similar tools produce. */
    private static final Pattern OBFUSCATED = Pattern.compile("[a-z]{1,2}(\\$[a-z0-9]{1,2})*");

    /** "com.x.arena.mob.Boss" → "mob.Boss" for classes under the main class's package; others stay whole. */
    public String relativeClass(String className) {
        return ownPackage.contains(".") && className.startsWith(ownPackage) ? className.substring(ownPackage.length()) : className;
    }

    private static final class LogAgg {
        final String level;
        final String message;
        final AtomicLong count = new AtomicLong();

        LogAgg(String level, String message) {
            this.level = level;
            this.message = message;
        }

        LogAgg copy(long n) {
            LogAgg out = new LogAgg(level, message);
            out.count.set(n);
            return out;
        }
    }

    private static final class ErrorAgg {
        final String type;
        final String message;
        final String stack;
        final AtomicLong count = new AtomicLong();

        ErrorAgg(String type, String message, String stack) {
            this.type = type;
            this.message = message;
            this.stack = stack;
        }

        ErrorAgg copy(long n) {
            ErrorAgg out = new ErrorAgg(type, message, stack);
            out.count.set(n);
            return out;
        }
    }

    /** Remembers the first time an actor (a player, or the server when null) did something: funnels are built from these. */
    void first(String event, String actor) {
        if (!enabled) return;
        if (!allFirsts && actor != null && !"$join".equals(event)) return; // the service only keeps first joins on this plan
        String seen = event + "|" + actor;
        if (firstsSeen.contains(seen)) return;
        synchronized (firsts) {
            // Marked seen only once queued, so a first that doesn't fit now is taken when there's room.
            if (firsts.size() >= MAX_FIRSTS) return;
            if (firstsSeen.size() >= MAX_FIRSTS_SEEN) firstsSeen.clear(); // repeats are ignored by the service
            if (firstsSeen.add(seen)) firsts.add(new Object[] {event, actor, System.currentTimeMillis() / 1000});
        }
    }

    private void samplePlayers() {
        try {
            playerSum.addAndGet(client ? 1 : host.onlinePlayers());
            playerSamples.incrementAndGet();
            Double mspt = host.averageTickTime();
            if (mspt != null) {
                msptSum.addAndGet(Math.round(mspt * 1000));
                msptSamples.incrementAndGet();
            }
        } catch (Throwable ignored) {
            // Reading the player list off the main thread can race on some servers; the next sample will do.
        }
    }

    /**
     * Most servers log exceptions (commands, events, tasks) through log4j rather than java.util.logging, and
     * Velocity and mod loaders log everything through it. The appender is a dynamic proxy so the SDK doesn't
     * depend on log4j: it captures exceptions from the plugin's code, and the lines of its own loggers.
     */
    private Object attachLog4j() {
        try {
            Class<?> appenderType = Class.forName("org.apache.logging.log4j.core.Appender");
            Class<?> stateType = Class.forName("org.apache.logging.log4j.core.LifeCycle$State");
            Object started = Enum.valueOf(stateType.asSubclass(Enum.class), "STARTED");
            String name = "PluginAnalytics-" + host.name();
            Set<String> loggers = host.loggerNames();
            Object appender = Proxy.newProxyInstance(Engine.class.getClassLoader(), new Class<?>[] {appenderType}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "append":
                        Object event = args[0];
                        Object thrown = event.getClass().getMethod("getThrown").invoke(event);
                        if (thrown instanceof Throwable) captureIfOurs((Throwable) thrown);
                        if (!loggers.isEmpty()) {
                            Object loggerName = event.getClass().getMethod("getLoggerName").invoke(event);
                            // Its own loggers by name, and loggers named after its classes (mods often use one per class).
                            if (loggerName != null && (loggers.contains(loggerName) || isOwn(String.valueOf(loggerName)))) {
                                String level = String.valueOf(event.getClass().getMethod("getLevel").invoke(event));
                                Object msg = event.getClass().getMethod("getMessage").invoke(event);
                                Object text = msg == null ? null : msg.getClass().getMethod("getFormattedMessage").invoke(msg);
                                captureLine(level, text == null ? null : String.valueOf(text));
                            }
                        }
                        return null;
                    case "getName": return name;
                    case "isStarted": return true;
                    case "isStopped": return false;
                    case "ignoreExceptions": return true;
                    case "getState": return started;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return name;
                    default: return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }
            });
            Object root = Class.forName("org.apache.logging.log4j.LogManager").getMethod("getRootLogger").invoke(null);
            root.getClass().getMethod("addAppender", appenderType).invoke(root, appender);
            return appender;
        } catch (Throwable t) {
            return null; // no log4j: java.util.logging still covers the server
        }
    }

    private void captureLog(LogRecord record) {
        try {
            String raw = record.getMessage();
            if (raw == null) return;
            Object[] params = record.getParameters();
            if (params != null && params.length > 0 && raw.contains("{0")) raw = java.text.MessageFormat.format(raw, params);
            Level l = record.getLevel();
            captureLine(l.intValue() >= Level.SEVERE.intValue() ? "SEVERE" : l.intValue() >= Level.WARNING.intValue() ? "WARNING" : "INFO", raw);
        } catch (Throwable ignored) {
            // Logging must never break because of analytics.
        }
    }

    /** One of the plugin's log lines; {@code levelName} in java.util.logging or log4j words (WARN, ERROR, FATAL...). */
    private void captureLine(String levelName, String raw) {
        try {
            if (raw == null || raw.startsWith("PluginAnalytics") || SAYING.get() != null) return;
            // BungeeCord's plugin loggers prefix every line with "[PluginName] ".
            String prefix = "[" + host.name() + "] ";
            if (raw.startsWith(prefix)) raw = raw.substring(prefix.length());
            if (said.containsKey(raw.trim())) return;
            String lv = levelName == null ? "INFO" : levelName.toUpperCase(java.util.Locale.ROOT);
            if (lv.equals("DEBUG") || lv.equals("TRACE") || lv.startsWith("FINE")) return;
            String level = lv.equals("SEVERE") || lv.equals("ERROR") || lv.equals("FATAL") ? "SEVERE" : lv.startsWith("WARN") ? "WARNING" : "INFO";
            int rank = level.equals("SEVERE") ? Level.SEVERE.intValue() : level.equals("WARNING") ? Level.WARNING.intValue() : Level.INFO.intValue();
            if (rank < logLevel) return;
            String message = redact(clip(raw.replace('\n', ' ').trim(), 300));
            if (message.isEmpty()) return;
            remember(recentLogs, new Object[] {System.currentTimeMillis() / 1000, level, message}, 40);
            if (!collectLogs) return; // still kept above for bug reports
            String id = level + "|" + message;
            LogAgg agg = logLines.get(id);
            if (agg == null) {
                if (logLines.size() >= MAX_LOG_LINES) return;
                LogAgg created = new LogAgg(level, message);
                LogAgg raced = logLines.putIfAbsent(id, created);
                agg = raced != null ? raced : created;
            }
            agg.count.incrementAndGet();
        } catch (Throwable ignored) {
            // Logging must never break because of analytics.
        }
    }

    private void captureIfOurs(Throwable error) {
        // The same exception often reaches both logging systems.
        synchronized (recentErrors) {
            if (!recentErrors.add(error)) return;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            for (StackTraceElement el : t.getStackTrace()) {
                String c = el.getClassName();
                if (isOwn(c)) {
                    capture(error);
                    return;
                }
            }
        }
    }

    private void capture(Throwable error) {
        try {
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            StringBuilder fp = new StringBuilder(root.getClass().getName());
            int frames = 0;
            for (StackTraceElement el : root.getStackTrace()) {
                if (isOwn(el.getClassName()) && frames++ < 5) fp.append('|').append(el.getClassName()).append('#').append(el.getMethodName());
            }
            if (frames == 0) {
                StackTraceElement[] st = root.getStackTrace();
                for (int i = 0; i < Math.min(3, st.length); i++) fp.append('|').append(st[i].getClassName()).append('#').append(st[i].getMethodName());
            }
            String id = sha256(fp.toString()).substring(0, 16);
            remember(recentFailures, new Object[] {System.currentTimeMillis() / 1000, id, root.getClass().getName()}, 20);
            ErrorAgg agg = errors.get(id);
            if (agg == null) {
                if (errors.size() >= 50) return;
                StringBuilder stack = new StringBuilder();
                for (Throwable t = error; t != null && stack.length() < 6000; t = t.getCause() == t ? null : t.getCause()) {
                    if (t != error) stack.append("Caused by: ");
                    stack.append(t.getClass().getName()).append(": ").append(redact(String.valueOf(t.getMessage()))).append('\n');
                    StackTraceElement[] st = t.getStackTrace();
                    for (int i = 0; i < Math.min(25, st.length); i++) stack.append("\tat ").append(st[i]).append('\n');
                }
                ErrorAgg created = new ErrorAgg(root.getClass().getName(), redact(clip(String.valueOf(root.getMessage()), 500)), clip(stack.toString(), 6000));
                ErrorAgg raced = errors.putIfAbsent(id, created);
                agg = raced != null ? raced : created;
                if (raced == null) urgent = true; // a new error goes out within seconds, for notifications
            }
            agg.count.incrementAndGet();
        } catch (Throwable ignored) {
            // Never let error reporting cause an error.
        }
    }

    /* ---------------------------------------------------------------- sending */

    /** Over its plan's server allowance, the service asks this server to report less often (a day, usually). */
    private volatile long minGapMillis = 0;
    private volatile long lastSent = 0;
    /** After a failed batch the next try waits, doubling from 30 s to 10 minutes; its data stays pending. */
    private volatile long nextAttempt;
    private volatile int failures;
    /** The service accepts an HTTP batch from a server at most this often (live batches aren't limited by it). */
    private static final long HTTP_MIN_GAP_MS = 4 * 60_000L;
    /** A live batch not acknowledged this long is taken back and sent again. */
    private static final long ACK_TIMEOUT_MS = 30_000L;

    /** Held while taking, sending and settling a batch: batches go out one at a time, from any thread. */
    private final ReentrantLock sending = new ReentrantLock();
    private final Condition replied = sending.newCondition();
    /** The live batch waiting for its reply (at most one); guarded by {@link #sending}. */
    private volatile Batch inFlight;

    /** One batch's data, taken out of the running totals; merged back if it isn't delivered. */
    private static final class Batch {
        final long at;
        final List<EventAgg> events = new ArrayList<EventAgg>();
        final List<Object[]> firsts = new ArrayList<Object[]>();
        final Map<String, ErrorAgg> errors = new LinkedHashMap<String, ErrorAgg>();
        final List<LogAgg> logs = new ArrayList<LogAgg>();
        final List<PerfAgg> perf = new ArrayList<PerfAgg>();
        final Map<String, String> props = new TreeMap<String, String>();
        final Map<String, String> defaults = new TreeMap<String, String>();
        Map<String, Object[]> window = Collections.emptyMap();
        long msptSamples, msptSum, playerSamples, playerSum;
        String json;
        /** The live connection it went out on, while its reply is awaited. */
        Live via;

        Batch(long at) {
            this.at = at;
        }
    }

    /** Every 2 seconds: keeps the live connection up and sends when a batch is due. */
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            Live l = live;
            boolean connected = l != null && l.open;
            if (!connected && !client && now >= nextLiveAttempt && !Boolean.getBoolean("pluginanalytics.httpOnly")) {
                connected = connectLive(now);
                l = live;
            }
            expireInFlight(now);
            long gap = connected
                ? everySeconds * 1000L
                : Math.max(5, Math.min(60, Long.getLong("pluginanalytics.interval", client ? CLIENT_INTERVAL_MINUTES : INTERVAL_MINUTES))) * 60_000L;
            if (minGapMillis > gap) gap = minGapMillis;
            boolean due = inFlight == null && now >= nextAttempt
                && (!sentOnce || failures > 0 || now - lastSent >= gap || (urgent && connected && now - lastSent >= 5000));
            if (due) {
                urgent = false;
                sendSafely();
            } else if (connected && now - l.lastWrite > 45_000) {
                l.ping(); // keeps the connection open through proxies; pings aren't billed
            }
        } catch (Throwable t) {
            if (logSent) say(Level.INFO, "PluginAnalytics: " + t);
        }
    }

    private String api(String name) {
        return endpoint.replace("/ingest", "/" + name);
    }

    private boolean connectLive(long now) {
        String url = api("live").replaceFirst("^http", "ws") + "?key=" + key + "&server=" + serverId;
        Live l = new Live(this);
        try {
            l.connect(url);
            live = l; // before the reader starts, so its messages never meet the previous connection
            l.startReader();
            return true;
        } catch (Throwable t) {
            // Backs off up to 30 minutes; HTTP carries the batches meanwhile.
            liveFailures++;
            nextLiveAttempt = now + Math.min(30 * 60_000L, 15_000L << Math.min(7, liveFailures)) + ThreadLocalRandom.current().nextInt(10_000);
            if (logSent) say(Level.INFO, "PluginAnalytics live connection failed: " + t);
            return false;
        }
    }

    /** A live connection ended; {@code dropped} unless this side closed it on purpose. */
    void liveClosed(Live l, boolean dropped) {
        long now = System.currentTimeMillis();
        if (dropped) {
            if (l.closeCode == 1008) {
                // Refused by policy (plugin deleted, SDK too old): asking again soon won't change that.
                nextLiveAttempt = now + TimeUnit.HOURS.toMillis(6);
            } else {
                // Only a connection that held for a while proves the service is fine; quick drops back off further.
                if (now - l.openedAt >= 5 * 60_000L) liveFailures = 0;
                else liveFailures++;
                long wait = 5_000 + ThreadLocalRandom.current().nextInt(25_001);
                if (liveFailures > 0) wait = Math.max(wait, Math.min(30 * 60_000L, 15_000L << Math.min(7, liveFailures)));
                nextLiveAttempt = now + wait;
            }
        }
        sending.lock();
        try {
            Batch b = inFlight;
            if (b != null && b.via == l) {
                inFlight = null;
                failed(b, now); // no reply came: the service may not have it
            }
            replied.signalAll();
        } finally {
            sending.unlock();
        }
    }

    /** A live batch unanswered for too long goes back into the totals, and the silent connection is dropped. */
    private void expireInFlight(long now) {
        Live stale = null;
        sending.lock();
        try {
            Batch b = inFlight;
            if (b != null && now - b.at > ACK_TIMEOUT_MS) {
                inFlight = null;
                stale = b.via;
                failed(b, now);
            }
        } finally {
            sending.unlock();
        }
        if (stale != null) stale.close(true);
    }

    /** A message from the service on the live connection {@code l}. */
    @SuppressWarnings("unchecked")
    void onLive(Live l, String text) {
        Object parsed;
        try {
            parsed = new MiniJson(text).value();
        } catch (RuntimeException e) {
            return;
        }
        if (!(parsed instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) parsed;
        Object t = m.get("t");
        if ("reply".equals(t)) {
            readReply(m);
            sending.lock();
            try {
                Batch b = inFlight;
                if (b != null && b.via == l) {
                    inFlight = null;
                    long now = System.currentTimeMillis();
                    if (Boolean.TRUE.equals(m.get("ok"))) delivered(b, now);
                    else failed(b, now); // refused (sent too soon): it goes again with the next one
                    replied.signalAll();
                }
            } finally {
                sending.unlock();
            }
        } else if ("config".equals(t) || "every".equals(t)) {
            Object every = m.get("every");
            if (every instanceof Number) everySeconds = (int) Math.max(5, Math.min(86_400, ((Number) every).longValue()));
            applyCollect(m);
            // A pace of an hour or more doesn't need a connection held open.
            if (everySeconds >= 3600) {
                nextLiveAttempt = System.currentTimeMillis() + everySeconds * 1000L;
                l.close();
            }
        } else if ("send".equals(t)) {
            urgent = true;
        }
        if (logSent) say(Level.INFO, "PluginAnalytics received: " + text);
    }

    /** What the service collects on this plugin's plan, and its unique window; in the live config and HTTP replies. */
    @SuppressWarnings("unchecked")
    private void applyCollect(Map<String, Object> m) {
        Object collect = m.get("collect");
        if (collect instanceof Map) {
            Map<String, Object> c = (Map<String, Object>) collect;
            collectPerformance = !Boolean.FALSE.equals(c.get("performance"));
            collectLogs = !Boolean.FALSE.equals(c.get("logs"));
            collectConfig = !Boolean.FALSE.equals(c.get("config"));
            allFirsts = !"join".equals(c.get("firsts"));
        }
        Object window = m.get("window");
        if (window instanceof Number) windowSeconds = Math.max(60, ((Number) window).longValue());
    }

    private void sendSafely() {
        sending.lock();
        try {
            attempt(false);
        } finally {
            sending.unlock();
        }
    }

    /** The last batch when stopping: whatever is pending, without waiting long. Called from stop(). */
    private void finalSend() {
        try {
            if (!sending.tryLock(1, TimeUnit.SECONDS)) return; // a batch is stuck going out: skip the last one
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            Live l = live;
            if (l != null && l.open) {
                awaitReply(2000);
                if (inFlight == null && hasPending()) {
                    long now = System.currentTimeMillis();
                    // The service refuses live batches closer than 4 s apart: use HTTP if allowed, else wait it out.
                    long wait = lastSent + 4500 - now;
                    boolean http = wait > 0 && now - lastHttpSent >= HTTP_MIN_GAP_MS;
                    if (wait > 0 && !http) Thread.sleep(wait);
                    attempt(http);
                    awaitReply(2000);
                }
                l.close();
            } else if (!deliveredOnce || System.currentTimeMillis() - lastHttpSent >= HTTP_MIN_GAP_MS) {
                attempt(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sending.unlock();
        }
    }

    /** Waits (holding {@link #sending}) until the batch in flight is answered or {@code millis} pass. */
    private void awaitReply(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        for (long left; inFlight != null && (left = deadline - System.nanoTime()) > 0; ) replied.awaitNanos(left);
    }

    private boolean hasPending() {
        for (EventAgg e : events.values()) if (e.pending()) return true;
        if (!firsts.isEmpty()) return true;
        for (ErrorAgg e : errors.values()) if (e.count.get() > 0) return true;
        for (LogAgg e : logLines.values()) if (e.count.get() > 0) return true;
        for (PerfAgg p : perf.values()) if (p.calls.get() > 0) return true;
        return false;
    }

    /** Takes a batch and sends it, live or over HTTP. Holding {@link #sending}. */
    private void attempt(boolean httpOnly) {
        long now = System.currentTimeMillis();
        if (inFlight != null) return; // one live batch at a time
        if (minGapMillis > 0 && sentOnce && now - lastSent < minGapMillis) return;
        sentOnce = true;
        lastSent = now;
        Batch b = null;
        try {
            b = take(now);
            b.json = batchJson(b);
            Live conn = live;
            if (!httpOnly && conn != null && conn.open) {
                b.via = conn;
                inFlight = b; // settled by the reply, a timeout or the connection dropping
                try {
                    conn.sendText(b.json);
                    if (logSent) say(Level.INFO, "PluginAnalytics sent: " + b.json);
                    return;
                } catch (IOException e) {
                    inFlight = null;
                    b.via = null;
                    conn.close(true);
                }
            }
            int status = post(endpoint, b.json, true);
            lastHttpSent = now;
            if (status == 200) {
                delivered(b, now);
                if (logSent) say(Level.INFO, "PluginAnalytics sent: " + b.json);
            } else if (status >= 400 && status < 500 && status != 429) {
                failures = 0; // rejected for good: dropping is right
            } else {
                failed(b, now);
            }
        } catch (Throwable t) {
            if (b != null) failed(b, now);
            if (logSent) say(Level.INFO, "PluginAnalytics could not send data: " + t);
        }
    }

    /** The service has the batch: its players count as seen and its properties as sent. */
    private void delivered(Batch b, long now) {
        failures = 0;
        nextAttempt = 0;
        commitWindow(b.window);
        lastBatch = b.at;
        deliveredOnce = true;
        synchronized (sentProperties) {
            sentProperties.putAll(b.props);
            sentDefaults.putAll(b.defaults);
        }
        if (!b.props.isEmpty()) propertiesSentAt = now;
        // Lets the platform catch up with things registered since (Bukkit wraps new listeners and commands).
        if (now - refreshedAt >= 10 * 60_000L) {
            refreshedAt = now;
            try {
                host.refresh();
            } catch (Throwable ignored) {
                // Optional.
            }
        }
    }

    /** Not delivered: everything goes back for the next try, which waits longer after each failure. */
    private void failed(Batch b, long now) {
        mergeBack(b);
        int n = failures = Math.min(failures + 1, 30);
        long delay = Math.min(10 * 60_000L, 30_000L << Math.min(5, n - 1));
        nextAttempt = now + delay + ThreadLocalRandom.current().nextLong(delay / 4 + 1);
    }

    /** Takes what happened since the last batch out of the running totals. */
    private Batch take(long now) {
        Batch b = new Batch(now);
        // Counted while this ran? Retired entries hand it back to the totals first.
        for (Map.Entry<String, ErrorAgg> e : retiredErrors) {
            long n = e.getValue().count.getAndSet(0);
            if (n > 0) mergeError(e.getKey(), e.getValue().copy(n));
        }
        retiredErrors.clear();
        for (LogAgg l : retiredLogs) {
            long n = l.count.getAndSet(0);
            if (n > 0) mergeLog(l.copy(n));
        }
        retiredLogs.clear();
        for (PerfAgg p : retiredPerf) {
            PerfAgg late = p.drain();
            if (late.calls.get() > 0 || late.totalNanos.get() > 0) mergePerf(late);
        }
        retiredPerf.clear();

        for (EventAgg e : events.values()) {
            EventAgg d = e.drain(events);
            if (d != null) b.events.add(d);
        }
        synchronized (firsts) {
            b.firsts.addAll(firsts);
            firsts.clear();
        }
        for (Iterator<Map.Entry<String, ErrorAgg>> it = errors.entrySet().iterator(); it.hasNext() && b.errors.size() < MAX_ERRORS; ) {
            Map.Entry<String, ErrorAgg> e = it.next();
            long n = e.getValue().count.getAndSet(0);
            if (n > 0) {
                b.errors.put(e.getKey(), e.getValue().copy(n));
            } else {
                it.remove();
                retiredErrors.add(e);
            }
        }
        for (Iterator<LogAgg> it = logLines.values().iterator(); it.hasNext() && b.logs.size() < 100; ) {
            LogAgg l = it.next();
            long n = l.count.getAndSet(0);
            if (n > 0) {
                b.logs.add(l.copy(n));
            } else {
                it.remove();
                retiredLogs.add(l);
            }
        }
        for (Iterator<PerfAgg> it = perf.values().iterator(); it.hasNext(); ) {
            PerfAgg p = it.next();
            if (p.calls.get() == 0) {
                it.remove();
                retiredPerf.add(p);
            } else {
                b.perf.add(p.drain());
            }
        }
        b.msptSamples = msptSamples.getAndSet(0);
        b.msptSum = msptSum.getAndSet(0);
        b.playerSamples = playerSamples.getAndSet(0);
        b.playerSum = playerSum.getAndSet(0);
        readProperties(b, now);
        b.window = windowCounts(b.events, now);
        return b;
    }

    /** Puts an undelivered report back into the running totals. */
    private void mergeBack(Batch b) {
        for (EventAgg e : b.events) {
            while (true) {
                EventAgg cur = events.computeIfAbsent(e.id, k -> new EventAgg(k, e.name, e.player, e.props));
                if (cur.addAll(e.count, e.actors)) break;
            }
        }
        synchronized (firsts) {
            for (Object[] f : b.firsts) {
                if (firsts.size() < MAX_FIRSTS) firsts.add(f);
                else firstsSeen.remove(f[0] + "|" + f[1]); // dropped for now: recorded again next time it happens
            }
        }
        for (Map.Entry<String, ErrorAgg> e : b.errors.entrySet()) mergeError(e.getKey(), e.getValue());
        for (LogAgg l : b.logs) mergeLog(l);
        for (PerfAgg p : b.perf) mergePerf(p);
        playerSum.addAndGet(b.playerSum);
        playerSamples.addAndGet(b.playerSamples);
        msptSum.addAndGet(b.msptSum);
        msptSamples.addAndGet(b.msptSamples);
    }

    private void mergeError(String id, ErrorAgg e) {
        ErrorAgg cur = errors.putIfAbsent(id, e);
        if (cur != null) cur.count.addAndGet(e.count.get());
    }

    private void mergeLog(LogAgg l) {
        LogAgg cur = logLines.putIfAbsent(l.level + "|" + l.message, l);
        if (cur != null) cur.count.addAndGet(l.count.get());
    }

    private void mergePerf(PerfAgg p) {
        PerfAgg cur = perf.putIfAbsent(p.name, p);
        if (cur == null) return;
        cur.calls.addAndGet(p.calls.get());
        cur.totalNanos.addAndGet(p.totalNanos.get());
        cur.raiseMax(p.maxNanos.get());
        for (int i = 0; i < 7; i++) cur.buckets.addAndGet(i, p.buckets.get(i));
    }

    /* ---------------------------------------------------------------- config usage */

    /** Words that make a config key private: anything matching is never read. */
    private static final Pattern PRIVATE_KEY = Pattern.compile(
        "pass|secret|token|key|host|user|login|url|uri|link|webhook|address|e-?mail|database|jdbc|auth|credential|licen[cs]e|api|domain|"
            + "salt|seed|cookie|session|bearer|signature|private|cert|smtp|rcon|proxy|ssh|hash|"
            + "discord|channel|guild|motd|prefix|format|message|world|(^|[-_.])(id|ip|db|port|name|pw|pwd|pin|otp|code)([-_.]|$)");
    private static final Pattern CHOICE = Pattern.compile("[A-Za-z0-9_.\\-]{1,24}");
    private static final Pattern IP_LIKE = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}.*");

    /** A config value worth reporting, as text; null for anything that isn't a plain choice. */
    private static String choice(Object v) {
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Number) {
            String n = v.toString();
            return n.length() <= 16 ? n : null;
        }
        if (!(v instanceof String)) return null; // lists, sections, colors, items...
        String t = (String) v;
        if (!CHOICE.matcher(t).matches() || IP_LIKE.matcher(t).matches() || UUIDS.matcher(t).find()) return null;
        if (t.length() > 8 && t.matches(".*\\d{5,}.*")) return null; // long numbers in text: ids, keys
        // Upper and lower case letters with digits: how passwords look, not choices like "sqlite" or "SQLITE".
        if (t.matches(".*[a-z].*") && t.matches(".*[A-Z].*") && t.matches(".*\\d.*")) return null;
        return t;
    }

    /** Reads the plugin's config into config.* properties, plus its packaged default for each. */
    private Map<String, String> readConfig(Map<String, String> defaultsOut) {
        Map<String, String> out = new TreeMap<String, String>();
        if (!trackConfig) return out;
        Map<String, Object> defaults = new HashMap<String, Object>();
        Map<String, Object> config = host.config(defaults);
        if (config == null) return out;
        for (Map.Entry<String, Object> e : new TreeMap<String, Object>(config).entrySet()) {
            if (out.size() >= 100) break;
            String path = e.getKey();
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (PRIVATE_KEY.matcher(lower).find() || excluded(lower)) continue;
            String v = choice(e.getValue());
            if (v == null) continue;
            String name = "config." + lower.replaceAll("[^a-z0-9_.:\\-]", "_");
            if (name.length() > 48) continue;
            out.put(name, v);
            String d = choice(defaults.get(path));
            if (d != null) defaultsOut.put(name, d);
        }
        return out;
    }

    private boolean excluded(String path) {
        for (String e : configExcluded) if (path.equals(e) || path.startsWith(e + ".")) return true;
        return false;
    }

    /**
     * Properties are sent when they change and once a day otherwise. Suppliers, dependencies and the config are
     * read at most every 5 minutes (batches come every few seconds while a developer watches).
     */
    private void readProperties(Batch b, long now) {
        if (readAt == 0 || now - readAt >= 5 * 60_000L) {
            readAt = now;
            Map<String, String> values = new TreeMap<String, String>();
            for (Map.Entry<String, Supplier<?>> e : properties.entrySet()) {
                try {
                    Object v = e.getValue().get();
                    if (v != null) values.put(e.getKey(), clip(String.valueOf(v), 64));
                } catch (Throwable ignored) {
                    // A failing supplier must not stop the batch.
                }
            }
            // Integrations: which of the plugins or mods you depend on (or softdepend on) each server has.
            try {
                for (Map.Entry<String, Boolean> dep : host.dependencies().entrySet()) {
                    String name = "dep." + dep.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.\\-]", "_");
                    if (name.length() <= 48) values.put(name, Boolean.TRUE.equals(dep.getValue()) ? "installed" : "missing");
                }
            } catch (Throwable ignored) {
                // Optional.
            }
            Map<String, String> defaults = new TreeMap<String, String>();
            Map<String, String> config;
            try {
                config = collectConfig ? readConfig(defaults) : Collections.<String, String>emptyMap();
            } catch (Throwable t) {
                config = Collections.emptyMap(); // a config being reloaded right now: next time
                defaults.clear();
            }
            if (!config.isEmpty()) config.keySet().removeAll(properties.keySet()); // your own properties win
            readValues = values;
            readConfigValues = config;
            readDefaults = defaults;
        }
        boolean daily = now - propertiesSentAt > TimeUnit.HOURS.toMillis(23);
        synchronized (sentProperties) {
            for (Map.Entry<String, String> e : readValues.entrySet()) {
                if (changed(daily, sentProperties, e)) b.props.put(e.getKey(), e.getValue());
            }
            if (!collectConfig) return;
            for (Map.Entry<String, String> e : readConfigValues.entrySet()) {
                if (changed(daily, sentProperties, e)) b.props.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, String> e : readDefaults.entrySet()) {
                if (changed(daily, sentDefaults, e)) b.defaults.put(e.getKey(), e.getValue());
            }
        }
    }

    private static boolean changed(boolean daily, Map<String, String> sent, Map.Entry<String, String> e) {
        return daily || !e.getValue().equals(sent.get(e.getKey()));
    }

    private static String eventId(boolean player, String name, Map<String, String> props) {
        return (player ? "p|" : "s|") + name + (props == null ? "" : "|" + props);
    }

    /**
     * For each event: its players not yet counted in this window, and whether the event is new in it. Once an
     * event has {@code MAX_ACTORS_PER_EVENT} players in the window, further ones aren't counted as new (they
     * couldn't be remembered, so they would count again in every batch).
     */
    private Map<String, Object[]> windowCounts(List<EventAgg> evs, long nowMs) {
        Map<String, Object[]> out = new HashMap<String, Object[]>();
        synchronized (windowActors) {
            long start = nowMs / 1000 / windowSeconds * windowSeconds;
            if (start != windowStart) {
                windowStart = start;
                windowActors.clear();
                windowEvents.clear();
            }
            // The window is per event name: a player in two property variants counts once.
            Map<String, Set<String>> byName = new HashMap<String, Set<String>>();
            for (EventAgg e : evs) {
                String name = (e.player ? "p|" : "s|") + e.name;
                Set<String> seen = windowActors.get(name);
                int known = seen == null ? 0 : seen.size();
                Set<String> taken = byName.get(name);
                boolean firstVariant = taken == null;
                if (taken == null) byName.put(name, taken = new java.util.HashSet<String>());
                Set<String> fresh = new java.util.HashSet<String>();
                for (String a : e.actors) {
                    if (known + taken.size() >= MAX_ACTORS_PER_EVENT) break;
                    if ((seen == null || !seen.contains(a)) && taken.add(a)) fresh.add(a);
                }
                out.put(e.id, new Object[] {fresh, firstVariant && !windowEvents.contains(name), name});
            }
        }
        return out;
    }

    /** After a batch went out: its players and events count as seen in this window. */
    @SuppressWarnings("unchecked")
    private void commitWindow(Map<String, Object[]> window) {
        synchronized (windowActors) {
            for (Object[] w : window.values()) {
                String name = (String) w[2];
                Set<String> seen = windowActors.get(name);
                if (seen == null) windowActors.put(name, seen = new java.util.HashSet<String>());
                seen.addAll((Set<String>) w[0]); // bounded by windowCounts
                windowEvents.add(name);
            }
        }
    }

    private String batchJson(Batch b) {
        long players = b.playerSamples == 0 ? (client ? 1 : host.onlinePlayers()) : Math.round((double) b.playerSum / b.playerSamples);
        double mspt;
        if (b.msptSamples > 0) {
            mspt = b.msptSum / 1000.0 / b.msptSamples;
        } else {
            Double now = host.averageTickTime(); // batches come more often than the minute samples
            mspt = now == null ? -1 : now;
        }
        long secs = Math.max(1, Math.min(7200, (b.at - lastBatch) / 1000));
        Json j = new Json().open();
        j.field("v", 1).field("key", key).field("server", serverId).field("players", players).field("span", secs);
        j.raw("env", env());
        if (!b.props.isEmpty()) {
            j.name("props").open();
            for (Map.Entry<String, String> e : b.props.entrySet()) j.field(e.getKey(), e.getValue());
            j.close();
        }
        if (!b.defaults.isEmpty()) {
            j.name("defaults").open();
            for (Map.Entry<String, String> e : b.defaults.entrySet()) j.field(e.getKey(), e.getValue());
            j.close();
        }
        j.name("events").array();
        for (EventAgg e : b.events) {
            Object[] w = b.window.get(e.id);
            // Players (u) and this server (s) only when new in the current window, so sums stay "unique per window".
            j.open().field("n", e.name).field("k", e.player ? "p" : "s").field("c", e.count)
                .field("u", e.player ? ((Set<?>) w[0]).size() : (Boolean) w[1] ? 1 : 0).field("s", (Boolean) w[1] ? 1 : 0);
            if (e.props != null) {
                j.name("p").open();
                for (Map.Entry<String, String> p : e.props.entrySet()) j.field(p.getKey(), p.getValue());
                j.close();
            }
            j.close();
        }
        j.end();
        j.name("firsts").array();
        for (Object[] f : b.firsts) {
            j.open().field("n", (String) f[0]);
            if (f[1] != null) j.field("a", (String) f[1]);
            j.field("t", (Long) f[2]).close();
        }
        j.end();
        j.name("errors").array();
        for (Map.Entry<String, ErrorAgg> e : b.errors.entrySet()) {
            ErrorAgg a = e.getValue();
            j.open().field("f", e.getKey()).field("type", a.type).field("msg", a.message).field("stack", a.stack).field("c", a.count.get()).close();
        }
        j.end();
        j.name("perf").array();
        if (collectPerformance) {
            for (PerfAgg p : b.perf) {
                j.open().field("n", p.name).field("k", p.kind).field("c", p.calls.get()).field("t", p.totalNanos.get() / 1000).field("mx", p.maxNanos.get() / 1000);
                j.name("b").array();
                for (int i = 0; i < 7; i++) j.item(p.buckets.get(i));
                j.end().close();
            }
        }
        j.end();
        j.name("tick").open().field("secs", secs);
        if (mspt >= 0) j.field("mspt", mspt);
        j.close();
        j.name("logs").array();
        if (collectLogs) for (LogAgg l : b.logs) j.open().field("lv", l.level).field("m", l.message).field("c", l.count.get()).close();
        j.end();
        return j.close().toString();
    }

    private String envJson;

    /** What this server runs: the same in every batch, so built once. */
    private String env() {
        if (envJson == null) {
            Json j = new Json().open()
                .field("platform", host.platform())
                .field("mc", host.minecraftVersion())
                .field("software", host.software())
                .field("java", System.getProperty("java.specification.version"))
                .field("os", System.getProperty("os.name"))
                .field("cores", Runtime.getRuntime().availableProcessors())
                .field("plugin", host.version())
                .field("sdk", SDK_VERSION);
            Boolean online = host.onlineMode();
            if (online != null) j.field("online_mode", online.booleanValue());
            int max = host.maxPlayers();
            if (max >= 0) j.field("players_max", max);
            envJson = j.close().toString();
        }
        return envJson;
    }

    private int post(String url, String json, boolean report) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        byte[] body = buf.toByteArray();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(finalBatch ? 2000 : 5000);
        c.setReadTimeout(finalBatch ? 3000 : 10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Content-Encoding", "gzip");
        c.setRequestProperty("User-Agent", "PluginAnalytics/" + SDK_VERSION);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body);
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        if (in != null) {
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            for (int n; (n = in.read(chunk)) > 0 && reply.size() < 16384; ) reply.write(chunk, 0, n);
            in.close();
            if (report && status == 200) readReply(new String(reply.toByteArray(), StandardCharsets.UTF_8));
        }
        if (status != 200 && logSent) say(Level.INFO, "PluginAnalytics got HTTP " + status);
        return status;
    }

    /** The reply to a batch may carry an update notice and a feedback prompt set up in the dashboard. */
    @SuppressWarnings("unchecked")
    private void readReply(String body) {
        Object parsed;
        try {
            parsed = new MiniJson(body).value();
        } catch (RuntimeException e) {
            return;
        }
        if (parsed instanceof Map) readReply((Map<String, Object>) parsed);
    }

    /**
     * Notices are only replaced when the reply has their key (null withdraws one): live replies carry them
     * every half hour, and replies to limited servers never.
     */
    @SuppressWarnings("unchecked")
    private void readReply(Map<String, Object> reply) {
        if (!Boolean.FALSE.equals(reply.get("ok"))) {
            Object interval = reply.get("interval");
            minGapMillis = interval instanceof Number ? Math.max(0, Math.min(24 * 60, ((Number) interval).longValue())) * 60_000L : 0;
        }
        applyCollect(reply);
        if (reply.containsKey("update")) {
            Object u = reply.get("update");
            if (u instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) u;
                update = new Notice("update:" + m.get("latest"), String.valueOf(m.get("message")), Boolean.TRUE.equals(m.get("admins")), Boolean.TRUE.equals(m.get("critical")));
            } else {
                update = null;
            }
        }
        if (reply.containsKey("feedback")) {
            Object f = reply.get("feedback");
            if (f instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) f;
                feedbackPrompt = new Notice("feedback:" + m.get("id"), m.get("message") + " &f" + m.get("url"), true, false);
            } else {
                feedbackPrompt = null;
            }
        }
        if (!ownerAllowsNotices) return;
        Notice n = update;
        if (n != null && updateNotices && tell("console|" + n.id)) say(n.warning ? Level.WARNING : Level.INFO, plain(n.message));
        Notice fb = feedbackPrompt;
        if (fb != null && feedbackPrompts && tell("console|" + fb.id)) say(Level.INFO, plain(fb.message));
    }

    /** A notice without its {@code &} (or section sign) color codes, for the console. */
    public static String plain(String message) {
        return message == null ? "" : message.replaceAll("(?i)[&§][0-9a-fk-orx]", "");
    }

    /** True the first time a notice goes to someone; the memory is bounded (at worst someone is told twice). */
    private boolean tell(String who) {
        if (told.size() > 10_000) told.clear();
        return told.add(who);
    }
    /**
     * A minimal WebSocket client (RFC 6455): text messages, pings and closing, over TLS for wss://. Enough
     * for the live connection without a dependency; anything unexpected closes it and HTTP takes over.
     */
    private static final class Live {
        private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        private final Engine owner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ReentrantLock writing = new ReentrantLock();
        private volatile java.net.Socket socket;
        private InputStream in;
        private OutputStream out;
        volatile boolean open;
        volatile long lastWrite = System.currentTimeMillis();
        volatile long openedAt;
        /** The close code the service sent, if any. */
        volatile int closeCode;

        Live(Engine owner) {
            this.owner = owner;
        }

        void connect(String url) throws IOException {
            java.net.URI u = java.net.URI.create(url);
            boolean tls = "wss".equals(u.getScheme());
            String host = u.getHost();
            int port = u.getPort() != -1 ? u.getPort() : tls ? 443 : 80;
            java.net.Socket raw = new java.net.Socket();
            java.net.Socket s = raw;
            try {
                raw.connect(new java.net.InetSocketAddress(host, port), 5000);
                raw.setSoTimeout(10_000); // also bounds the TLS handshake and the upgrade
                if (tls) {
                    javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                        .createSocket(raw, host, port, true);
                    s = ssl;
                    javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS"); // check the certificate is for this host
                    ssl.setSSLParameters(params);
                    ssl.startHandshake();
                }
                in = new java.io.BufferedInputStream(s.getInputStream());
                out = s.getOutputStream();
                byte[] nonce = new byte[16];
                RANDOM.nextBytes(nonce);
                String key = java.util.Base64.getEncoder().encodeToString(nonce);
                String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
                String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + (u.getPort() != -1 ? ":" + port : "") + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: PluginAnalytics/" + SDK_VERSION + "\r\n\r\n";
                out.write(request.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                String status = line();
                String accept = null;
                for (String h; !(h = line()).isEmpty(); ) {
                    int colon = h.indexOf(':');
                    if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("sec-websocket-accept")) accept = h.substring(colon + 1).trim();
                }
                if (!status.contains(" 101")) throw new IOException("live connection refused: " + status);
                byte[] expected;
                try {
                    expected = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException(e);
                }
                if (!java.util.Base64.getEncoder().encodeToString(expected).equals(accept)) throw new IOException("bad websocket handshake");
                // Every report gets a reply and an idle connection is pinged every 45 s, which the service
                // answers: silence this long means it's gone.
                s.setSoTimeout(150_000);
            } catch (IOException | RuntimeException e) {
                closeQuietly(s);
                closeQuietly(raw);
                throw e;
            }
            socket = s;
            openedAt = System.currentTimeMillis();
            open = true;
        }

        void startReader() {
            Thread reader = new Thread(this::read, "PluginAnalytics-live-" + owner.host.name());
            reader.setDaemon(true);
            reader.start();
        }

        private String line() throws IOException {
            StringBuilder b = new StringBuilder();
            for (int c; (c = in.read()) != -1; ) {
                if (c == '\n') break;
                if (c != '\r') b.append((char) c);
                if (b.length() > 8192) throw new IOException("header too long");
            }
            return b.toString();
        }

        void sendText(String text) throws IOException {
            send(1, text.getBytes(StandardCharsets.UTF_8));
        }

        void ping() {
            try {
                send(9, new byte[0]);
            } catch (IOException e) {
                close(true);
            }
        }

        private void send(int opcode, byte[] payload) throws IOException {
            writing.lock();
            try {
                if (!open) throw new IOException("closed");
                write(opcode, payload);
            } finally {
                writing.unlock();
            }
        }

        /** One masked frame, as clients must send them. Holding {@link #writing}. */
        private void write(int opcode, byte[] payload) throws IOException {
            int n = payload.length;
            int header = n < 126 ? 2 : n < 65536 ? 4 : 10;
            byte[] f = new byte[header + 4 + n];
            f[0] = (byte) (0x80 | opcode);
            if (n < 126) {
                f[1] = (byte) (0x80 | n);
            } else if (n < 65536) {
                f[1] = (byte) (0x80 | 126);
                f[2] = (byte) (n >>> 8);
                f[3] = (byte) n;
            } else {
                f[1] = (byte) (0x80 | 127);
                for (int i = 0; i < 4; i++) f[6 + i] = (byte) (n >>> (8 * (3 - i))); // bytes 2-5 stay 0
            }
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            System.arraycopy(mask, 0, f, header, 4);
            for (int i = 0; i < n; i++) f[header + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
            out.write(f);
            out.flush();
            lastWrite = System.currentTimeMillis();
        }

        private void read() {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            int messageType = -1; // 1 text or 2 binary while a fragmented message is coming in
            try {
                while (open) {
                    int b0 = in.read();
                    if (b0 < 0) break;
                    int b1 = readByte();
                    boolean fin = (b0 & 0x80) != 0;
                    int opcode = b0 & 0x0F;
                    long len = b1 & 0x7F;
                    if (len == 126) {
                        len = (readByte() << 8) | readByte();
                    } else if (len == 127) {
                        len = 0;
                        for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                    }
                    if (len > 1_000_000 || len < 0) break;
                    if (opcode >= 8 && (len > 125 || !fin)) break; // control frames are short and never fragmented
                    byte[] maskKey = null;
                    if ((b1 & 0x80) != 0) {
                        maskKey = new byte[4];
                        readFully(maskKey);
                    }
                    byte[] payload = new byte[(int) len];
                    readFully(payload);
                    if (maskKey != null) for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i & 3];
                    if (opcode == 9) {
                        send(10, payload);
                        continue;
                    }
                    if (opcode == 10) continue;
                    if (opcode == 8) {
                        if (payload.length >= 2) closeCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        break;
                    }
                    if (opcode == 1 || opcode == 2) {
                        if (messageType != -1) break; // a new message before the last one ended
                        messageType = opcode;
                    } else if (opcode != 0 || messageType == -1) {
                        break; // unknown opcode, or a continuation of nothing
                    }
                    if (messageType == 1) message.write(payload, 0, payload.length);
                    if (message.size() > 1_000_000) break;
                    if (fin) {
                        if (messageType == 1) owner.onLive(this, new String(message.toByteArray(), StandardCharsets.UTF_8));
                        message.reset();
                        messageType = -1;
                    }
                }
            } catch (Throwable ignored) {
                // a dropped connection: the next tick reconnects or falls back to HTTP
            }
            close(true);
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new IOException("closed");
            return b;
        }

        private void readFully(byte[] b) throws IOException {
            for (int off = 0; off < b.length; ) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new IOException("closed");
                off += n;
            }
        }

        /** Closes on purpose (no reconnect backoff). */
        void close() {
            close(false);
        }

        /** Closes once, from any thread; {@code dropped} when the connection failed rather than being ended here. */
        void close(boolean dropped) {
            if (!closed.compareAndSet(false, true)) return;
            boolean was = open;
            open = false;
            java.net.Socket s = socket;
            if (was) {
                // Says goodbye if the writer is free soon; a write stuck on a dead socket must not hold this up.
                try {
                    if (writing.tryLock(1, TimeUnit.SECONDS)) {
                        try {
                            write(8, new byte[] {0x03, (byte) 0xE8}); // 1000: normal closure
                        } finally {
                            writing.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable ignored) {
                    // already gone
                }
            }
            closeQuietly(s);
            if (was) owner.liveClosed(this, dropped);
        }

        private static void closeQuietly(java.net.Socket s) {
            try {
                if (s != null) s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /** Just enough JSON to read the server's reply. */
    static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new HashMap<String, Object>();
                i++;
                skip();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    skip();
                    String k = string();
                    skip();
                    i++; // :
                    m.put(k, value());
                    skip();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<Object>();
                i++;
                skip();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    skip();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.valueOf(s.substring(start, i));
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    /* ---------------------------------------------------------------- helpers */

    /** Event, property and span names: lowercase letters, digits and {@code _ . : -}, up to 48 characters. */
    public static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Key/value pairs as a map: {@code "kit", "archer"}. */
    public static Map<String, String> pairs(String[] kv) {
        if (kv == null || kv.length < 2) return null;
        Map<String, String> m = new TreeMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Error messages often include a player's name, IP or UUID: none of them may leave the server. */
    private String redact(String s) {
        s = URL_CREDENTIALS.matcher(s).replaceAll("$1<credentials>@");
        s = SECRET_PAIR.matcher(s).replaceAll("$1$2<redacted>");
        s = EMAIL.matcher(s).replaceAll("<email>");
        s = UUIDS.matcher(s).replaceAll("<uuid>"); // before IPv6: a UUID's groups aren't an address
        s = IPV6.matcher(IPV4.matcher(s).replaceAll("<ip>")).replaceAll("<ip>");
        s = HOME_PATH.matcher(s).replaceAll("$1<user>");
        try {
            for (String name : host.onlinePlayerNames()) {
                if (name != null && name.length() >= 3) s = s.replace(name, "<player>");
            }
        } catch (Throwable ignored) {
            // The player list may be unavailable during startup or shutdown.
        }
        return s;
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A tiny JSON writer: the SDK has no dependencies. */
    private static final class Json {
        private final StringBuilder sb = new StringBuilder();
        private boolean comma;

        Json open() { sep(); sb.append('{'); comma = false; return this; }
        Json close() { sb.append('}'); comma = true; return this; }
        Json array() { sb.append('['); comma = false; return this; }
        Json end() { sb.append(']'); comma = true; return this; }
        Json name(String n) { sep(); str(n); sb.append(':'); comma = false; return this; }
        Json field(String n, String v) { name(n); if (v == null) sb.append("null"); else str(v); comma = true; return this; }
        Json field(String n, long v) { name(n); sb.append(v); comma = true; return this; }
        Json field(String n, boolean v) { name(n); sb.append(v); comma = true; return this; }
        Json field(String n, double v) { name(n); sb.append(v); comma = true; return this; }
        Json item(long v) { sep(); sb.append(v); comma = true; return this; }
        Json raw(String n, String json) { name(n); sb.append(json); comma = true; return this; }

        private void sep() { if (comma) sb.append(','); }

        private void str(String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }

        @Override public String toString() { return sb.toString(); }
    }
}
