package com.gamepathics.paexample;

import com.gamepathics.pluginanalytics.PluginAnalytics;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** A test plugin that shows the whole SDK surface. */
public final class ExamplePlugin extends JavaPlugin {

    private PluginAnalytics analytics;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        analytics = PluginAnalytics.start(this, getConfig().getString("analytics-key", System.getProperty("pluginanalytics.key", "pa_example")));
        analytics.property("storage", () -> "sqlite");
        analytics.property("language", () -> "en");
        analytics.track("plugin_enabled");
        getServer().getPluginManager().registerEvents(new ArenaListener(), this);
        analytics.time("load_arenas", () -> busy(3));
        // Not measured by hand: the SDK's sampler finds it on its own. Folia has no main thread (nor this scheduler).
        if (!isFolia()) getServer().getScheduler().runTaskTimer(this, new ScoreboardTask(), 20, 1);
        getLogger().info("Loaded 14 arenas in 82 ms");
        getLogger().warning("Arena 'skywars-2' has no spawn points set");
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Burns CPU for roughly the given milliseconds: a stand-in for real work. */
    static void busy(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            Math.sqrt(System.nanoTime());
        }
    }

    public static final class ScoreboardTask implements Runnable {
        @Override
        public void run() {
            updateBoards();
        }

        private void updateBoards() {
            busy(2);
        }
    }

    public static final class ArenaListener implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
            busy(1);
        }

        @org.bukkit.event.EventHandler
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
            busy(4);
        }
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? java.util.Arrays.asList("game", "kit", "log", "error", "feedback") : java.util.Collections.emptyList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String what = args.length > 0 ? args[0] : "game";
        switch (what) {
            case "error":
                throw new IllegalStateException("Demo failure for " + sender.getName());
            case "log":
                getLogger().warning("Vault not found, economy rewards disabled");
                getLogger().info("Player " + sender.getName() + " joined arena 3");
                break;
            case "feedback":
                int rating;
                try {
                    rating = args.length > 1 ? Integer.parseInt(args[1]) : 0;
                } catch (NumberFormatException e) {
                    sender.sendMessage("Usage: /" + label + " feedback <0-5> [message]");
                    return true;
                }
                analytics.feedback(sender, rating, args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "");
                return true;
            case "kit":
                if (sender instanceof Player) analytics.track("kit_selected", (Player) sender, "kit", args.length > 1 ? args[1] : "warrior");
                break;
            default:
                if (sender instanceof Player) {
                    analytics.track("game_started", (Player) sender, "mode", "solo");
                    analytics.track("game_finished", (Player) sender);
                } else {
                    analytics.track("arena_created");
                }
        }
        sender.sendMessage("Tracked " + what);
        return true;
    }
}
