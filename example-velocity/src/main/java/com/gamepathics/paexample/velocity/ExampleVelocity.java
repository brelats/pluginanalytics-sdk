package com.gamepathics.paexample.velocity;

import com.gamepathics.pluginanalytics.PluginAnalytics;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/** A test plugin that shows the SDK on a Velocity proxy. */
public final class ExampleVelocity {

    private final ProxyServer server;
    private final Path dataDirectory;
    private final Logger logger;
    private PluginAnalytics analytics;

    @Inject
    public ExampleVelocity(ProxyServer server, @DataDirectory Path dataDirectory, Logger logger) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        analytics = PluginAnalytics.start(this, server, dataDirectory, System.getProperty("pluginanalytics.key", "pa_example"));
        analytics.property("servers", () -> server.getAllServers().size());
        analytics.track("proxy_started");
        logger.info("Loaded 3 lobby servers");
        server.getCommandManager().register("pademo", (SimpleCommand) invocation -> {
            String[] args = invocation.arguments();
            String what = args.length > 0 ? args[0] : "party";
            if (what.equals("error")) throw new IllegalStateException("Demo failure on the proxy");
            if (what.equals("report")) {
                analytics.report(invocation.source(), String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                return;
            }
            if (invocation.source() instanceof Player) analytics.track("party_created", (Player) invocation.source(), "size", "4");
            else analytics.track("party_created");
            logger.warn("Party queue is full, waiting");
        });
    }
}
