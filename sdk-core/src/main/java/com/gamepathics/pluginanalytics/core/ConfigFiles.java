package com.gamepathics.pluginanalytics.core;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a plugin's or mod's own config file into flat paths ("storage.type" → "sqlite") for Config usage, on
 * platforms that have no config API of their own. Only scalar values matter (choices like true, 20, sqlite),
 * so these readers are simple: YAML by indentation, TOML by sections, JSON, and .properties. Lists, multi-line
 * values and anything unusual are skipped rather than guessed.
 */
public final class ConfigFiles {

    private ConfigFiles() {
    }

    /** The first existing file among {@code candidates}, read; empty when none exists or it can't be read. */
    public static Map<String, Object> read(List<File> candidates) {
        for (File f : candidates) {
            if (f != null && f.isFile()) {
                try {
                    return parse(f.getName(), new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                    return new LinkedHashMap<String, Object>();
                }
            }
        }
        return new LinkedHashMap<String, Object>();
    }

    /** A packaged default config inside the plugin's jar (e.g. "config.yml"), read the same way. */
    public static Map<String, Object> readResource(Class<?> owner, String name) {
        try (InputStream in = owner.getClassLoader().getResourceAsStream(name)) {
            if (in == null) return new LinkedHashMap<String, Object>();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) > 0 && buf.size() < 1_000_000; ) buf.write(chunk, 0, n);
            return parse(name, new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return new LinkedHashMap<String, Object>();
        }
    }

    static Map<String, Object> parse(String fileName, String text) {
        String n = fileName.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".toml")) return toml(text);
        if (n.endsWith(".json") || n.endsWith(".json5")) return json(text);
        if (n.endsWith(".properties") || n.endsWith(".cfg")) return properties(text);
        return yaml(text);
    }

    private static Object scalar(String raw) {
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if ((v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) || (v.startsWith("'") && v.endsWith("'") && v.length() >= 2)) return v.substring(1, v.length() - 1);
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return Boolean.valueOf(v.toLowerCase(java.util.Locale.ROOT));
        if (v.matches("-?\\d{1,15}")) return Long.valueOf(v);
        if (v.matches("-?\\d{1,12}\\.\\d{1,6}")) return Double.valueOf(v);
        if (v.startsWith("[") || v.startsWith("{") || v.startsWith("|") || v.startsWith(">")) return null; // lists, maps, blocks
        return v;
    }

    private static String stripComment(String line) {
        boolean quote = false;
        char q = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '"' || c == '\'') && (!quote || c == q)) {
                quote = !quote;
                q = c;
            } else if (c == '#' && !quote && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    static Map<String, Object> yaml(String text) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<int[]> indents = new ArrayList<int[]>(); // stack of indentation levels
        List<String> keys = new ArrayList<String>();
        for (String rawLine : text.split("\r?\n")) {
            String line = stripComment(rawLine);
            if (line.trim().isEmpty() || line.trim().startsWith("-") || line.trim().equals("---")) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String t = line.trim();
            int colon = t.indexOf(": ");
            if (colon < 0 && t.endsWith(":")) colon = t.length() - 1;
            if (colon <= 0) continue;
            String key = t.substring(0, colon).trim();
            if (key.length() >= 2 && (key.startsWith("\"") || key.startsWith("'"))) key = key.substring(1, key.length() - 1);
            while (!indents.isEmpty() && indents.get(indents.size() - 1)[0] >= indent) {
                indents.remove(indents.size() - 1);
                keys.remove(keys.size() - 1);
            }
            String value = colon + 1 < t.length() ? t.substring(colon + 1) : "";
            StringBuilder path = new StringBuilder();
            for (String k : keys) path.append(k).append('.');
            path.append(key);
            if (value.trim().isEmpty()) {
                indents.add(new int[] {indent});
                keys.add(key);
            } else {
                Object v = scalar(value);
                if (v != null && out.size() < 500) out.put(path.toString(), v);
            }
        }
        return out;
    }

    static Map<String, Object> toml(String text) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String section = "";
        for (String rawLine : text.split("\r?\n")) {
            String t = stripComment(rawLine).trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("[[")) {
                section = null; // arrays of tables: skipped
                continue;
            }
            if (t.startsWith("[") && t.endsWith("]")) {
                section = t.substring(1, t.length() - 1).trim().replace("\"", "");
                continue;
            }
            if (section == null) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).trim().replace("\"", "");
            Object v = scalar(t.substring(eq + 1));
            if (v != null && out.size() < 500) out.put(section.isEmpty() ? key : section + "." + key, v);
        }
        return out;
    }

    static Map<String, Object> properties(String text) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String rawLine : text.split("\r?\n")) {
            String t = rawLine.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            if (eq < 0) eq = t.indexOf(':');
            if (eq <= 0) continue;
            Object v = scalar(t.substring(eq + 1));
            if (v != null && out.size() < 500) out.put(t.substring(0, eq).trim(), v);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String text) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            // Comments (JSON5 and many mod configs allow them) are dropped first.
            String clean = text.replaceAll("(?m)^\\s*//.*$", "");
            Object parsed = new Engine.MiniJson(clean).value();
            if (parsed instanceof Map) flatten("", (Map<String, Object>) parsed, out);
        } catch (Throwable ignored) {
            // Not plain JSON: nothing.
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> m, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map) flatten(path, (Map<String, Object>) v, out);
            else if (v instanceof Double && ((Double) v) == Math.rint((Double) v) && Math.abs((Double) v) < 1e15) out.put(path, ((Double) v).longValue());
            else if (v instanceof String || v instanceof Boolean || v instanceof Number) out.put(path, v);
            if (out.size() >= 500) return;
        }
    }
}
