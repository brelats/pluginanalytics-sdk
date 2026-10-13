package com.gamepathics.paexample.bungee;

import com.gamepathics.pluginanalytics.PluginAnalytics;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

/** A test plugin that shows the SDK on a BungeeCord proxy. */
public final class ExampleBungee extends Plugin {

    private PluginAnalytics analytics;

    @Override
    public void onEnable() {
        analytics = PluginAnalytics.start(this, System.getProperty("pluginanalytics.key", "pa_example"));
        analytics.property("servers", () -> getProxy().getServers().size());
        analytics.track("proxy_started");
        getLogger().info("Loaded 3 lobby servers");
        getProxy().getPluginManager().registerCommand(this, new Command("pademo") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                String what = args.length > 0 ? args[0] : "party";
                if (what.equals("error")) throw new IllegalStateException("Demo failure on the proxy");
                if (what.equals("report")) {
                    analytics.report(sender, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                    return;
                }
                if (sender instanceof ProxiedPlayer) analytics.track("party_created", (ProxiedPlayer) sender, "size", "4");
                else analytics.track("party_created");
                getLogger().warning("Party queue is full, waiting");
            }
        });
    }

    @Override
    public void onDisable() {
        analytics.stop();
    }
}
