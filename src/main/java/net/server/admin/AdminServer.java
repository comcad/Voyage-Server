package net.server.admin;

import client.Character;
import client.DefaultDates;
import client.Job;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import config.YamlConfig;
import constants.game.GameConstants;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.DataProviderFactory;
import server.maps.MapFactory;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The server's admin page, on its own port (ADMIN_PORT), behind a password (ADMIN_PASSWORD; the environment
 * variables of the same names win over config.yaml). Without a password it stays off. It shows how the server is
 * doing and who is playing (and kicks, bans and unbans them), sends a notice to everyone, restarts or shuts the server down, changes the worlds and
 * every other setting of config.yaml, and shows the console. The page itself is in the jar (resources/admin).
 */
public final class AdminServer {
    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);
    private static final Path CONFIG = Path.of("config.yaml");
    private static final long SESSION_MS = 12L * 60 * 60 * 1000, LOCK_MS = 60_000;
    private static final int TRIES = 5, BODY_MAX = 1 << 20;
    /** The exit code that asks whatever started the server (container/start.sh) to start it again. */
    public static final int RESTART_CODE = 75;
    private static final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private static final Map<String, long[]> fails = new ConcurrentHashMap<>();
    private static final long STARTED = System.currentTimeMillis();
    private static HttpServer http;

    private AdminServer() {
    }

    private static String setting(String key, String fromFile) {
        String env = System.getenv(key);
        return env != null && !env.isBlank() ? env : fromFile == null ? "" : fromFile;
    }

    /** The admin page's password, and the admin account's when it is first made (AdminAccount). */
    static String password() {
        return setting("ADMIN_PASSWORD", YamlConfig.config.server.ADMIN_PASSWORD);
    }

    /** Whether the admin page is to run: a port and a password. Its console is kept from the start when it is. */
    public static boolean wanted() {
        return port() > 0 && !password().isEmpty();
    }

    private static int port() {
        try {
            return Integer.parseInt(setting("ADMIN_PORT", String.valueOf(YamlConfig.config.server.ADMIN_PORT)).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static synchronized void start() {
        if (http != null) {
            return;
        }
        if (port() <= 0) {
            return;
        }
        if (password().isEmpty()) {
            log.warn("The admin page is off: it has a port ({}) but no password. Set ADMIN_PASSWORD in config.yaml or the environment.", port());
            return;
        }
        String host = setting("ADMIN_HOST", YamlConfig.config.server.ADMIN_HOST);
        try {
            http = HttpServer.create(new InetSocketAddress(host.isEmpty() ? "0.0.0.0" : host, port()), 0);
        } catch (IOException e) {
            log.error("The admin page could not start on port {}", port(), e);
            return;
        }
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/", AdminServer::handle);
        http.start();
        log.info("Admin page on http://{}:{}/", host.isEmpty() ? "0.0.0.0" : host, port());
    }

    // ---------------------------------------------------------------- answering
    private static void handle(HttpExchange ex) {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                api(ex, path.substring(4));
                return;
            }
            String name = path.equals("/") ? "index.html" : path.substring(1);
            if (!name.matches("[a-z]+\\.(html|js|css)")) {
                send(ex, 404, "text/plain; charset=utf-8", "Not here.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body;
            try (InputStream in = AdminServer.class.getResourceAsStream("/admin/" + name)) {
                if (in == null) {
                    send(ex, 404, "text/plain; charset=utf-8", "Not here.".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                body = in.readAllBytes();
            }
            String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "text/javascript" : "text/css";
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, type + "; charset=utf-8", body);
        } catch (Exception e) {
            log.error("Admin page: {} {}", ex.getRequestMethod(), ex.getRequestURI(), e);
        }
    }

    private static void api(HttpExchange ex, String route) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        String method = ex.getRequestMethod();
        try {
            if (route.equals("/session")) {
                json(ex, 200, Map.of("in", signedIn(ex), "password", true));
                return;
            }
            if (!method.equals("GET") && !"1".equals(ex.getRequestHeaders().getFirst("X-Voyage-Admin"))) {
                json(ex, 403, Map.of("error", "not from the admin page"));
                return;
            }
            if (route.equals("/login") && method.equals("POST")) {
                login(ex);
                return;
            }
            if (route.equals("/logout") && method.equals("POST")) {
                String id = cookie(ex);
                if (id != null) {
                    sessions.remove(id);
                }
                ex.getResponseHeaders().add("Set-Cookie", "voyage_server_admin=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
                json(ex, 200, Map.of("in", false));
                return;
            }
            if (!signedIn(ex)) {
                json(ex, 401, Map.of("error", "Sign in."));
                return;
            }
            switch (method + " " + route) {
                case "GET /status" -> json(ex, 200, status());
                case "GET /players" -> json(ex, 200, players(ex));
                case "POST /players/kick" -> kick(ex, body(ex));
                case "POST /players/ban" -> ban(ex, body(ex));
                case "POST /players/unban" -> unban(ex, body(ex));
                case "GET /settings" -> json(ex, 200, Map.of("file", CONFIG.toAbsolutePath().normalize().toString(), "settings", AdminSettings.read(CONFIG)));
                case "PUT /settings" -> {
                    int n = AdminSettings.write(CONFIG, body(ex));
                    if (n > 0) {
                        log.warn("Admin page: {} line{} of config.yaml changed", n, n == 1 ? "" : "s");
                    }
                    json(ex, 200, Map.of("changed", n, "settings", AdminSettings.read(CONFIG)));
                }
                case "GET /log" -> json(ex, 200, AdminLog.after(number(query(ex, "after"))));
                case "POST /notice" -> notice(ex, body(ex));
                case "POST /control" -> control(ex, body(ex));
                default -> json(ex, 404, Map.of("error", "No such thing."));
            }
        } catch (IllegalArgumentException e) {
            json(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Admin page: {} {}", method, route, e);
            json(ex, 500, Map.of("error", "Something went wrong on the server: " + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- signing in
    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ip(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static void login(HttpExchange ex) throws IOException {
        String ip = ip(ex);
        long now = System.currentTimeMillis();
        long[] f = fails.get(ip);
        if (f != null && f[0] >= TRIES && now - f[1] < LOCK_MS) {
            json(ex, 429, Map.of("error", "Too many tries. Wait a minute."));
            return;
        }
        Object given = body(ex).get("password");
        if (!MessageDigest.isEqual(digest(given instanceof String s ? s : ""), digest(password()))) {
            fails.put(ip, new long[] {(f != null && now - f[1] < LOCK_MS ? f[0] : 0) + 1, now});
            log.warn("Admin page: a wrong password from {}", ip);
            json(ex, 401, Map.of("error", "Wrong password."));
            return;
        }
        fails.remove(ip);
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        sessions.put(id, now + SESSION_MS);
        sessions.values().removeIf(until -> until < now);
        ex.getResponseHeaders().add("Set-Cookie", "voyage_server_admin=" + id + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + SESSION_MS / 1000);
        log.info("Admin page: signed in from {}", ip);
        json(ex, 200, Map.of("in", true));
    }

    private static String cookie(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals("voyage_server_admin")) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private static boolean signedIn(HttpExchange ex) {
        String id = cookie(ex);
        Long until = id != null ? sessions.get(id) : null;
        return until != null && until > System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- what it shows and does
    private static Map<String, Object> status() {
        Server server = Server.getInstance();
        List<Map<String, Object>> worlds = new ArrayList<>();
        int online = 0;
        for (World w : server.getWorlds()) {
            List<Map<String, Object>> channels = new ArrayList<>();
            for (Channel ch : w.getChannels()) {
                channels.add(Map.of("id", ch.getId(), "players", ch.getPlayerStorage().getAllCharacters().size()));
            }
            int players = w.getPlayerStorage().getAllCharacters().size();
            online += players;
            Map<String, Object> world = new LinkedHashMap<>();
            world.put("id", w.getId());
            world.put("name", w.getId() < GameConstants.WORLD_NAMES.length ? GameConstants.WORLD_NAMES[w.getId()] : "World " + w.getId());
            world.put("players", players);
            world.put("channels", channels);
            worlds.add(world);
        }
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", server.isOnline());
        out.put("since", STARTED);
        out.put("players", online);
        out.put("worlds", worlds);
        out.put("memory", Map.of("used", rt.totalMemory() - rt.freeMemory(), "max", rt.maxMemory()));
        out.put("assets", String.valueOf(DataProviderFactory.assetsFolder()));
        out.put("customs", String.valueOf(DataProviderFactory.customsFolder()));
        return out;
    }

    private static String words(String name) {
        String s = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        return s.isEmpty() ? s : java.lang.Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A character on the Players tab: from the database, with what the game knows now when it is online. */
    private record Row(String name, String account, int level, int job, int map, String mapName, int world, int channel,
                       int gm, boolean online, boolean banned, String reason, Long seen, Long until) {}

    private static final List<String> SORTS = List.of("name", "account", "level", "job", "map", "world", "channel", "status", "seen");

    /**
     * Every character, online or not, for the Players tab: those matching the search ({@code q}, part of a character's
     * or account's name) and the filters ({@code status} online, offline or banned; {@code world}; {@code job} class;
     * {@code gm} yes or no; levels {@code min} to {@code max}), sorted by {@code sort} ({@code dir} asc or desc), one
     * {@code page} of {@code size} at a time. A character in the game shows what the game knows now (its level, job and
     * map may not be saved yet).
     */
    private static Map<String, Object> players(HttpExchange ex) throws SQLException {
        String q = text(query(ex, "q"), 30).toLowerCase(Locale.ROOT);
        String status = text(query(ex, "status"), 10), world = text(query(ex, "world"), 5), job = text(query(ex, "job"), 10);
        String gm = text(query(ex, "gm"), 5), sort = text(query(ex, "sort"), 10);
        long min = number(query(ex, "min")), max = number(query(ex, "max"));
        boolean desc = "desc".equals(query(ex, "dir"));
        long given = number(query(ex, "size"));
        int size = (int) (given <= 0 ? 50 : Math.min(200, given));
        if (!SORTS.contains(sort)) {
            sort = "status";
        }

        Map<Integer, Character> live = new HashMap<>();
        for (World w : Server.getInstance().getWorlds()) {
            for (Character c : w.getPlayerStorage().getAllCharacters()) {
                live.put(c.getId(), c);
            }
        }
        List<Row> all = new ArrayList<>();
        Map<Integer, String> mapNames = new HashMap<>();
        long now = System.currentTimeMillis();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT c.id, c.name, c.level, c.job, c.map, c.world, c.gm, c.lastLogoutTime, c.createdate, a.name, a.banned, a.banreason, a.tempban FROM characters c JOIN accounts a ON a.id = c.accountid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Character c = live.get(rs.getInt(1));
                String account = Objects.toString(rs.getString(10), "");
                // banned for good, or until a time still to come (then `until` is when it ends)
                Timestamp tempban = rs.getTimestamp(13);
                Long until = rs.getInt(11) != 1 && tempban != null && tempban.getTime() > now ? tempban.getTime() : null;
                boolean banned = rs.getInt(11) == 1 || until != null;
                String reason = banned ? Objects.toString(rs.getString(12), "") : "";
                if (c != null) {
                    all.add(new Row(c.getName(), account, c.getLevel(), c.getJob() != null ? c.getJob().getId() : rs.getInt(4),
                            c.getMapId(), c.getMap() != null ? c.getMap().getMapName() : "", c.getWorld(),
                            c.getClient() != null ? c.getClient().getChannel() : 0, c.gmLevel(), true, banned, reason, null, until));
                } else {
                    // a character that never logged out still has the column's default, from before it was made
                    Timestamp out = rs.getTimestamp(8), made = rs.getTimestamp(9);
                    Long seen = out != null && (made == null || !out.before(made)) ? out.getTime() : null;
                    int map = rs.getInt(5);
                    all.add(new Row(rs.getString(2), account, rs.getInt(3), rs.getInt(4), map,
                            mapNames.computeIfAbsent(map, MapFactory::loadPlaceName), rs.getInt(6), 0, rs.getInt(7), false,
                            banned, reason, seen, until));
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        for (Row r : all) {
            boolean keep = (q.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(q) || r.account().toLowerCase(Locale.ROOT).contains(q))
                    && switch (status) {
                        case "online" -> r.online();
                        case "offline" -> !r.online();
                        case "banned" -> r.banned();
                        default -> true;
                    }
                    && (world.isEmpty() || world.equals(String.valueOf(r.world())))
                    && (job.isEmpty() || job.equals(jobClass(r.job())))
                    && switch (gm) {
                        case "yes" -> r.gm() > 0;
                        case "no" -> r.gm() == 0;
                        default -> true;
                    }
                    && (min <= 0 || r.level() >= min) && (max <= 0 || r.level() <= max);
            if (keep) {
                rows.add(r);
            }
        }
        Comparator<Row> by = switch (sort) {
            case "name" -> Comparator.comparing((Row r) -> r.name().toLowerCase(Locale.ROOT));
            case "account" -> Comparator.comparing((Row r) -> r.account().toLowerCase(Locale.ROOT));
            case "level" -> Comparator.comparingInt(Row::level);
            case "job" -> Comparator.comparing((Row r) -> jobName(r.job()).toLowerCase(Locale.ROOT));
            case "map" -> Comparator.comparing((Row r) -> r.mapName().toLowerCase(Locale.ROOT)).thenComparingInt(Row::map);
            case "world" -> Comparator.comparingInt(Row::world);
            case "channel" -> Comparator.comparingInt(Row::channel);
            case "seen" -> Comparator.comparingLong((Row r) -> r.online() ? Long.MAX_VALUE : r.seen() == null ? Long.MIN_VALUE : r.seen());
            default -> Comparator.comparingInt((Row r) -> r.online() ? 0 : r.banned() ? 2 : 1);   // online, offline, banned
        };
        if (desc) {
            by = by.reversed();
        }
        rows.sort(by.thenComparing((Row r) -> r.name().toLowerCase(Locale.ROOT)));

        int pages = Math.max(1, (rows.size() + size - 1) / size);
        int page = (int) Math.min(Math.max(0, number(query(ex, "page"))), pages - 1);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Row r : rows.subList(page * size, Math.min(rows.size(), (page + 1) * size))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("account", r.account());
            m.put("level", r.level());
            m.put("job", jobName(r.job()));
            m.put("map", r.map());
            m.put("mapName", r.mapName());
            m.put("world", worldName(r.world()));
            m.put("channel", r.channel());
            m.put("gm", r.gm());
            m.put("online", r.online());
            m.put("banned", r.banned());
            m.put("reason", r.reason());
            m.put("seen", r.seen());
            m.put("until", r.until());
            list.add(m);
        }
        Set<Integer> worldIds = new java.util.TreeSet<>();
        for (World w : Server.getInstance().getWorlds()) {
            worldIds.add(w.getId());
        }
        for (Row r : all) {
            worldIds.add(r.world());
        }
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (int id : worldIds) {
            worlds.add(Map.of("id", id, "name", worldName(id)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("characters", all.size());
        out.put("online", all.stream().filter(Row::online).count());
        out.put("banned", all.stream().filter(Row::banned).count());
        out.put("total", rows.size());
        out.put("page", page);
        out.put("size", size);
        out.put("worlds", worlds);
        out.put("players", list);
        return out;
    }

    private static String text(String s, int most) {
        String t = s == null ? "" : s.trim();
        return t.length() > most ? t.substring(0, most) : t;
    }

    private static String jobName(int id) {
        Job job = Job.getById(id);
        return job != null ? words(job.name()) : "";
    }

    /** The class a job belongs to: Cygnus Knights and Aran count with the class they fight as. */
    private static String jobClass(int id) {
        int branch = id % 1000;
        if (branch == 0) {
            return "beginner";
        }
        return switch (branch / 100) {
            case 1 -> "warrior";
            case 2 -> "magician";
            case 3 -> "bowman";
            case 4 -> "thief";
            case 5 -> "pirate";
            case 9 -> "gm";
            default -> "";
        };
    }

    private static String worldName(int id) {
        return id >= 0 && id < GameConstants.WORLD_NAMES.length ? GameConstants.WORLD_NAMES[id] : String.valueOf(id);
    }

    /** A character in the game by its name, or null. */
    private static Character online(String name) {
        for (World w : Server.getInstance().getWorlds()) {
            Character c = w.getPlayerStorage().getCharacterByName(name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static String nameIn(Map<String, Object> body) {
        Object name = body.get("name");
        if (!(name instanceof String s) || s.isBlank() || s.length() > 13) {
            throw new IllegalArgumentException("Which character?");
        }
        return s.trim();
    }

    /**
     * Disconnects a player: tells them they were kicked (the game's notice for being cut off), logs the character out
     * and closes their connection, so their game ends too.
     */
    private static void kick(HttpExchange ex, Map<String, Object> body) throws IOException {
        String name = nameIn(body);
        Character c = online(name);
        if (c == null) {
            throw new IllegalArgumentException(name + " is not in the game.");
        }
        c.sendPacket(PacketCreator.sendPolice("You have been kicked from the server."));
        dropConnection(c.getClient());
        log.warn("Admin page: kicked {}", c.getName());
        json(ex, 200, Map.of("ok", true));
    }

    /**
     * Logs the character out and closes the connection. Client.disconnect alone only logs the character out on the
     * server: the connection stays open and the player's game keeps running as if nothing happened.
     */
    private static void dropConnection(client.Client client) {
        client.disconnect(false, false);
        client.closeSession();
    }

    /** The longest timed ban: ten years, in minutes. */
    private static final long MOST_MINUTES = 10L * 366 * 24 * 60;
    /** The game's reason for a timed ban, for its login message: "Your account has been blocked for misconduct." */
    private static final int TIMED_BAN_REASON = 7;

    /**
     * Bans a character's account (every character on it) for {@code minutes}, or for good without them. A player in
     * the game gets the game's notice for being cut off, saying for how long and why, and is disconnected; one who
     * tries to log in is told by the game when they can again. A ban for good also bans the machine ids their game
     * sent, as !ban does; a timed one doesn't, or it would outlast itself. Unlike !ban it bans no IP address: players
     * of the web client all reach the server from the address of the client's relay.
     */
    private static void ban(HttpExchange ex, Map<String, Object> body) throws IOException, SQLException {
        String name = nameIn(body);
        String given = body.get("reason") instanceof String s ? s.trim() : "";
        if (given.length() > 100 || given.contains("\n")) {
            throw new IllegalArgumentException("A reason is one line of up to 100 characters.");
        }
        Object asked = body.get("minutes");
        long minutes = asked instanceof Number n ? n.longValue() : 0;
        if (asked != null && !(asked instanceof Number n && n.doubleValue() == minutes && minutes >= 0 && minutes <= MOST_MINUTES)) {
            throw new IllegalArgumentException("A ban lasts from a minute to ten years, in whole minutes (or 0 for good).");
        }
        String length = minutes > 0 ? "for " + span(minutes) : "permanently";
        String reason = "Banned from the admin page " + length + (given.isEmpty() ? "" : ": " + given);
        Character c = online(name);
        if (minutes > 0) {
            int aid = c != null ? c.getAccountID() : Character.getAccountIdByName(name);
            if (aid <= 0) {
                throw new IllegalArgumentException("There is no character called " + name + ".");
            }
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE accounts SET banreason = ?, tempban = ?, greason = ? WHERE id = ?")) {
                ps.setString(1, reason);
                ps.setTimestamp(2, new Timestamp(System.currentTimeMillis() + minutes * 60_000));
                ps.setInt(3, TIMED_BAN_REASON);
                ps.setInt(4, aid);
                ps.executeUpdate();
            }
        } else if (c != null) {
            c.getClient().banMacs();
            c.ban(reason);
        } else if (!Character.ban(name, reason, false)) {
            throw new IllegalArgumentException("There is no character called " + name + ".");
        }
        if (c != null) {
            c.sendPacket(PacketCreator.sendPolice("You have been banned " + length + "." + (given.isEmpty() ? "" : "\r\nReason: " + given)));
            dropConnection(c.getClient());
        }
        log.warn("Admin page: banned {} ({})", name, reason);
        json(ex, 200, Map.of("ok", true));
    }

    /** "1 minute", "90 minutes", "2 hours", "3 days". */
    private static String span(long minutes) {
        long n = minutes % 1440 == 0 ? minutes / 1440 : minutes % 60 == 0 ? minutes / 60 : minutes;
        String unit = minutes % 1440 == 0 ? "day" : minutes % 60 == 0 ? "hour" : "minute";
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    /**
     * Lifts the ban on a character's account, timed or for good, and the machine and address bans that came with it,
     * as !unban does.
     */
    private static void unban(HttpExchange ex, Map<String, Object> body) throws IOException, SQLException {
        String name = nameIn(body);
        int aid = Character.getAccountIdByName(name);
        if (aid <= 0) {
            throw new IllegalArgumentException("There is no character called " + name + ".");
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET banned = IF(banned = 1, -1, banned), tempban = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.valueOf(DefaultDates.getTempban()));
                ps.setInt(2, aid);
                ps.executeUpdate();
            }
            for (String table : new String[] {"ipbans", "macbans"}) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + table + " WHERE aid = ?")) {
                    ps.setString(1, String.valueOf(aid));
                    ps.executeUpdate();
                }
            }
        }
        log.warn("Admin page: unbanned {}", name);
        json(ex, 200, Map.of("ok", true));
    }

    private static void notice(HttpExchange ex, Map<String, Object> body) throws IOException {
        Object text = body.get("text");
        if (!(text instanceof String s) || s.isBlank() || s.length() > 200 || s.contains("\n")) {
            throw new IllegalArgumentException("A notice is one line of up to 200 characters.");
        }
        for (World w : Server.getInstance().getWorlds()) {
            Server.getInstance().broadcastMessage(w.getId(), PacketCreator.serverNotice(6, s.trim()));
        }
        log.info("Admin page: notice sent: {}", s.trim());
        json(ex, 200, Map.of("ok", true));
    }

    private static void control(HttpExchange ex, Map<String, Object> body) throws IOException {
        String action = String.valueOf(body.get("action"));
        int code = switch (action) {
            case "restart" -> RESTART_CODE;
            case "shutdown" -> 0;
            default -> throw new IllegalArgumentException("Restart or shutdown.");
        };
        log.warn("Admin page: {}", action);
        json(ex, 200, Map.of("ok", true));
        Thread.ofPlatform().name("admin-" + action).start(() -> Server.getInstance().stopWith(code));
    }

    // ---------------------------------------------------------------- plumbing
    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(BODY_MAX + 1);
        if (raw.length > BODY_MAX) {
            throw new IllegalArgumentException("That is too much to send.");
        }
        if (raw.length == 0) {
            return new LinkedHashMap<>();
        }
        Object parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("That is not something to do.");
        }
        return (Map<String, Object>) parsed;
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv[0].equals(key)) {
                return kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private static long number(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
