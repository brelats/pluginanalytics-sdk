package com.gamepathics.pluginanalytics.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The config file every plugin or mod using PluginAnalytics on one server shares: one switch to turn it off,
 * the server's random id and the salt that makes player hashes differ on every server. Plain "key: value"
 * lines, readable as YAML, so it needs no YAML library (and stays compatible with files Bukkit wrote).
 */
final class SharedConfig {

    boolean enabled = true;
    String serverId;
    String salt;
    boolean logSent;
    boolean notifyAdmins = true;

    static SharedConfig load(File file, String shownPath, boolean client) {
        SharedConfig c = new SharedConfig();
        Map<String, String> values = new LinkedHashMap<String, String>();
        try {
            if (file.isFile()) {
                for (String line : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\r?\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#") || line.startsWith(" ") || line.startsWith("\t")) continue;
                    int colon = t.indexOf(':');
                    if (colon <= 0) continue;
                    String v = t.substring(colon + 1).trim();
                    int hash = v.indexOf(" #");
                    if (hash >= 0) v = v.substring(0, hash).trim();
                    if (v.length() >= 2 && (v.charAt(0) == '\'' || v.charAt(0) == '"') && v.charAt(v.length() - 1) == v.charAt(0)) v = v.substring(1, v.length() - 1);
                    values.put(t.substring(0, colon).trim(), v);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable: defaults, and a new id below.
        }
        boolean write = !file.isFile();
        c.enabled = !"false".equalsIgnoreCase(values.get("enabled"));
        c.logSent = "true".equalsIgnoreCase(values.get("log-sent-data"));
        c.notifyAdmins = !"false".equalsIgnoreCase(values.get("notify-admins"));
        c.serverId = valid(values.get("server-id")) ? values.get("server-id") : null;
        c.salt = values.get("salt");
        if (c.serverId == null) {
            c.serverId = UUID.randomUUID().toString();
            write = true;
        }
        // A missing salt (an edited file) gets its own random one: never anything the service already knows.
        if (c.salt == null || c.salt.isEmpty()) {
            c.salt = UUID.randomUUID().toString();
            write = true;
        }
        if (!values.containsKey("notify-admins") && !client) write = true; // added after the first release: write it so owners find it
        if (write) save(file, shownPath, c, client);
        return c;
    }

    private static boolean valid(String id) {
        try {
            return id != null && UUID.fromString(id) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void save(File file, String shownPath, SharedConfig c, boolean client) {
        StringBuilder b = new StringBuilder();
        if (client) {
            b.append("# PluginAnalytics collects anonymous usage statistics for the developers of your mods:\n")
                .append("# game and mod versions and how mod features are used. This install is a random id;\n")
                .append("# no names, accounts, IPs, chat or world data is ever sent.\n")
                .append("# Set enabled to false to turn it off for every mod. Learn more: https://pluginanalytics.dev/privacy\n");
        } else {
            b.append("# PluginAnalytics collects anonymous usage statistics for the developers of your plugins:\n")
                .append("# server software and versions, player counts and how plugin features are used.\n")
                .append("# Players are anonymous hashes; no names, IPs, chat or world data is ever sent.\n")
                .append("# Set enabled to false to turn it off for every plugin. Learn more: https://pluginanalytics.dev/privacy\n");
        }
        b.append("# File: ").append(shownPath).append('\n');
        b.append("enabled: ").append(c.enabled).append('\n');
        b.append("server-id: ").append(c.serverId).append('\n');
        b.append("salt: ").append(c.salt).append('\n');
        b.append("log-sent-data: ").append(c.logSent).append('\n');
        if (!client) b.append("notify-admins: ").append(c.notifyAdmins).append('\n');
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(file.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // An unwritable folder only means a new id on the next start.
        }
    }
}
