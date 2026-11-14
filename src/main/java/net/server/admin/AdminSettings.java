package net.server.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings file (config.yaml) as the admin page shows and changes it: the worlds, and every other setting under
 * the headings the file has. It is changed line by line: a new value replaces the old one on its own line and
 * everything else (the order, the comments, the spacing) stays as it was. The server reads the file when it starts,
 * so a change takes a restart.
 */
final class AdminSettings {
    /** A value the page may not have: one it must say no to, in words. */
    static final class Bad extends IllegalArgumentException {
        Bad(String message) {
            super(message);
        }
    }

    private static final Pattern SECRET = Pattern.compile("(^|_)(PASS|PASSWORD|SECRET|TOKEN)(_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTRY = Pattern.compile("^(\\s*(?:- )?)([A-Za-z_0-9]+):(\\s*)(.*)$");
    private static final Pattern WORLD_HEAD = Pattern.compile("^\\s*#\\s*Properties for (.+?)\\s+\\d+\\s*$");
    /** A world's settings, by the kind of value each holds. */
    private static final Map<String, String> WORLD = new LinkedHashMap<>();

    static {
        for (String k : List.of("flag", "channels", "exp_rate", "meso_rate", "drop_rate", "boss_drop_rate", "quest_rate", "fishing_rate", "travel_rate")) {
            WORLD.put(k, "int");
        }
        for (String k : List.of("server_message", "event_message", "why_am_i_recommended")) {
            WORLD.put(k, "text");
        }
    }

    private AdminSettings() {
    }

    /** One "key: value   #comment" line. */
    private record Entry(String lead, String key, String gap, String raw, String pad, String comment) {
    }

    private static Entry entry(String line) {
        Matcher m = ENTRY.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String rest = m.group(4), comment = "";
        char q = 0;
        // a comment starts at a # that follows a space and is outside quotes
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (q != 0) {
                if (c == q) {
                    q = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                if (i == 0) {
                    q = c;
                }
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(rest.charAt(i - 1)))) {
                comment = rest.substring(i);
                rest = rest.substring(0, i);
                break;
            }
        }
        String raw = rest.stripTrailing();
        return new Entry(m.group(1), m.group(2), m.group(3), raw, rest.substring(raw.length()), comment);
    }

    private static String kindOf(String raw) {
        if (raw.matches("true|false")) {
            return "bool";
        }
        if (raw.matches("-?\\d+")) {
            return "int";
        }
        if (raw.matches("-?\\d*\\.\\d+")) {
            return "number";
        }
        return "text";
    }

    private static char quoteOf(String raw) {
        if (raw.length() >= 2 && (raw.charAt(0) == '"' || raw.charAt(0) == '\'') && raw.charAt(raw.length() - 1) == raw.charAt(0)) {
            return raw.charAt(0);
        }
        return 0;
    }

    private static String unquote(String raw) {
        char q = quoteOf(raw);
        if (q == 0) {
            return raw;
        }
        String inner = raw.substring(1, raw.length() - 1);
        return q == '"' ? inner.replaceAll("\\\\([\"\\\\])", "$1") : inner.replace("''", "'");
    }

    private static String note(String comment) {
        return comment.replaceFirst("^#\\s*", "");
    }

    private static List<String> lines(Path file) throws IOException {
        return new ArrayList<>(Arrays.asList(Files.readString(file, StandardCharsets.UTF_8).split("\n", -1)));
    }

    /** The settings file as the page shows it: { worlds: [...], groups: [{ title, notes, options }] }. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path file) throws IOException {
        List<String> lines = lines(file);
        List<Map<String, Object>> worlds = new ArrayList<>(), groups = new ArrayList<>();
        String section = null, name = "";
        Map<String, Object> world = null, group = null, blockOption = null;
        int blockDepth = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches("^worlds:\\s*$")) {
                section = "worlds";
                continue;
            }
            if (line.matches("^server:\\s*$")) {
                section = "server";
                continue;
            }
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) {
                section = null;
                continue;
            }
            if (section == null || line.isBlank()) {
                continue;
            }
            if (section.equals("worlds")) {
                Matcher head = WORLD_HEAD.matcher(line);
                if (head.matches()) {
                    name = head.group(1);
                    continue;
                }
                Entry e = entry(line);
                if (e == null || line.matches("^\\s*#.*")) {
                    continue;
                }
                if (e.lead().contains("-")) {
                    world = new LinkedHashMap<>();
                    world.put("index", worlds.size());
                    world.put("name", name.isEmpty() ? "World " + worlds.size() : name);
                    world.put("fields", new LinkedHashMap<String, Object>());
                    world.put("last", i);
                    worlds.add(world);
                }
                if (world == null) {
                    continue;
                }
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("value", unquote(e.raw()));
                field.put("kind", kindOf(e.raw()));
                field.put("line", i);
                field.put("note", note(e.comment()));
                ((Map<String, Object>) world.get("fields")).put(e.key(), field);
                world.put("last", i);
                continue;
            }
            if (line.matches("^\\s*#.*")) {
                String text = line.replaceFirst("^\\s*#\\s*", "");
                if (group == null || !((List<?>) group.get("options")).isEmpty()) {
                    group = new LinkedHashMap<>();
                    group.put("title", text);
                    group.put("notes", new ArrayList<String>());
                    group.put("options", new ArrayList<Map<String, Object>>());
                    groups.add(group);
                } else {
                    ((List<String>) group.get("notes")).add(text);
                }
                blockOption = null;
                continue;
            }
            Entry e = entry(line);
            int depth = line.length() - line.stripLeading().length();
            if (blockOption != null && depth > blockDepth) {
                String was = (String) blockOption.get("value");
                blockOption.put("value", was + (was.isEmpty() ? "" : "\n") + line.trim());
                continue;
            }
            blockOption = null;
            if (e == null) {
                continue;
            }
            if (group == null) {
                group = new LinkedHashMap<>();
                group.put("title", "Settings");
                group.put("notes", new ArrayList<String>());
                group.put("options", new ArrayList<Map<String, Object>>());
                groups.add(group);
            }
            boolean secret = SECRET.matcher(e.key()).find();
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("key", e.key());
            option.put("kind", e.raw().isEmpty() ? "block" : kindOf(e.raw()));
            option.put("value", secret ? "" : unquote(e.raw()));
            option.put("secret", secret);
            if (secret) {
                option.put("set", !unquote(e.raw()).isEmpty());
            }
            option.put("note", note(e.comment()));
            option.put("line", i);
            ((List<Map<String, Object>>) group.get("options")).add(option);
            if (e.raw().isEmpty()) {
                blockOption = option;
                blockDepth = depth;
            }
        }
        groups.removeIf(g -> ((List<?>) g.get("options")).isEmpty());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worlds", worlds);
        out.put("groups", groups);
        return out;
    }

    /** A new value as the file's own text: the kind it had is kept, and text is quoted the way it was or when it must be. */
    private static String written(String kind, String was, Object value, String key) {
        switch (kind) {
            case "bool" -> {
                if (!(value instanceof Boolean)) {
                    throw new Bad(key + " is on or off.");
                }
                return value.toString();
            }
            case "int" -> {
                if (value instanceof Long || value instanceof Integer) {
                    return value.toString();
                }
                if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
                    return String.valueOf(d.longValue());
                }
                throw new Bad(key + " is a whole number.");
            }
            case "number" -> {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                    throw new Bad(key + " is a number.");
                }
                double d = n.doubleValue();
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.format("%d.0", (long) d) : String.valueOf(d);
            }
            default -> {
                if (!(value instanceof String text) || text.contains("\n") || text.contains("\r")) {
                    throw new Bad(key + " is one line of text.");
                }
                char q = quoteOf(was);
                if (q == '\'') {
                    return "'" + text.replace("'", "''") + "'";
                }
                boolean plain = q == 0 && !text.isEmpty() && text.equals(text.trim())
                        && !text.matches("^[-?:,\\[\\]{}#&*!|>'\"%@`].*") && !Pattern.compile(": |\\s#").matcher(text).find()
                        && !text.endsWith(":") && kindOf(text).equals("text") && !text.matches("(?i)null|~|yes|no|on|off");
                return plain ? text : "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        }
    }

    /**
     * Writes changes into the settings file: { server: { KEY: value }, worlds: [{ index, fields: { key: value } }] }.
     * Returns how many lines changed. The file is written in place (a container may hold on to the file itself),
     * after a copy of how it was is put beside it (config.yaml.before-admin).
     */
    @SuppressWarnings("unchecked")
    static int write(Path file, Map<String, Object> changes) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<String> lines = lines(file);
        Map<String, Object> now = read(file);
        int[] changed = {0};
        java.util.function.BiConsumer<Integer, Object[]> put = (i, a) -> {
            Entry e = entry(lines.get(i));
            String next = written((String) a[0], e.raw(), a[1], (String) a[2]);
            if (next.equals(e.raw())) {
                return;
            }
            // the comment stays in its column when the new value leaves room for it
            String head = e.lead() + e.key() + ":" + (e.gap().isEmpty() ? " " : e.gap());
            int room = (e.raw() + e.pad()).length();
            lines.set(i, e.comment().isEmpty() ? head + next
                    : head + (next.length() < room ? String.format("%-" + room + "s", next) : next + " ") + e.comment());
            changed[0]++;
        };
        Map<String, Map<String, Object>> options = new HashMap<>();
        for (Map<String, Object> g : (List<Map<String, Object>>) now.get("groups")) {
            for (Map<String, Object> o : (List<Map<String, Object>>) g.get("options")) {
                options.put((String) o.get("key"), o);
            }
        }
        Object server = changes.get("server");
        if (server instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> c : map.entrySet()) {
                String key = String.valueOf(c.getKey());
                Map<String, Object> o = options.get(key);
                if (o == null) {
                    throw new Bad("There is no setting called " + key + ".");
                }
                if (o.get("kind").equals("block")) {
                    throw new Bad(key + " is a list: change it in the file itself.");
                }
                put.accept((Integer) o.get("line"), new Object[] {o.get("kind"), c.getValue(), key});
            }
        }
        List<Map<String, Object>> worlds = (List<Map<String, Object>>) now.get("worlds");
        List<Map<String, Object>> asked = new ArrayList<>();
        if (changes.get("worlds") instanceof List<?> list) {
            for (Object w : list) {
                if (w instanceof Map<?, ?> m) {
                    asked.add((Map<String, Object>) m);
                }
            }
        }
        // from the last world up, so lines added to one leave the line numbers of those above as they were
        asked.sort(Comparator.comparingLong((Map<String, Object> w) -> ((Number) w.getOrDefault("index", -1L)).longValue()).reversed());
        for (Map<String, Object> w : asked) {
            int index = ((Number) w.getOrDefault("index", -1L)).intValue();
            if (index < 0 || index >= worlds.size()) {
                throw new Bad("There is no world " + index + ".");
            }
            Map<String, Object> world = worlds.get(index);
            Map<String, Object> fields = (Map<String, Object>) world.get("fields");
            List<String> add = new ArrayList<>();
            if (w.get("fields") instanceof Map<?, ?> f) {
                for (Map.Entry<?, ?> c : f.entrySet()) {
                    String key = String.valueOf(c.getKey());
                    String kind = WORLD.get(key);
                    if (kind == null) {
                        throw new Bad("A world has no setting called " + key + ".");
                    }
                    String label = world.get("name") + "'s " + key;
                    Map<String, Object> had = (Map<String, Object>) fields.get(key);
                    if (had != null) {
                        put.accept((Integer) had.get("line"), new Object[] {kind, c.getValue(), label});
                    } else {
                        add.add("    " + key + ": " + written(kind, "", c.getValue(), label));
                    }
                }
            }
            if (!add.isEmpty()) {
                lines.addAll((Integer) world.get("last") + 1, add);
                changed[0] += add.size();
            }
        }
        if (changed[0] > 0) {
            Files.writeString(file.resolveSibling(file.getFileName() + ".before-admin"), text, StandardCharsets.UTF_8);
            Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        }
        return changed[0];
    }
}
